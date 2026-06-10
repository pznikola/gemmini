# Plan: Add MXINT8 Support to Gemmini

> **[SUPERSEDED for forward planning, 2026-06-10.]** New work follows
> `DOCS_MX/PLAN_MX_UPDATED.md` (execution phases P0–P11) and
> `DOCS_MX/RESEARCH_PLAN.md` (research/publication plan, ACM TRETS target).
> This file remains the authoritative **historical log** of the R0–R3 / S1–S3 /
> RES-OPT / bug-fix work — do not rewrite it; its §12 progress log is the evidence
> base for the paper's verification story.

## Context

**Why.** This project adds OCP **Microscaling INT8 (MXINT8)** block-scaled numerics to the
Gemmini systolic-array accelerator. MXINT8 represents a 32-element block with 32 signed
int8 payloads sharing one 8-bit E8M0 power-of-two scale. The research thesis (see
`DOCS_MX/mxint8_gemmini_plan_updated.pdf` and `DOCS_MX/research-report 4.md`) is **not**
"a datatype port" — it is *metadata-aware execution of block-scaled numerics on a
transposer-based systolic array when the logical MX block size (32) does not match the
physical array width (`DIM`)*. The hard case is `DIM=16`, where one logical 32-element MX
block spans two physical K phases.

**Numerical contract (frozen).** Defined in `mxint8_policy.md`. For payload matrices
`A[M][K]`, `B[K][N]` with per-32-K-block E8M0 scales:
`C[i][j] = Σ_b round_nearest_even_sat( (Σ_{t∈block b} A·B) · 2^(eA[i][b]+eB[b][j]-12) )`,
where `e = s - 127` (E8M0 decode), `-12 = 2·6` cancels the two implicit `2^-6` payload
scales, scale `0xff` (NaN) is rejected in v1, K-tail lanes are zero-padded, and each raw
block partial is scaled **once per logical block before** cross-block accumulation.

**Outcome.** A correct, tested, staged MXINT8 GEMM path in Gemmini: DIM=32 aligned MVP
first, then the DIM=16 two-phase research contribution, with a bit-exact software golden
model, baremetal hardware tests, FireSim performance counters, and a baseline/ablation
matrix sufficient for a conference paper.

**Important — current repo state.** The Gemmini submodule (`generators/gemmini`) already
contains a **substantial uncommitted MXINT8 WIP**. This plan is *hybrid*: it documents the
full target design and, per work item, annotates **[EXISTS]** (WIP present, needs
audit/verify), **[PARTIAL]**, or **[TODO]** (missing). The single largest gap is that there
is **no end-to-end *hardware* GEMM test** — only the software golden-model unit test
(`mxint8_golden.c`) exists. The WIP now **compiles cleanly** (R0a, 2026-06-04) **and
elaborates to Verilog** (R0b, 2026-06-04) — the M0 build gate is closed. Next: R1 (audit
scaffolding) and the top-priority gap S3 (first end-to-end hardware GEMM test).

**Coding styles (mandatory).** Chisel/Scala generator code follows
`CODING_STYLES/scala_coding_style.md` (Databricks guide); C software follows
`CODING_STYLES/c_cpp_coding_style.md` (Google/lowRISC). See "Coding Style Compliance".

---

## 1. MX / MXINT8 Background (from OCP spec + online research)

- **Block size `k = 32`**, fixed for all OCP MX formats. Scale = **E8M0**: unsigned biased
  Float32 exponent, bias 127, `0xff` reserved for NaN. Decoded exponent `e = s - 127`.
- **MXINT8 payload**: signed int8, two's-complement, with an **implicit scale `2^-6`**.
  Value `v_i = X · p_i` where `X = 2^(s-127)` and `p_i` carries the `2^-6` fractional weight.
- Metadata overhead is small (1 scale byte / 32 payloads = 3.125%); the architectural
  difficulty is **alignment**, not storage.
- Sources: OCP MX v1.0 spec (opencompute.org), Microscaling Data Formats for Deep Learning
  (arXiv:2310.10537), microsoft/microxcaling reference library, AMD Quark MX docs.

---

## 2. Current Implementation State (WIP Inventory)

All paths relative to repo root. Status from direct inspection of the uncommitted diff.

### RTL (Chisel) — `generators/gemmini/src/main/scala/gemmini/`

| File | Status | What is present / what remains |
|---|---|---|
| `MXScaleSRAM.scala` (new) | **[EXISTS]** | Sidecar metadata SRAM: `rawA/rawB`, predecoded `expA/expB` (E8M0→signed exp at ingest), `invalidA/invalidB`; A/B read+write ports; `0xff` reject assert. Remaining: audit read latency vs. consumer timing; confirm `busy` semantics; no unit test. |
| `MXScaleLoadController.scala` (new) | **[EXISTS]** | DMA loader for scale rows via `DMACommandTracker`; A/B stride mux; row counter FSM. Remaining: audit `cols ≤ DIM` / tail; round-trip test. |
| `Scratchpad.scala` | **[EXISTS]** | `StreamReader` for MX scales, `io.mx` read/write, extra TLB port, counters wired. Remaining: confirm `mx_scale_row_bits` width math; counter coverage. |
| `Controller.scala` | **[EXISTS]** | Instantiates `MXScaleSRAM`+`MXScaleLoadController`; reservation-station load routing (`ld_issue_is_mx_scale`); `CONFIG_MXINT8_CMD` decode (`mx_runtime_enabled`, strides, reset pulse); extra TLB port. Remaining: confirm completion-arb index (uses `in(3)`); FENCE/dep ordering between scale loads and compute. |
| `ExecuteController.scala` | **[PARTIAL]** | MX IO; `mesh_tag` MX fields; `mx_k_lane_counter`/`mx_logical_block`/`mx_second_half`; B-scale read+latch; A-scale read on output; **inline BlockScaleUnit** (`roundRightNearestEven`, `scalePowerOfTwo`, `saturateToAcc`, `scale_shift=eA+eB-2·frac`); DIM=16 two-phase `mx_raw_half` accumulation + `mx_suppress_acc_write`; invariant asserts (transpose disabled, valid scales). **Audit priority**: A-scale read address uses `output_counter` (verify it indexes the correct logical row); `mx_raw_half` sizing/index; tail-lane masking; whether int32 acc (post-saturate-per-block) matches the wide-acc reference for K>32. |
| `GemminiISA.scala` | **[EXISTS]** | `CONFIG_MXINT8_CMD=26`, `LOAD_MX_SCALE_A_CMD=27`, `LOAD_MX_SCALE_B_CMD=28`, `LOOP_WS_MXINT8=29`. |
| `Configs.scala` | **[EXISTS]** | `mxint8DIM32Config`, `mxint8DIM16Config`, `GemminiMXINT8DIM32Config`, `GemminiMXINT8DIM16Config`. Remaining: confirm a Chipyard top-config mixes these in for sim. |
| `GemminiConfigs.scala` | **[EXISTS]** | `mx_enabled/mx_block_size/mx_scale_bits/mx_int_frac_bits/mx_scale_sp_capacity` + derived (`mx_scale_sp_entries`, `mx_scale_exp_bits`, `mx_scale_addr_bits`, `mx_scale_row_bits`); `require` guards; `generateHeader()` MX fields. |
| `ReservationStation.scala` | **[EXISTS]** | MX scale loads classified as load/config for dependency tracking. |
| `MeshWithDelays.scala`, `PE.scala`, `Mesh.scala`, `Transposer.scala`, `Arithmetic.scala` | **[UNCHANGED]** | Mesh stays pure int8 MAC; scales never enter the payload transposer (by design). No change expected; transposer-aware path is out of v1 scope. |

### Software (C) — `generators/gemmini/software/gemmini-rocc-tests/`

| File | Status | What is present / what remains |
|---|---|---|
| `include/mxint8_golden.h` | **[EXISTS]** | Bit-exact reference: `mxint8_e8m0_decode/is_valid`, `mxint8_round_right_shift_nearest_even`, `mxint8_scale_raw_block` (`eA+eB-2·FRAC`), `mxint8_saturate_acc`, `mxint8_ref_gemm_acc` (wide int64 acc). Matches `mxint8_policy.md`. |
| `bareMetalC/mxint8_golden.c` | **[PARTIAL]** | Unit-tests the *software* golden helpers only — **no hardware path exercised**. |
| `include/gemmini.h` | **[PARTIAL]** | Intrinsics `gemmini_config_mxint8`, `gemmini_mvin_mxscale_a/b`, `gemmini_loop_ws_mxint8`. Remaining: confirm `gemmini_loop_ws_mxint8` lowers correctly (it composes config + scale mvin + `gemmini_loop_ws`); decide whether `LOOP_WS_MXINT8=29` needs a real hardware unroller or stays reserved. |
| `include/gemmini_params.h` | **[EXISTS]** | Generated MX fields (`MX_ENABLED`, `MX_BLOCK_SIZE`, `MX_SCALE_BITS`, `MX_INT_FRAC_BITS`, `MX_SCALE_SP_ROWS`, `MX_SCALE_ROW_BYTES`, `mx_scale_t`, `mx_exp_t`). |
| `bareMetalC/Makefile` | **[EXISTS]** | `mxint8_golden` registered; `mxint8_golden.h` in `GEMMINI_HEADERS`. Remaining: register new hardware tests. |

---

## 3. Target Architecture (design of record)

**Sidecar metadata pipeline (scales are never payload):**
```
Payload : DRAM → Payload DMA → Scratchpad → Transposer → Mesh (int8 MAC) ┐
Metadata: DRAM → MX Scale DMA → MXScaleSRAM(predecoded exp) → ExecuteController ┘→ BlockScaleUnit → Accumulator
```
- **MXScaleSRAM** stores predecoded signed exponents (decode off the hot path).
- **Logical K-block controller** (in `ExecuteController`) tracks the current logical
  32-element block via `mx_k_lane_counter`, independent of physical row width.
- **BlockScaleUnit** (currently inline in `ExecuteController`): for each raw block partial
  `P^(b)_ij` (int, ≤32·127²=516,128, fits int32), apply `2^(eA+eB-12)` by left-shift
  (positive) or round-nearest-even right-shift (negative), saturate, then accumulate.
- **DIM=16 invariant**: A/B scale vectors are held constant across both 16-wide physical K
  phases of one logical block; raw partials of half-0 and half-1 are summed *before* a
  single scale application (`mx_raw_half` + `mx_suppress_acc_write` on half-0).
- **DIM=32**: one physical K episode == one logical MX block (the bring-up case).
- **Accumulator policy (v1)**: fixed-point, per-block scale-then-int32-accumulate. The
  software reference uses a wide int64 acc; the int32 RTL limitation is documented and
  tested for dynamic-range safety. A wider shadow accumulator is a later phase.

---

## 4. Implementation Plan — RTL (Chisel)

Follow `CODING_STYLES/scala_coding_style.md` throughout (see §8). Staged DIM=32 → DIM=16.

**Phase R0 — Build/elaboration gate.**
- **R0a Compile gate — [DONE, 2026-06-04].** `sbt "project gemmini" clean compile` recompiles
  all **57** gemmini Scala sources (incl. the MX WIP: `MXScaleSRAM`, `MXScaleLoadController`,
  `ExecuteController`, `Controller`, `Scratchpad`, `Configs`, …) with **0 errors** (only
  pre-existing style/feature warnings). No MX code breakage; the only blocker was
  environmental — see **Build Environment** below.
- **R0b Full elaboration — [DONE, 2026-06-04].** Gemmini has no standalone full-design
  elaborator, so two Chipyard top-configs were added in
  `generators/gemmini/chipyard/GemminiConfigs.scala` (mirroring `GemminiRocketConfig`):
  `GemminiMXINT8DIM32RocketConfig` and `GemminiMXINT8DIM16RocketConfig` (Rocket huge core +
  128-bit system bus + `AbstractConfig`). Elaborated with
  `make -C sims/verilator CONFIG=GemminiMXINT8DIM32RocketConfig verilog`.
  **Result: PASS — 648 `.sv`/`.v` files emitted, incl. `MXScaleSRAM.sv`,
  `MXScaleLoadController.sv`, `ExecuteController.sv`, `Scratchpad.sv`, `AccumulatorMem.sv`,
  `Gemmini.sv` (493 KB).** Elaboration surfaced **two real bugs the compile gate could not**
  (both fixed):
  1. **`AccumulatorMem.scala:223` `None.get`** — `io.ext_mem.get(i)` was called in the
     local-SRAM branch where `ext_mem` is `None` (pre-existing Gemmini bug, latent until a
     `acc_singleported=true` config — which the MX config inherits from `largeChipConfig` —
     is elaborated standalone without shared external memory). Fixed None-safely with
     `io.ext_mem.foreach { … }`, matching the existing init at lines 158–166.
  2. **`Scratchpad.scala:936` counter double-connect** ("Port 18 is already connected") — an
     **MX WIP bug**: `mx_scale_reader` is a `StreamReader` and reuses the main reader's
     `CounterEvent` IDs, so `io.counter.collect(...)` on it collided. Tied it off with
     `DontCare` (as `spad_writer` already is); dedicated MX metadata counters are deferred to
     **Phase R4**.
  - **Stock parity (at parse stage):** `GemminiRocketConfig` was elaborated the same way and
    failed identically at the *old-firtool parse* stage. **Correction (see R1, Bug C):** stock
    was not actually lowered to clean Verilog here — running it through the pinned firtool 1.62.1
    during R1 exposed a separate MX-disabled-path bug, now fixed. With that fix, stock produces
    clean Verilog and the MX changes introduce **no elaboration regression**.
  - Exit criterion met: MX config elaborates cleanly to Verilog and stock configs are
    unaffected.

> **Build Environment (reproducibility — critical).** The conda env (`.conda-env`) pins
> **JDK 20** and its own `sbt 1.8.2`, but the default PATH puts `/usr/bin` (system **JDK 21**)
> ahead of `.conda-env/bin`. System JDK 21 breaks sbt's build-definition parser
> (`FatalError` in `ClassfileParser` → `Could not initialize class sbt.internal.parser.SbtParser$`).
> Always build with (use the **absolute** path to `env.sh` — a drifted shell cwd makes
> `source ./env.sh` silently no-op and fall back to JDK 21):
> ```
> source /home/nikolap/Research/2026/chipyard/env.sh && export PATH="$CONDA_PREFIX/bin:$PATH"
> sbt "project gemmini" compile
> ```
> **Firtool gotcha (verilog step):** the `make … verilog` step needs CIRCT **firtool**. PATH
> picks up a stale `/usr/local/bin/firtool` (LLVM-17, Jan-2024) that **cannot parse** the
> backtick-escaped numeric field names (e.g. `` `0` `` in the `mem_axi4` port) emitted by this
> repo's Chisel — failing **both stock and MX** at the `model_module_hierarchy.json` step.
> The chipyard-pinned firtool is at `~/.cache/llvm-firtool/1.62.1/bin/firtool`. Pass it
> explicitly: `make … CONFIG=… FIRTOOL_BIN=$HOME/.cache/llvm-firtool/1.62.1/bin/firtool verilog`.

**Phase R1 — Audit & harden existing scaffolding [DONE, 2026-06-04].**
Full trace of the scale path: `MXScaleSRAM` → `MXScaleLoadController` → `Controller` wiring →
`ReservationStation` deps → `ExecuteController` consumer. The audit found the scaffolding
mostly sound, but uncovered **two linked correctness bugs in the reservation-station
integration** (both fixed) plus several minor notes.

**Bugs found & fixed — `ReservationStation.scala` (root cause: MX scale mvins were modelled as
stateless `is_config` commands):**
- **Bug A — missing scale→compute ordering.** A matmul took **no** dependency on the scale
  mvins it consumes. Scale loads target the sidecar `MXScaleSRAM` (no scratchpad address), so
  `dst/op1/op2` are all invalid (`opa.valid=false`) and the address-overlap dep at the `is_ex`
  `deps_ld` never includes them. The matmul could read `MXScaleSRAM` **before the scale DMA
  completes** → stale scales. It only "worked" because the tiny scale DMA usually finishes
  before the larger payload DMA — timing luck that breaks for small/odd tiles and DIM=16.
- **Bug B — false completion / freed-slot assert.** `complete_on_issue = is_config && q≠exqu`
  made the scale-load entry free **on issue**, yet `MXScaleLoadController` later sends a *real*
  `io.completed` for the same `rob_id` (completion arbiter `in(3)`), striking
  `assert(entries_ld(issue_id).valid)` on a freed (or reused) slot. Never observed because the
  only MX test today is the software-only `mxint8_golden.c`; it would fire the first time a
  hardware scale mvin runs — i.e. it **blocks S3**.
- **Fix (surgical, inert for non-MX):** added an `is_mx_scale` entry marker; excluded scale
  loads from `complete_on_issue` (they now complete on the controller's real DMA completion);
  and added `e.bits.is_mx_scale` to the `is_ex` `deps_ld` so a compute/preload depends on every
  in-flight scale load. With MX disabled `is_mx_scale` is always false, so `deps_ld` reduces to
  the original expression — **stock configs are provably unaffected.** This is the
  architecturally-correct fix: it preserves scale/payload pipelining (no fence needed).

**Bug C — `ExecuteController` MX-disabled path leaves undriven sinks (fixed).** Surfaced while
validating R1 by elaborating *stock* `GemminiRocketConfig` through the pinned firtool 1.62.1
(R0b had only lowered the *MX* config with 1.62.1; the stock run there died earlier at the
old-firtool parse stage, so this never ran — correcting the R0b "stock parity" note, which held
only at parse). `mx_b_exp`/`mx_b_invalid` (`ExecuteController.scala:860-861`) and
`mx_mesh_resp_bits` (`:994`) are declared `if (mx_enabled) <Reg> else Wire(...)` but only driven
inside `if (mx_enabled)` blocks, so with MX off they are dead, undriven wires → 64 firtool
"sink not fully initialized" errors. **Fix:** drive all three with `DontCare` in the
`!mx_enabled` path (matching the existing convention, e.g. `Controller.scala:396`); no-ops for
the MX config.

**Validation (2026-06-04, run `bofwxdri0`):** both configs elaborate to clean Verilog with the
pinned firtool — `STOCK_EXIT=0`, `MX_EXIT=0`, **0 firtool `error:` lines** for each (the only
"sink" strings in the log are the `LayerSink` MLIR pass name in firtool's pass-timing table, not
the Bug-C "sink not fully initialized" error). Collateral: stock `GemminiRocketConfig` = 622
`.sv` + 13 `.v` (635 total) and `GemminiMXINT8DIM32RocketConfig` = 635 `.sv` + 13 `.v` (648
total), incl. `MXScaleSRAM`/`MXScaleLoadController` — i.e. the +13 MX-module delta on top of
stock. (The 635/648 figures count `.sv`+`.v`; later phases quote the `.sv`-only counts
622/635 — same hardware.) This confirms compile+elaboration only; **behavioral** correctness of
the RS ordering fix awaits the S3 hardware GEMM test vs. the golden model.

**Audited OK:**
- `MXScaleSRAM.scala`: 1-cycle `SyncReadMem` read; `resp.valid := RegNext(fire)` aligns with
  data (consumer must capture the next cycle — verify in R2). E8M0 predecode `e=s-127` stored
  signed; `0xff` reject via assert **and** stored `invalid` flag. `mx_scale_exp_bits = 9`
  (verified in `GemminiConfigs.scala`) safely holds the decode range incl. the `0xff→128` case.
- `MXScaleLoadController.scala`: row FSM, `cols ≤ DIM` assert, `localaddr+row < entries` assert,
  A/B stride mux, `DMACommandTracker` (one completion per mvin — matches the RS fix).
- `Controller.scala`: RS load routing (`ld_issue_is_mx_scale` gate), extra TLB port, completion
  arbiter `in(3)`, `busy` includes the scale loader (so fences drain scale DMAs).
- Param math (`GemminiConfigs.scala`): `mx_scale_row_bits = 8·DIM`, `mx_scale_sp_entries`,
  `require` guards (block 32, E8M0, frac 6, signed int8, WS, DIM∈{16,32}) all correct.

**Minor notes (non-blocking, tracked):**
- `MXScaleSRAM.io.busy` is hardwired `false` — acceptable (always-ready 1-cycle SRAM); nothing
  critical depends on it.
- `MXScaleLoadController` is instantiated even when `mx_enabled=false` (DMA tied off) — small
  dormant-area waste; could wrap in `Option` later.
- **B-base `mx_scale_sp_entries/2`** (used by `gemmini_mvin_mxscale_b`) is a **vestigial
  software convention** from a shared-SRAM design; A/B are now separate SRAMs, so it just halves
  usable scale capacity. Software cleanup candidate (S2) — not a correctness bug.
- `CONFIG_MXINT8` is consumed at decode and **bypasses the RS** (sets `mx_runtime_enabled`/
  strides immediately). In-order decode preserves config→matmul ordering, but reconfiguring
  while an MX matmul is still draining is an unguarded hazard → **fence before mid-stream MX
  reconfig** (document as a software constraint).
- `MXScaleSRAM` masked partial writes leave **stale lanes** for tail blocks (`cols<DIM`); the
  consumer must zero/mask tail lanes — an R2 (`ExecuteController`) responsibility.

**Phase R2 — DIM=32 datapath correctness [DONE, 2026-06-04].**
Static datapath audit of the DIM=32 MX path in `ExecuteController.scala` against the frozen
golden (`mxint8_golden.h`) and policy (`mxint8_policy.md`), plus the `MXScaleSRAM` 1-cycle
`SyncReadMem` read timing. Found and fixed **two result-corrupting bugs** (both invisible to
the elaboration gate); three other items audited clean.

**Bugs found & fixed — `ExecuteController.scala`:**
- **Bug R2-1 — A-scale read address off-by-one (corrupts every multi-row MX matmul).** The
  A-scale SRAM read is issued in the *undelayed* mesh-output domain (so its 1-cycle read
  latency overlaps the 1-cycle `mx_delay_outputs` shadow), but used `output_counter`, which
  advances on the *delayed* output stream and therefore lags the undelayed row index by one.
  Net effect: output row `i` was scaled by `eA[i-1]` instead of `eA[i]` for every row ≥ 1
  (row 0 was coincidentally correct). The golden indexes `a_scale[i*stride + block]` per
  output row, so this diverges whenever A scales differ across M rows — i.e. essentially
  always. **Fix:** dedicated `mx_a_read_row` counter that mirrors `output_counter`'s
  increment one cycle earlier (in the undelayed domain), so the read address equals the
  undelayed output-row index. Inert when MX is off (lives inside `if (mx_enabled)`).
- **Bug R2-2 — `scalePowerOfTwo` left-shift wraps instead of saturating.** `(value <<
  shift)(63,0)` truncated to 64 bits (silent wrap) for large positive shifts and synthesised
  a ~1000-bit dynamic shifter. The golden (`mxint8_scale_raw_block`) saturates to the int64
  range first. **Fix:** saturate to int64 on overflow exactly like the golden, and bound the
  shift operand to `log2(64)` bits (smaller shifter too). Also `roundRightNearestEven` now
  returns 0 at shift ≥ 63 (matching the golden) instead of rounding to ±1.

**Audited OK (no change):**
- **B-scale read/latch timing.** B scale is read once at mesh-feed time (`first`), latched
  into `mx_b_exp`, and held across all output rows — correct, since `eB[b][j]` is constant
  across the M rows of one matmul. Lane = N column `j`; row = `b_scale_base + b`. Matches the
  golden `b_scale[b*stride + j]`.
- **Tail-lane masking (K or N not divisible by DIM).** Payload tail lanes are zero-padded
  (`a_unpadded_cols`/`b_unpadded_cols`), so the raw block sum already excludes tail-K. N-tail
  columns beyond `w_matrix_cols` are masked out of the accumulator write (`w_mask`), so stale
  B-scale lanes never reach a written column. The `0xff` (NaN) scale byte is rejected at
  *write* time in `MXScaleSRAM`, so the invalid-scale asserts on stale lanes are inert.
- **Cross-block K=64/96/128 accumulation.** Each DIM=32 matmul is one logical block; its
  scaled+saturated partial is added to the accumulator with the `accumulate` flag — per-block
  scale-then-accumulate, matching the golden's block loop, modulo the **documented** int32
  RTL accumulator vs. int64 software-reference limitation (saturation can differ only when an
  intermediate exceeds int32 range across blocks).

**Known limitations (documented, deferred):** (1) int32 RTL accumulator vs. int64 reference
for K>32 when partials exceed int32 range (existing §10 risk; a wider shadow accumulator is a
later phase); (2) A-scale lane indexing uses `mx_block mod DIM`, so K is bounded to ≤ 32·DIM
blocks before lane wrap (also bounded by `mx_scale_sp_entries`) — fine for the S3 K≤128 test
range. **Validation:** both configs re-elaborate to clean Verilog with the pinned firtool —
MX `GemminiMXINT8DIM32RocketConfig` (635 `.sv` incl. the MX modules, 0 errors) and stock
`GemminiRocketConfig` (622 `.sv`, 0 errors), the same +13 MX-module delta as R1 ⇒ no
regression. **Compile+elaboration + structural-equivalence-to-golden only**; behavioral
confirmation of both fixes lands with **S3** (DIM=32 hardware GEMM vs. `mxint8_ref_gemm_acc`).

**Phase R3 — DIM=16 two-phase research path [DONE — single-block PASSES 2026-06-05; cross-block
(K=64/96/128) PASSES 2026-06-08].**
Verified `mx_second_half`, `mx_raw_half` buffering, and `mx_suppress_acc_write` implement the
hold invariant (scale vectors constant across both 16-wide K phases; scale applied once per
logical 32-block) by running the DIM=16 hardware GEMM vs. the golden. Added a two-phase
invariant assertion (`require(mx_block_size == 2*block_size)` + a runtime first/second-half
alternation assert, guarded by `DIM < mx_block_size` so it is inert at DIM=32). The behavioral
run (`mxint8_matmul_dim16`, see S3 #3) **proved the two-phase accumulation** (probe: each
output = `32 << shift`, i.e. the two 16-wide halves summed to the full 32-lane block then scaled
**once** — a broken sum would give `16 << shift`) and surfaced **three real RTL bugs** invisible
to compile/elaboration (all masked at DIM=32; details in S3 #3 "Behavioral RTL fixes" — D1/D2/D3).
**D4 cross-block — RESOLVED 2026-06-08 (it was a tag-misalignment, not a drain).** An
instrumented K=64 run disproved the earlier "WS last-compute drain" hypothesis: all four
physical K phases drain all 16 rows and the last block *does* reach the accumulator. The real
bug was that the per-output `mx_block`/`mx_second_half` tag fields — sampled from the live feed
counter `mx_k_lane_counter` at enqueue (`ExecuteController` ~884) — **drift one physical phase**
relative to the output at the logical-block boundary, because under WS preload/compute fusion +
the mesh's +2 tag latency the counter sample and the output association are taken at different
points (the C-address rides the tag FIFO correctly, so the *accumulate bit* is right; these
counter samples are not). The earlier "0 of 16 rows for the last block's second phase" was that
phase's rows mis-tagged `blk0 sh1`, not missing. Net effect: block1's first half was treated as
a second half (summed with a stale buffer, scaled by **block0's** exponents, written) and its
true second half as a first half (buffered + suppressed → dropped). **Fix:** re-derive
`(logical block, second-half)` at *output* time from the strict FIFO drain order with an
output-domain phase counter (`mx_phase` undelayed / `RegNext` delayed → `block = phase>>1`,
`second_half = phase(0)`), mirroring the proven `mx_a_read_row`/`output_counter` split; scoped to
`DIM < mx_block_size` so the DIM=32 tag path is byte-for-byte unchanged. Zero added cycles (no
flush, no extra pipeline stage). Verified on Verilator: `mxint8_matmul_dim16` PASSES probe +
random K=32, deterministic cross-block `xprobe` K=64 (c=96) and K=128 (c=480), and random K=64/96
(2/3-block) — i.e. 1/2/3/4 logical blocks all exact-match the golden.

**Phase R4 — Counters & (optional) refactor.**
- Add FireSim AutoCounter cover points (see §7): metadata reads/writes, metadata stall
  cycles, logical K-block transitions, DIM=16 half count, BlockScaleUnit active rows,
  accumulator writes.
- Optional cleanliness: extract the inline BlockScaleUnit into a named `BlockScaleUnit`
  module and a `MXKBlockController` to match the design doc and Rule-of-30 (methods <30
  lines, class <30 methods). Only if it does not destabilize a passing datapath.

---

## 5. Implementation Plan — Software (C)

Follow `CODING_STYLES/c_cpp_coding_style.md` throughout (see §8). Target C11.

**Phase S1 — Packer & golden model [DONE, 2026-06-04].**
Added `include/mxint8_pack.h` (new; `mxint8_golden.h` left frozen). Public API, all
`static inline` / `lower_snake_case` / `mxint8_`-prefixed with Doxygen `/** */`:
- `mxint8_quantize_block(vals, n, payload)` — quantizes one MX block of up to
  `MX_BLOCK_SIZE` fp32 values to signed int8 + one E8M0 byte. Picks the **smallest** exponent
  `e` with payload step `2^(e-6) ≥ amax/127` (so the block max maps to ≤ 127, max precision),
  rounds each element nearest-even via the house `ROUND_NEAR_EVEN` macro, clamps to the
  **symmetric** `[-127,127]` (never `-128`). All-zero block → neutral `e=0`.
- `mxint8_pack_a(a, m, k, a_stride, a_scale_stride, a_payload, a_scale)` — `A[M][K]` row-major
  fp32 → payload (same stride) + `a_scale[i·stride + b]` (one row per M row, blocks across
  lanes), exactly the `gemmini_mvin_mxscale_a` / golden layout.
- `mxint8_pack_b(b, k, n, b_stride, b_scale_stride, b_payload, b_scale)` — `B[K][N]` row-major
  fp32 → payload + `b_scale[b·stride + j]` (one row per K block, columns across lanes);
  gathers the K-strided block, quantizes, scatters back.
- Helpers `mxint8_exp2f_int` / `mxint8_ceil_log2f` (libm-free IEEE-754 bit ops) and
  `mxint8_e8m0_encode` (inverse of the golden's decode; clamps to `[-127,127]` ⇒ never `0xff`).
- **Host validation (gcc, against the real golden + DIM32 MX header):** 6 randomized cases
  (single-block, multi-block, K-tails K=33/40, non-square, full 32×32 tile, skinny 1×1) all
  pass — no `0xff` scales, no `-128` payloads, golden returns 0, and the golden accumulator
  **exactly** equals the unrounded reconstruction dot product within the per-block rounding
  bound (`max(|c−recon| − 0.5·k_blocks) = 0`), confirming payloads, scales, layout, and
  strides. Registered `mxint8_pack.h` in `bareMetalC/Makefile` `GEMMINI_HEADERS`.

**Phase S2 — Intrinsics audit [DONE, 2026-06-04].**
Cross-checked every MX intrinsic in `gemmini.h` against the RTL decode (`Controller.scala`
`CONFIG_MXINT8` handler + `MXScaleLoadController.MXScaleLoadRs2`) and the R2-validated
`MXScaleSRAM` read indexing and the golden scale strides. **Instruction encodings match
exactly — no encoding fixes needed.** One real software bug found in the loop wrapper
(deferred to S3 #5) plus a build prerequisite.

**Verified consistent (SW ↔ RTL ↔ golden):**
- `gemmini_config_mxint8` → `CONFIG_MXINT8` (26): `enable=rs1(0)`, `set_stride=rs1(1)`
  (`0x2`), `stride_is_b=rs1(2)` (`0x4`), `reset=rs1(3)` (`0x8`), `stride=rs2`. Exact.
- `gemmini_mvin_mxscale_a/b`: each is **two** instructions — a `CONFIG_MXINT8` that latches
  the DMA stride (into `mx_scale_stride_a`/`_b` per `stride_is_b`), then a
  `MVIN_MXSCALE_A`/`_B` (27/28). `rs1 = vaddr`; `rs2 = {num_rows[63:48], num_cols[47:32],
  local_addr[low log2(entries)]}` — matches `MXScaleLoadRs2` bit-for-bit (`ADDR_LEN=32`).
  `is_b` is taken from the **opcode**; the controller selects `Mux(is_b, stride_b, stride_a)`
  and `actual_stride = stride==0 ? cols : stride` (byte units; `mx_scale_t` is 1 byte).
- **A-scale layout:** mvin `rows=M`, `cols=k_blocks` ⇒ `MXScaleSRAM_A[i]` lane `b` = `eA[i][b]`
  — matches the R2 read (`addr = output row i`, `lane = mx_block mod DIM = b`) and the golden
  `a_scale[i·stride + block]`.
- **B-scale layout:** mvin `rows=k_blocks`, `cols=N` ⇒ `MXScaleSRAM_B[base+b]` lane `j` =
  `eB[b][j]` — matches the R2 read (`addr = b_scale_base + b`, `lane = j`) and golden
  `b_scale[block·stride + j]`. **B base aligns:** SW writes at `MX_SCALE_SP_ROWS/2`, RTL reads
  at `mx_scale_sp_entries/2`, and `MX_SCALE_SP_ROWS == mx_scale_sp_entries == 256` for the
  DIM=32 / 8 KB config (`= 8·1024·8 / (8·32)`), so both are 128. (The `/2` is the vestigial
  shared-SRAM convention from R1 — A in `[0,128)`, B in `[128,256)`; correct, just halves
  capacity. Software cleanup candidate, not a bug.)
- **`gemmini_loop_ws_mxint8` ordering:** `config_mxint8(true,0)` → `mvin_mxscale_a` →
  `mvin_mxscale_b` → `gemmini_loop_ws` (payload mvins/preloads/computes emitted by the
  standard WS loop; the MX K-lane counter advances on each compute). Ordering is correct, and
  the R1 reservation-station dependency makes the scale→compute order safe without a fence.
- **`LOOP_WS_MXINT8` (29) fate: RESERVED.** `gemmini_loop_ws_mxint8` is a pure software
  composition and never emits opcode 29; the RTL decodes it nowhere (only the `GemminiISA`
  definition). Keep it reserved for a possible future hardware scale-aware unroller.
- **MX params header:** `include/gemmini_params_mxint8_dim32.h` is correct and RTL-consistent
  (`MX_ENABLED 1`, `DIM 32`, `MX_BLOCK_SIZE 32`, `MX_SCALE_BITS 8`, `MX_INT_FRAC_BITS 6`,
  `MX_SCALE_EXP_BITS 9 == mx_scale_bits+1`, `MX_SCALE_SP_ROWS 256`, `mx_scale_t=uint8_t`,
  `mx_exp_t=int16_t`).

**Build prerequisite for S3 (critical):** the checked-in `include/gemmini_params.h` is the
**stock** header (`MX_ENABLED 0`, `DIM 16`), so all MX intrinsics currently `#if`-compile to
no-ops. MX baremetal tests **must** be built against the DIM=32 MX header (regenerate/copy
`gemmini_params_mxint8_dim32.h` → `gemmini_params.h`, or otherwise force `MX_ENABLED=1`).

**`gemmini_loop_ws_mxint8` tile-vs-element bug — FIXED 2026-06-08 (S3 #5).** `gemmini_loop_ws`'s
`I/J/K` are **tile** iterators (`LoopMatmul` multiplies each by `block_size`), but the wrapper
fed those tile counts straight to the element-indexed scale mvins. **Two** count bugs (both
fixed in `include/gemmini.h`): (1) A-scale `rows` / B-scale `cols` must be the M / N **element**
counts (`I·DIM − pad_I` / `J·DIM − pad_J`), not `I` / `J`; (2) `k_blocks` was computed as
`ceil(K/MX_BLOCK_SIZE)` treating the **tile** count `K` as elements — it must be over the K
*element* extent `K·DIM − pad_K` (e.g. 2 tiles at DIM=32 = 64 K-elem = 2 MX blocks, not
`ceil(2/32)=1`; the second bug was caught by the new `mxint8_tiled.c` when its K=64 case tripped
the `invalid E8M0 scale` assert because block 1's A scales were never loaded). Also added a hard
single-output-tile guard (`I==J==1`, `printf`+`exit`) — the A-scale read address is the output
row (`output_counter`, 0..DIM-1) with **no output-tile component**, so multi-tile would reuse one
tile's scales; multi-tile needs a new RTL scale-read-address tiling path (later phase). **Test:**
`bareMetalC/mxint8_tiled.c` (registered) drives the HW WS loop unroller through the wrapper at
I=J=1 and PASSES a single-MX-block GEMM vs. the golden on the DIM=32 Verilator sim. **New
limitation found (deferred) — fully diagnosed 2026-06-09:** cross-block K > one MX block
*through the hardware loop unroller* is wrong, but **only when the loop GEMM is preceded by
another loop GEMM** (a loop K=64 in isolation passes). **Root cause: inter-GEMM state leakage in
the systolic mesh's internal output pipeline** — a prior loop GEMM leaves residual mesh state
that freezes the next GEMM's first K-tile mesh output after ~2 rows (it holds a stale prior
value). Exhaustively ruled out everything else (each verified on RTL): the MX scaling, two-phase
buffering, cross-block accumulate, and mvout are all correct (`mvout == block0 + block1`); the
A/B scale reads, `tag.mx_block`, `mx_a_read_row`, and `mx_delay_outputs` are all correct (an
earlier "corrupt raw" reading was a 20-bit-signed logging artifact); the A-feed completes (afc
0→31); and every deterministic data/scale-variation probe **passes in isolation**. A software
mesh drain (dummy WS matmuls between GEMMs) does **not** clear it, and an RTL flush cannot
either (the mesh is idle between GEMMs, so flush only re-advances the array like the dummy
matmuls). The fix would require resetting Mesh-internal pipeline state between GEMMs — the
`Mesh` is **DO-NOT-TOUCH**. This is **not** an MX-datapath bug and is orthogonal to the wrapper
scale-count fix and the **manual** cross-block path (`mxint8_matmul_dim32` K=64 and
`mxint8_matmul_dim16` K=64/96/128 all pass), which is the supported route for multi-block MX.
**[SUPERSEDED 2026-06-10 — the mesh-leakage conclusion above was WRONG; the limitation is
LIFTED.** Real root cause: an RS RAW-hazard miss — the scale mvins polluted the reservation
station's CONFIG_LOAD decode mirror, mis-decoding the A-payload mvin's dependency range, so the
compute issued while the A tile was still being DMA-written; the "frozen mesh output" was the
mesh correctly multiplying stale scratchpad rows. Fixed with a one-line `!is_mx_scale` decode
guard in `ReservationStation.scala`; loop-wrapper multi-block K now passes and is
regression-tested (`mxint8_btb`, `mxint8_tiled` K=32→K=64 BtB). Full story: `DOCS_MX/BUG.md`
and the 2026-06-10 progress-log entries below.]

**Phase S3 — End-to-end hardware tests [IN PROGRESS — first HW GEMM PASSES on RTL].**
Add to `bareMetalC/` and register in `Makefile`:
1. ~~`mx_scale_mvin_mvout.c` — load scale bytes into `MXScaleSRAM`, read back, compare.~~
   **Reframed:** a *pure* scale mvin→mvout round-trip is **infeasible** — there is **no scale
   read-back DMA path** (the S2 audit confirmed `MXScaleSRAM` is write + `read_a`/`read_b`
   into the datapath only; adding a scale-mvout would be new RTL out of v1 scope). The
   scale-load + RS-ordering validation is therefore **folded into the scale-sensitive matmul
   (#2)**: if the hardware GEMM matches the golden, the scales were loaded and read correctly.
2. **`mxint8_matmul_dim32.c` — [DONE — PASSES on Verilator RTL, 2026-06-05].** Two cases, both
   exact-match the golden: a **deterministic probe** (all-ones payloads, `eA[i]=6+(i%4)`,
   `eB[j]=6+(j%3)` ⇒ `hw[i][j] = raw << ((i%4)+(j%3))`; the per-row variation makes the R2-1
   per-output-row A-scale fix *directly observable*) and a **randomized** case packed by
   `mxint8_pack.h` at **K=64** (two MX blocks ⇒ cross-block accumulation), single output tile
   `M=N=DIM`. **Construction (important — differs from the original sketch):** MX v1 is
   **untransposed-WS-only**, so the standard `tiled_matmul_auto` path **cannot** be used (it
   feeds B through the transposer, `bd_transpose=1`, which trips the MX assert). The test uses
   the **manual** untransposed WS sequence (`config_mxint8` → `config_ex(WS)` →
   `mvin_mxscale_a/b` → payload mvins → per-block `preload(B)`/`compute(A)` accumulating in a
   **full-width int32 accumulator** (`acc_base = (1<<(ADDR_LEN-1)) | (1<<(ADDR_LEN-3))`,
   `config_st(DIM*sizeof(acc_t))`) → `mvout`). Built with the chipyard `$RISCV` gcc 13.2.0
   (+newlib; the system `/usr/bin` elf-gcc lacks newlib) against the staged DIM32 MX header.
   **This run is the behavioral validator for R1, R2-1, R2-2, S1, and S2** — and it surfaced
   **three real RTL bugs** invisible to compile+elaboration (see "Behavioral RTL fixes" below).
3. **`mxint8_matmul_dim16.c` — [DONE — single logical block PASSES on Verilator RTL,
   2026-06-05].** First end-to-end **DIM=16 two-phase** hardware GEMM. Built against a new
   `include/gemmini_params_mxint8_dim16.h` (`DIM 16`, `MX_SCALE_SP_ROWS 512`; identical to the
   DIM32 header except those two fields) and run on a freshly-built
   `GemminiMXINT8DIM16RocketConfig` Verilator sim. Each logical 32-K block is issued as **two**
   `preload(B half)/compute(A half)` pairs of 16 K-rows (`MX_BLOCK_SIZE/DIM = 2` physical
   phases); the inline BlockScaleUnit buffers the first half (`mx_raw_half`, acc write
   suppressed) and on the second half sums both halves' raw partials and applies the block scale
   **once**. Three cases all exact-match the golden: a **deterministic probe** (`hw[i][j] =
   32 << ((i%4)+(j%3))`; the `32` — not `16` — directly proves the two 16-wide halves are summed
   into the full 32-lane block before scaling, i.e. the 32-lane K-block advance) and **two
   randomized** single-block draws. **Behaviorally validates R3** and **fixed three real RTL
   bugs** no compile/elaboration could catch (all inert/masked at DIM=32):
   - **D1 — `MXScaleLoadController` scale-load DMA `cmd_id` misattribution.** `cmd_id =
     RegEnable(alloc.cmd_id, alloc.fire)` lags the allocation by one cycle, but the **first** row
     request of a command is issued in the *same* cycle as the allocation, so it used the stale
     id → the DMA response was attributed to the wrong `DMACommandTracker` slot → `bytes_left`
     underflow assert (`DMACommandTracker.scala:89`). Only triggers when `nCmds = max_in_flight/
     DIM + 1 > 1`, i.e. **2 at DIM=16** (1 at DIM=32, so the stale id is always 0 and the bug is
     masked). **Fix:** drive the first request's `cmd_id` combinationally from
     `cmd_tracker.io.alloc.bits.cmd_id` while in `waiting_for_command` (`Mux` on the state).
   - **D2 — `mx_raw_half` first-half buffer clobbered by WS bubbles.** The store `when
     (mx_dim16_first_half)` lacked the `start_array_outputting && write_this_row` guards the
     accumulator write has. WS emits non-output cycles that are still `mx_enabled` (the
     multiply's garbage-address output + pipeline bubbles, `rob_id.valid=false`, stuck at
     `output_counter 0`); these re-stored `mx_raw_half(0)` with **zero** between the two phases,
     so the second half read 0 and only one 16-wide half reached the accumulator (`hw = 16`
     instead of `32`). **Fix:** gate the store like the accumulator write.
   - **D3 — B scale latched at feed time, clobbered across logical blocks.** B was read once at
     mesh-feed (`b_read_start`) into a single `mx_b_exp` latch and held. With the deep WS output
     pipeline (worst at DIM=16, where a logical block = two phases) the **next** block's feed
     overwrote the latch while the **current** block's outputs were still draining, so the
     current block's tail rows were scaled by the next block's B exponents (plus a spurious
     B-not-valid assert). **Fix:** read B at **output** time, addressed by each output's own
     `tag.mx_block`, mirroring the proven A-scale read (deleted `mx_b_exp/_invalid/_valid`
     regs + the feed-time read block). Strictly more correct; **DIM=32 re-verified — no
     regression** (`mxint8_matmul_dim32` incl. K=64 cross-block, `mxint8_corner`,
     `mxint8_matmul_partial` all still PASS on a rebuilt DIM=32 sim).

   **Cross-block K > 32 at DIM=16 — [DONE — PASSES on Verilator RTL, 2026-06-08].** Originally
   filed as a known limitation (D4, suspected WS last-compute *drain*), but an instrumented K=64
   run showed all phases drain fully; the real bug was the feed-sampled `mx_block`/`mx_second_half`
   tag fields **drifting one physical phase** at the logical-block boundary (WS preload/compute
   fusion + the mesh's +2 tag latency), so the last block was scaled by the previous block's
   exponents and its true second half was buffered+suppressed. **Fix (D4):** re-derive
   `(block, half)` at output time from the FIFO drain order via an output-domain phase counter,
   scoped to `DIM < mx_block_size` (DIM=32 untouched). The extended test now covers 1/2/3/4 logical
   blocks: probe + random K=32, deterministic `xprobe` K=64 (=96) / K=128 (=480), random K=64/96 —
   all exact-match the golden. (Full root cause in Phase R3 above.)
4. **`mxint8_corner.c` — [DONE — PASSES on Verilator RTL, 2026-06-05].** Single full tile
   `M=N=DIM`, integer setup (no fp packer, so all cases fit one binary under an extended
   `timeout_cycles`). All pass vs. the golden: **all-zero** payloads (C=0); **max ±127** with a
   large shift saturating the int32 accumulator — both the in-range case and the **64-bit
   overflow regime** (exercises the R2-2 `scalePowerOfTwo`/`saturateToAcc` path, → `INT32_MAX`/
   `INT32_MIN`); **alternating signs** with small scales (negative shift ⇒ nearest-even
   right-shift rounding); **random valid exponents** (mixed ±shifts); **K-tail `K=40`** (second
   block only 8 valid K lanes, rest zero-padded — first behavioral confirmation of the
   tail-masking the R2 audit reasoned about); and a golden-only **`0xff` reject** (golden
   returns −1; loading `0xff` into hardware would fire the `MXScaleSRAM` assert). The test is
   `M=N=DIM` only — partial `M/N<DIM` is covered by #4b below.
4b. **`mxint8_matmul_partial.c` — [DONE — PASSES on Verilator RTL, 2026-06-05].** Closes the
   last DIM=32 gap: **partial output tiles** (`M` and/or `N < DIM`). Payload `mvin`, `preload`,
   `compute`, and `mvout` all carry the real `M`/`N` via the **extended-dim** intrinsics; the K
   dimension per MX block stays full (`MX_BLOCK_SIZE == DIM == 32`), so only `M`,`N` are
   partial. Six cases (randomized + golden, a deterministic per-row/col **probe**, and a
   no-spill check that nothing is written outside `M×N`): `probe(10×12)`, `full(32×32)`,
   `row1(1×32)`, `col1(32×1)`, `part(13×20)`, `part2(7×9, K=64 cross-block)` — all PASS.
   **Partial-tile scale contract (no HW change):** the MX scale `mvin` must cover the **full
   DIM** lanes/rows (pad unused A-rows / B-cols with neutral `encode(0)`), because (a) the
   B-scale validity assert (`ExecuteController` ~894) checks **all DIM lanes** of the loaded
   vector and (b) the A-scale read counter wraps at the mesh `total_rows` (= K block size =
   `DIM` here, since `rows_b=K` dominates even when `M<DIM`), so every DIM-th row is read even
   when only `M` are written out. The output masking (`output_counter < C_rows`,
   `lane < C_cols`) drops the padded rows/cols from the accumulator and mvout. Payload mvin /
   compute / mvout use the partial dims directly.
5. `mxint8_tiled.c` — larger GEMM through the loop wrapper (after baremetal passes; also fixes
   the S2 `gemmini_loop_ws_mxint8` tile-vs-element-count bug).

Each test: random inputs, pack with the §S1 packer, run hardware, diff vs. golden, exit
0/1. Reuse `gemmini_testutils.h` helpers and existing test structure.

**Run pipeline (used for #2):** (a) stage the DIM32 MX header as `include/gemmini_params.h`
(`MX_ENABLED=1`); (b) build the baremetal ELF with the chipyard `$RISCV` toolchain
(`source env.sh`) — direct `riscv64-unknown-elf-gcc … -T riscv-tests/benchmarks/common/test.ld
… *.c *.S` (avoids the autotools `build.sh` rebuilding all tests); (c) build the **Verilator**
simulator (`make -C sims/verilator CONFIG=GemminiMXINT8DIM32RocketConfig FIRTOOL_BIN=…`); (d)
`make … run-binary BINARY=…`. Spike/esp-tools will **not** validate this — the gemmini ISA
model does not implement the MX sidecar, so only the RTL sim exercises the fixes.
**Gotchas learned:** Chisel `printf` output goes to **stderr** → the run's `*.out` file (after
`spike-dasm`), *not* the `*.log`; the Verilator watchdog (`TestDriver.v:147`) stops at ~10 ms
sim time, so keep a test to ≲2 matmul tiles; an RTL edit requires forcing re-elaboration (`rm
-rf` the config's `generated-src`).

**Behavioral RTL fixes (found by S3 #2; none catchable by compile/elaboration).** All three
were exposed only by running the GEMM on the RTL and diffing against the golden:
- **B1 — reset-less transpose Regs.** `a_transpose`/`bd_transpose` were `Reg(Bool())` (no
  reset). `mx_compute_active` goes high the instant MX is enabled and the MX path asserts
  untransposed-WS-only, so under Verilator rand-reset the garbage transpose value tripped the
  assert before `config_ex` ran. **Fix:** `RegInit(false.B)` (a genuinely latent bug — stock
  always sets transpose via `config_ex` before compute, so it was masked).
- **B2 — WS output inherited the preload's tag, which was not MX-tagged (the decisive bug).**
  In weight-stationary mode the matmul *result* is tagged by the **preload** (it carries the C
  address; the multiply feeds A with a garbage output address). The MX WIP set the tag's
  `mx_enabled`/`mx_block` only on the **multiply** (`performing_single_mul || performing_mul_pre`),
  so the output tag had `mx_enabled=0` and the BlockScaleUnit was skipped — the accumulator got
  the **raw, unscaled** int partial (`hw==raw` exactly). **Fix:** also mark
  `performing_single_preload`, so the preload (= the output-bearing tag) carries the MX marker
  and the correct logical-K block. **Fix verified in-sim:** the per-row `aexp` trace showed each
  output row reading its own `eA[i]` (6,7,8,9,…) and `hw = raw << shift`.
- **B3 — (test-side, but RTL-adjacent) the standard tiled WS path is transposed.**
  `tiled_matmul_auto`/`sp_tiled_matmul_ws` feed B through the transposer (`bd_transpose=1`),
  which the MX v1 assert forbids; the test must use the manual untransposed
  `preload(B)`/`compute(A)` form. Documented as an MX-usage constraint (a transpose-aware MX
  path is out of v1 scope).

**Validated (2026-06-05):** `mxint8_matmul_dim32` PASSES on Verilator — probe `K=32` and
randomized `K=64` (cross-block) both exact-match the golden; stock `GemminiRocketConfig` still
elaborates with **0 firtool errors** (the B1/B2 fixes are inert when MX is disabled —
`mx_compute_active` is false). This **behaviorally confirms R1, R2-1, R2-2, S1, S2** on real
RTL — upgrading them from "compile+elaboration only" to "hardware-verified" for `M=N=DIM`,
`K∈{32,64}`.

---

## 6. Verification Plan (end-to-end)

**Layered, matching the design doc:**
1. **Software unit** — `make mxint8_golden`; golden helpers pass deterministic + random.
2. **HW module sim** — Verilator/VCS baremetal: `mx_scale_mvin_mvout` round-trip.
3. **Tile RTL** — DIM=32 identity, K=32/64/96/128 vs. golden; then DIM=16 two-phase.
4. **Corner cases** — the `mxint8_corner` matrix above; assertions must not fire.
5. **System** — tiled GEMM through the C wrapper; reproducible counters.

**Invariants to assert (keep/confirm in RTL):** metadata-valid-before-compute; DIM=16 hold;
K-block advances per 32 logical lanes; tail lanes contribute zero; no scale byte in payload
transposer; block scaled before cross-block accumulate.

**Build/run flow.** Verilator/VCS first for short RTL debug
(`generators/gemmini/software/gemmini-rocc-tests/build/bareMetalC/<test>-baremetal` via the
standard Gemmini build script `build.sh`); move to FireSim only once baremetal passes and
the target config builds cleanly. Freeze Chipyard/Gemmini/FireSim commits before evaluation.

---

## 7. Research & Evaluation Track

**FireSim AutoCounters** (cover functions in `ExecuteController`, `Scratchpad`,
`MXScaleSRAM`, `MXScaleLoadController`): kernel cycles, useful MACs, payload vs. metadata
DMA bytes, MXScaleSRAM reads/writes, metadata stall cycles, K-block transitions, DIM=16 half
count, transposer active cycles, BlockScaleUnit active rows, accumulator writes, and
LUT/FF/BRAM/DSP/Fmax from synthesis.

**Configurations / baseline matrix:**

| Config | DIM | MX mode | Purpose |
|---|---|---|---|
| Stock Gemmini int8 | 32 | off | aligned lower bound |
| MXINT8 aligned | 32 | sidecar | working MX path |
| Stock Gemmini int8 | 16 | off | default-style baseline |
| Software-repack | 16 | repack | realistic SW workaround |
| Duplicate-transposed metadata | 16 | duplicate | storage/movement trade-off |
| **Proposed MXINT8** | 16 | logical-K | main contribution |
| Oracle no-stall | 16 | logical-K | headroom |

**Ablations:** DIM=32 vs 16; metadata-stall on/off; duplicate vs logical remap; int32 vs
int48/64 shadow acc; fixed vs dynamic tile exponent; tail-block odd K; identity-scale ==
stock int8; transpose-aware on/off (only if claimed).

**Workloads:** synthetic GEMM suite (K∈{32,64,128,256,512}, square/skinny/fat/K-tail);
transformer-derived (BERT-style attention+MLP) GEMM traces; ResNet50-style lowered GEMM
(if low friction); software-only accuracy sanity (BERT-Large/SQuAD) — software only.

**Paper artifacts:** figures (MX block/scale grouping; DIM=16 mismatch; sidecar arch; DIM=32
& DIM=16 timelines; perf/metadata/area charts) and tables (numerical policy; configs;
HW module changes; corner cases; counters; resource overhead; ablations). Target a
reconfigurable-computing/architecture venue (e.g. FCCM); short letter fallback (CAL).

---

## 8. Coding Style Compliance

**Scala/Chisel (`CODING_STYLES/scala_coding_style.md`):** 2-space indent, ≤100-char lines;
`PascalCase` classes/objects, `camelCase` methods/vals, `UPPER_CASE` constants in companion
objects; JavaDoc `/** ... */` (not ScalaDoc `/** * */`); braces around all conditionals/loops;
`override` on all overrides; explicit return types on public methods; avoid implicits and
symbolic methods; Rule-of-30 (methods <30 lines, classes <30 methods) — motivates extracting
`BlockScaleUnit`/`MXKBlockController`; `private[this]` and `while` loops in perf-sensitive
generator code where applicable.

**C (`CODING_STYLES/c_cpp_coding_style.md`):** C11; pointer asterisk by name (`elem_t *p`);
braces on all conditionals/loops; `// C99` comments, backtick-delimit identifiers; Doxygen
`/** */` with `@param`/`@return` on public functions in headers; `lower_snake_case` for
functions/structs/typedefs/enums; enum constants prefixed by their type; one shared prefix
per header (`mxint8_`, `gemmini_`); prefer `enum` over macro constants except where used by
the RoCC asm encodings (macros are the right choice there); hygienic function-like macros
(`do { } while (false)`, no trailing `;`); designated initializers for the MX tile
descriptor struct; `TODO: ...` format referencing issues.

---

## 9. Milestones (staged)

| Milestone | Exit criterion |
|---|---|
| M0 Build gate | ✅ **DONE (2026-06-04)** — gemmini compiles clean (R0a); `GemminiMXINT8DIM32RocketConfig` elaborates to Verilog, 648 `.sv` incl. all MX modules (R0b); stock `GemminiRocketConfig` unaffected |
| M1 Golden + packer | ✅ **DONE (2026-06-04)** — `mxint8_pack.h` packer + frozen `mxint8_golden.h`; randomized host validation passes on odd/even/tail M,N,K (`c == reconstruction` within per-block rounding bound) |
| M2 Scale round-trip | ✅ **subsumed by M3/M4 (2026-06-05)** — no scale-readback path exists; correct scaled GEMM proves the load+read path |
| M3 DIM=32 identity | ✅ **DONE (2026-06-05)** — probe with `shift=0` lanes matches the raw int8 partial; full probe exact on Verilator RTL |
| M4 DIM=32 scaled | ✅ **DONE (2026-06-05)** — `mxint8_matmul_dim32` exact vs. golden, probe `K=32` + randomized `K=64` (cross-block). K=96/128 deferred (sim watchdog; needs split runs) |
| M5 DIM=16 two-phase | ✅ **DONE (2026-06-08)** — `mxint8_matmul_dim16` exact vs. golden on Verilator RTL: probe `hw = 32<<shift` (two 16-wide phases sum to the full 32-lane block, scaled once) + random K=32, **and cross-block** (deterministic `xprobe` K=64=96 / K=128=480, random K=64/96) — 1/2/3/4 logical blocks all pass. Fixed 4 RTL bugs (D1 scale-DMA `cmd_id`, D2 `mx_raw_half` clobber, D3 feed-time B-scale latch, **D4 feed-sampled `mx_block`/`mx_second_half` tag drift → output-domain re-derivation**). |
| M6 Corner cases | ✅ **DONE (2026-06-05)** — `mxint8_corner` passes (zero, ±int32/64-bit saturation, neg-shift rounding, random exps, **K-tail**, `0xff` reject), no assertion fires; `mxint8_matmul_partial` passes **partial `M/N<DIM`** tiles (single row/col, general, partial+cross-block) — last DIM=32 gap closed |
| M7 Eval cut | baseline/proposed plots + resource/Fmax reports exist |

---

## 10. Risks & Mitigations

- **WIP doesn't build** → R0 build gate first; fix before features.
- **Accumulator semantics for K>32** → freeze exponent policy; test int32 vs int64 acc;
  document int32 RTL limitation vs. wide-acc reference.
- **A-scale read addressing / tail bugs** → targeted audit in R2 + corner tests (most MX
  bugs are metadata bugs, not arithmetic bugs).
- **DIM=16 control invasiveness** → keep DIM=32 always passing; gate DIM=16 behind M4.
- **Transposer/OS scope creep** → v1 asserts WS-only, untransposed; narrow the claim.
- **FireSim/Chipyard drift** → freeze commits before evaluation.

---

## 11. Deliverable

This document (`DOCS_MX/PLAN_MX.md`) is the plan of record. It reuses the existing WIP and
frozen contract (`mxint8_policy.md`, `mxint8_golden.h`), names the exact files to modify, and
provides a staged, testable path from build gate → DIM=32 MVP → DIM=16 contribution →
evaluation.

---

## 12. Progress Log

- **2026-06-04 — R0a compile gate: PASS.** Diagnosed and fixed the build environment (system
  JDK 21 was shadowing conda-pinned JDK 20, breaking sbt's parser; fix = prepend
  `$CONDA_PREFIX/bin` to PATH — see Build Environment box in §4). `sbt "project gemmini"
  clean compile` then recompiled all 57 gemmini sources including the full MX WIP with 0
  errors. Conclusion: the uncommitted MXINT8 scaffolding compiles cleanly; no MX code fixes
  needed at the compile level. **Next:** R0b — create a Chipyard top-config that instantiates
  Gemmini RoCC with `GemminiMXINT8DIM32Config` and elaborate to Verilog.
- **2026-06-04 — R0b full Verilog elaboration: PASS.** Added `GemminiMXINT8DIM32RocketConfig`
  + `GemminiMXINT8DIM16RocketConfig` to `generators/gemmini/chipyard/GemminiConfigs.scala`.
  Chisel→FIRRTL→Verilog via `make … CONFIG=GemminiMXINT8DIM32RocketConfig verilog` emitted
  **648 `.sv`/`.v`** files incl. all MX modules (`MXScaleSRAM`, `MXScaleLoadController`,
  `ExecuteController`, `Scratchpad`, `AccumulatorMem`, `Gemmini`). Elaboration caught two real
  bugs (invisible to the R0a compile gate), both fixed: **(1)** `AccumulatorMem.scala:223`
  `None.get` — pre-existing Gemmini bug (`io.ext_mem.get(i)` in the non-shared-ext-mem branch),
  latent until an `acc_singleported` config elaborates standalone; fixed with `.foreach`.
  **(2)** `Scratchpad.scala:936` "Port 18 already connected" — MX WIP bug: `mx_scale_reader`
  reused the main reader's `CounterEvent` IDs; tied off with `DontCare` (dedicated MX counters
  → R4). Also diagnosed a **firtool toolchain mismatch** affecting *both* stock and MX (stale
  `/usr/local/bin/firtool` LLVM-17 can't parse backtick-escaped numeric fields; use pinned
  `~/.cache/llvm-firtool/1.62.1` via `FIRTOOL_BIN`). Stock `GemminiRocketConfig` elaborates
  identically → no MX regression. **M0 build gate closed. Next:** R1 (audit scaffolding) and
  S3 (first end-to-end hardware GEMM test — the top-priority gap).
- **2026-06-04 — R1 audit & harden: DONE.** Full trace of the scale path. Found and fixed
  **three** real bugs invisible to the R0a compile gate: **(A)** matmul took no RS dependency on
  the scale mvins it consumes (scales live in the sidecar SRAM, no address overlap) → could read
  stale scales; **(B)** scale loads were `is_config` ⇒ `complete_on_issue`, freeing the RS slot
  on issue while `MXScaleLoadController` still sends a real completion → freed/reused-slot
  assert, blocking S3; **(C)** `ExecuteController` left `mx_b_exp`/`mx_b_invalid`/
  `mx_mesh_resp_bits` undriven when `mx_enabled=false` → 64 firtool uninitialized-sink errors in
  *stock* (surfaced by lowering stock through the pinned firtool for the first time; corrects the
  R0b "stock parity" note). Fixes: `ReservationStation.scala` gained an `is_mx_scale` marker
  (scale loads now complete on real DMA completion; a compute depends on every in-flight scale
  load — inert when MX is off); `ExecuteController.scala` drives the disabled-path wires with
  `DontCare`. **Validated:** stock (635 `.sv`) and MX DIM32 (648 `.sv`) both elaborate to clean
  Verilog, 0 firtool errors. Compile+elaboration only — **behavioral** correctness of the RS
  ordering fix awaits **S3** vs. the golden model. **Next:** R2 (DIM=32 datapath correctness)
  and S3 (first end-to-end hardware GEMM test).
- **2026-06-04 — R2 DIM=32 datapath correctness: DONE.** Static audit of the DIM=32 MX path in
  `ExecuteController.scala` against the frozen golden (`mxint8_golden.h`) and policy, plus the
  `MXScaleSRAM` 1-cycle read timing. Found and fixed **two** result-corrupting bugs the
  elaboration gate cannot catch: **(R2-1)** the A-scale SRAM read used the *delayed*
  `output_counter` while being issued in the *undelayed* output domain, so output row `i` was
  scaled by `eA[i-1]` for every row ≥ 1 — fixed with a dedicated undelayed `mx_a_read_row`
  counter; **(R2-2)** `scalePowerOfTwo`'s left shift truncated to 64 bits (silent wrap) and
  built a ~1000-bit shifter — now saturates to int64 like the golden with a bounded shifter,
  and `roundRightNearestEven` returns 0 at shift ≥ 63 to match the golden. B-scale timing,
  tail masking, and cross-block accumulation audited clean (cross-block matches the golden
  modulo the documented int32-vs-int64 accumulator limit). Both fixes are inert when MX is
  off. **Validated:** stock (622 `.sv`) and MX DIM32 (635 `.sv`) both re-elaborate to clean
  Verilog, 0 firtool errors, identical +13 MX-module delta ⇒ no regression. Compile +
  elaboration + structural equivalence to the frozen golden only; **behavioral** confirmation
  awaits **S3**. **Next:** S3 (first end-to-end DIM=32 hardware GEMM vs. `mxint8_ref_gemm_acc`
  — now the top-priority gap and the behavioral validator for R1+R2) and R3 (DIM=16 two-phase).
- **2026-06-04 — S2 intrinsics audit: DONE.** Cross-checked all MX intrinsics in `gemmini.h`
  against the RTL decode (`Controller` `CONFIG_MXINT8`, `MXScaleLoadController.MXScaleLoadRs2`)
  and the R2-validated `MXScaleSRAM` indexing + golden strides. **Encodings match exactly** —
  config bits (`enable`/`set_stride`/`is_b`/`reset` + `rs2` stride), the two-instruction scale
  mvin (`CONFIG` sets stride → `MVIN_MXSCALE_A/B` 27/28 with `rs2={rows[63:48],cols[47:32],
  laddr}`), A layout (`rows=M,cols=k_blocks` ⇒ `eA[i][b]`) and B layout (`rows=k_blocks,cols=N`
  ⇒ `eB[b][j]`) all agree with the read path and golden. B base `MX_SCALE_SP_ROWS/2 ==
  mx_scale_sp_entries/2 == 128` (both = 256 for DIM32/8KB). `LOOP_WS_MXINT8` (29) is **reserved**
  (never emitted, never decoded). **No encoding fixes needed.** Found one **software bug**:
  `gemmini_loop_ws_mxint8` feeds the scale mvins **tile-counts** (`I`, `J`) where they need
  **element-counts** (A `rows` must be `I·DIM−pad_I`; B `cols` must be `J·DIM−pad_J`), and the
  A-scale read has no tile index ⇒ the datapath is single-output-tile only (M,N ≤ DIM). The
  manual mvin path (S3 #1–#4) is correct; **deferred** the wrapper fix to S3 #5. **Build
  prereq flagged:** default `gemmini_params.h` is stock (`MX_ENABLED 0`); MX tests must build
  against `gemmini_params_mxint8_dim32.h` (`MX_ENABLED 1`, DIM 32, `MX_SCALE_SP_ROWS 256`).
  **Next:** S1 (packer) then S3 #1 (`mx_scale_mvin_mvout` round-trip) and #2
  (`mxint8_matmul_dim32` vs. `mxint8_ref_gemm_acc` — behavioral validator for R1+R2).
- **2026-06-04 — S1 packer: DONE.** Added `include/mxint8_pack.h` (golden left frozen):
  `mxint8_quantize_block` (fp32 block → int8 payloads + E8M0 byte; smallest exponent with
  `2^(e-6) ≥ amax/127`, nearest-even via `ROUND_NEAR_EVEN`, symmetric `[-127,127]`),
  `mxint8_pack_a` / `mxint8_pack_b` (full-matrix pack in the exact mvin/golden row/lane layout
  and strides), plus libm-free `mxint8_exp2f_int` / `mxint8_ceil_log2f` and `mxint8_e8m0_encode`
  (never emits `0xff`). Validated on the host vs. the real golden + DIM32 MX header: 6
  randomized cases (single/multi-block, K-tails 33/40, non-square, 32×32 tile, skinny 1×1) all
  pass — golden returns 0 and `c_acc` exactly equals the reconstruction dot within the per-block
  rounding bound (`max(|c−recon|−0.5·k_blocks)=0`). Registered in `bareMetalC/Makefile`
  `GEMMINI_HEADERS`. M1 closed. **Next:** S3 #1 `mx_scale_mvin_mvout` (scale SRAM round-trip,
  M2) → S3 #2 `mxint8_matmul_dim32` vs. golden (M3/M4, behavioral validator for R1+R2), built
  against `gemmini_params_mxint8_dim32.h` and run on Verilator (on PATH).
- **2026-06-04 — S3 first hardware test: WRITTEN + COMPILES (behavioral run pending).** A pure
  scale mvin→mvout round-trip (planned #1) is **infeasible** — no scale read-back DMA path
  exists (S2) — so the scale-load + R1-ordering validation is folded into the scale-sensitive
  matmul. Wrote `bareMetalC/mxint8_matmul_dim32.c` (registered in the Makefile,
  `#if MX_ENABLED`-guarded): fp32 inputs with per-row/col magnitude spread → `mxint8_pack.h` →
  `gemmini_mvin_mxscale_a/b` → WS `full_C` `tiled_matmul_auto` (int32 acc out; MX transparent
  to the payload datapath) → diff vs. `mxint8_ref_gemm_acc`, for `M=N=DIM`, `K∈{32,64,96,128}`.
  The per-row A-scale spread specifically exercises the R2-1 fix. **Compile-validated for
  RISC-V** with the staged DIM32 MX header using the chipyard `$RISCV` gcc 13.2.0 (+newlib);
  the `/usr/bin` elf-gcc has no newlib. **Next:** behavioral run — stage MX header → build
  baremetal ELF (`source env.sh`) → build the Verilator `GemminiMXINT8DIM32RocketConfig` sim
  (long) → `run-binary`, expect `PASS`. (Spike won't do: no MX in the ISA model.)
- **2026-06-05 — S3 #2 first end-to-end DIM=32 hardware GEMM: PASS on Verilator RTL.** Built the
  baremetal ELF (chipyard `$RISCV` gcc + `test.ld`) and the `GemminiMXINT8DIM32RocketConfig`
  Verilator simulator, and ran `mxint8_matmul_dim32` to a clean `PASS`: a deterministic probe
  (`K=32`, per-row/col scales) and a randomized cross-block case (`K=64`) both exact-match
  `mxint8_ref_gemm_acc`; stock elaborates with 0 firtool errors (no regression). The run
  **behaviorally confirms R1, R2-1, R2-2, S1, S2** on real RTL. Getting there required rewriting
  the test from `tiled_matmul_auto` to a **manual untransposed WS** sequence with a full-width
  int32 accumulator mvout (MX v1 forbids the transposer the tiled path uses), and **fixed three
  RTL bugs no compile/elaboration could catch** (see "Behavioral RTL fixes" in §S3): **(B1)**
  reset-less `a_transpose`/`bd_transpose` → `RegInit(false)`; **(B2, decisive)** in WS the output
  is tagged by the *preload* but the MX marker was only on the *multiply*, so the BlockScaleUnit
  was skipped and raw partials were written — fixed by tagging `performing_single_preload` too;
  **(B3)** documented the untransposed-WS-only usage constraint. Debugging used temporary Chisel
  `printf`s (since removed) — note: their output lands in the run's `*.out` (stderr), not `*.log`.
  **Next:** extend S3 to corner cases (`mxint8_corner`: K-tail, partial M/N, `0xff` reject,
  all-zero/max blocks) and the DIM=16 two-phase path (R3 + `mxint8_matmul_dim16`); revisit the
  K=96/128 cases with split runs (10 ms sim watchdog) and the `gemmini_loop_ws_mxint8` wrapper
  fix (S3 #5).
- **2026-06-05 — S3 #4 corner cases: PASS on Verilator RTL.** Added `bareMetalC/mxint8_corner.c`
  (integer setup, no fp packer; all cases in one binary under an extended `timeout_cycles`).
  All pass vs. the golden: all-zero (C=0), max ±127 saturating the int32 acc in both the
  in-range (`shift 14`) and **64-bit-overflow (`shift 50`)** regimes (→ INT32_MAX/MIN; exercises
  the R2-2 saturate path), alternating-sign payloads with small scales (negative shift ⇒
  nearest-even right-shift rounding), random valid exponents (mixed ±shifts), **K-tail K=40**
  (block 1 = 8 valid K lanes + zero pad — first behavioral confirmation of tail masking), and a
  golden-only `0xff`-reject (rc=−1). Reused the untransposed-WS manual sequence + full-int32
  accumulator; the MX sim binary was already built, so corner iterations are ELF-only.
  **Remaining:** partial `M/N<DIM` tiles (need extended preload/compute/mvout dims). **Next:**
  R3 + `mxint8_matmul_dim16.c` (the DIM=16 two-phase contribution), partial-tile corner, K=96/128
  split runs, and the `gemmini_loop_ws_mxint8` wrapper fix (S3 #5).
- **2026-06-05 — S3 #4b partial output tiles: PASS on Verilator RTL (last DIM=32 gap closed).**
  Added `bareMetalC/mxint8_matmul_partial.c`. Six cases pass vs. the golden in ~19 min on the
  existing MX sim binary: deterministic `probe(10×12)` (per-row/col scale addressing:
  `c[9][11] = 32<<((9%4)+(11%3)) = 256` ✓), `full(32×32)`, `row1(1×32)`, `col1(32×1)`,
  `part(13×20)`, `part2(7×9, K=64 cross-block)` — plus a **no-spill** check confirming the
  hardware writes nothing outside `M×N`. The partial dims ride the **extended-dim** intrinsics
  (`gemmini_extended_{mvin,preload,compute_preloaded,mvout}`); per-block K stays full
  (`MX_BLOCK_SIZE==DIM`), so only M,N are partial. **Partial-tile scale contract (no RTL
  change):** scale `mvin` covers **full DIM** lanes/rows, padding unused A-rows / B-cols with
  neutral `encode(0)` — required because the B-scale assert checks all DIM lanes and the A-scale
  counter wraps at `total_rows`(=K=DIM); the HW output masking (`output_counter<C_rows`,
  `lane<C_cols`) drops the padding. No new RTL bugs surfaced — the stock partial-tile masking
  path was already MX-correct. **Next:** R3 + `mxint8_matmul_dim16.c` (DIM=16 two-phase
  contribution), K=96/128 split runs, and the `gemmini_loop_ws_mxint8` wrapper fix (S3 #5).
- **2026-06-05 — R3 + S3 #3 DIM=16 two-phase: single logical block PASSES on Verilator RTL
  (M5 partial).** Added `include/gemmini_params_mxint8_dim16.h` (`DIM 16`, `MX_SCALE_SP_ROWS 512`),
  wrote `bareMetalC/mxint8_matmul_dim16.c` (registered), and built a fresh
  `GemminiMXINT8DIM16RocketConfig` Verilator sim. Each logical 32-K block is fed as two
  `preload(B half)/compute(A half)` pairs of 16 K-rows; the BlockScaleUnit buffers half-0
  (`mx_raw_half`, write suppressed) and on half-1 sums both raw partials and scales **once**.
  Probe (`hw = 32<<((i%4)+(j%3))` — the `32` not `16` proves both 16-wide halves are summed into
  the full 32-lane block before scaling) + two random K=32 draws all exact-match the golden, with
  the restored validity asserts active and no fires. Added a two-phase invariant assertion
  (`require(mx_block_size==2*block_size)` + half alternation, guarded by `DIM<mx_block_size`).
  **Fixed three behavioral RTL bugs (all masked at DIM=32):** **D1** `MXScaleLoadController`
  scale-DMA `cmd_id` lagged the alloc by a cycle so the first row request used a stale id →
  `DMACommandTracker` `bytes_left` underflow (only at `nCmds=2`, i.e. DIM=16) → drive the first
  request's `cmd_id` combinationally from `alloc.bits.cmd_id`; **D2** the `mx_raw_half` store was
  ungated, so WS bubble/garbage-addr cycles (`rob_id.valid=false`) overwrote the buffered half
  with zero between phases (`hw=16` not `32`) → gate by `start_array_outputting && write_this_row`;
  **D3** B scale was latched once at feed time and clobbered by the next block's feed mid-drain →
  read B at **output** time addressed by the output's own `tag.mx_block`, mirroring the A read
  (removed `mx_b_exp/_invalid/_valid`). Diagnosed via temporary Chisel `printf`s (since removed;
  output lands in the run's `*.out`). **D3 touches shared MX RTL, so re-verified DIM=32: rebuilt
  the DIM32 sim and `mxint8_matmul_dim32` (probe + K=64 cross-block), `mxint8_corner`, and
  `mxint8_matmul_partial` all still PASS — no regression.** **Known limitation (D4, deferred):**
  cross-block K>32 at DIM=16 drops the last logical block — its final WS compute is not clocked
  out of the array before the `mvout` (0 of 16 raw rows for its 2nd phase; a trailing dummy
  preload drains only ~4/16), a WS *drain* interaction orthogonal to the two-phase scaling
  (DIM=32 K=64 is fine). **[Superseded 2026-06-08 — D4 was not a drain bug; see next entry.]**
- **2026-06-08 — D4 cross-block at DIM=16 RESOLVED (M5 DONE). It was a tag-misalignment, not a
  drain.** Re-added a temporary instrumented `run_random(64)` + Chisel `printf`s in the output
  path and ran K=64: **all four phases drain all 16 rows and the last block reaches the
  accumulator** (the `accum` bit, which rides the C-address through the tag FIFO, is correct) —
  disproving the "WS last-compute drain" hypothesis. The "0 of 16 rows for the last block's 2nd
  phase" was those rows **mis-tagged** `blk0 sh1`, not missing. Root cause: the per-output
  `mx_block`/`mx_second_half` tag fields are sampled from the live feed counter `mx_k_lane_counter`
  at enqueue (`ExecuteController` ~884); under WS preload/compute fusion + the mesh's +2 tag
  latency the sample drifts one physical phase relative to the output at the logical-block
  boundary, so block1's first half was treated as a second half (summed with a stale buffer,
  scaled by **block0's** exponents, written) and its true second half as a first half
  (buffered+suppressed → dropped). **Fix (D4):** re-derive `(block, half)` at output time from the
  strict FIFO drain order with an output-domain phase counter — `mx_phase` (undelayed, +1 per
  drained MX matmul, reset per GEMM on `mx_reset`) and `mx_phase_d = RegNext(mx_phase)` →
  `block = phase>>1`, `second_half = phase(0)` — mirroring the proven `mx_a_read_row`/
  `output_counter` undelayed/delayed split; the undelayed value addresses the B-scale read, the
  delayed value drives the A-scale lane + the two-phase decisions. Scoped via Scala `if (DIM <
  mx_block_size)` so the DIM=32 path keeps the original tag reads byte-for-byte. **Zero added
  cycles** (no flush, no extra pipeline stage, full in-flight depth). Verified on Verilator:
  `mxint8_matmul_dim16` PASSES probe + random K=32, deterministic `xprobe` K=64 (c=96) / K=128
  (c=480), random K=64/96 — 1/2/3/4 logical blocks all exact-match the golden (test now bumped to
  `K_MAX=128`, trimmed to 6 cases for one-run Verilator wall-clock). **No regression: rebuilt the
  DIM=32 sim; `mxint8_matmul_dim32` (probe + K=64), `mxint8_corner` (incl. ktail/nan-reject), and
  `mxint8_matmul_partial` all PASS.** Printfs/temporary test line removed; stock `gemmini_params.h`
  restored. **Next:** R4 (FireSim AutoCounters) and the `gemmini_loop_ws_mxint8` wrapper
  tile-vs-element-count fix (S3 #5).
- **2026-06-08 — S3 #5 `gemmini_loop_ws_mxint8` wrapper fixed + `mxint8_tiled.c` added.** Fixed
  two tile-vs-element count bugs in the wrapper (`include/gemmini.h`): A-scale `rows` / B-scale
  `cols` now use the M / N element counts (`I·DIM−pad_I` / `J·DIM−pad_J`), and `k_blocks` is now
  computed over the K *element* extent (`K·DIM−pad_K`) since the wrapper's `K` is a tile count.
  Added a hard `I==J==1` single-output-tile guard (A-scale read has no output-tile component).
  New `bareMetalC/mxint8_tiled.c` (registered) drives the HW WS loop unroller through the wrapper
  and PASSES a single-MX-block GEMM at I=J=1 on the DIM=32 sim (`tiled K=32: PASS`). The K=64 case
  surfaced a deeper, separate limitation (deferred). **[Root cause corrected/finalized 2026-06-09
  — see next entry; it is NOT the K-block counter.]**
- **2026-06-09 — loop-wrapper cross-block fully root-caused (deferred as a documented
  limitation; no safe fix).** Exhaustive RTL investigation of the `mxint8_tiled` K=64 failure.
  **Deterministic repro:** the loop K=64 GEMM passes *in isolation* but fails *only when preceded
  by another loop GEMM*. **Root cause: inter-GEMM state leakage in the systolic mesh's internal
  output pipeline** — a prior loop GEMM leaves residual mesh state that freezes the next GEMM's
  first K-tile mesh output after ~2 rows (holds a stale prior value). **Ruled out (all verified on
  RTL):** MX scaling, two-phase, cross-block accumulate, mvout (`mvout==block0+block1`); A/B scale
  reads, `tag.mx_block`, `mx_a_read_row`, `mx_delay_outputs`; the A-feed completes (afc 0→31); and
  every deterministic data/scale-variation probe passes in isolation (an earlier "corrupt raw" was
  a 20-bit-signed logging artifact). A software mesh-drain (dummy WS matmuls) does **not** clear
  it, and an RTL flush cannot either (the mesh is idle between GEMMs → flush just re-advances the
  array like the dummy matmuls). The fix needs Mesh-internal pipeline reset between GEMMs — the
  `Mesh` is **DO-NOT-TOUCH**. **Not an MX bug**; the manual cross-block path is the supported route
  and is fully verified. All DBG instrumentation removed; stock header restored. **Next:** R4
  (FireSim AutoCounters); the loop-wrapper multi-block path remains documented as a limitation.
- **2026-06-09 — RES-OPT Part A (HW resource reduction, bit-exact): DONE + RTL-verified.** First
  pass of a hardware-resource-optimization effort (goal: cut FF/SRAM of the MX additions, keep
  throughput, stay bit-exact vs. `mxint8_golden.h`). Three safe edits, all gated by `mx_enabled`
  (stock provably untouched):
  - **A1 `MXScaleSRAM.scala` — 6 → 2 SyncReadMems.** Dropped `rawA`/`rawB` (written every scale
    mvin but **never read** by any consumer) and `invalidA`/`invalidB` (the invalid flag is
    `exp === 128` ⇔ the rejected 0xff NaN, recomputed combinationally at read; 0xff still rejected
    at write). Kept only `expA`/`expB`. Removed `raw` from `MXScaleSRAMReadResp` (+ the matching
    `ExecuteController` IO ctor arg). ~⅔ scale-SRAM cut. **Confirmed in elaborated Verilog:** the
    `rawA/rawB/invalidA/invalidB` memory modules are gone; only the `expA_ext` mem module remains
    (firtool dedups `expA`/`expB` → one module, two instances).
  - **A2 `ExecuteController.scala` — narrow + gate `mx_raw_half`.** The DIM=16 two-phase buffer was
    `Reg(Vec(DIM, Vec(_, Vec(_, SInt(64.W)))))` storing a 20-bit mesh output in 64 bits AND declared
    even at DIM=32 (dead 32×32×64 ≈ 64K FF). Now `Option`-wrapped, instantiated **only when
    `DIM < mx_block_size`**, at `SInt(spatialArrayOutputType.getWidth.W)` (=20b); the two-phase sum
    uses a widening add (`+&` → 21b, no wrap). DIM=16: 16×16×64 → 16×16×20 (~3×); DIM=32: buffer
    removed entirely.
  - **A3 `ExecuteController.scala` — robustness/power.** Reset `mx_a_read_row` on `mx_reset`
    (symmetry with `mx_phase`); gate `mx.read_a/read_b.valid` on `tag.rob_id.valid` so WS bubble
    cycles no longer issue wasted scale-SRAM reads.
  - **Verification (Verilator RTL, all bit-exact vs. golden):** `sbt compile` clean; both
    `GemminiMXINT8DIM32RocketConfig` and `GemminiMXINT8DIM16RocketConfig` re-elaborate with **0
    firtool errors**. **DIM=32:** `mxint8_matmul_dim32` (probe K=32 + random K=64), `mxint8_corner`
    (8 cases: zero/±int32-int64 sat/altsign/randexp/ktail K=40/0xff-reject), `mxint8_matmul_partial`
    (6 cases incl. row1/col1/partial + K=64 cross-block) — all **PASS**. **DIM=16:**
    `mxint8_matmul_dim16` — `probe K=32` (c=32, proving the narrowed buffer still sums both 16-wide
    halves), `random K=32`, `xprobe K=64` (96), `random K=64/96`, `xprobe K=128` (480) — 1/2/3/4
    logical blocks all **PASS**. (Note: `corner`/`dim16` need `timeout_cycles` raised above the 10M
    default — many cases per binary; not a correctness issue. The checked-in DIM=16 test ELF was
    stale stock-header; rebuilt against `gemmini_params_mxint8_dim16.h`.) Throughput unchanged.
    Stock header restored after the runs. **Next:** RES-OPT Part B — remove the ~20K-FF
    `mx_mesh_resp_bits` full-output delay register via prefetched exponent registers (undelayed
    scaling), then re-run the full regression (DIM=16 cross-block is the decisive gate).
- **2026-06-09 — RES-OPT Part B (remove the output-delay register): DONE + RTL-verified.** Removed
  the `mx_mesh_resp_bits` full-mesh-output delay shadow register (~20K FF at DIM=32, ~5K at DIM=16 —
  the single largest MX register, present in every MX config). It existed only to hide
  `MXScaleSRAM`'s 1-cycle `SyncReadMem` read latency by delaying the whole mesh output to meet the
  late scale. Replaced with **undelayed scaling + a per-cycle scale read-ahead** in
  `ExecuteController.scala`:
  - The mesh output is now consumed in the undelayed domain (`mesh_resp := mesh.io.resp.bits`, no
    Mux, no shadow reg). Every output-domain signal (`output_counter`, the block/half tracking, the
    two-phase buffer) moved to that one domain — a uniform 1-cycle-earlier shift of the output stage.
  - **Read-ahead:** each cycle, address `MXScaleSRAM` for the output that will drain *next* — A at
    `wrappingAdd(output_counter,1,total_rows)` (next row; holds during bubbles), B at the next
    drained matmul's block — so the 1-cycle `SyncReadMem` response lines up with the next drained
    output. One read/cycle keeps **exact pace** with the one-row/cycle drain (no batch gather, no
    FIFO, no throughput cost; output latency drops 1 cycle).
  - **(block, second-half) tracking unified:** a single drain-order matmul counter `mx_matmul`
    (ticks once per drained MX matmul) gives `block = mx_matmul >> log2(phases/block)` and, for
    DIM<block, `second_half = mx_matmul(0)` — replacing the old `mx_phase`/`mx_a_read_row`
    delayed/undelayed split. This recovers (block, half) from the strict drain order (the feed
    tag fields drift one phase — the D4 effect — so they are not used for it).
  - **Cost:** a few small counters/regs (`mx_matmul`, scale-response wires) vs. the removed
    DIM×DIM×(20b data + tag) register. Net ≈ **−20K FF (DIM=32)** / **−5K FF (DIM=16)**.
  - **False start (recorded for the record):** a first attempt gathered each matmul's full
    A-exponent column into an order-preserving prefetch FIFO at feed time. It was **throughput-
    broken** — gathering a DIM-row column needs ≥DIM+1 SRAM reads but the mesh drains DIM rows in
    DIM cycles, so on back-to-back **full** tiles the FIFO underflowed (probe K=32 and partial tiles
    passed; `mxint8_matmul_dim32` random **K=64** tripped the `mx_set_valid` assert). The single
    scale-SRAM read port cannot batch-prefetch faster than the drain. The per-cycle read-ahead
    (1 read/cycle = drain rate) is the throughput-correct fix.
  - **Verification (Verilator RTL, all bit-exact vs. golden):** `sbt compile` clean; DIM=32 and
    DIM=16 elaborate with **0 firtool errors** and `mx_mesh_resp_bits` is **gone from the generated
    Verilog**. **DIM=32:** `mxint8_matmul_dim32` (probe K=32 + random K=64), `mxint8_matmul_partial`
    (6 cases), `mxint8_corner` (8 cases) — all **PASS**. **DIM=16:** `mxint8_matmul_dim16` — probe
    K=32 (c=32), random K=32, xprobe K=64 (96) / K=128 (480), random K=64/96 — 1/2/3/4 logical
    blocks all **PASS**. The remaining MX baremetal tests also **PASS** on the read-ahead DIM=32
    sim: `mxint8_golden` (software golden helpers) and `mxint8_tiled` (loop-wrapper, single tile
    `K=32` — the loop multi-block path remains a documented pre-existing mesh-leakage limitation,
    unrelated to this change). So the **full MX baremetal suite (all 6 tests)** passes. Stock
    `GemminiRocketConfig` re-elaborates clean (the undelayed `mesh_resp` is byte-identical to
    stock's original path; all new signals are `mx_enabled`-gated). Throughput unchanged.
    **RES-OPT (Parts A + B) complete.** **Next:** R4 FireSim AutoCounters + capture the
    LUT/FF/BRAM deltas from synthesis to quantify the saving.
- **2026-06-10 — CORRECTION: the loop multi-block failure is MX-associated, NOT a generic
  stock-mesh limitation.** The 2026-06-09 entry (and the §S3 #5 / mesh notes) concluded the
  inter-GEMM loop failure was *"not an MX bug — inter-GEMM state leakage in the stock mesh's
  internal output pipeline."* A direct A/B experiment on the current (Part B) RTL **contradicts the
  "not an MX bug / generic mesh" part** (the inter-GEMM characterization itself stands). Tests added
  in `bareMetalC/` (not registered; investigation artifacts): `stock_btb.c` (standard transposed WS
  loop), `stock_btb_untr.c` (untransposed WS loop, the *exact* mesh feed the MX wrapper uses, minus
  scales), `mxint8_btb.c` (MX loop K=32→K=64), `mxint8_iso.c` (single MX loop K=64). Results
  (Verilator):
  - **Stock int8 back-to-back loop GEMMs PASS** — *transposed* (DIM=32 MX-inert, and real
    `GemminiRocketConfig` DIM=16) *and* **untransposed** (DIM=32). So the bare mesh + loop unroller +
    untransposed-WS feed runs back-to-back **correctly**.
  - **A single MX loop K=64 GEMM PASSES** in isolation; **the same K=64 preceded by another MX loop
    GEMM FAILS** (887/1024 mism, first bad row 1, no assert) — i.e. strictly **inter-GEMM**.
  - The only difference between the passing `stock_btb_untr` and the failing `mxint8_btb` is the MX
    scale sidecar (`config_mxint8` + scale mvins + scale datapath). ⇒ **the failure is caused/triggered
    by the MX additions, not the stock mesh.** (Part B neither introduced nor fixed it; it reproduces
    on both old and new RTL.)
  - **Open mechanism (needs raw-vs-scaled mesh-output probing):** (a) MX-path state not reset across
    loop GEMMs (survives `gemmini_flush`+`config_mxint8`), or (b) the MX instruction stream's timing
    exposing a latent mesh fragility the tighter stock sequence avoids. Either way this reframes the
    loop multi-block limitation as a **likely-fixable MX-side inter-GEMM bug**, not an
    untouchable-mesh dead end — a candidate to revisit before declaring loop multi-block unsupported.
- **2026-06-10 — BUG-HUNT Phase 1 (instrumented diagnosis of the loop inter-GEMM failure).**
  Plan: instrument the current RTL, diff failing back-to-back (BtB) vs. passing isolated runs,
  pin the mechanism, then fix **without runtime resets** (user constraint: no resets at runtime;
  overwrite registers at ordered events or derive state from tags instead). Work so far:
  - **Instrumentation (temporary, marked `MX-DBG`, to be removed):** per-drain-row trace in
    `ExecuteController` (output_counter, rob_id, garbage, tag.mx_block, `mx_matmul`/drain block,
    raw mesh `data(0)(0)`, consumed eA/eB, scaled wdata, acc addr/bit, scale read addrs),
    `mx_reset` pulses, mesh req stream (prop/rows/garbage/rob/mx tags/k-lane), feed-side
    `a/b/d.fire` data, and MXScaleSRAM write beats. New deterministic probe mode in
    `mxint8_btb.c` (`-DMXINT8_BTB_PROBE=1`, separate binary `mxint8_btb_probe`): GEMM2 block0
    raw = 32/cell, block1 raw = 64/cell, distinct per-(row,block) eA / per-(block,col) eB, so a
    wrong hw value decodes to exactly which scale/data the hardware used.
  - **Hypotheses KILLED by the trace (all MX sidecar state/logic is correct in the failing run):**
    (1) missed/raced `mx_reset` — both config pulses fire and clear `mx_matmul`/`mx_k_lane_counter`
    correctly between GEMMs; (2) feed-side counters/tags — REQ stream shows correct
    `mx_block`/`klane`/prop/rows for every GEMM2 req; (3) drain-side tracking — `mx_matmul`/
    `mx_drain_block` correct (0 for block0, 1 for block1), garbage groups (`rob_id.valid=0`)
    correctly do not tick; (4) scale-SRAM writes — GEMM2 writes A rows 0–31 lanes {0,1} and B rows
    {128,129} correctly (DMA row reordering visible but complete); (5) scale consumption — per-row
    eA and eB consumed by the BlockScaleUnit are exactly the freshly-written GEMM2 scales.
  - **The actual corruption (old BUG.md §6.4 CONFIRMED on current RTL):** the **raw mesh output**
    of GEMM2's **first** K-tile: row 0 is CORRECT (probe: 32), rows 1–31 are a constant stale
    value (probe: −231 — the very value the 2026-06-09 diagnosis recorded; random: 30448).
    Block1's raw output is fully correct (probe: all 64s). Scales applied to the frozen raw are
    correct, confirming the bug is upstream of the BlockScaleUnit.
  - **NEW decisive control (shape-matched stock):** the 2026-06-10 CORRECTION's `stock_btb_untr`
    used K=64→K=64 and passed, but the failing MX sequence is **K=32→K=64** — the A/B was not
    shape-matched. `stock_btb_untr.c` reworked to GEMM1 K=DIM (1 K-tile) → GEMM2 K=2·DIM
    (2 K-tiles), untransposed loop, config verbatim from `mxint8_btb.c` minus scales:
    **PASSES**. So the stock loop/mesh handles the exact failing K-shape sequence; the trigger
    is genuinely tied to the MX run, but **not** via any incorrect MX state (all killed above).
  - **Trace fingerprint of the failure:** in all passing first-computes (GEMM1, iso block0,
    stock GEMM2 block0) the real output group drains **contiguously**; in the failing MX GEMM2
    block0 the drain has **mid-wave bubbles** (feed stalls) and the stale constant in rows 1–31.
    The stale GEMM1 garbage group (`rob=41`, data 0s) drains by design on the next GEMM's preload
    wave (MeshWithDelays tag/id matching verified from code: tags ride `out_id`; an undrained
    garbage group from GEMM-N drains during GEMM-N+1's preload — that part is normal).
  - **Current suspicion (being tested):** rows 1–31 constant despite per-row-varying A data rules
    out "computed with wrong/stale weights" (that would vary per row); it matches **the same A row
    (or stale feed register) being fed repeatedly** during GEMM2's first compute wave — i.e. an
    ExecuteController **feed-path** replay under a specific stall timing that only the MX run's
    surrounding DMA/instruction traffic produces (stock latent, MX-triggered). Feed-side
    `a/b/d.fire` data prints added; rerun in progress. Crucially, the EC feed path is **not**
    DO-NOT-TOUCH, so if confirmed this is fixable without touching the mesh.
  - **Feed-replay CONFIRMED, narrowed to the scratchpad A-read data (2026-06-10, later).** New
    `a/b/d.fire` + spad `read req/resp` traces on the failing run: GEMM2's first compute wave
    fires 32 A rows into the mesh, but the DATA is row 0 once (correct, e.g. bytes 192,16) and
    then the **same stale row repeated for rows 1-31** (bytes 199,250 — identical in every
    fire). The spad READ REQUESTS are perfect: bank 2, ascending addresses 0,1,2,...,31, one per
    cycle, `fromDMA=0`, responses popped every cycle (`rdy=1`) — yet the RESPONSES carry
    identical data for distinct addresses. The preload wave (B_new via the d port, bank 3,
    descending addresses) streams varying data correctly in the same window. So the corruption
    is either (a) the **bank content itself** (GEMM2's A payload mvin wrote duplicated rows —
    DMA write-side replay), or (b) a stuck SRAM read-data path. Probe-vs-random cross-check
    favors stale GEMM1-era content over duplicated fresh content (probe's A is all-ones, yet the
    fed rows decode to a GEMM1-era vector: both runs share the same GEMM1 PRNG data and both
    decode to the same stale vector). Spad bank WRITE-port instrumentation (`SPW`) added to
    decide; rerun in progress. Notably the MX scale DMA is a **separate StreamReader sharing the
    TL crossbar** with the payload StreamReader (`Scratchpad.scala:211-227`) — the only
    MX-specific coupling left standing, consistent with "stock-latent, MX-timing-triggered".
- **2026-06-10 — ROOT CAUSE FOUND + FIX: the "loop multi-block inter-GEMM" corruption is a
  reservation-station RAW-hazard miss caused by the MX scale mvins polluting the RS's load-config
  mirror.** Chain of evidence (all from MX-DBG traces on the failing `mxint8_btb` run):
  1. Scratchpad write/read trace: GEMM2's first compute READ bank2 rows 1-31 (correct ascending
     addresses) *before* the A-payload mvin's DMA beats WROTE them (addr 0 written then read 0
     correct; addr 1 read at cyc 2503919, its write landed after) — a textbook RAW hazard. The
     "frozen raw mesh output" was the mesh correctly multiplying stale, repeated scratchpad rows
     (uninitialized memory reads identical on every row); rows 1-31 fed the same stale vector.
  2. RS event trace: the compute allocated with `deps_ld = 00000011` — depending ONLY on the two
     scale mvins (slots 0,1), NOT on the A mvin (slot 3). It issued the moment the scales
     completed; the A mvin completed long after the mesh request had already fired.
  3. The A mvin's dependency range was decoded as **[1998, 2030)** instead of the true
     **[2048, 2080)** (= bank2 rows 0-31). Off by exactly 50 = a garbage `pixel_repeats`:
     `dst.start.floorSub(pixel_repeats)` in the RS operand decode (first-layer-optimization path).
  4. The garbage came from `ReservationStation.scala`'s alloc hook: every `is_config && ldq`
     entry was decoded as a CONFIG_LOAD (`ld_block_strides(id) := rs1(31,16)`,
     `ld_pixel_repeats(id) := rs1(15,8)`, `id = rs1(4,3)`). The MX scale mvins are `is_config`
     (correct — they carry no spad range) **but their rs1 is the scale buffer's DRAM pointer**,
     not a config word. With the 64-aligned `a_scale`/`b_scale` buffers, `id = ptr[4:3] = 0` (the
     A mvin's load id) and `pixel_repeats = ptr[15:8] - 1 = 50` — clobbering the RS mirror that
     payload-mvin DEPENDENCY ranges are decoded with.
  - **Why every prior symptom fit a "mesh/state" story:** the race only bites when the A mvin's
    DMA is slow (TLB flush + accumulated memory traffic in a 2nd GEMM); GEMM1 wins the race, so
    only GEMMs-after-GEMMs failed ("inter-GEMM"); issue timing made it look timing-sensitive;
    and the mesh dutifully output a constant (stale row × new B) that looked "frozen". The mesh,
    the MX datapath, the counters, the scales, and the loop unroller were all correct. The old
    BUG.md §5 mesh-leakage mechanism is disproven; its §6.4 raw-freeze OBSERVATION was accurate.
  - **FIX (one-line gating + comment, philosophy-compliant: no resets, no mesh changes, pure
    decode guard):** `ReservationStation.scala` — exclude scale mvins from the CONFIG_LOAD decode
    hook: `.elsewhen(new_entry.is_config && new_entry.q === ldqu && !new_entry.is_mx_scale)`.
    Stock behavior is untouched (stock never allocates `is_mx_scale` entries). Verification in
    progress: `mxint8_btb` (random + probe) on the fixed RTL, then full bit-exact regression on
    both configs + stock elaboration, then MX-DBG instrumentation removal.
- **2026-06-10 — FIX VERIFIED: full bit-exact regression green on the final RTL; loop
  multi-block limitation LIFTED.** MX-DBG instrumentation fully removed (the only RTL change
  that ships is the one-line `!is_mx_scale` guard + comment in `ReservationStation.scala`).
  Clean rebuilds of both MX configs; results (Verilator, all exact-match vs `mxint8_golden.h`):
  - **DIM=32 (`GemminiMXINT8DIM32RocketConfig`):** `mxint8_golden`, `mxint8_matmul_dim32`
    (probe K=32 + random K=64), `mxint8_matmul_partial` (6 cases), `mxint8_corner` (8 cases),
    `mxint8_tiled` (**now K=32 then K=64 back-to-back through the loop wrapper** — the
    previously-failing case), `mxint8_btb` (random BtB), `mxint8_btb_probe` (deterministic BtB),
    `mxint8_iso`, and the stock control `stock_btb_untr` — **all PASS**.
  - **DIM=16 (`GemminiMXINT8DIM16RocketConfig`):** `mxint8_matmul_dim16` (probe, random K=32,
    xprobe K=64/128, random K=64/96 — 1/2/3/4 logical blocks) — **PASS**.
  - **Stock `GemminiRocketConfig`:** elaborates with **0 firtool errors** (fix is invisible to
    stock — it only gates a decode that stock never reaches).
  - **Test housekeeping:** `mxint8_btb` **registered** in the bareMetalC Makefile (the
    regression for this fix); `mxint8_tiled` extended to the multi-block BtB case; headers of
    `mxint8_btb.c`/`mxint8_tiled.c`/`stock_btb_untr.c` rewritten to the corrected story;
    `mxint8_iso.c`/`stock_btb*.c` remain unregistered investigation artifacts. `DOCS_MX/BUG.md`
    rewritten (status FIXED, real root cause, evidence chain, fix). `AGENT.md` status updated.
  - **S3 #5 status: the "loop multi-block unsupported / mesh-leakage" limitation is REMOVED.**
    The loop wrapper now supports multi-block K (single output tile I=J=1 remains the v1
    constraint). **Next:** R4 (FireSim AutoCounters + synthesis LUT/FF/BRAM deltas).
