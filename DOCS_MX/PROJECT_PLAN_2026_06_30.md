# MXINT8-on-Gemmini — Project Status (single source of truth, 2026-06-30)

> **Read this first.** This file is the single entry point for understanding the project's
> current status: what is done, what each fix fixed, how to build/test/run, and the hard
> constraints. Detailed docs are indexed in §13. The **append-only running log** lives in
> [PLAN_MX_UPDATED.md](PLAN_MX_UPDATED.md) §5 — this file is a dated *snapshot*; if it and §5
> disagree, §5 (newer dates) wins.

**TL;DR.** We are adding OCP **MXINT8** (block-scaled int8: 32 int8 payloads + one E8M0 scale
byte per 32-element K-block, implicit payload scale `2^-6`) to the **Gemmini** systolic
accelerator in this Chipyard RISC-V SoC, as a **pure control-plane retrofit** around an
**unmodified** integer mesh. State: **RTL-verified bit-exact at DIM 32/16/8/4**; multi-tile
(I/J>1) works; OCP conformance audited (P0+P1 done). Performance: the historical ~5× slowdown
was a **host-side software loop**, now fixed — **256³ DIM32 = 57,423 cyc** (3.16× faster than
the original MX path; B-scale repack eliminated), still **1.63× stock** (35,238). Target venue:
**ACM TRETS** (journal-first). Forward work: P2 (counters/synthesis), P4–P11.

---

## 1. Goal & research thesis

**The open gap (our thesis):** every published MX hardware work *builds a new datapath*. This
project shows OCP MX semantics can be **retrofitted as a pure control-plane / metadata mechanism
around an unmodified integer systolic mesh** — Gemmini's `Mesh`/`PE`/transposer byte-for-byte
untouched — **bit-exact** to a frozen, spec-mapped numerical contract, at **zero throughput
cost**, validated full-stack (Chipyard → Verilator → FireSim/VCU118 → FPGA), with the **logical
32-element K-block vs physical array-width (DIM) mismatch** (DIM<32 two-phase) solved as the hard
case. "What is the minimal control-plane delta to make an installed int8 accelerator MX-capable?"
is the question the new-datapath papers don't answer.

- **Venue:** ACM TRETS (journal-first; CAL letter fallback). **Standard:** OCP MX v1.0 (Sep 2023).
- **Competitive landscape (all build new datapaths):** MX+ (MICRO'25), MX minifloat systolic
  arrays on FPGA (TRETS'25 — closest, same venue), SNAX precision-scalable MX (ASP-DAC'26),
  MXDOTP/VMXDOTP (RISC-V ISA, not systolic).
- Full rationale, baselines, timeline: [RESEARCH_PLAN.md](RESEARCH_PLAN.md).

---

## 2. Hard constraints (read before touching anything)

1. **DO-NOT-TOUCH** `Mesh.scala`, `PE.scala`, `MeshWithDelays.scala` (the retrofit story depends
   on it). Config-*derived* width parameters are fair game; the module code is not.
2. **Bit-exactness vs [`mxint8_golden.h`](../generators/gemmini/software/gemmini-rocc-tests/include/mxint8_golden.h)
   is the non-negotiable gate.** The contract ([`mxint8_policy.md`] v1.1) is frozen and
   spec-mapped to OCP v1.0; any semantics change needs a policy bump + full regression.
3. **Stock Gemmini stays bit-identical** with MX disabled. Every MX change is `mx_enabled`-gated
   or provably unreachable; re-elaborate `GemminiRocketConfig` (0 firtool errors) each regression.
4. **No runtime resets.** Derive state from the instruction stream / tags / drain order, or
   overwrite registers at well-ordered instruction events (the whole verified design does this).
5. **The user commits git himself.** Stage and describe changes; **never `git commit`**. Tags OK;
   **ask before pushing** anywhere.
6. **Update docs after every step** (append to [PLAN_MX_UPDATED.md](PLAN_MX_UPDATED.md) §5).
7. **DIM=64 is out of scope.** Supported DIMs: 32 / 16 / 8 / 4 (phases-per-block = 32/DIM).

---

## 3. Architecture overview

MX is a **metadata sidecar** bolted onto the stock weight-stationary int8 datapath:

- **Scales** live in a dedicated sidecar SRAM **`MXScaleSRAM`** (predecoded E8M0 exponents, two
  mems for A and B), loaded by **`MXScaleLoadController`** via dedicated mvin commands. **Payloads
  stay in the unmodified scratchpad and flow through the unmodified mesh** as ordinary int8.
- **ISA ops** (`GemminiISA.scala`): `26` CONFIG_MXINT8, `27` MVIN_MXSCALE_A, `28` MVIN_MXSCALE_B
  (`29` LOOP_WS_MXINT8 reserved/unused). CONFIG bits: enable / set_stride / is_b / reset_k.
- **Scaling** is an inline **BlockScaleUnit** in `ExecuteController`: at **output (drain) time**
  it applies the power-of-two block scale `2^(eA+eB-12)` (`-12 = -2·6` is the product of the two
  implicit payload scales), addressed by the **output's own tag / drain order** — never by live
  feed counters.
- **DIM<32 two-phase:** one logical 32-element K-block spans `32/DIM` physical K-phases. Raw
  partials are buffered across phases and **scaled once** at the last half. `(block, half)` is
  re-derived at output from a drain-order phase counter (this was the D4 fix — feed-time sampling
  drifted a phase under preload/compute fusion + mesh tag latency).
- **RES-OPT:** scaling is **undelayed/streaming** with a **1-SRAM-read-per-cycle read-ahead**
  that paces the 1-row-per-cycle drain exactly ⇒ **zero metadata stall cycles by construction**,
  and the largest MX register (~20K FF at DIM=32) was **removed**. This is a headline finding, not
  a baseline: the tile-buffered two-step scaling the old plans prescribed is unnecessary.

**Numerical contract:** E8M0 scale (unsigned, bias 127, `0xff`=NaN); INT8 payload, 2's-complement,
implicit scale `2^-6` (1 sign + 1 int + 6 frac, symmetric ±127, −128 unused by the packer); int32
accumulator with documented saturation. Golden reference + packer:
[`mxint8_golden.h`] / [`mxint8_pack.h`]; frozen rules in [`mxint8_policy.md`] v1.1.

**Key RTL/SW files** — see the full map in §10.

---

## 4. Status by phase

Plan of record: [PLAN_MX_UPDATED.md](PLAN_MX_UPDATED.md) §3 (phases) + §5 (log).

| Phase | Scope | Status |
|---|---|---|
| **P0** | Artifact hygiene: commit/tag the WIP | **DONE** — gemmini `mx_dev`, tag `mxint8-rtl-verified-20260610`; scripts added |
| **P1** | OCP MX v1.0 conformance audit | **DONE** — [OCP_CONFORMANCE.md] 16-clause matrix; packer → §6.3; microxcaling oracle green; policy v1.1; deviations D1/D2/D3 |
| **R0–R3 / S1–S3** | Build/elaborate, RTL audit, DIM32 + DIM16 two-phase datapath, packer, golden, HW tests | **DONE** — all bit-exact (see §5) |
| **P3** | Multi-tile loop I/J>1 | **DONE** — `mxint8_multitile` green at all DIMs |
| **Stage B** | DIM=8 / DIM=4 generalization | **DONE** — sims + DIM-agnostic tests green |
| **Perf track** | Root-cause + cache fix (30edd64) + **Phase C** offline pre-tile | **DONE** — repack eliminated; 256³ 1.63× stock (§7) |
| **P2** | AutoCounters + Vivado OOC synthesis deltas (stock vs MX) | forward |
| **P4** | Wider-accumulator config option + saturation-incidence study | forward |
| **P5** | One validated transpose/OS case (hard 4-week gate) | forward |
| **P6** | Eval harness + SW-MX & stock-int8 baselines | forward (mx_bench exists) |
| **P7** | FireSim on VCU118 (+ arXiv preprint trigger) | forward |
| **P8** | Nexys Video DIM=16 FPGA prototype | forward |
| **P9** | Model-level accuracy sanity via microxcaling | forward |
| **P10** | MXINT16 "MX-consistent generalization" (gated; needs P4 wide-acc) | forward |
| **P11** | Paper + artifact packaging | forward |

---

## 5. Bug & fix history — what each fix fixed

Every bug below was caught only by a **running RTL sim** (compile/elaboration missed them); all
fixes are `mx_enabled`-gated / stock-inert. Full narratives in the auto-memory and [BUG.md].

| ID | Symptom | Root cause | Fix (where) |
|---|---|---|---|
| **R2-1** | Output row *i* scaled by `eA[i-1]` (row 0 OK) | A-scale SRAM read used the *delayed* `output_counter` but issues in the undelayed output domain | dedicated undelayed `mx_a_read_row` counter (`ExecuteController`) |
| **R2-2** | Wrong saturation at large scales | `(value<<sh)` wrapped on 64-bit overflow + built a ~1000-bit shifter | bounded 6-bit shifter, saturate to int64 like golden; `>>≥63 ⇒ 0` |
| **B1** | Rand-reset trips the untransposed-WS assert | `a_transpose`/`bd_transpose` were reset-less `Reg(Bool())` | `RegInit(false.B)` |
| **B2** (decisive) | `hw == raw` exactly (scaling skipped) | In WS the **output is tagged by the PRELOAD**, but MX set `tag.mx_enabled` only on the MULTIPLY ⇒ output tag `mx_enabled=0` ⇒ BlockScaleUnit bypassed | include `performing_single_preload` in the `mx_enabled` enq gate (`ExecuteController` ~856) |
| **B3** | — | Untransposed-WS-only is a real usage constraint | documented (assert guards it) |
| **D1** | `bytes_left` underflow assert at DIM<32 (nCmds>1) | scale-DMA `cmd_id = RegEnable(alloc.cmd_id, fire)` lags 1 cyc, but the first row req fires the same cycle as alloc ⇒ resp misattributed | `cmd_id := Mux(state==waiting_for_command, alloc.cmd_id, cmd_id)` (`MXScaleLoadController`) |
| **D2** | DIM16 `hw=16` not 32 (2nd half reads 0) | `mx_raw_half` stored on WS bubble/garbage-addr cycles between phases | gate store by `start_array_outputting && write_this_row` |
| **D3-bug** | DIM16 tail rows scaled by wrong block's B-exp | B-scale read at **feed** time into a single latch; deep WS pipeline lets the next block clobber it before drain | read B at **output** time addressed by `tag.mx_block` (mirror the A read); delete feed-time latch |
| **D4** | DIM16 cross-block: half mis-tagged `blk0 sh1` | per-output `(block,half)` sampled from the live feed counter drifts one phase under preload/compute fusion + mesh +2 tag latency | re-derive `(block,half)` at output from a **drain-order phase counter** (Scala-guarded `if (DIM<mx_block_size)`) |
| **RS hazard** | Loop multi-block "frozen first K-tile" (constant stale value) | scale mvins are `is_config`; the CONFIG_LOAD decode hook read a scale mvin's DRAM pointer as `ld_pixel_repeats/strides` ⇒ next A-mvin's dependency range mis-decoded ⇒ compute issued over a still-DMAing tile | one-line `&& !new_entry.is_mx_scale` guard (`ReservationStation`) |
| **RS ordering** | Matmul could read stale scales / freed-slot assert | scales live in sidecar (no address overlap); loads `complete_on_issue` | `is_mx_scale` marker: complete on real DMA completion; compute depends on every in-flight scale load |
| **R0b** | Stock elaboration errors | `None.get` in `AccumulatorMem`; `mx_scale_reader` counter-ID double-connect | `.foreach`; counters tied off (proper fix deferred to P2) |
| **S3#5** | Loop wrapper under-counted scales / single-tile only | `gemmini_loop_ws_mxint8` passed tile counts where element counts were needed; K treated as elements | element-count A-rows/B-cols + K-element block count (`gemmini.h`) |

> **Two different "D" numbering schemes — do not confuse them.** The **DIM16 RTL bugs** above are
> D1–D4. The **OCP conformance deviations** in §6 are *also* called D1/D2/D3 but are unrelated.

---

## 6. OCP conformance deviations

Audit: [OCP_CONFORMANCE.md] (16-clause matrix). Packer follows the OCP **§6.3 recommended
algorithm** `X = 2^floor(log2 amax)`, payloads `RNE(V·2^(6−e))` clamped to ±127 — verified
bit-exact vs **microsoft/microxcaling** incl. the old divergence band `amax∈(1.984·2^j, 2·2^j)`.
Three documented profile deviations:

- **D1 — NaN scale `0xff` rejected at ingress, not propagated.** Integer outputs have no NaN to
  propagate; golden returns −1, RTL asserts. Conformant producers never emit `0xff`.
- **D2 — cross-block accumulation is int32, not §6.2 *should*-Float32.** Exact (stronger than FP32)
  inside the non-saturating envelope; saturation incidence is workload-dependent → quantified by
  the P4 wide-acc study.
- **D3 — −128 payloads accepted only within the RES-OPT `SInt(20.W)` raw envelope.** The packer
  never emits −128; an all-±128 block reaches `32·128² = 2^19`, one LSB past the narrowed mesh
  output width. Widening = DO-NOT-TOUCH mesh + RES-OPT regression, for data the packer never makes.

---

## 7. Performance status

Measured on Verilator, `GemminiMXINT8DIM32RocketConfig`, `mx_bench` to clean `$finish`
(`mx_bench: PASS`), all shapes bit-exact. Full attribution: [PERF_ANALYSIS.md] (§"★★ PHASE C").

| shape | original MX | cache-fix (30edd64) | **Phase C** | repack_cyc (Phase C) | issue_cyc | stock |
|---|---:|---:|---:|---:|---:|---:|
| 64³  | 5,952   | 5,397  | **3,345**  | 0 | 69     | — |
| 128³ | 26,688  | 15,402 | **10,102** | 0 | 2,457  | — |
| 256³ | 181,703 | 74,484 | **57,423** | 0 | 21,258 | **35,238** |

- **Root cause (definitive):** the ~5× slowdown was the **host-side B-scale repack**
  (`mxint8_repack_b_scales_tiled`, a scalar loop re-tiling dense B-scales on the in-order Rocket) =
  **84% of 256³** — NOT the hardware. The MX datapath was already competitive.
- **Cache fix (30edd64):** repack once per `(j,k)` chunk, reuse across the `i0` sweep → 256³ repack
  152,234 → 22,452 (6.8×).
- **Phase C (this session):** pre-tile the constant weight B-scales **once, offline/untimed**
  (`mxint8_pretile_b_scales` + `tiled_matmul_mxint8_pretiled` in `gemmini.h`; public
  `tiled_matmul_mxint8` signature unchanged → test callers byte-identical) → **repack_cyc = 0**,
  256³ 74,484 → 57,423 (3.16× vs original). Mesh occupancy up to 49%.
- **Residual gap to stock:** host command-issue (`issue_cyc≈21k`) + mesh-feed backpressure
  (`no_cmd≈59k`) — `gemmini_loop_ws_mx` ROCC emission stalls on the RoCC queue.
- **Refuted theories (do not re-explore):** drain-walk "Fix B" is a no-op (the walk ≡ the mesh
  tag, never gates issue); the scale-mvin/DAE dependency never blocks; and **there was never a
  hang** (see §8).
- **★ OPEN DECISION:** *bank Phase C* (clean, deployment-realistic, bit-exact 3.16×) vs. *push the
  command-issue / mesh-feed overlap toward stock parity* — the latter is DO-NOT-TOUCH-adjacent and
  must be measure-first.

---

## 8. Validation methodology & gotchas (hard-won — every one cost a session)

Authoritative: [SIM_VALIDATION_NOTES.md].

- **JDK trap:** system JDK 21 shadows conda JDK 20 and breaks sbt's parser. Always source
  `env.sh` by **absolute path** (a drifted cwd makes `source ./env.sh` silently no-op).
- **firtool MUST be pinned:** bare `firtool` on PATH is `/usr/local/bin/firtool` (LLVM 17) and
  cannot parse the pinned 1.62.1 (LLVM 18) `.fir` dialect (`error: unexpected character`). Pass
  `FIRTOOL_BIN=$HOME/.cache/llvm-firtool/1.62.1/bin/firtool`. **Trap found 2026-06-30:** a *stale*
  sim makes `run-binary-fast` silently **re-elaborate**, and without `FIRTOOL_BIN` it falls back
  to LLVM 17 → spurious "FAIL" that is a build error, not a correctness failure. Fixed: `run_one`
  now pins `FIRTOOL_BIN`. (A sim "FAIL" that is a firtool parse error during a rebuild ≠ a bug.)
- **`+loadmem` for large binaries:** the default TSI serial loader writes the whole image
  (incl. `.bss`) before releasing the core, so **boot time scales with footprint** (mx_bench's
  `.bss` is 423 KB — `c_hw[256][256]` int32 alone is 256 KB). Run with `LOADMEM=1` (→
  `+loadmem=<elf>`, preloads DRAM, boots any size fast).
- **Output buffering:** `+verbose` commit-log and bare-metal `printf` both flush only at
  `$finish`/`exit`. A killed/timed-out run shows partial/no output and **looks hung when it isn't**.
  Let it reach `$finish`, or bound `+max-cycles` so the watchdog `$finish` flushes.
- **Kill sims by exact PID/name**, never `pkill -f <pattern>` (it matches its own shell → no-op
  kill, can leave a stray sim pinning a core).
- **Spike/esp-tools does NOT model the MX sidecar** — only RTL runs validate MX.
- **RTL edit** ⇒ `rm -rf sims/verilator/generated-src/<Top>` (+ `rm -f` the sim binary) to force
  re-elaboration (Zinc/`touch` won't).
- **Header staging:** the checked-in `gemmini_params.h` is the **stock** header (`MX_ENABLED 0`).
  Stage the MX header before building MX tests and **restore stock afterward** (the run scripts do
  this for you).

---

## 9. Commands cookbook (copy-paste; absolute paths)

```bash
# 0) Activate toolchain (EVERY session; absolute path; verify JDK 20)
source /home/nikolap/Research/2026/chipyard/env.sh && export PATH="$CONDA_PREFIX/bin:$PATH"
java -version    # must show 20.x

# 1) Type-check / compile gemmini (fast gate)
sbt "project gemmini" compile          # add `clean` to force a full recompile

# 2) Build a Verilator sim (RTL edit first: rm -rf the generated-src/<Top> + sim binary)
FIRTOOL=$HOME/.cache/llvm-firtool/1.62.1/bin/firtool
make -C sims/verilator CONFIG=GemminiMXINT8DIM32RocketConfig FIRTOOL_BIN=$FIRTOOL   # builds the sim
#   stock baseline config: GemminiRocketConfig ; MX configs: GemminiMXINT8DIM{32,16,8,4}RocketConfig

# 3) Build the baremetal tests (header staging; stock header restored after)
cd generators/gemmini/software/gemmini-rocc-tests
cp include/gemmini_params.h /tmp/stock.h
cp include/gemmini_params_mxint8_dim32.h include/gemmini_params.h   # or _dim16/_dim8/_dim4
./build.sh
cp /tmp/stock.h include/gemmini_params.h                            # ALWAYS restore
cd -

# 4) Run ONE test on RTL (LOADMEM=1 = fast boot for large .bss; raise timeout for many-case bins)
make -C sims/verilator CONFIG=GemminiMXINT8DIM32RocketConfig FIRTOOL_BIN=$FIRTOOL run-binary-fast \
  BINARY=$PWD/generators/gemmini/software/gemmini-rocc-tests/build/bareMetalC/mx_bench-baremetal \
  LOADMEM=1 timeout_cycles=300000000

# 5) FULL bit-exact regression gate (the formal gate; builds stale sims with the pinned firtool)
./DOCS_MX/scripts/run_regression.sh --build-sims --dims=32,16,8,4   # OVERALL: PASS expected
#   reuse existing sims (skip rebuild): ./DOCS_MX/scripts/run_regression.sh --dims=32

# 6) Performance benchmark (stock vs MX) + report
./DOCS_MX/scripts/run_perf.sh --dims 32           # writes DOCS_MX/scripts/results/perf.csv
python3 DOCS_MX/scripts/make_report.py            # formats the CSV

# 7) External OCP oracle (host; from gemmini-rocc-tests/)
/abs/path/.mx-venv/bin/python3 tools/mxint8_external_diff.py --sweep \
  --json-out build/mxint8_external_diff_sweep.json

# 8) Vivado OOC synthesis deltas (P2)
./DOCS_MX/scripts/run_synth.sh                    # uses DOCS_MX/scripts/synth_ooc.tcl
```

---

## 10. File & directory map

**RTL** (`generators/gemmini/src/main/scala/gemmini/`):
- `MXScaleSRAM.scala` — sidecar scale SRAM (predecoded E8M0 exps, 2 mems); asserts on `0xff`.
- `MXScaleLoadController.scala` — scale-mvin DMA controller (D1 cmd_id fix).
- `ExecuteController.scala` — inline BlockScaleUnit, DIM<32 two-phase, drain-order `(block,half)`,
  undelayed scaling + read-ahead (B2/D2/D3-bug/D4/R2-1/R2-2 live here).
- `AccumulatorScale.scala`, `Scratchpad.scala` — acc/spad integration.
- `ReservationStation.scala` — `is_mx_scale` marker + CONFIG_LOAD decode guard (RS-hazard fix).
- `GemminiISA.scala` — ops 26/27/28 (+29 reserved).
- `GemminiConfigs.scala` — `GemminiMXINT8DIM{32,16,8,4}RocketConfig`; int8 pinning ~:213-222.
- `Configs.scala` — param plumbing (`mx_block_size`, `mx_scale_bits`, RES-OPT output type).

**Software** (`generators/gemmini/software/gemmini-rocc-tests/`):
- `include/gemmini.h` — MX intrinsics + tiler. Key fns: `tiled_matmul_mxint8` /
  `tiled_matmul_mxint8_impl` / `tiled_matmul_mxint8_pretiled`, `gemmini_loop_ws_mxint8`,
  `mxint8_repack_b_scales_tiled`, `mxint8_compute_geom`, `mxint8_pretile_b_scales`,
  `MX_PRETILE_SLOT_ELEMS`.
- `include/mxint8_golden.h` — frozen bit-exact reference (`mxint8_ref_gemm_acc`).
- `include/mxint8_pack.h` — OCP §6.3 packer/quantizer.
- `include/gemmini_params_{mxint8,stock}_dim{32,16,8,4}.h` — staged params headers.
- `bareMetalC/mx_bench.c` — perf harness (MXBENCH/MXCOUNT/MXCPU lines).
- `bareMetalC/mxint8_{golden,matmul_dim32,matmul_dim16,matmul_nphase,corner,matmul_partial,tiled,btb,multitile}.c`
  — the regression suite. (`mxint8_iso.c`, `mx_fence_probe.c` = investigation artifacts.)

**Scripts** (`DOCS_MX/scripts/`): `run_regression.sh` (bit-exact gate), `run_perf.sh` (perf),
`make_report.py`, `run_synth.sh` + `synth_ooc.tcl` (Vivado OOC).

**Docs:** see the index in §13.

---

## 11. Repo & git layout

- **Three-level submodule nesting:** chipyard (superproject) → `generators/gemmini` →
  `software/gemmini-rocc-tests`. RTL + SW + tests all live in the gemmini submodule chain; DOCS_MX,
  top configs, and the sim/build glue live in the chipyard superproject.
- **Branches:** `mx_dev` in both gemmini and chipyard. **Tag:** `mxint8-rtl-verified-20260610`.
- **Commit choreography (user does this):** commit the innermost repo first → bump the gemmini
  submodule pointer → commit chipyard (recording the submodule SHA). Ask before pushing.
- **Currently staged, awaiting your commit (Phase C):** in `gemmini-rocc-tests` —
  `include/gemmini.h`, `bareMetalC/mx_bench.c`; in chipyard —
  `DOCS_MX/{PERF_ANALYSIS,PLAN_MX_UPDATED,SIM_VALIDATION_NOTES}.md`,
  `DOCS_MX/scripts/{run_perf,run_regression}.sh`, and this file.

---

## 12. Open items / next decisions

1. **★ Perf decision:** bank Phase C (256³ = 57,423, 1.63× stock, bit-exact) **or** attempt the
   command-issue/mesh-feed overlap toward parity (DO-NOT-TOUCH-adjacent; start with a measurement
   of where `issue_cyc` stalls before any RTL edit).
2. **P2:** wire AutoCounters (fix the R0b counter-ID tie-off) + Vivado OOC synthesis deltas
   (stock vs MX, DIM32/16).
3. **Refresh the perf matrix:** the old DIM32 table in [PERF_ANALYSIS.md] §"Data" is repack-
   dominated (pre-fix) — re-cut via `run_perf.sh`.
4. **Commit the staged Phase C** (§11) and continue P4–P11.

---

## 13. Deep-dive pointer index

| Doc | What it authoritatively covers |
|---|---|
| [PLAN_MX_UPDATED.md](PLAN_MX_UPDATED.md) | **Plan of record** (phases P0–P11, §4 regression matrix, env recipes) + **§5 append-only running log** (newest status) |
| [RESEARCH_PLAN.md](RESEARCH_PLAN.md) | Thesis, 2025–26 landscape, baselines/ablations, venue, timeline |
| [PLAN_MX.md](PLAN_MX.md) | Historical execution log (pre-replan); §12 has the full bug/fix narratives |
| [OCP_CONFORMANCE.md](OCP_CONFORMANCE.md) | OCP MX v1.0 clause-by-clause matrix; deviations D1/D2/D3 |
| [PERF_ANALYSIS.md](PERF_ANALYSIS.md) | Performance attribution (root cause, cache fix, Phase C) |
| [SIM_VALIDATION_NOTES.md](SIM_VALIDATION_NOTES.md) | Validation methodology + the "never a hang" correction + gotchas |
| [BUG.md](BUG.md) | Deep dive on the RS RAW-hazard / "frozen first K-tile" bug |
| [P3_DESIGN.md](P3_DESIGN.md), [DMA_FIX_PLAN.md](DMA_FIX_PLAN.md), [DRAIN_PIPELINING_DESIGN.md](DRAIN_PIPELINING_DESIGN.md), [MATMUL_PIPELINING_FIX_PLAN.md](MATMUL_PIPELINING_FIX_PLAN.md), [DIM64_FEASIBILITY.md](DIM64_FEASIBILITY.md) | Design notes (some superseded — check dates against §5) |
| [`mxint8_policy.md`](../mxint8_policy.md) (chipyard repo root) | Frozen numerical contract (v1.1, spec-mapped) |
| [PLAN_TEST.md](PLAN_TEST.md) | Test catalogue / planning |
| `mxint8_gemmini_plan_updated.pdf`, `research-report 4.md` | April-2026 inputs (historical) |
