# PLAN_MX_UPDATED.md — Execution Plan of Record for the MXINT8→TRETS Track

**This file is the forward-looking plan of record for AI agents working on this repo.**
It supersedes `DOCS_MX/PLAN_MX.md` for *new* work; PLAN_MX.md remains the authoritative
**historical log** (its §12 progress log documents every bug, fix, and verification run —
read it before touching anything it mentions). The research-side rationale (venue, thesis,
landscape, baselines) lives in `DOCS_MX/RESEARCH_PLAN.md`; this file is the *how*.

---

## 0. Standing rules (read first, follow always)

1. **Update the docs after every completed step**: append a dated entry to this file's §5
   progress log (same discipline as PLAN_MX.md §12), and update `AGENT.md`'s status line.
2. **Coding styles are mandatory**: Chisel/Scala per `CODING_STYLES/scala_coding_style.md`
   (2-space indent, ≤100-char lines, PascalCase/camelCase/UPPER_CASE, JavaDoc `/** */`,
   braces everywhere, explicit public return types, Rule-of-30); C per
   `CODING_STYLES/c_cpp_coding_style.md` (C11, pointer `*` by name, `//` comments, Doxygen
   on public APIs, `lower_snake_case`, one shared prefix per header, hygienic
   `do { } while (false)` macros — macros are correct for RoCC asm encodings).
3. **The `generators/gemmini` submodule holds the entire implementation.** Never
   `git reset`/`clean`/checkout it destructively. After P0 it has real commits — keep it
   that way (commit after every verified step).
4. **Design philosophy — no runtime resets.** Resets put systems into a known state at
   power-on only. Derive state from the instruction stream / tags / drain order, or
   overwrite registers at well-ordered instruction events. (The entire verified design
   follows this; do not regress it.)
5. **Bit-exactness is the gate.** The numerical contract (`mxint8_policy.md` +
   `include/mxint8_golden.h`) is frozen; from P1 it is additionally spec-mapped to OCP MX
   v1.0. Any change to semantics requires a policy-file version bump and full regression.
   The regression matrix (§4) must be green after **every** phase.
6. **Stock Gemmini must remain bit-identical** with MX disabled. Every MX change is
   `mx_enabled`-gated or provably unreachable for stock; re-elaborate stock
   `GemminiRocketConfig` (0 firtool errors) as part of every regression.
7. **Mesh.scala / PE.scala / MeshWithDelays.scala are DO-NOT-TOUCH** (code; config-derived
   width parameters are fair game). The retrofit story (RESEARCH_PLAN.md C1) depends on it.

---

## 1. Environment & proven recipes (copy-paste; all traps already paid for)

### 1.1 Toolchain activation (every session, absolute path)
```bash
source /home/nikolap/Research/2026/chipyard/env.sh && export PATH="$CONDA_PREFIX/bin:$PATH"
# verify: which java -> .conda-env/bin/java ; java -version -> 20.x
```
Traps: system JDK 21 ahead of conda JDK 20 breaks sbt's parser; a drifted cwd makes
`source ./env.sh` silently no-op → always absolute path. Run heavy builds in background;
background tasks run from repo root regardless of foreground drift.

### 1.2 Compile / elaborate
```bash
sbt "project gemmini" compile          # fast type-check gate (from repo root)
sbt "project gemmini" clean compile    # Zinc invalidates by content hash; touch does nothing

make -C sims/verilator CONFIG=GemminiMXINT8DIM32RocketConfig \
  FIRTOOL_BIN=$HOME/.cache/llvm-firtool/1.62.1/bin/firtool verilog
```
Trap: bare `firtool` on PATH is a broken LLVM-17 build — **always** pass `FIRTOOL_BIN`.
Stock comparison config: `GemminiRocketConfig`. RTL edits require `rm -rf` of the config's
`sims/verilator/generated-src/<Top>` (and `rm -f` the sim binary) to force re-elaboration.

### 1.3 Software tests
```bash
cd generators/gemmini/software/gemmini-rocc-tests && ./build.sh
```
**Header staging (critical):** the checked-in `include/gemmini_params.h` is the stock
header (`MX_ENABLED 0`). Before building MX tests, stage
`include/gemmini_params_mxint8_dim32.h` (or `_dim16.h`) as `include/gemmini_params.h`;
**restore the stock header afterwards** (uncommitted-diff hygiene). Direct ELF builds
(faster than build.sh): chipyard `$RISCV` riscv64-unknown-elf-gcc with the bareMetalC
Makefile flags + `-T riscv-tests/benchmarks/common/test.ld` (the system elf-gcc lacks
newlib). New test = drop `bareMetalC/<name>.c`, add to `tests` in `bareMetalC/Makefile`.

**External oracle (host, P1):** chipyard-root venv `.mx-venv` (untracked; conda Python
3.14 + CPU torch 2.12 + `pip install --no-deps git+https://github.com/microsoft/microxcaling`
— its `torch==2.2.0` pin has no cp314 wheel, the CPU path runs fine on newer torch; plus
`numpy packaging`). Run from `gemmini-rocc-tests/`:
```bash
/abs/path/.mx-venv/bin/python3 tools/mxint8_external_diff.py --sweep \
  --json-out build/mxint8_external_diff_sweep.json
```
It self-compiles `tools/mxint8_host_ref.c` with host gcc against staged MX headers.

### 1.4 Run on RTL
```bash
make -C sims/verilator CONFIG=<Config> run-binary BINARY=<abs path>-baremetal \
  timeout_cycles=300000000
```
Traps: spike/esp-tools does **not** model the MX sidecar — only RTL runs validate MX;
Chisel `printf` output lands in the run's `*.out` file (stderr), not `*.log`; many-case
binaries need the raised `timeout_cycles`; `grep | head`-style pipelines that filter all
output exit nonzero and break `&&` chains (`|| true`).

### 1.5 FPGA flows (P7/P8)
- **VCU118 + FireSim**: on-prem XDMA flow (`sims/firesim`); freeze commits first.
- **Nexys Video**: Chipyard classic flow, harness at `fpga/src/main/scala/nexysvideo/`
  (`Harness.scala`, `HarnessBinders.scala`, `Configs.scala`); Vivado required.

---

## 2. Current state (2026-06-10) — what exists vs what this plan adds

**DONE and RTL-verified bit-exact** (details: PLAN_MX.md §12; AGENT.md status):
- Contract frozen (`mxint8_policy.md`), golden (`mxint8_golden.h`), packer
  (`mxint8_pack.h`), MX params headers (dim32/dim16).
- RTL: `MXScaleSRAM` (predecoded exps, 2 mems), `MXScaleLoadController`,
  RS ordering (`is_mx_scale` marker, real DMA completion, CONFIG_LOAD decode guard),
  `ExecuteController` MX integration (inline BlockScaleUnit, DIM=16 two-phase,
  drain-order `(block, half)` derivation, per-cycle scale read-ahead, undelayed scaling),
  ISA ops 26/27/28 (+29 reserved), configs `GemminiMXINT8DIM32/16RocketConfig`.
- Tests (all green, both configs): `mxint8_golden`, `mxint8_matmul_dim32`,
  `mxint8_matmul_dim16` (1–4 blocks), `mxint8_corner` (8 cases), `mxint8_matmul_partial`
  (6 cases), `mxint8_tiled` (loop wrapper K=32→K=64 BtB), `mxint8_btb` (+probe).
  Unregistered investigation artifacts: `mxint8_iso.c`, `stock_btb*.c`.
- RES-OPT A+B done (−20K FF at DIM=32, zero throughput cost). Stock bit-inert.

**Known v1 constraints this plan addresses:** nothing committed to git (→P0); contract
never audited against the OCP spec (→P1); no counters/synthesis numbers (→P2); loop
wrapper single-output-tile I=J=1 (→P3); int32 accumulator profile (→P4);
untransposed-WS-only (→P5); no eval harness/workloads (→P6); no FireSim/FPGA runs
(→P7/P8); no accuracy sanity (→P9); int8-only (→P10, gated).

---

## 3. Phased work plan

Every phase ends with: (a) the §4 regression matrix green, (b) a dated §5 log entry,
(c) an AGENT.md status-line update, (d) a git commit in the submodule (post-P0).

### P0 — Artifact hygiene (FIRST; nothing else before this)
**Goal:** the uncommitted months of WIP become a committed, tagged, reproducible baseline.
- In `generators/gemmini`: create/confirm branch `mx_dev`, commit the WIP in logical
  chunks (RTL sidecar; EC integration; RS fixes; SW headers/golden/packer; tests; docs),
  tag `mxint8-rtl-verified-20260610`. Commit chipyard-side changes (top-configs, DOCS_MX,
  AGENT.md) on the chipyard `mx_dev` branch, recording the submodule SHA.
- Reproduction script skeleton: `DOCS_MX/scripts/` (env check, build both sims, run the
  regression matrix, collect PASS/FAIL table).
- **Exit:** `git status` clean in both repos; fresh-checkout rebuild re-runs the §4 matrix
  green; tag pushed to whatever remote the user designates (ask before pushing anywhere).

### P1 — OCP MX v1.0 conformance audit (BEFORE any new RTL)
**Why first:** a contract change must land while no downstream eval data exists yet.
Spec: `DOCS_MX/OCP_Microscaling Formats (MX).pdf`. Findings already identified
(RESEARCH_PLAN.md §3.1): packer §6.3 divergence, NaN-reject profile, §6.2 Float32
*should*, −128 untested.
- Write `DOCS_MX/OCP_CONFORMANCE.md`: matrix of every normative clause (§5.1, §5.2.1,
  §5.3.4, §5.4.1, §6.1–6.3) → project behavior → conform / documented deviation / N.A.
- `include/mxint8_pack.h`: switch `mxint8_quantize_block` to the spec §6.3 algorithm —
  `X = 2^floor(log2(amax))` (largest power-of-two representable in INT8 is 1.0),
  elements `round_ties_to_even(V/X · 64)` clamped to ±127 preserving sign; all-zero block
  keeps the neutral-scale convention (spec leaves it undefined — document). Keep the old
  algorithm available under a flag only if a test needs it; default = spec.
- `bareMetalC/mxint8_corner.c`: add a **−128-payload** case (hand-built payloads, since
  the spec-conformant packer never emits −128): golden vs HW, raw bound 32·128².
- Implement `tools/mxint8_external_diff.py` (PLAN_TEST.md §1.3): seeded tensors → in-repo
  packer + `mxint8_ref_gemm_acc` vs **microsoft/microxcaling** (`block_size=32,
  scale_bits=8, w/a_elem_format='int8', round='even'`, CPU); compare scales, payloads,
  and dequantized GEMM values; JSON records (seed, shape, k-blocks, exponent range,
  pass/fail). Include cases in the old divergence band `amax ∈ (1.984·2^j, 2·2^j)`.
  Secondary backend `ROCm/tensorcast` optional. (pip-install into the conda env or a
  venv; record versions.)
- `mxint8_policy.md` → **v1.1**: add a "Normative mapping to OCP MX v1.0" section (clause
  references) + "Documented deviations" (NaN-scale reject profile; int32 accumulator
  narrow profile vs §6.2 *should*-Float32, to be complemented by the P4 wide option).
  The golden GEMM semantics do **not** change.
- **Exit:** conformance matrix complete; external diff green over ≥1000 seeded blocks
  incl. the divergence band; §4 matrix green with the spec packer (test vectors
  re-baselined where outputs legitimately changed — document which and why).

### P2 — Counters + synthesis numbers (old R4)
- AutoCounter cover points (PerfCounter/cover) in `ExecuteController`, `Scratchpad`,
  `MXScaleSRAM`, `MXScaleLoadController`: kernel cycles, useful MACs, payload vs metadata
  DMA bytes, scale-SRAM reads/writes, **metadata stall cycles (claim: ≡0)**, K-block
  transitions, DIM=16 half count, BlockScaleUnit active rows, accumulator writes.
  Fix properly the R0b counter-ID collision (mx_scale_reader currently `DontCare`-tied).
  Counters must be `mx_enabled`-gated and stock-invisible.
- Vivado **out-of-context synthesis deltas**: stock vs MX at DIM=32 and DIM=16
  (LUT/FF/BRAM/DSP/Fmax), same constraints; record scripts in `DOCS_MX/scripts/`.
  This quantifies RES-OPT A+B and the headline sidecar cost.
- **Exit:** counter CSVs from Verilator runs of the existing suite; resource delta table
  draft for the paper.

### P3 — Multi-tile loop support (I/J > 1)
**Design first, then RTL** (most MX bugs are addressing bugs):
- Extend the scale-layout contract (policy v1.1 appendix) for M, N > DIM:
  A-scale row = `tile_i·DIM + output_row`; B-scale row = `b·ceil(N/DIM) + tile_j` (or an
  equivalent documented layout the packer/wrapper both implement).
- Derive `(tile_i, tile_j)` **statelessly from each output's C-address/tag** (the C
  address already rides the tag FIFO correctly — proven by the accumulate bit), not from
  new free-running counters (rule 0.4). The A-read row and B-read row/lane computations in
  `ExecuteController` gain the tile components.
- Lift the `I==J==1` guard in `gemmini_loop_ws_mxint8` (`include/gemmini.h`); scale mvins
  already carry element counts (S3 #5 fix).
- New test `bareMetalC/mxint8_multitile.c` (registered): 2×2 output tiles, partial edge
  tiles, multi-block K, random + deterministic per-tile probe; golden comparison.
- **Exit:** a named BERT-Base shape (e.g. 64×768×768 tiled) runs through the wrapper
  bit-exact on DIM=32 and DIM=16; full §4 matrix green.

### P4 — Wider accumulator option + saturation study
- **Investigate first**: `AccumulatorMem`/`Scratchpad`/mvout width assumptions for
  `accType = SInt(48.W)` or `SInt(64.W)` in an MX config variant
  (`GemminiMXINT8DIM32WideAccRocketConfig`). The R0b `None.get` fix area is adjacent —
  re-read it. BlockScaleUnit saturation target is already golden-shaped; parameterize to
  `accType.getWidth`.
- Golden-side **saturation-incidence study** (host program over the packer + golden):
  sweep workload-realistic exponent distributions and K ∈ {32..4096}; count int32
  intermediate clamps vs int64; CSV for the paper ablation.
- If the RTL widening proves too invasive (mvout format, acc scratchpad banking), ship
  the study alone and document the narrow profile quantitatively (RESEARCH_PLAN.md risk
  table) — do not destabilize the verified core.
- **Exit:** ablation data (int32 vs wide) + either a green wide-acc config or a written
  feasibility analysis; §4 matrix green on the default configs regardless.

### P5 — One validated OS/transpose case (HARD GATE: 4 weeks)
- Candidate order: **(a)** WS + A-transpose (transposer feeds A; metadata remap only —
  A-scale indexing follows the transposed view; mesh untouched); **(b)** OS with K ≤ 32
  per pass (the block invariant holds trivially per §6.1; cross-block accumulation stays
  in the accumulator). Pick (a) unless its scale-indexing contract turns out ill-defined.
- On success: new test (`mxint8_transpose.c` or `mxint8_os.c`), policy appendix, claim
  stays "WS + one validated transpose/OS case".
- On gate expiry: write the claim-narrowing section (mechanism analysis of *why* it is
  out of v1 scope) for RESEARCH_PLAN.md §3 — and stop. No schedule slip.
- **Exit:** one passing case + regression green, OR the written narrowing.

### P6 — Evaluation harness + baselines
- `software/gemmini-rocc-tests/bareMetalC/mx_bench_*.c` (or a generator script emitting
  them): the RESEARCH_PLAN.md §5.2 shape suites as data-driven cases; per-run
  machine-readable output (shape, seed, cycles via `read_cycles()`, counters, PASS).
- **Software-MX baseline**: per-32-block GEMMs on stock configs + host E8M0
  scale-and-accumulate (same shapes, same golden check) — the "do MX in software" cost.
- Stock int8 baselines at both DIMs (cycle parity vs MX configs proves zero-overhead).
- CSV/JSON emitters + plot scripts (`DOCS_MX/scripts/plots/`).
- **Exit:** reproducible baseline-vs-proposed dataset from Verilator; draft figures.

### P7 — FireSim on VCU118
- Freeze chipyard/gemmini/firesim commits (tag `mxint8-eval-freeze-<date>`).
- Bring-up ladder: stock FireSim example → stock Gemmini target + known workload →
  `GemminiMXINT8DIM32RocketConfig` → DIM=16. AutoCounter CSV collection on the P6 suite.
- **Exit:** counter CSVs from FPGA-accelerated full-system runs; **arXiv preprint
  trigger point** (RESEARCH_PLAN.md §7).

### P8 — Nexys Video prototype
- Try a DIM=16 MX config on the `nexysvideo` harness (XC7A200T will not fit DIM=32).
  Fallback ladder: shrink Rocket (small core)/spad → if still no fit, Vivado OOC numbers
  from P2 are the resource evidence (already in hand).
- **Exit:** bitstream + Vivado resource/Fmax report, or the documented OOC fallback.

### P9 — Model-level accuracy sanity (software-only)
- One public checkpoint (BERT-Base layer or small ViT/MLP) through microxcaling's MXINT8
  emulation at the frozen policy settings vs FP32; report the delta. Same library as the
  P1 oracle — one external reference for both bit-level and model-level chains.
  (microxcaling validates numerical semantics only; sidecar layout stays validated by the
  in-repo golden + RTL tests.)
- **Exit:** JSON/CSV + a one-paragraph accuracy statement for the paper.

### P10 — MXINT(d) format parameterization (gated stretch; after MXINT8 is fully done)
**Claim discipline:** OCP MX v1.0 Table 1 defines only MXINT8 as a concrete integer
format. MXINT16 is an "MX-consistent generalization" within the spec's §5.1 framework
(scale × element × k=32) — never call it an OCP format. MXINT4 is **out of scope**
(sub-byte payloads break byte-aligned DMA/`elem_t` assumptions).
- (a) **Audit** int8-hardwired assumptions. RTL pinning is concentrated in
  `GemminiConfigs.scala:213-222` (`inputType.getWidth == 8`, `mx_int_frac_bits == 6`,
  weightType width); most widths already derive from config (`mx_scale_row_bits =
  mx_scale_bits·DIM`; `mx_raw_half`/BlockScaleUnit from `spatialArrayOutputType.getWidth`
  since RES-OPT). SW: `elem_t` typedefs, packer ranges, golden `MX_INT_FRAC_BITS`,
  ISA rs2 field packing.
- (b) Generalized contract (policy appendix): MXINT(d) = 1 sign + 1 integer + (d−2)
  fraction bits ⇒ implicit `2^-(d-2)`, shift `eA+eB−2(d−2)`, symmetric `±(2^(d-1)−1)`.
- (c) d=16 width math: raw block partial ≤ 32·(2^15−1)² ≈ 2^34 ⇒ `spatialArrayOutputType`
  ≥ 36 b (config-derived; no mesh code change) and **P4 wide-acc is a prerequisite**.
  Scale sidecar unchanged (one E8M0 byte per block regardless of d).
- (d) Implement: parametric requires, parameterized golden/packer over d,
  `GemminiMXINT16DIM32/16RocketConfig`s + params headers, port the test suite (probe,
  corner, partial, two-phase — same logic, wider lanes).
- **Exit:** MXINT16 bit-exact vs parameterized golden on both DIMs; MXINT8 + stock
  regressions untouched. **Gate:** lands before paper freeze ⇒ paper contribution C5;
  else future-work — no slip into P11.

### P11 — Paper + artifact packaging
- Figures/tables per RESEARCH_PLAN.md §5 + the OCP conformance matrix table; the
  hazard-taxonomy table from PLAN_MX.md §12 material; reproduction README; TRETS
  submission package (+ artifact appendix).

---

## 4. Regression matrix (must be green after every phase)

| Test | DIM=32 config | DIM=16 config |
|---|---|---|
| `mxint8_golden` | PASS | — |
| `mxint8_matmul_dim32` (probe K=32 + random K=64) | PASS | — |
| `mxint8_matmul_dim16` (probe, random K=32, xprobe K=64/128, random K=64/96) | — | PASS |
| `mxint8_corner` (12 cases incl. the P1 −128 quartet) | PASS | — |
| `mxint8_matmul_partial` (6 cases) | PASS | — |
| `mxint8_tiled` (wrapper K=32 → K=64 BtB) | PASS | — |
| `mxint8_btb` (+ probe build) | PASS | — |
| `mxint8_multitile` (P3: probes, non-pow2 J, edges, random K, tiler J-chunk) | PASS | PASS |
| `tools/mxint8_external_diff.py --sweep` (microxcaling oracle, host `.mx-venv`) | host | host |
| Stock `GemminiRocketConfig` elaboration | 0 firtool errors | — |

Plus per-phase additions (transpose/OS test from P5; MXINT16 suite from P10). Bit-exact
vs `mxint8_golden.h` always; any legitimate re-baseline (P1 packer change) must be
documented in the §5 log with the reason.

---

## 5. Progress log (append dated entries; newest last)

- **2026-06-10 — Plan created.** Research analysis done (faults of the April 2026 plans,
  2025–26 landscape, OCP v1.0 clause-by-clause review → P1 findings: packer §6.3
  divergence band, NaN-reject profile, §6.2 Float32 *should*, −128 untested). Venue
  decided: ACM TRETS, journal-first. RTL scope decided: multi-tile + wide-acc +
  OS/transpose (gated) + MXINT(d) parameterization (gated stretch). See
  `DOCS_MX/RESEARCH_PLAN.md`. **Next: P0 (artifact hygiene), then P1 (conformance).**
- **2026-06-10 — P0 artifact hygiene: DONE (fresh-checkout regression deferred).**
  User committed the gemmini-submodule WIP: 8 logical commits on branch **`mxint8_dev`**
  (`0242a5e4` initial … `1e3843d7` multi-block fix), clean tree, nested
  `gemmini-rocc-tests` pointer committed (`35d1e8b6`, heads/dev); submodule URL repointed
  to the `pznikola/gemmini` fork (chipyard `a361b4ac`) and the SHA recorded in chipyard
  `0b74ec82 "sync gemmini"` on branch `mx_dev`. This session added: annotated tag
  **`mxint8-rtl-verified-20260610`** on `1e3843d7`; reproduction-script skeleton
  `DOCS_MX/scripts/run_regression.sh` (env/JDK/firtool checks, header staging+restore,
  §4 matrix runner, PASS/FAIL table); chipyard-side docs committed (AGENT.md,
  CODING_STYLES/, DOCS_MX/, mxint8_policy.md). Not yet done: fresh-checkout rebuild +
  full matrix re-run (O(hours) — run before the P2 numbers are published); nothing
  pushed to any remote (user's call). **Next: P1 (OCP conformance audit).**
- **2026-06-11 — P1 OCP MX v1.0 conformance audit: DONE.** Deliverables: (1)
  `DOCS_MX/OCP_CONFORMANCE.md` — 16-row clause-by-clause matrix (every normative §5/§6
  clause → behavior+evidence → CONFORM / DEVIATION / N.A.); finding: §5.1 *sanctions* the
  separate scale sidecar. (2) Packer switched to the spec-§6.3 recommended algorithm in
  `include/mxint8_pack.h` (`e = floor(log2(amax))`, reachable ±127 clamp; new
  `mxint8_floor_log2f`; two exact pow2 multiplies to avoid `2^(6-e)` fp32 overflow for
  e<-121). Golden GEMM + RTL unaffected by construction (they consume given
  payload+scale); all packer-dependent tests re-green. (3) External oracle implemented:
  `tools/mxint8_host_ref.c` (runs the in-repo packer+golden on the host) +
  `tools/mxint8_external_diff.py` vs **microsoft/microxcaling** (3 checks: §6.3 scale
  byte, bit-exact dequant, exact-integer policy GEMM) — `--sweep` **PASS over 8000 blocks,
  4000 in the old divergence band** `amax∈(1.984·2^j,2·2^j)`; negative control confirms
  the pre-P1 packer fails the band (e=1/p=64 vs spec e=0/p=127). venv `.mx-venv`
  (untracked, gitignored; Python 3.14 + CPU torch 2.12 + microxcaling 1.1.0 via
  `--no-deps`). (4) `mxint8_corner.c` −128 cases. **New finding (D3):** the RES-OPT mesh
  output `SInt(20.W)` holds the packer's `32·127²=516128` bound but is one LSB short of
  the worst-case all-±128 block `32·128²=2^19=524288`; the int64 golden is unaffected.
  Fixed the cases to 31 saturating −128 lanes (raw 507904, in-envelope) — all 12 corner
  cases PASS on RTL (`neg128_exact=507904`, `neg128_sat=INT32_MAX`, `neg128_round=8`,
  `neg128mix`); the all-±128 overflow is documented as deviation D3, **not** fixed
  (widening the mesh is DO-NOT-TOUCH and would regress RES-OPT, to represent data the
  packer never emits). (5) `mxint8_policy.md` → v1.1 (spec-§6.3 packing section,
  normative mapping, deviations D1 NaN-reject / D2 int32-acc / D3 −128-envelope; GEMM
  semantics unchanged). Regression: full §4 matrix green (corner re-verified in isolation
  post-fix; a consolidated re-run was launched). Nothing committed or pushed (user
  commits). **Next: P2 (counters + synthesis numbers).**
- **2026-06-13 — P3 multi-tile loop support (I/J > 1): RTL-verified bit-exact on DIM=32
  and DIM=16.** Implements the policy **Appendix A** contract (added this session). (1)
  **Geometry plumbing:** `CONFIG_MXINT8` rs2 now carries `{log2_Jp, J_tiles, I_tiles}`
  to the ExecuteController (`Controller.scala`); `LOOP_WS` rs1 bit 3 (MX-loop marker) +
  bits 7:4 (`log2_Jp`) carry the padded-pitch info to the loop unroller — stock software
  leaves those bits zero, so non-MX loops are unaffected. (2) **ExecuteController:** the
  scalar drain counter `mx_matmul` is replaced by a chained wrapping walk
  `(kp, tile_i, tile_j, kb)` that recovers each drained output's tile/block/phase from the
  strict drain order; the A/B scale reads are now tile-addressed (A row `tile_i*DIM+row`,
  B row `b_scale_base + kb*Jp + tile_j`); a per-row **assert cross-checks the
  tag-derived tile** (sliced from the C acc-row under the padded pitch) against the walk —
  it stayed silent across every multi-tile drain. (3) **LoopMatmul:** Execute/StC/LdD use
  the **padded power-of-two C-tile pitch** (`i<<log2_Jp | j`) under `req.mx`; the
  accumulate bit is now keyed by the **logical block** (`k >> log2(phases)`), not the
  physical k phase (**latent bug fixed** — phase-keying would accumulate block-0's
  committed write into stale data at DIM<32). (4) **DIM<32 multi-tile reorder
  (Appendix A.3):** for `req.mx && I*J>1` the unroller issues the `mx_block_size/DIM`
  physical phases of one (tile, block) **back-to-back** (`kb` slowest … `kp` fastest), so
  the existing single-tile `mx_raw_half` buffer suffices unchanged — **zero buffer growth**
  (the literature-corroborated decision: exact-integer MX needs a raw-partial hold, kept
  minimal at `DIM²×20b`; B is re-preloaded per episode, COMPUTE_AND_FLIP each phase).
  (5) **Software:** `gemmini.h` wrapper lifts the I==J==1 guard, validates the
  Appendix-A.5 envelope, repacks B scales to the tiled image (`mxint8_repack_b_scales_tiled`,
  dense fast path when already contiguous), and **fences before `CONFIG_MXINT8`** (latent
  hazard fixed — the front-end RESET_K pulse would otherwise race in-flight drains across
  tiler invocations). New `tiled_matmul_mxint8` outer tiler chunks M/N/K to the envelope
  with `ex_accumulate` K-continuation. New registered test `bareMetalC/mxint8_multitile.c`
  (per-tile nonzero-signature probes, non-pow-2 J, partial edges all dims, random
  multi-block K, outer-tiler J-chunk case). **Verification (all `run-binary-fast`):**
  DIM=32 multitile 5/5 PASS; DIM=16 multitile 5/5 PASS (incl. the 2-J-chunk tiler case —
  first hardware exercise of the reorder); DIM=16 single-tile `mxint8_matmul_dim16` 6/6
  PASS (reorder inert at I=J=1); full §4 matrix green on both DIMs; stock
  `GemminiRocketConfig` re-elaborates (622 .sv, 0 firtool errors — bit-identical).
  **Perf note:** found+fixed that `make run-binary` (+verbose → spike-dasm pipe) throttled
  sims ~1000× (66M cycles/9.5h); `run_regression.sh`/`run_perf.sh` switched to
  `run-binary-fast`. Mesh/PE untouched. Nothing committed (user commits). **Next: Stage B
  (DIM generalization {4,8,16,32}: N-phase RTL refactor + DIM=8/4 configs).**
