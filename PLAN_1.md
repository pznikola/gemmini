# MXINT8 Gemmini DIM16 Optimization Plan

Status snapshot: 2026-07-04.

This file is the second execution plan for MXINT8 Gemmini optimization. It starts
from the final implementation selected in `PLAN.md` iteration I20 and focuses on
improving DIM16 MXINT8 large-shape performance and area while preserving the final
DIM32 MX behavior.

This plan is intentionally iterative and discovery-driven. It does not preselect
twenty Gemmini modifications. For every iteration, the agent must measure, analyze
results, read the relevant Gemmini code, research the observed bottleneck online,
choose one candidate change, implement it, test it, and document the decision.

## 1. Campaign Goal

Optimize the current MXINT8 implementation so that DIM16 MXINT8 is closer to pure
stock INT8 in cycles and area, especially for large square shapes, without
regressing the final DIM32 MX result.

Starting point from `PLAN.md` I20:

| DIM | Impl | 64^3 cycles | 128^3 cycles | 256^3 cycles |
|---|---|---:|---:|---:|
| 32 | stock | 5,095 | 10,445 | 41,022 |
| 32 | MX | 4,317 | 9,855 | 37,921 |
| 16 | stock | 6,088 | 12,900 | 78,453 |
| 16 | MX | 4,390 | 14,349 | 101,565 |

Current DIM16 problem:

- MX DIM16 is faster than stock at `64^3`.
- MX DIM16 is slower than stock at `128^3` by 1,449 cycles.
- MX DIM16 is slower than stock at `256^3` by 23,112 cycles.

Final I20 OOC area versus pure stock INT8:

| DIM | LUT delta | FF delta | BRAM36 delta | DSP delta |
|---|---:|---:|---:|---:|
| 32 | +32,036 (+15.15%) | +2,800 (+2.36%) | +32 (+10.00%) | +3 (+1.12%) |
| 16 | +27,833 (+29.93%) | +7,947 (+14.98%) | +8 (+5.00%) | +3 (+1.27%) |

The final I20 implementation keeps pure stock `reservation_station_entries_st=4`
and explicitly sets MX DIM32/DIM16 to `reservation_station_entries_st=8` and
`st_queue_length=4`.

## 2. Non-Negotiable Rules

- DIM32 MX behavior must remain unchanged unless the user explicitly approves a
  tradeoff.
- DIM32 MX cycles must remain same or better than I20:
  - `64^3 <= 4,317`
  - `128^3 <= 9,855`
  - `256^3 <= 37,921`
- DIM16 candidates are useful only if they improve `128^3`, `256^3`, or area
  without breaking DIM32.
- All accepted MX results must remain bit-exact against the MX golden model.
- Pure stock INT8 must remain pure: no MX scale SRAM, MX scale-load controller,
  MX datapath, or MX-specific control logic should be generated for stock configs.
- Do not change MXINT8 numerical semantics or `mxint8_policy.md` unless the user
  explicitly approves it.
- Do not use `/tmp` for long Verilator, Vivado, reference-checkout, log, or scratch
  campaigns.

Run-location rule:

- All long simulations, logs, temporary headers, and scratch must stay under
  `sims/verilator/gemmini`.
- Use the existing scripts' default `RUN_DIR`, `LOG_DIR`, and `TMPDIR`.
- Override only with `MX_RUN_DIR=/path/to/workspace-local/run-dir`.

## 3. Required Context Before Each Iteration

At the start of every iteration, read or re-check the files that are relevant to
the measured bottleneck. Do not rely on memory or on this plan alone.

Always keep these anchors in mind:

- `PLAN.md`
- `DOCS_MX/WORK_DONE.md`
- `DOCS_MX/WORK_TODO.md`
- `DOCS_MX/COMMANDS.md`
- `generators/gemmini/src/main/scala/gemmini/Configs.scala`
- `generators/gemmini/chipyard/GemminiConfigs.scala`
- `generators/gemmini/src/main/scala/gemmini/Controller.scala`
- `generators/gemmini/src/main/scala/gemmini/ReservationStation.scala`
- `generators/gemmini/src/main/scala/gemmini/ExecuteController.scala`
- `generators/gemmini/src/main/scala/gemmini/LoopMatmul.scala`
- `generators/gemmini/software/gemmini-rocc-tests/include/gemmini.h`
- `generators/gemmini/software/gemmini-rocc-tests/bareMetalC/mx_bench.c`

For each iteration, also inspect the specific code implicated by counters and
logs. For example, if DIM16 loss appears to be phase scheduling, inspect the
DIM<32 phase logic before touching store buffers or scale SRAM.

## 4. Standard Commands

Environment:

```bash
cd /home/nikolap/Research/2026/chipyard
source /home/nikolap/Research/2026/chipyard/env.sh
export PATH="$CONDA_PREFIX/bin:$PATH"
export FIRTOOL="$HOME/.cache/llvm-firtool/1.62.1/bin/firtool"
```

Compile gate:

```bash
sbt "project gemmini" compile
```

DIM32 performance gate, run before DIM16:

```bash
DOCS_MX/scripts/run_perf.sh --dims 32 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_jXX_dim32.csv
```

DIM16 performance gate, only after DIM32 is same or better:

```bash
DOCS_MX/scripts/run_perf.sh --dims 16 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_jXX_dim16.csv
```

DIM16 diagnostic counter run, required before hardware/config changes:

```bash
MX_BENCH_COUNTER_SET=2 DOCS_MX/scripts/run_perf.sh --dims 16 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_jXX_dim16_debug.csv
```

Correctness gates:

```bash
DOCS_MX/scripts/run_regression.sh --dims=32 --skip-stock-elab --timeout-cycles=300000000
DOCS_MX/scripts/run_regression.sh --dims=16 --skip-stock-elab --timeout-cycles=300000000
```

Full rebuilt-simulator regression for final or risky hardware candidates:

```bash
DOCS_MX/scripts/run_regression.sh --build-sims --dims=32,16 --timeout-cycles=300000000
```

Vivado OOC synthesis for accepted hardware/config changes or finalists:

```bash
source ~/Programs/Xilinx/Vivado/2022.2/settings64.sh
DOCS_MX/scripts/run_synth.sh --csv DOCS_MX/scripts/results/synth_plan1_jXX.csv
```

Stock rows do not need to be rerun unless the stock config, stock RTL, benchmark
timed region, common software path, compiler flags, or simulator flags changed.
If any of those change, regenerate stock and document why cached stock rows are
no longer fair.

## 5. Required Iteration Protocol

There are exactly twenty optimization iterations: `J01` through `J20`.

Each iteration must follow this sequence:

1. Record current baseline, hashes, and the observed DIM16 bottleneck.
2. Run or reuse fair DIM32 and DIM16 performance data.
3. Analyze counters and logs, especially DIM16 `128^3` and `256^3`.
4. Read the relevant Gemmini hardware and software code before proposing changes.
5. Perform online research for the observed bottleneck.
6. Record research links and the concrete idea taken from each source.
7. Decide whether the candidate is software-only, hardware-only, or co-designed.
8. Implement the smallest candidate change that tests the hypothesis.
9. Run focused correctness for the changed behavior.
10. Run DIM32 performance first.
11. Run DIM16 performance only if DIM32 is same or better.
12. Run synthesis only for accepted hardware/config changes or finalists.
13. Bank or revert the candidate.
14. Document the result in the iteration log before starting the next iteration.

Online research is mandatory in every iteration. Use papers, official
documentation, architecture manuals, or credible technical sources. For each
source, record:

- URL or citation.
- Why the source is relevant to the measured bottleneck.
- What idea, warning, or design pattern the iteration takes from it.
- Whether the idea is software-only, hardware-only, or co-designed.

Do not precompute candidate modifications for future iterations. The candidate
for iteration `JNN` must be chosen from that iteration's measurements, code
inspection, and research.

## 6. Candidate Decision Rules

Before implementation, write a short candidate note:

- Measured bottleneck.
- Code inspected.
- Research consulted.
- Hypothesis.
- Expected effect on DIM32.
- Expected effect on DIM16.
- Expected area impact.
- Risk to correctness.
- Exact rollback plan.

Candidate classification:

- **Software-only**: changes C headers, tiler scheduling, benchmark
  instrumentation, command issue order, or helper code without changing generated
  RTL.
- **Hardware-only**: changes Scala RTL/config behavior without changing software
  scheduling policy.
- **Co-designed**: changes both hardware contract and software scheduling/API.

Banking rules:

- Bank if DIM32 is same or better, DIM16 improves meaningfully or area improves,
  correctness passes, and the change is simple enough to defend.
- Revert if DIM32 regresses, correctness fails, stock purity is threatened, or the
  area cost is not justified by DIM16 speed.
- If a candidate improves DIM16 but regresses DIM32, stop and ask the user before
  keeping it.

## 7. Metrics To Record

For every accepted or rejected candidate, record:

- Top-level, Gemmini, and rocc-tests hashes.
- DIM32 MX cycles for `64^3`, `128^3`, `256^3`.
- DIM16 MX cycles for `64^3`, `128^3`, `256^3`, or the reason DIM16 was skipped.
- Cached stock baseline used for comparison.
- MX/stock ratio for the primary shapes.
- `MXCOUNT`, `MXCPU`, `MXPART`, and `MXSCALEDEP` counters when available.
- Code inspected before the change.
- Online research sources and the idea taken from each source.
- Correctness commands and results.
- Area/timing result for accepted hardware/config changes.
- Whether the change was banked or reverted.

Use this table for the iteration log:

| Iter | Date | Hash | Measured bottleneck | Code inspected | Research sources | Hypothesis | Change | DIM32 result | DIM16 result | Correctness | Area | Decision |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| J01 | 2026-07-04 | top `8712c011` + uncommitted `run_perf.sh`; gemmini `ad11d23c`; rocc-tests `f4622b56` | DIM16 large-shape loss reproduced; debug command initially emitted normal `MXCOUNT` because `MX_BENCH_COUNTER_SET` was not passed to the C build | `run_perf.sh`; `mx_bench.c`; `gemmini.h`; `Configs.scala` | Gemmini paper; RISC-V counters spec; TVM/Gemmini scheduling paper | First fix measurement fidelity: make the required counter command select the intended compile-time counter set without changing RTL or default perf behavior | Software-only: `run_perf.sh` now maps `MX_BENCH_COUNTER_SET` and `MX_BENCH_PRINT_GEOM` env vars into validated `EXTRA_CFLAGS` | Preserved exactly: `4317 / 9855 / 37921` | Preserved exactly in default run: `4390 / 14349 / 101565`; debug set 2 now emits `MXSCALEDEP` | `mxint8_matmul_dim16` focused regression PASS | N/A; no RTL/config change | Banked measurement fix; J02 should use the new counters to attack DIM16 non-scale/store/load pressure |
| J02 | 2026-07-04 | top `8712c011` + uncommitted `run_perf.sh`; gemmini `ad11d23c`; rocc-tests `f4622b5` | DIM16 `256^3` geometry has 16 chunks, 4 J chunks, `b_reuse=0`, 16 B payload loads, 16 A-scale loads, 16 B-scale loads, high issue/backpressure counters | `gemmini.h`; `LoopMatmul.scala`; `ReservationStation.scala`; `ExecuteController.scala`; `Configs.scala` | Gemmini paper; TVM/Gemmini scheduling paper; RASA register-aware systolic scheduling paper | Larger execute reservation station might reduce `ex_pool_full`/issue backpressure in DIM16 large shapes | Hardware-only DIM16 trial: `reservation_station_entries_ex=32`; compiled, rebuilt, measured, then reverted | Reused J01 DIM32 because DIM32 config/source untouched: `4317 / 9855 / 37921` | Candidate unchanged: `4390 / 14349 / 101565`; reverted-source rebuild also `4390 / 14349 / 101565` | `mx_bench: PASS`; J01 focused DIM16 regression still valid for banked tree | No synthesis; rejected before area because no cycle gain and likely area increase | Reverted. Next iteration should target DIM16 software scheduling/reuse or scale-load reduction, not EX RS depth |
| J03 | 2026-07-04 | top `8712c011` + uncommitted `run_perf.sh`; gemmini `ad11d23c`; rocc-tests `f4622b5` + modified `include/gemmini.h` | DIM16 `256^3` reloads B payload 16 times because four J chunks exceed the two resident B regions; J02 showed this is a scheduling/reuse bottleneck, not EX RS depth | `gemmini.h`; `LoopMatmul.scala`; J02 geometry and perf logs | TVM/Gemmini scheduling paper; RASA register-aware systolic scheduling paper; Gemmini paper | For DIM16 four-J-chunk, single-K-chunk shapes, process two J chunks as a resident group across the full I sweep so each pair reuses B in the existing two spad regions | Software-only guarded pair-J schedule in `gemmini.h`; initial all-path sequence refactor was rejected, then fallback was restored to original nested loops | Preserved exactly after guard tightening: `4317 / 9855 / 37921` | `4340 / 14446 / 94543`; `256^3` improved by 7,022 cycles, `128^3` regressed by 97 cycles | DIM16 regression PASS; DIM32 regression PASS; `mx_bench: PASS` | N/A; no RTL/config change | Banked with caveat. Keep for the large `256^3` win, but J04 must recover the DIM16 `128^3` regression/common-path overhead |
| J04 | 2026-07-04 | top `8712c011` + uncommitted `run_perf.sh`; gemmini `ad11d23c`; rocc-tests `f4622b5` + temporary `gemmini.h` cleanup | J03 banked a `256^3` win but regressed DIM16 `128^3` by 97 cycles; suspected common-path branch/code-shape overhead | `gemmini.h` J03 branch/fallback; J03 perf logs | GCC built-in/branch-layout documentation; TVM/Gemmini scheduling paper | Fast-reject sub-256 shapes and simplify pair-J-only code to recover DIM16 `128^3` while preserving DIM32 and most of the `256^3` win | Software-only cleanup: add `M/N/K >= 256` predicate and remove redundant pair-branch conditionals; then revert | Preserved exactly: `4317 / 9855 / 37921` | `4481 / 14453 / 94320`; `256^3` improved 223 cycles versus J03, but `64^3` and `128^3` worsened | `mx_bench: PASS`; no full regression because rejected after perf | N/A; no RTL/config change | Reverted to banked J03. Small `256^3` gain did not justify worse `64^3`/`128^3` |
| J05 | 2026-07-04 | top `8712c011` + uncommitted `run_perf.sh`; gemmini `ad11d23c`; rocc-tests `f4622b5` + modified `include/gemmini.h` | DIM16 still reloaded B scales across reused B payloads; J03/J04 showed `128^3` needed scale/load relief and `256^3` still had high issue/scale overhead | `gemmini.h` B-scale residency path; `LoopMatmul.scala` resident-region mapping; J03/J04 perf logs | Gemmini paper; TVM/Gemmini scheduling paper; RASA register-aware systolic scheduling paper | Extend B-scale residency to DIM16 when B payload is resident, while keeping DIM32's existing residency threshold unchanged | Software-only: lift `g_mx_bscale_resident`/residency bookkeeping out of DIM32-only guards, use `mx_b_reuse || mx_pairj_b_reuse`, and allow DIM16 residency when `mx_IC >= 2` | Preserved exactly: `4317 / 9855 / 37921` | `4492 / 14320 / 92765`; improves both problem shapes, but regresses `64^3` | DIM16 regression PASS; DIM32 regression PASS; `mx_bench: PASS` | N/A; no RTL/config change | Banked. J06 should address the `64^3` slowdown or find further large-shape wins without losing DIM32 |
| J06 | 2026-07-04 | top `8712c011` + uncommitted `run_perf.sh`; gemmini `ad11d23c`; rocc-tests `f4622b5` + temporary `gemmini.h` signature experiment | J05 improved problem shapes but regressed DIM16 `64^3`; suspected global B-scale-resident flag stores/loads added overhead when residency disabled | `gemmini.h` `gemmini_loop_ws_mxint8` signature/call sites; direct MX tests using the old helper API | GCC inline documentation; Gemmini paper; TVM/Gemmini scheduling paper | Pass B-scale residency as a local call argument instead of a global flag to reduce non-resident path overhead | Software-only API experiment; first build failed direct callers, then wrapper-fixed form failed DIM32 perf and was reverted | Failed gate: `64^3=4474`, `128^3=9875`; run stopped before `256^3` | Skipped because DIM32 regressed | Initial test build failed, fixed build ran; no full regression because rejected | N/A; no RTL/config change | Reverted to banked J05. Do not change public helper signature without a broader API audit |
| J07 | 2026-07-04 | top `8712c011` + uncommitted `run_perf.sh`; gemmini `ad11d23c`; rocc-tests `f4622b5` + modified `mx_bench.c` | User raised whether high MX issue time means too many instructions and whether an MX custom instruction is needed; J05/J07 counters showed huge `issue_cyc`, especially DIM16 `256^3` | `mx_bench.c`; `gemmini.h` RoCC macros; `LoopMatmul.scala` LOOP_WS decode/config; Rocket Chip `LazyRoCC.scala` | RISC-V ISA extension docs; Gemmini README/paper; Rocket Chip RoCC source | Add retired-instruction attribution before pursuing a co-designed MX fused/custom command | Software-only diagnostic: add `MX_BENCH_COUNTER_SET=3`, pass it through `run_perf.sh`, and print `MXINST` with `rdinstret` around the timed GEMM | Preserved exactly: `4317 / 9855 / 37921` | Preserved exactly in default run: `4492 / 14320 / 92765`; diagnostic `MXINST` showed DIM16 `256^3` retired only `4297` instructions while `issue_cyc` was `74974` | `mx_bench: PASS`; no functional RTL/software-kernel change | N/A; no RTL/config change | Banked diagnostic. A custom MX instruction is feasible, but J08 should not treat plain retired-instruction count as the root cause |
| J08 | 2026-07-04 | top `8712c011` + uncommitted `run_perf.sh`/`PLAN_1.md`; gemmini `ad11d23c`; rocc-tests `f4622b5` + modified `gemmini.h`/`mx_bench.c` | J07 showed instrumentation itself was still in the default timed MX path: per-chunk `rdcycle` probes and MXCPU accumulator stores were paid by normal performance runs | `gemmini.h` CPU timing probes in `gemmini_loop_ws_mxint8`; `mx_bench.c` MXCPU reset/print path; `run_perf.sh`; RISC-V counter docs | RISC-V counter docs; Gemmini paper; Gemmini README | Make CPU timing opt-in so fair default perf does not include diagnostic overhead, while retaining the diagnostic when requested | Software-only: add `MX_BENCH_CPU_TIMING=0` default, guard MXCPU globals/probes/print with it, and let `run_perf.sh` pass `MX_BENCH_CPU_TIMING=1` | Improved: `4229 / 9689 / 37919` | `4428 / 14219 / 92884`; improves `64^3` and `128^3` vs J05, but `256^3` regresses by 119 cycles vs J05 while still 18,681 cycles better than I20 | DIM32 regression PASS; DIM16 regression PASS; `mx_bench: PASS` | N/A; no RTL/config change | Banked with caveat. Future CPU diagnostics must set `MX_BENCH_CPU_TIMING=1`; J09 should recover or explain the small DIM16 `256^3` loss |
| J09 | 2026-07-04 | top `8712c011` + uncommitted plan/script/results; gemmini `ad11d23c` + modified `Configs.scala`; rocc-tests `f4622b5` + modified headers/tests | DIM16 MX scale SRAM was 8KB/512 rows even though current DIM16 tiler envelope fits in 256 rows; J08 still had DIM16 `256^3` slightly above J05 | `Configs.scala`; generated DIM16 params header; `gemmini.h` scale-SRAM envelope checks; `MXScaleLoadController.scala`; `ExecuteController.scala`; DIM16 regression shapes | Gemmini README/paper; Vivado/FPGA BRAM granularity sources; RASA local-storage scheduling paper | Reduce DIM16 MX scale SRAM capacity from 8KB to 4KB to reduce architectural sidecar storage and possibly improve address/timing behavior | Hardware/config: `mxint8DIM16Config.mx_scale_sp_capacity=CapacityInKilobytes(4)` and regenerated `gemmini_params_mxint8_dim16.h` with `MX_SCALE_SP_ROWS 256` | Preserved J08: `4229 / 9689 / 37919` | Improved J08: `4368 / 14187 / 92799`; still `256^3` is 34 cycles slower than J05 but 18,766 cycles faster than I20 | DIM16 regression PASS; `mx_bench: PASS`; DIM32 perf PASS | J09 MX DIM16 synth: `121197 LUT / 60946 FF / 168 BRAM36 / 239 DSP`; vs I20 MX DIM16: `+372 LUT / -59 FF / 0 BRAM / 0 DSP`; vs stock: `+28205 LUT / +7888 FF / +8 BRAM / +3 DSP` | Banked with caveat. Architectural scale SRAM rows are halved and cycles improve vs J08, but Vivado BRAM36 does not drop; J10 should seek real area reduction or more speed |
| J10 | 2026-07-04 | top `8712c011` + uncommitted plan/script/results; gemmini `ad11d23c` + temporary `Configs.scala`; rocc-tests `f4622b5` + modified headers/tests | Fresh J09-era counters showed DIM16 `256^3` still has `ex_pool_full=69094`, `ld_pool_full=17772`, `ld_inflight=30220`, and large non-scale EX blocking; J02 only tested deeper EX entries | `ReservationStation.scala`; `CounterFile.scala`; `Configs.scala`; `gemmini.h`; `StoreController.scala`; DIM16 J10 counter logs | Gemmini paper; TVM/Gemmini schedule tuning paper; RASA overlap/local-storage paper; AMD/Xilinx UG473/UG901 memory docs | If LD pool pressure is throttling the unroller and feeding non-scale EX deps, increasing DIM16 MX LD reservation entries from 8 to 16 might improve large-shape cycles | Hardware/config trial only: temporarily set `mxint8DIM16Config.reservation_station_entries_ld=16`, rebuilt DIM16 sim, measured, then reverted and rebuilt the reverted sim | Preserved exactly: `4229 / 9689 / 37919` | Candidate unchanged: `4368 / 14187 / 92799`; reverted-source rebuild also `4368 / 14187 / 92799` | `mx_bench: PASS`; no full regression because rejected after perf | No synthesis; rejected before area because no cycle gain and likely area increase | Reverted. LD RS depth is not the active DIM16 bottleneck; J11 should target dependency ordering, store/output pressure, or a lower-risk software/measurement improvement |
| J11 | 2026-07-04 | top `8712c011` + uncommitted plan/script/results; gemmini `ad11d23c`; rocc-tests `f4622b5` + temporary `gemmini.h` fence experiment | J10 counters showed residual DIM16 `256^3` non-scale EX blocking and no-command time; the pair-J software path still inserted a full fence at the second J-pair boundary | `gemmini.h` pair-J schedule; `ReservationStation.scala` scratchpad overlap deps; `MXScaleLoadController.scala` scale ping-pong credit; `ExecuteController.scala` loop-drained credit release; J10/J11 logs | MatrixFlow data-streaming co-design paper; DAE speculation paper; RASA paper; TVM/Gemmini schedule-tuning paper | Removing the pair-J group-boundary fence might let dependency-controlled overlap replace a broad CPU-side serialization point | Software-only trial: temporarily removed `pair_group_barrier` from the pair-J `do_fence` condition, measured, then restored it | Preserved exactly: `4229 / 9689 / 37919` | Candidate `4438 / 14386 / 92516`; reverted baseline `4368 / 14187 / 92799` | `mx_bench: PASS`; no full regression because rejected after perf tradeoff | N/A; no RTL/config change | Reverted. The candidate improved `256^3` by 283 cycles but regressed `64^3` by 70 and `128^3` by 199, so the local fence remains the better current tradeoff |
| J12 | 2026-07-04 | top `8712c011` + uncommitted plan/script/results; gemmini `ad11d23c`; rocc-tests `f4622b5` + temporary `gemmini.h` branch-layout experiment | J11 changed only the pair-J branch logically, but DIM16 `128^3` still moved, pointing to shared inline tiler code layout or branch-shape sensitivity | `gemmini.h` pair-J/fallback split; `mx_bench.c` geometry printer; `run_perf.sh`; bare-metal Makefile flags; J11/J12 logs | GCC `__builtin_expect` docs; GCC optimization docs; TVM/Gemmini schedule-tuning paper; Gemmini paper | Marking pair-J as unlikely might keep the normal fallback path hotter for `64^3`/`128^3` without changing hardware or numerical behavior | Software-only trial: temporarily changed `if (mx_pairj_b_reuse)` to `if (__builtin_expect(mx_pairj_b_reuse, 0))`, measured, then restored it | Preserved exactly: `4229 / 9689 / 37919` | Candidate `4338 / 14238 / 92641`; restored source uses J11 reverted baseline `4368 / 14187 / 92799` | `mx_bench: PASS`; no full regression because rejected after perf tradeoff | N/A; no RTL/config change | Reverted. The hint improved `64^3` by 30 and `256^3` by 158, but regressed the named `128^3` problem by 51 cycles |
| J13 | 2026-07-04 | top `8712c011` + uncommitted plan/script/results; gemmini `ad11d23c`; rocc-tests `f4622b5` + modified `mx_bench.c` diagnostic path | J11/J12 showed pair-J and B-scale-residency effects, but `MX_BENCH_PRINT_GEOM` still modeled the old nested schedule and over-counted B-scale loads | `mx_bench.c` geometry printer; `gemmini.h` pair-J schedule and B-scale residency; `run_perf.sh`; J13 logs | RISC-V counter spec; Gemmini paper; TVM/Gemmini schedule-tuning paper; GCC docs for diagnostic build flags | Fix geometry diagnostics so future iterations reason from the actual banked DIM16 schedule | Software-only diagnostic: update `print_mx_geom()` to mirror pair-J ordering, pair-group fence, A/B payload reuse, and B-scale residency; default perf path remains behind `MX_BENCH_PRINT_GEOM=0` | Preserved exactly: `4229 / 9689 / 37919` | Diagnostic run `4354 / 14065 / 92781`; key `MXGEOM` now reports `128^3` B-scale loads `2` and `256^3` pair-J `chunks=16`, `fences=2`, `b_payload_loads=4`, `b_scale_loads=4` | `mx_bench: PASS`; diagnostic path compiled and ran | N/A; no RTL/config change | Banked measurement fix. Future bottleneck analysis should use the corrected `MXGEOM` fields |
| J14 | 2026-07-04 | top `8712c011` + uncommitted plan/script/results; gemmini `ad11d23c` + modified `Configs.scala`; rocc-tests `f4622b5` + modified headers/tests | Fresh DIM16 counters still showed large-shape output/store pressure and accelerator-side stalls: for `128^3`/`256^3`, `st_pool_full=1856/8562`, `st_inflight=6442/26268`, and `ex_blocked_scale_only=2194/11072` | `ReservationStation.scala`; `StoreController.scala`; `Configs.scala`; `CounterFile.scala`; DIM16 params headers and J14 logs | MatrixFlow data-streaming paper; AccelSync synchronization paper; Gemmini paper; TVM/Gemmini schedule-tuning paper | If DIM16 output-side commands are held back by too few store reservation entries, a DIM16-only ST RS increase can improve overlap without touching DIM32 behavior | Hardware/config DIM16-only: set `reservation_station_entries_st=16`; keep `st_queue_length=4`, DIM32 ST entries, and stock configs unchanged | Preserved exactly: `4229 / 9689 / 37919` | `4368 / 13768 / 89310`; versus J09-J13 baseline this is `0 / -419 / -3489` cycles | DIM16 regression PASS; `mx_bench: PASS`; DIM32 perf PASS; staged stock DIM16 header still has `MX_ENABLED 0` | J14 MX DIM16 synth: `117643 LUT / 63162 FF / 167.5 BRAM36 / 239 DSP`; vs J09 MX DIM16: `-3554 LUT / +2216 FF / -0.5 BRAM / 0 DSP`; vs stock: `+24651 LUT / +10104 FF / +7.5 BRAM / +3 DSP` | Banked. This is the best DIM16 `128^3`/`256^3` result so far and also reduces LUT/BRAM versus J09, with the FF increase accepted for now |
| J15 | 2026-07-04 | top `8712c011` + uncommitted plan/script/results; gemmini `ad11d23c` + banked `Configs.scala`; rocc-tests `f4622b5` + modified headers/tests | Post-J14 counters showed `st_pool_full=0`, so store RS pressure was fixed; remaining DIM16 `256^3` pressure was `ld_pool_full=17890`, `ex_blocked_scale_only=10484`, and only `3738` retired host instructions over `89277` diagnostic cycles | `ReservationStation.scala`; `LoadController.scala`; `StoreController.scala`; `Configs.scala`; `mx_bench.c`; DIM16 logs | RISC-V counter docs; custom RISC-V instruction exploration paper; Gemmini paper; MatrixFlow paper; TVM/Gemmini paper | Retest DIM16 LD RS depth now that ST RS is no longer full; do not pursue custom instruction yet because fresh `instret` is too small to explain the gap alone | Hardware/config trial only: temporarily set DIM16 `reservation_station_entries_ld=16` on top of J14, rebuilt/measured, then reverted | Preserved exactly: `4229 / 9689 / 37919` | Candidate unchanged: `4368 / 13768 / 89310`; reverted-source rebuild also `4368 / 13768 / 89310` | `sbt` compile PASS; candidate and reverted `mx_bench: PASS`; no full regression because rejected after perf | No synthesis; rejected before area because no cycle gain and likely FF/LUT cost | Reverted. LD RS depth is still not the limiter; custom instruction remains possible only if future counters show command issue, not just low-level stalls |
| J16 | 2026-07-04 | top `8712c011` + uncommitted plan/script/results; gemmini `ad11d23c` with J16 trial reverted; rocc-tests `f4622b5` + banked headers/tests | J15 showed instruction count was not dominant, but MX scale helpers still emitted repeated stride-config RoCC commands before scale mvins; test a smaller software command-count reduction before any custom instruction | `gemmini.h` MX scale helpers and tiler; J15 `MXINST` logs; DIM32 gate log | RISC-V counter docs; custom RISC-V instruction exploration paper; Gemmini paper; TVM/Gemmini paper | If redundant scale-stride config commands cost cycles, caching A/B scale strides in software should reduce command traffic without hardware | Software-only trial: temporary MX-only cached A/B scale stride state in `gemmini_mvin_mxscale_a/b`; reverted after failed DIM32 gate | Failed partial gate: `64^3=4407`, `128^3=9754`; stopped before `256^3` because DIM32 regressed | Skipped because DIM32 regressed | Candidate rows passed individually, but run was interrupted after `128^3`; no full regression because rejected | N/A; no RTL/config change and rejected | Reverted. Even small command-count/cache changes can hurt code layout or command timing; custom-instruction work stays lower priority without stronger issue-overhead evidence |
| J17 | 2026-07-04 | top `8712c011` + uncommitted plan/script/results; gemmini `ad11d23c` + J17 trial reverted to banked `Configs.scala`; rocc-tests `f4622b5` + modified headers/tests | After J14, `st_pool_full=0` but `st_inflight` remained high, so test whether StoreController queue depth, not RS depth, limits output overlap | `StoreController.scala`; `ReservationStation.scala`; `Configs.scala`; J15/J17 logs | MatrixFlow paper; AccelSync synchronization paper; Gemmini paper; TVM/Gemmini paper | Deeper DIM16 store command queue might hide output DMA latency after the deeper ST RS fix | Hardware/config trial only: temporarily set DIM16 `st_queue_length=8` on top of banked ST RS 16, rebuilt/measured, then reverted to `4` | Preserved exactly: `4229 / 9689 / 37919` | Candidate unchanged: `4368 / 13768 / 89310`; reverted-source rebuild also `4368 / 13768 / 89310` | `sbt` compile PASS; candidate and reverted `mx_bench: PASS`; no full regression because rejected after no perf gain | No synthesis; rejected before area because no cycle gain and likely queue/state cost | Reverted. ST queue length is not the limiter; do not retry this knob unless new counters show StoreController queue backpressure |
| J18 | 2026-07-04 | top `8712c011` + uncommitted plan/script/results; gemmini `ad11d23c` + J18 trial reverted to banked `Configs.scala`; rocc-tests `f4622b5` + modified headers/tests | J18 debug showed post-J14 `st_pool_full=0`, suggesting ST RS 16 might be over-provisioned for area, but `ReservationStation.scala` requires per-type RS sizes to be powers of two | `ReservationStation.scala`; `Configs.scala`; `StoreController.scala`; J18 debug and build logs | Gemmini paper; TVM/Gemmini paper; MatrixFlow paper; AMD/Xilinx FPGA memory/resource docs | Right-size DIM16 ST RS from 16 to 12 to reduce area while keeping the J14 cycle gain | Hardware/config trial only: temporarily set DIM16 `reservation_station_entries_st=12`; DIM32 gate passed, but DIM16 elaboration failed at `assert(isPow2(reservation_station_entries_st))`; reverted to 16 | Preserved exactly: `4229 / 9689 / 37919` | Candidate did not elaborate, so no DIM16 candidate cycles; reverted-source rebuild/check restored `4368 / 13768 / 89310` | `sbt` compile PASS; candidate Chisel elaboration FAIL before simulation; reverted `mx_bench: PASS` | No synthesis; illegal non-power-of-two RS size, no valid area point | Reverted. Keep ST RS 16; the only legal smaller value is 8 and J14 already showed 8 is slower |
| J19 | 2026-07-04 | top `8712c011` + uncommitted plan/script/results; gemmini `ad11d23c`; rocc-tests `f4622b5` + banked J19 `gemmini.h` scheduling change | Post-J14/J18 counters showed `st_pool_full=0`, low retired host instructions, but remaining DIM16 large-shape dependency/issue stalls; J11 had tested barrier removal before the ST RS fix, so retest after J14 | `gemmini.h` pair-J schedule; `MXScaleLoadController.scala`; `ExecuteController.scala`; `ReservationStation.scala`; J18/J19 logs | RASA; Gemmini paper; TVM/Gemmini schedule paper; MatrixFlow | After deeper DIM16 ST RS, the second pair-J group fence may be redundant because hardware scale ping-pong credit and scratchpad deps can enforce ordering | Software-only: remove `pair_group_barrier` from the pair-J `do_fence` condition, leaving fences for first chunk, K chunking, and geometry changes | Preserved exactly: `4229 / 9689 / 37919` | `4438 / 13724 / 88379`; improves large shapes by `44 / 931` cycles vs J14/J18, but regresses `64^3` by 70 cycles | DIM16 regression PASS; DIM32 regression PASS; `mx_bench: PASS` | No synthesis; software-only change. Area remains the banked J14 hardware area | Banked. The campaign target is DIM16 large-shape speed and DIM32 stayed unchanged; keep the small `64^3` regression documented |
| J20 | 2026-07-04 | top `8712c011` + uncommitted plan/script/results; gemmini `ad11d23c`; rocc-tests `f4622b5` + J20 trial reverted to banked J19 `gemmini.h` | J19 improved DIM16 `128^3`/`256^3` but regressed `64^3` by 70 cycles; test whether a shape gate can keep the large-shape win while restoring the small shape | `gemmini.h` pair-J branch and `do_fence`; J19/J20 perf logs; relevant scheduling/dependency notes | RASA; TVM/Gemmini paper; Gemmini paper; SISA small/skewed-shape paper | Restore the old pair-group fence only for small shapes while keeping J19's relaxed barrier for `M/N/K >= 128` | Software-only trial: temporary `relax_pair_group_barrier = (M >= 128) && (N >= 128) && (K >= 128)` and reintroduced `pair_group_barrier` for smaller shapes; then reverted | Preserved exactly: `4229 / 9689 / 37919` | Candidate `4426 / 13866 / 88555`; vs J19 it recovers only 12 cycles at `64^3` but loses 142/176 cycles on `128^3`/`256^3` | `mx_bench: PASS`; no full regression because rejected after perf; final J19 candidate had already passed DIM16 and DIM32 regressions | No synthesis; rejected software-only trial. Final area remains J14 hardware area | Reverted. Final selection remains banked J19 on top of banked J14/J09 hardware/config |

### J01 Detail

Baseline data:

- Cached I20 stock baseline remains the comparison point because no stock RTL,
  benchmark timed region, compiler flags, or simulator flags changed.
- Post-change DIM32 MX default perf:
  `DOCS_MX/scripts/results/perf_plan1_j01_dim32.csv` =
  `4317 / 9855 / 37921` cycles for `64^3 / 128^3 / 256^3`.
- Post-change DIM16 MX default perf:
  `DOCS_MX/scripts/results/perf_plan1_j01_dim16.csv` =
  `4390 / 14349 / 101565` cycles for `64^3 / 128^3 / 256^3`.
- Required debug command:
  `MX_BENCH_COUNTER_SET=2 DOCS_MX/scripts/run_perf.sh --dims 16 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j01_dim16_debug.csv`.
  The first run exposed a measurement bug: it printed `MXCOUNT`, not
  `MXSCALEDEP`, because `MX_BENCH_COUNTER_SET` is a C preprocessor macro in
  `mx_bench.c` and the script did not forward the environment variable to
  `EXTRA_CFLAGS`.

J01 candidate note:

- Classification: software-only measurement/instrumentation.
- Hypothesis: if the script forwards the documented debug selector to the C
  build, the mandatory PLAN_1 diagnostic command will expose the intended
  scale-dependency/load-store counters while leaving normal perf unchanged.
- Rollback: revert the `run_perf.sh` hunk which constructs `extra_cflags` from
  `MX_BENCH_COUNTER_SET` and `MX_BENCH_PRINT_GEOM`.
- Expected DIM32/DIM16 effect: no change for default perf; only debug binaries
  differ when the env vars are set.
- Expected area effect: none.

Evidence after the fix:

- Build log contains `-DMX_BENCH_COUNTER_SET=2`.
- DIM16 debug rows now print `MXSCALEDEP`.
- `128^3` debug counters:
  `cycles=14231`, `ex_blocked_on_scale=2740`,
  `ex_blocked_scale_only=2396`, `ex_blocked_nonscale=11921`,
  `ld_pool_full=2010`, `st_inflight=6287`, `scale_dma_active=2888`,
  `issue_cyc=8743`.
- `256^3` debug counters:
  `cycles=101753`, `ex_blocked_on_scale=17329`,
  `ex_blocked_scale_only=13661`, `ex_blocked_nonscale=96009`,
  `ld_pool_full=30299`, `st_pool_full=8287`, `st_inflight=29856`,
  `scale_dma_active=17733`, `issue_cyc=88199`.
- Interpretation: scale dependencies are real but are not the dominant observed
  counter in this pass. The large DIM16 loss is more strongly associated with
  command issue/backpressure plus non-scale dependency, load-pool, and store
  in-flight pressure. J02 should start from those counters and inspect the
  DIM16 tiler geometry, ReservationStation, LoopMatmul, ExecuteController, and
  store/load queue behavior before choosing a technical candidate.

Commands run:

```bash
DOCS_MX/scripts/run_perf.sh --dims 32 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j01_dim32.csv
DOCS_MX/scripts/run_perf.sh --dims 16 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j01_dim16.csv
MX_BENCH_COUNTER_SET=2 DOCS_MX/scripts/run_perf.sh --dims 16 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j01_dim16_debug.csv
MX_BENCH_COUNTER_SET=2 DOCS_MX/scripts/run_perf.sh --dims 16 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j01_dim16_debug.csv --force
DOCS_MX/scripts/run_perf.sh --dims 32 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j01_dim32.csv --force
DOCS_MX/scripts/run_perf.sh --dims 16 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j01_dim16.csv --force
DOCS_MX/scripts/run_regression.sh --dims=16 --skip-stock-elab --tests mxint8_matmul_dim16 --timeout-cycles=300000000
```

All long simulations, logs, and temporary headers stayed under
`sims/verilator/gemmini`.

Online research recorded for J01:

- https://arxiv.org/abs/1911.09925 - Gemmini is presented as a full-stack,
  full-system DNN accelerator evaluation framework; the J01 takeaway is that
  Gemmini optimization should be guided by measured hardware/software
  interaction, not just isolated RTL intuition.
- https://docs.riscv.org/reference/isa/unpriv/counters.html - RISC-V exposes
  cycle and performance counter concepts for low-overhead attribution; the J01
  takeaway is to fix the counter-selection path before changing architecture.
- https://arxiv.org/abs/2212.03034 - Gemmini performance tuning work uses
  schedules which interleave movement and compute to keep the systolic array
  utilized; the J01 takeaway is that the next candidate should be based on
  measured feed/issue pressure, not a preselected hardware tweak.

### J02 Detail

Baseline and diagnostics:

- J02 reused the banked J01/I20 default baseline:
  DIM32 MX `4317 / 9855 / 37921`, DIM16 MX `4390 / 14349 / 101565`.
- The focused DIM16 geometry/counter pass used:
  `MX_BENCH_COUNTER_SET=1 MX_BENCH_PRINT_GEOM=1 DOCS_MX/scripts/run_perf.sh --dims 16 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j02_dim16_part_geom.csv`.
- That diagnostic run produced debug-timing cycles
  `4401 / 14410 / 101947` and confirmed the large-shape geometry:
  `128^3` uses `mx_IC=2`, `mx_JC=2`, `mx_KC=1`, `chunks=4`,
  `a_reuse=1`, `b_reuse=1`, `a_payload_loads=2`,
  `b_payload_loads=2`, `a_scale_loads=4`, `b_scale_loads=4`.
- `256^3` uses `mx_IC=4`, `mx_JC=4`, `mx_KC=1`, `chunks=16`,
  `a_reuse=1`, `b_reuse=0`, `a_payload_loads=4`,
  `b_payload_loads=16`, `a_scale_loads=16`, `b_scale_loads=16`.
- The `256^3` `MXPART` row showed high pressure:
  `cycles=101947`, `ex_ready=78764`, `ex_blocked=96030`,
  `ex_inflight=80692`, `ex_pool_full=79884`, `ld_inflight=41712`,
  `loopmm_active=100199`, `issue_cyc=88187`.

Code inspection:

- `gemmini.h` shows the DIM<32 MX tiler currently caps `j_chunk` to 4
  phase columns, computes `mx_IC/mx_JC/mx_KC`, chooses A/B reuse, then emits
  chunked payload and scale loads before loop-matmul commands.
- A direct `j_chunk=8` software change was not selected because, under the
  current DIM16 capacities (`ACC_ROWS=512`, `MX_SCALE_SP_ROWS=512`,
  `BANK_ROWS=1024`, `BANK_NUM=4`), it would reduce legal `i_chunk`, reduce
  `k_chunk`, and likely introduce K chunking and extra fences.
- `LoopMatmul.scala` exposes only two resident spad regions for this path:
  `concurrent_loops = 2`, with 2-bit `a_ex_spad_id` and `b_ex_spad_id`
  selecting the two halves. That explains why the existing software only
  enables B reuse when the active B tile set fits in two resident regions.
- `ReservationStation.scala`, `ExecuteController.scala`, and `Configs.scala`
  were inspected because the counter data showed execute-pool fullness and
  issue pressure.

J02 candidate note:

- Classification: hardware-only DIM16 config experiment.
- Hypothesis: increasing execute reservation station depth from the current
  default to 32 entries could absorb the large `ex_pool_full` count and reduce
  issue stalls for DIM16 `128^3`/`256^3`.
- Expected DIM32 effect: none, because the candidate touched only
  `mxint8DIM16Config`.
- Expected DIM16 effect: lower execute-pool backpressure and modest speedup on
  large shapes.
- Expected area effect: negative if banked, because a deeper execute queue costs
  state; therefore it needed a cycle gain to justify synthesis.
- Rollback: remove the DIM16 `reservation_station_entries_ex=32` override and
  rebuild the DIM16 simulator from the reverted source.

Evidence:

- The candidate compiled with `sbt "project gemmini" compile`.
- Candidate performance:
  `DOCS_MX/scripts/results/perf_plan1_j02_dim16_exrs32.csv` =
  `4390 / 14349 / 101565`, exactly unchanged from baseline.
- The candidate was reverted. `git -C generators/gemmini status --short`
  was clean after revert.
- The DIM16 simulator was rebuilt from the reverted source with:
  `DOCS_MX/scripts/run_perf.sh --build-sims --dims 16 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j02_dim16_reverted.csv`.
- Reverted-source performance:
  `DOCS_MX/scripts/results/perf_plan1_j02_dim16_reverted.csv` =
  `4390 / 14349 / 101565`, with `mx_bench: PASS`.
- Interpretation: execute queue depth was not the limiting root cause. The
  stronger lead is DIM16 large-shape scheduling and reuse: `256^3` reloads B
  payload 16 times because four J chunks do not fit the two existing resident
  regions. J03 should investigate a guarded DIM16 software schedule which
  processes two J chunks at a time, or another measured way to reduce redundant
  B payload/scale movement without touching DIM32 behavior.

Commands run:

```bash
MX_BENCH_COUNTER_SET=1 MX_BENCH_PRINT_GEOM=1 DOCS_MX/scripts/run_perf.sh --dims 16 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j02_dim16_part_geom.csv
sbt "project gemmini" compile
DOCS_MX/scripts/run_perf.sh --build-sims --dims 16 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j02_dim16_exrs32.csv
DOCS_MX/scripts/run_perf.sh --build-sims --dims 16 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j02_dim16_reverted.csv
```

All long simulations, logs, and temporary headers stayed under
`sims/verilator/gemmini`.

Online research recorded for J02:

- https://arxiv.org/abs/1911.09925 - Gemmini's generator/software stack makes
  loop scheduling, dataflow, and memory movement first-class performance knobs;
  the J02 takeaway is to distinguish queue symptoms from data-reuse causes.
- https://arxiv.org/abs/2212.03034 - Gemmini schedule tuning work emphasizes
  feeding the array through tuned tiling and overlapping movement with compute;
  the J02 takeaway is that DIM16 needs a scheduling experiment for the measured
  geometry rather than only deeper queues.
- https://arxiv.org/abs/2110.01752 - RASA argues that register-aware systolic
  schedules reduce traffic and improve utilization by respecting local storage;
  the J02 takeaway is to respect the two resident spad regions and test a
  software schedule that reuses B within that storage limit.

### J03 Detail

Baseline and hypothesis:

- Banked baseline entering J03:
  DIM32 MX `4317 / 9855 / 37921`, DIM16 MX `4390 / 14349 / 101565`.
- J02 showed DIM16 `256^3` has `mx_IC=4`, `mx_JC=4`, `mx_KC=1`,
  `chunks=16`, `a_payload_loads=4`, `b_payload_loads=16`, and only two
  resident B scratchpad regions in `LoopMatmul.scala`.
- Hypothesis: for the exact DIM16 four-J-chunk, single-K-chunk geometry, issue
  two J chunks as a group across all I chunks. Within each group, the two B
  chunks fit the two resident regions and can be reused for the I sweep. This
  should reduce B payload movement at `256^3`, while a fence at the group
  boundary prevents clobbering a resident B region that an in-flight loop may
  still read.

Code inspection:

- `gemmini.h` was reread around `mxint8_compute_geom` and
  `tiled_matmul_mxint8_impl`.
- `LoopMatmul.scala` was reread around `concurrent_loops = 2`, the
  `a_ex_spad_id`/`b_ex_spad_id` fields, and the resident-region address mapping.
- The change was kept software-only. No Scala RTL/config file was modified in
  the banked J03 candidate.

Candidate note:

- Classification: software-only scheduling.
- Expected DIM32 effect: none, because DIM32 must take the original fallback
  loop order.
- Expected DIM16 effect: improve `256^3` by reducing B payload reloads; `128^3`
  was expected to stay close to baseline because it already has only two J
  chunks and B reuse is already enabled.
- Expected area effect: none.
- Rollback: revert the J03 `gemmini.h` changes in `tiled_matmul_mxint8_impl`.

Implementation and refinement:

- First implementation replaced the common nested loop with a generic sequence
  mapper. It preserved correctness but changed DIM32 software timing:
  `DOCS_MX/scripts/results/perf_plan1_j03_dim32.csv` =
  `4254 / 9741 / 37944`.
- Because DIM32 `256^3` exceeded the I20 limit `37921` by 23 cycles, that
  form was rejected.
- The banked form restores the original nested loop as the fallback path and
  enters the new sequence mapper only when:
  `DIM < MX_BLOCK_SIZE`, `!k_chunked`, `mx_JC == 4`, `mx_KC == 1`, and the
  I/J chunk grids have no tails.

Performance evidence:

- Guarded DIM32 gate:
  `DOCS_MX/scripts/results/perf_plan1_j03_dim32_guarded.csv` =
  `4317 / 9855 / 37921`, exactly preserving I20.
- DIM16 gate:
  `DOCS_MX/scripts/results/perf_plan1_j03_dim16.csv` =
  `4340 / 14446 / 94543`.
- DIM16 `256^3` improved by 7,022 cycles versus baseline
  (`101565 -> 94543`) and utilization rose from 64% to 69%.
- DIM16 `128^3` regressed by 97 cycles (`14349 -> 14446`), so J03 is banked
  only with a caveat. J04 should specifically recover this common-path overhead
  or make the branch/code layout cheaper for non-pair-J DIM16 shapes.
- Candidate `256^3` counters:
  `matmul_in_progress=75005`, `loopmm_active=91442`, `no_cmd=32699`,
  `rdma_active=22620`, `issue_cyc=76224`, `fence_cyc=5754`.
  The lower `rdma_active` is consistent with the intended B payload reuse, while
  the added `fence_cyc` is the cost of the pair-group safety barrier.

Correctness:

- DIM16 regression:
  `DOCS_MX/scripts/run_regression.sh --dims=16 --skip-stock-elab --timeout-cycles=300000000`
  passed `mxint8_matmul_dim16`, `mxint8_matmul_nphase`, and `mxint8_multitile`.
- DIM32 regression:
  `DOCS_MX/scripts/run_regression.sh --dims=32 --skip-stock-elab --timeout-cycles=300000000`
  passed `mxint8_golden`, `mxint8_matmul_dim32`, `mxint8_corner`,
  `mxint8_matmul_partial`, `mxint8_tiled`, `mxint8_btb`,
  `mxint8_multitile`, and `mxint8_matmul_nphase`.
- Both regression matrices ended with `OVERALL: PASS`.

Commands run:

```bash
DOCS_MX/scripts/run_perf.sh --dims 32 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j03_dim32.csv
DOCS_MX/scripts/run_perf.sh --dims 32 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j03_dim32_guarded.csv
DOCS_MX/scripts/run_perf.sh --dims 16 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j03_dim16.csv
DOCS_MX/scripts/run_regression.sh --dims=16 --skip-stock-elab --timeout-cycles=300000000
DOCS_MX/scripts/run_regression.sh --dims=32 --skip-stock-elab --timeout-cycles=300000000
```

All long simulations, logs, and temporary headers stayed under
`sims/verilator/gemmini`.

Online research recorded for J03:

- https://arxiv.org/abs/2212.03034 - The Gemmini/TVM paper reports that
  generated schedules can outperform default hand-tuned schedules and that
  schedule search matters for Gemmini. The J03 takeaway is that changing loop
  order is a valid first-class optimization knob when counters show movement
  pressure.
- https://arxiv.org/abs/2110.01752 - RASA motivates respecting limited local
  storage and using scheduling to hide or reduce stalls. The J03 takeaway is
  that a two-resident-region machine should schedule only two active B chunks at
  a time, not reload four B chunks across every I chunk.
- https://arxiv.org/abs/1911.09925 - Gemmini is a full-stack generator where
  hardware parameters and software loops interact. The J03 takeaway is to bank
  the software reuse win only after DIM32 preservation and bit-exact regression
  gates pass.

### J04 Detail

Starting point:

- J04 started from the banked J03 implementation.
- J03 preserved DIM32 exactly and improved DIM16 `256^3`, but DIM16 `128^3`
  regressed from `14349` to `14446`.
- Hypothesis: the J03 branch and pair-J code shape might be adding enough
  CPU-side overhead to hurt non-pair-J DIM16 shapes, especially `128^3`.

Candidate note:

- Classification: software-only cleanup.
- Change attempted:
  add cheap `M >= 256`, `N >= 256`, `K >= 256` terms to the pair-J predicate
  and simplify constant conditionals inside the pair-J-only branch.
- Expected DIM32 effect: none; DIM32 should still take the original fallback.
- Expected DIM16 effect: recover part of the `128^3` regression while keeping
  most of the `256^3` pair-J benefit.
- Expected area effect: none.
- Rollback: restore the J03 predicate and pair-J branch body.

Evidence:

- DIM32 gate:
  `DOCS_MX/scripts/results/perf_plan1_j04_dim32.csv` =
  `4317 / 9855 / 37921`, preserving I20/J03 exactly.
- DIM16 gate:
  `DOCS_MX/scripts/results/perf_plan1_j04_dim16.csv` =
  `4481 / 14453 / 94320`.
- Interpretation: the cleanup gave only a small extra `256^3` gain
  (`94543 -> 94320`) but worsened `64^3` and `128^3`. This is the wrong
  direction for the campaign because DIM16 `128^3` is one of the two problem
  shapes.
- Decision: rejected and reverted to the banked J03 source. No synthesis or
  full regression was run because the candidate failed the performance tradeoff.

Commands run:

```bash
DOCS_MX/scripts/run_perf.sh --dims 32 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j04_dim32.csv
DOCS_MX/scripts/run_perf.sh --dims 16 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j04_dim16.csv
```

All long simulations, logs, and temporary headers stayed under
`sims/verilator/gemmini`.

Online research recorded for J04:

- https://gcc.gnu.org/onlinedocs/gcc/Other-Builtins.html - GCC documents
  `__builtin_expect` as a way to provide branch prediction information when
  profile data is hard to collect. The J04 takeaway was that code layout and
  branch shape can matter in a CPU-issued accelerator loop, but the measured
  result did not justify keeping the attempted cleanup.
- https://gcc.gnu.org/onlinedocs/gcc/Common-Attributes.html - GCC documents
  `hot` and `cold` attributes as code locality/layout hints. The J04 takeaway
  was to consider isolating uncommon scheduling paths in future, but not to
  introduce attributes without a measured benefit.
- https://arxiv.org/abs/2212.03034 - The Gemmini/TVM schedule tuning work
  reinforces that software schedule changes must be validated empirically on
  the target accelerator. The J04 takeaway is to reject plausible code-shape
  cleanups when measurements hurt an important shape.

### J05 Detail

Starting point:

- Banked J03 plus rejected/reverted J04 left DIM32 preserved and DIM16
  `256^3` improved, but DIM16 `128^3` was still above the original target.
- J03 counters showed the pair-J schedule lowered B payload traffic, but B-scale
  mvins were still issued on reused B chunks because the B-scale residency path
  was guarded by `#if DIM >= 32` and did not treat pair-J reuse as B reuse.

Candidate note:

- Classification: software-only scheduling/residency.
- Hypothesis: if B payload is resident across the I sweep, the matching B-scale
  image can also remain resident in the same ping-pong half. Skipping redundant
  B-scale mvins should help DIM16 `128^3`, and should stack with J03's pair-J
  payload reuse at `256^3`.
- Expected DIM32 effect: none or exact preservation, because DIM32 keeps the
  existing `mx_IC > 2` threshold.
- Expected DIM16 effect: improve `128^3` and `256^3`; possible risk to `64^3`
  from extra residency bookkeeping.
- Expected area effect: none.
- Rollback: restore the DIM32-only B-scale residency guards and the original
  `mx_b_reuse`-only residency predicate.

Implementation:

- Lifted `g_mx_bscale_resident` and the per-slot `mx_bscale_resident[4][2]`
  bookkeeping out of DIM32-only preprocessor guards.
- Changed the residency predicate to use `mx_b_reuse || mx_pairj_b_reuse`.
- Kept DIM32 behavior guarded by the old `mx_IC > 2` threshold, while allowing
  DIM16 residency at `mx_IC >= 2`.
- Applied the residency flag in both the pair-J path and the original fallback
  path.

Performance evidence:

- DIM32 gate:
  `DOCS_MX/scripts/results/perf_plan1_j05_dim32.csv` =
  `4317 / 9855 / 37921`, exactly preserving I20/J03.
- DIM16 gate:
  `DOCS_MX/scripts/results/perf_plan1_j05_dim16.csv` =
  `4492 / 14320 / 92765`.
- Versus the original DIM16 baseline:
  `128^3` improved by 29 cycles (`14349 -> 14320`) and `256^3` improved by
  18,800 cycles (`101565 -> 92765`).
- Versus J03:
  `128^3` improved by 126 cycles (`14446 -> 14320`) and `256^3` improved by
  1,778 cycles (`94543 -> 92765`), while `64^3` regressed by 152 cycles
  (`4340 -> 4492`).
- Counter evidence:
  J05 `128^3` `scaleb_cyc=139` versus J03 `150`.
  J05 `256^3` `scaleb_cyc=267` versus J03 `535`, with
  `issue_cyc=75156`, `rdma_active=22200`, and utilization 70%.

Correctness:

- DIM16 regression:
  `DOCS_MX/scripts/run_regression.sh --dims=16 --skip-stock-elab --timeout-cycles=300000000`
  passed `mxint8_matmul_dim16`, `mxint8_matmul_nphase`, and `mxint8_multitile`.
- DIM32 regression:
  `DOCS_MX/scripts/run_regression.sh --dims=32 --skip-stock-elab --timeout-cycles=300000000`
  passed `mxint8_golden`, `mxint8_matmul_dim32`, `mxint8_corner`,
  `mxint8_matmul_partial`, `mxint8_tiled`, `mxint8_btb`,
  `mxint8_multitile`, and `mxint8_matmul_nphase`.
- Both regression matrices ended with `OVERALL: PASS`.

Commands run:

```bash
DOCS_MX/scripts/run_perf.sh --dims 32 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j05_dim32.csv
DOCS_MX/scripts/run_perf.sh --dims 16 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j05_dim16.csv
DOCS_MX/scripts/run_regression.sh --dims=16 --skip-stock-elab --timeout-cycles=300000000
DOCS_MX/scripts/run_regression.sh --dims=32 --skip-stock-elab --timeout-cycles=300000000
```

All long simulations, logs, and temporary headers stayed under
`sims/verilator/gemmini`.

Online research recorded for J05:

- https://arxiv.org/abs/1911.09925 - Gemmini frames accelerator performance as a
  hardware/software interaction across generator parameters, software tiling,
  and memory movement. The J05 takeaway is that a software-residency fix is
  appropriate when the hardware already has the storage and the issue stream is
  redundantly moving the same side data.
- https://arxiv.org/abs/2212.03034 - The Gemmini/TVM work emphasizes empirical
  schedule selection and data-movement overlap. The J05 takeaway is to validate
  B-scale residency with both performance counters and bit-exact regressions.
- https://arxiv.org/abs/2110.01752 - RASA argues that respecting small local
  storage reduces traffic and stalls in systolic-array mappings. The J05
  takeaway is to keep both payload and scale side data resident when the same
  B chunk is reused across the I sweep.

### J06 Detail

Starting point:

- J05 was banked because it improved both DIM16 problem shapes:
  `128^3=14320`, `256^3=92765`.
- J05 regressed DIM16 `64^3` to `4492`, likely due to extra software-side
  B-scale residency bookkeeping even when residency is disabled.

Candidate note:

- Classification: software-only API/code-shape experiment.
- Hypothesis: replacing the global `g_mx_bscale_resident` flag with a local
  boolean argument to `gemmini_loop_ws_mxint8` would remove global stores around
  each chunk and improve the non-resident `64^3` path.
- Expected DIM32 effect: ideally none, because the residency predicate remained
  unchanged.
- Expected DIM16 effect: recover part of the `64^3` loss while keeping J05's
  `128^3` and `256^3` wins.
- Expected area effect: none.
- Rollback: restore the J05 global flag and old public helper signature.

Evidence:

- First implementation changed the public helper signature directly. The test
  build failed because standalone tests such as `mxint8_btb.c`,
  `mxint8_tiled.c`, `mxint8_multitile.c`, and `mxint8_matmul_nphase.c` call
  `gemmini_loop_ws_mxint8` directly with the old argument list.
- A compatibility-fixed version introduced an internal residency-aware helper
  and kept a macro wrapper for the old public call shape.
- The fixed version compiled far enough to run DIM32 performance, but failed
  the DIM32 gate:
  `64^3=4474`, `128^3=9875`; both exceed the I20/J05 limits
  `4317` and `9855`.
- The run was interrupted before `256^3`, DIM16 was skipped, and the candidate
  was reverted to J05.

Commands run:

```bash
DOCS_MX/scripts/run_perf.sh --dims 32 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j06_dim32.csv
DOCS_MX/scripts/run_perf.sh --dims 32 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j06_dim32_fixed.csv
```

All long simulations, logs, and temporary headers stayed under
`sims/verilator/gemmini`.

Online research recorded for J06:

- https://gcc.gnu.org/onlinedocs/gcc/Inline.html - GCC documents that inlining
  can eliminate call overhead and expose constant arguments for simplification.
  The J06 takeaway was that changing call shape might reduce software overhead,
  but the measured DIM32 regression rejected the implementation.
- https://arxiv.org/abs/1911.09925 - Gemmini's full-stack framing reinforces
  that helper APIs are part of the accelerator contract. The J06 takeaway is to
  avoid public helper signature churn unless all direct callers are audited.
- https://arxiv.org/abs/2212.03034 - The Gemmini/TVM schedule work reinforces
  empirical schedule validation. The J06 takeaway is that plausible CPU-side
  cleanups must still pass the strict DIM32 performance gate.

### J07 Detail

Starting point:

- J07 started from banked J05 after the rejected/reverted J06 API experiment.
- The user asked whether, if instruction count is the problem, the MX Gemmini
  could define a custom instruction. Local code inspection confirmed that this
  is architecturally feasible: Gemmini already uses RoCC custom instructions,
  `gemmini_loop_ws_mx` emits six RoCC commands per chunk, and each MX scale mvin
  emits additional RoCC commands.
- The measured risk was misdiagnosis: high `g_mx_issue_cyc` may mean many CPU
  instructions, but it may also mean a small number of RoCC instructions stalling
  on accelerator queue/dependency/backpressure.

Candidate note:

- Classification: software-only diagnostic/instrumentation.
- Hypothesis: add a retired-instruction counter around the timed GEMM to decide
  whether high `issue_cyc` is mainly many retired instructions or few retired
  instructions spending many cycles stalled.
- Expected DIM32 effect: none in default mode, because the new path is gated by
  compile-time `MX_BENCH_COUNTER_SET=3`.
- Expected DIM16 effect: none in default mode; diagnostic rows may have normal
  code-layout/timing jitter and are not the fair performance rows.
- Expected area effect: none.
- Rollback: remove `read_insts()`, the `MX_BENCH_COUNTER_SET=3` print path in
  `mx_bench.c`, and the `run_perf.sh` validation extension from `0|1|2|3` back
  to `0|1|2`.

Implementation:

- Added `read_insts()` using `rdinstret`.
- Added `MX_BENCH_COUNTER_SET=3`, which records `inst_start/inst_end` around
  the timed `run_gemm()` and prints an `MXINST` line with retired instructions,
  cycles per retired instruction scaled by 1000, and a compact subset of the
  default hardware counters.
- Extended `DOCS_MX/scripts/run_perf.sh` to accept and forward counter set `3`.

Performance evidence:

- Default DIM32 gate:
  `DOCS_MX/scripts/results/perf_plan1_j07_dim32.csv` =
  `4317 / 9855 / 37921`, exactly preserving I20/J05.
- Default DIM16 gate:
  `DOCS_MX/scripts/results/perf_plan1_j07_dim16.csv` =
  `4492 / 14320 / 92765`, exactly preserving J05.
- Diagnostic DIM16 instruction run:
  `DOCS_MX/scripts/results/perf_plan1_j07_dim16_inst.csv` =
  `4533 / 14360 / 92948`. These are diagnostic-timing rows only.
- `MXINST` rows:
  - `64^3`: `instret=515`, `cyc_per_inst_x1000=8801`,
    `issue_cyc=80`.
  - `128^3`: `instret=1537`, `cyc_per_inst_x1000=9342`,
    `issue_cyc=8253`.
  - `256^3`: `instret=4297`, `cyc_per_inst_x1000=21630`,
    `issue_cyc=74974`.
- Interpretation: the DIM16 large-shape issue path is not dominated by a huge
  number of retired CPU instructions. The high cycles-per-retired-instruction
  and high `issue_cyc` indicate RoCC/accelerator backpressure or dependency
  stalls during a relatively small instruction stream. A custom/fused MX
  instruction remains a valid co-designed option only if it also changes queueing
  or dependency behavior; reducing the C-side instruction count alone is unlikely
  to close the `256^3` gap.

Custom-instruction feasibility note:

- Feasible options include an MX fused setup/load command, an MX-specific
  `LOOP_WS` variant that emits scale mvins internally, a pair-J group command,
  or a scale-residency hint command.
- Any such candidate is co-designed: it touches RoCC software macros plus Gemmini
  controller/decode/loop/load-scale RTL, must be guarded by `mx_enabled`, and
  must include a stock-purity audit so pure INT8 configs do not elaborate MX
  hardware.
- J07 does not choose that hardware direction yet. J08 should first target the
  observed RoCC/backpressure/dependency behavior with the smallest local change.

Commands run:

```bash
MX_BENCH_COUNTER_SET=2 MX_BENCH_PRINT_GEOM=1 DOCS_MX/scripts/run_perf.sh --dims 16 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j07_dim16_debug.csv
bash -n DOCS_MX/scripts/run_perf.sh
git diff --check -- DOCS_MX/scripts/run_perf.sh generators/gemmini/software/gemmini-rocc-tests/bareMetalC/mx_bench.c generators/gemmini/software/gemmini-rocc-tests/include/gemmini.h
DOCS_MX/scripts/run_perf.sh --dims 32 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j07_dim32.csv
DOCS_MX/scripts/run_perf.sh --dims 16 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j07_dim16.csv
MX_BENCH_COUNTER_SET=3 MX_BENCH_PRINT_GEOM=1 DOCS_MX/scripts/run_perf.sh --dims 16 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j07_dim16_inst.csv
```

All long simulations, logs, and temporary headers stayed under
`sims/verilator/gemmini`.

Online research recorded for J07:

- https://docs.riscv.org/reference/isa/unpriv/extending.html - The RISC-V
  unprivileged ISA manual explicitly supports non-standard extensions and
  custom accelerator-oriented encodings. The J07 takeaway is that an MX custom
  instruction is legitimate for a research prototype if it is documented and
  isolated from stock INT8.
- https://github.com/ucb-bar/gemmini - The Gemmini README describes Gemmini as
  a RoCC accelerator using non-standard RISC-V custom instructions and a
  decoupled access/execute architecture. The J07 takeaway is that extending the
  Gemmini RoCC command set is natural, but it must respect load/execute/store
  decoupling instead of only reducing host instruction count.
- https://github.com/chipsalliance/rocket-chip/blob/master/src/main/scala/tile/LazyRoCC.scala -
  Rocket Chip's RoCC source exposes the command fields (`funct`, `rs1`, `rs2`,
  opcode, response channel). The J07 takeaway is that a fused MX command would
  consume scarce command-field encoding and must be matched by hardware decode
  and software macro changes.
- https://arxiv.org/abs/1911.09925 - The Gemmini paper frames optimization as
  full-stack hardware/software work. The J07 takeaway is to pursue a custom
  instruction only when counters show the software/hardware interface, not just
  software instruction count, is the bottleneck.

### J08 Detail

Starting point:

- J07 added instruction-retirement attribution and showed the DIM16 large-shape
  path retires a small instruction stream but spends many cycles around RoCC
  issue/backpressure.
- Code inspection found that normal performance builds still executed the
  MXCPU diagnostic probes inside `gemmini_loop_ws_mxint8`: `rdcycle` reads and
  global accumulator stores around fences, MX config, A-scale mvin, B-scale mvin,
  B-scale repack, and `gemmini_loop_ws_mx`.
- Because stock INT8 does not pay these MXCPU probes, leaving them in the default
  MX timed region was not ideal for publication-quality performance data.

Candidate note:

- Classification: software-only measurement-overhead cleanup.
- Hypothesis: make CPU timing opt-in so the default benchmark measures the MX
  implementation rather than the MX instrumentation. Keep the diagnostic path
  available for future counter passes.
- Expected DIM32 effect: same or faster, because only measurement instructions
  are removed from the default timed region.
- Expected DIM16 effect: same or faster on small/medium shapes; large-shape
  code-layout effects were possible.
- Expected area effect: none.
- Rollback: set `MX_BENCH_CPU_TIMING` default back to always-on behavior or
  remove the new guards.

Implementation:

- Added `MX_BENCH_CPU_TIMING`, defaulting to `0`, in `gemmini.h`.
- Guarded MXCPU globals, `mx_rdcycle_()`, per-chunk timing probes, and
  `mx_bench.c` reset/print logic with `#if MX_BENCH_CPU_TIMING`.
- Extended `DOCS_MX/scripts/run_perf.sh` to pass
  `-DMX_BENCH_CPU_TIMING=0|1` when requested.
- Default performance runs no longer print `MXCPU`; future CPU-timing diagnostics
  should explicitly set `MX_BENCH_CPU_TIMING=1`.

Performance evidence:

- DIM32 default gate:
  `DOCS_MX/scripts/results/perf_plan1_j08_dim32.csv` =
  `4229 / 9689 / 37919`.
- Versus J05/J07 DIM32:
  `64^3` improved by 88 cycles, `128^3` improved by 166 cycles, and `256^3`
  improved by 2 cycles. DIM32 remains below the I20 limits.
- DIM16 default gate:
  `DOCS_MX/scripts/results/perf_plan1_j08_dim16.csv` =
  `4428 / 14219 / 92884`.
- Versus J05 DIM16:
  `64^3` improved by 64 cycles and `128^3` improved by 101 cycles, while
  `256^3` regressed by 119 cycles (`92765 -> 92884`).
- Versus the original I20 DIM16 baseline:
  `128^3` improved by 130 cycles (`14349 -> 14219`) and `256^3` improved by
  18,681 cycles (`101565 -> 92884`).
- Interpretation: J08 is worth banking because it improves DIM32, improves
  DIM16 `128^3`, preserves bit-exact behavior, and has zero area cost. The
  small DIM16 `256^3` regression versus J05 is documented as the next item to
  recover or explain.

Correctness:

- DIM32 regression:
  `DOCS_MX/scripts/run_regression.sh --dims=32 --skip-stock-elab --timeout-cycles=300000000`
  passed `mxint8_golden`, `mxint8_matmul_dim32`, `mxint8_corner`,
  `mxint8_matmul_partial`, `mxint8_tiled`, `mxint8_btb`,
  `mxint8_multitile`, and `mxint8_matmul_nphase`.
- DIM16 regression:
  `DOCS_MX/scripts/run_regression.sh --dims=16 --skip-stock-elab --timeout-cycles=300000000`
  passed `mxint8_matmul_dim16`, `mxint8_multitile`, and
  `mxint8_matmul_nphase`.
- Both regression matrices ended with `OVERALL: PASS`.

Commands run:

```bash
bash -n DOCS_MX/scripts/run_perf.sh
git diff --check -- DOCS_MX/scripts/run_perf.sh generators/gemmini/software/gemmini-rocc-tests/include/gemmini.h generators/gemmini/software/gemmini-rocc-tests/bareMetalC/mx_bench.c
DOCS_MX/scripts/run_perf.sh --dims 32 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j08_dim32.csv
DOCS_MX/scripts/run_perf.sh --dims 16 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j08_dim16.csv
DOCS_MX/scripts/run_regression.sh --dims=32 --skip-stock-elab --timeout-cycles=300000000
DOCS_MX/scripts/run_regression.sh --dims=16 --skip-stock-elab --timeout-cycles=300000000
```

All long simulations, logs, and temporary headers stayed under
`sims/verilator/gemmini`.

Online research recorded for J08:

- https://docs.riscv.org/reference/isa/unpriv/counters.html - The RISC-V
  unprivileged ISA documents cycle and instruction-retirement counters as
  architectural observability mechanisms. The J08 takeaway is that counter reads
  are useful for diagnostics, but they should not remain inside the default
  timed kernel when measuring final performance.
- https://github.com/ucb-bar/gemmini - The Gemmini README frames Gemmini as a
  full-stack accelerator with software APIs and hardware counters. The J08
  takeaway is to keep diagnostics available while separating them from the
  publication default path.
- https://arxiv.org/abs/1911.09925 - The Gemmini paper emphasizes full-system
  evaluation. The J08 takeaway is that benchmark methodology matters: both stock
  and MX should be timed without asymmetric debug instrumentation.

### J09 Detail

Starting point:

- J08 banked the default-off MXCPU timing cleanup and produced DIM16
  `4428 / 14219 / 92884`.
- DIM16 `MX_SCALE_SP_ROWS` was still 512 because `mxint8DIM16Config` used the
  same 8KB scale SRAM capacity as DIM32. Since DIM16 rows are half as wide,
  that doubled the number of rows relative to DIM32.
- Code inspection of `mxint8_compute_geom()` and the envelope checks showed the
  current DIM16 benchmark tiler uses at most 64 A-scale rows per half and 32
  B-scale rows per half for the measured `64^3`, `128^3`, and `256^3` shapes.
  A 256-row scale SRAM still provides 128 rows per A/B region and 64 rows per
  ping-pong half.

Candidate note:

- Classification: hardware/config.
- Hypothesis: reduce DIM16 MX scale SRAM from 8KB to 4KB, making
  `MX_SCALE_SP_ROWS` 256 instead of 512. This should preserve the current tiler
  envelope, may simplify address widths, and might reduce area.
- Expected DIM32 effect: none; DIM32 config remains at 8KB/256 rows.
- Expected DIM16 effect: same or slightly faster; risk is envelope failure or
  hidden regression shape exceeding the smaller scale SRAM.
- Expected area effect: possible BRAM or LUT reduction. FPGA BRAM reduction was
  uncertain because BRAMs are allocated in coarse primitives.
- Rollback: restore `mx_scale_sp_capacity = CapacityInKilobytes(8)` in
  `mxint8DIM16Config`, rebuild DIM16 simulator/header, and rerun perf.

Implementation:

- Changed `generators/gemmini/src/main/scala/gemmini/Configs.scala`:
  `mxint8DIM16Config.mx_scale_sp_capacity` from `8KB` to `4KB`.
- Rebuilt the DIM16 simulator with `run_perf.sh --build-sims`, which regenerated
  `generators/gemmini/software/gemmini-rocc-tests/include/gemmini_params_mxint8_dim16.h`.
- Confirmed the generated header now contains `#define MX_SCALE_SP_ROWS 256`.

Performance evidence:

- DIM32 gate:
  `DOCS_MX/scripts/results/perf_plan1_j09_dim32.csv` =
  `4229 / 9689 / 37919`, preserving J08 and staying below I20 limits.
- DIM16 gate:
  `DOCS_MX/scripts/results/perf_plan1_j09_dim16.csv` =
  `4368 / 14187 / 92799`.
- Versus J08 DIM16:
  `64^3` improved by 60 cycles, `128^3` improved by 32 cycles, and `256^3`
  improved by 85 cycles.
- Versus J05 DIM16:
  `64^3` improved by 124 cycles and `128^3` improved by 133 cycles, while
  `256^3` remains 34 cycles slower (`92765 -> 92799`).
- Versus original I20 DIM16:
  `128^3` improved by 162 cycles and `256^3` improved by 18,766 cycles.

Correctness:

- DIM16 regression:
  `DOCS_MX/scripts/run_regression.sh --dims=16 --skip-stock-elab --timeout-cycles=300000000`
  passed `mxint8_matmul_dim16`, `mxint8_multitile`, and
  `mxint8_matmul_nphase`.
- The DIM16 performance run ended with `mx_bench: PASS`.
- DIM32 behavior was checked by the DIM32 performance gate. The candidate did
  not modify shared RTL/software beyond the DIM16 config and generated DIM16
  header.

Synthesis:

- Command:
  `DOCS_MX/scripts/run_synth.sh --skip-verilog --csv DOCS_MX/scripts/results/synth_plan1_j09_dim16.csv GemminiMXINT8DIM16RocketConfig`.
- J09 DIM16 MX OOC synthesis:
  `121197 LUT`, `60946 FF`, `168 BRAM36`, `239 DSP`, `WNS=-39.464ns`,
  `Fmax_est=20.2167MHz`.
- I20 DIM16 MX OOC synthesis:
  `120825 LUT`, `61005 FF`, `168 BRAM36`, `239 DSP`.
- Cached stock DIM16 OOC synthesis:
  `92992 LUT`, `53058 FF`, `160 BRAM36`, `236 DSP`.
- Area interpretation:
  FPGA BRAM36 did not drop despite halving the architectural scale-SRAM rows,
  likely because the design still maps into the same coarse BRAM count. J09 is
  not an FPGA BRAM-area win. It is a small speed win and an architectural
  storage-capacity reduction with almost unchanged FPGA area:
  `+372 LUT`, `-59 FF`, `0 BRAM36`, `0 DSP` versus I20 MX DIM16.

Commands run:

```bash
sbt "project gemmini" compile
DOCS_MX/scripts/run_perf.sh --dims 32 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j09_dim32.csv
DOCS_MX/scripts/run_perf.sh --build-sims --dims 16 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j09_dim16.csv
DOCS_MX/scripts/run_regression.sh --dims=16 --skip-stock-elab --timeout-cycles=300000000
DOCS_MX/scripts/run_synth.sh --skip-verilog --csv DOCS_MX/scripts/results/synth_plan1_j09_dim16.csv GemminiMXINT8DIM16RocketConfig
```

Notes:

- The first `sbt` attempt failed under system JDK 21 with Scala
  `bad constant pool index`; rerunning through `env.sh` with conda JDK 20 and
  escalated access to `~/.sbt/boot/sbt.boot.lock` passed.
- The DIM32 perf gate rebuilt its simulator because the Scala config timestamp
  changed, but the final DIM32 numbers matched J08 exactly.
- All long simulations, logs, temporary headers, synthesis work, and scratch
  stayed under `sims/verilator/gemmini` or `DOCS_MX/scripts/results/synth`.

Online research recorded for J09:

- https://github.com/ucb-bar/gemmini - Gemmini exposes generator parameters for
  memory capacities and software-visible headers. The J09 takeaway is that
  scale-SRAM capacity should be tuned as a generator/config parameter, not by
  ad hoc software assumptions.
- https://arxiv.org/abs/1911.09925 - The Gemmini paper motivates generator-level
  design-space exploration across performance and area. The J09 takeaway is that
  changing a capacity parameter is a valid DSE point only after performance,
  correctness, and synthesis checks agree.
- https://arxiv.org/abs/2110.01752 - RASA emphasizes matching schedules to local
  storage resources. The J09 takeaway is to size local scale storage to the
  measured tile envelope, while documenting when FPGA primitive granularity hides
  the expected area reduction.

### J10 Detail

Starting point:

- J09 banked the DIM16 4KB scale-SRAM config and produced DIM16
  `4368 / 14187 / 92799`.
- Fresh J10 diagnostic runs were taken before another hardware/config change:
  - `perf_plan1_j10_dim16_debug.csv` with `MX_BENCH_COUNTER_SET=2`:
    `4243 / 14193 / 92627`.
  - `perf_plan1_j10_dim16_part.csv` with `MX_BENCH_COUNTER_SET=1`:
    `4317 / 14183 / 92466`.
- The J10 counter logs are diagnostic builds, so the acceptance decision used
  default perf CSVs, not the diagnostic cycle counts.

Measured bottleneck:

- DIM16 `256^3` J10 `MXSCALEDEP`:
  `ex_blocked_on_scale=11826`, `ex_blocked_scale_only=10979`,
  `ex_blocked_nonscale=85371`, `ld_pool_full=17772`,
  `ld_blocked=0`, `st_pool_full=8650`, `st_inflight=26258`,
  `scale_dma_active=12191`.
- DIM16 `256^3` J10 `MXPART`:
  `no_cmd=30308`, `ex_pool_empty=17853`, `ex_ready=73346`,
  `ex_blocked=85242`, `ex_inflight=75472`, `ex_pool_full=69094`,
  `ld_inflight=30220`, `loopmm_active=89512`.
- Interpretation:
  scale-only stalls are still present, but they are no longer the dominant
  residual. The larger residual is non-scale dependency/queue pressure around
  EX/LD/ST. Since J02 already showed deeper EX reservation entries had zero
  cycle effect, J10 tested the next visible queue pressure: LD reservation
  entries.

Candidate note:

- Classification: hardware/config.
- Hypothesis: increase only DIM16 MX `reservation_station_entries_ld` from 8 to
  16. If LD pool fullness was throttling the unroller or creating non-scale EX
  dependencies, this should reduce DIM16 `128^3`/`256^3` cycles.
- Expected DIM32 effect: none, because DIM32 config was not edited. The required
  DIM32 perf gate was still run first.
- Expected area effect: likely area increase if banked, because this adds more
  reservation-station entries and dependency bits. Therefore the candidate must
  earn a cycle win before synthesis is worth running.
- Rollback: remove the temporary `reservation_station_entries_ld = 16` line,
  rebuild DIM16 simulator/header from the reverted source, and rerun perf.

Implementation:

- Temporarily changed `generators/gemmini/src/main/scala/gemmini/Configs.scala`
  in `mxint8DIM16Config`:
  `reservation_station_entries_ld = 16`.
- Rebuilt DIM32 as part of the required DIM32-first gate because the Scala file
  timestamp caused Verilator to refresh generated collateral.
- Rebuilt DIM16 simulator with `--build-sims`.

Performance evidence:

- DIM32 gate:
  `DOCS_MX/scripts/results/perf_plan1_j10_dim32.csv` =
  `4229 / 9689 / 37919`, exactly preserving J09/J08 and staying below I20
  limits.
- DIM16 candidate:
  `DOCS_MX/scripts/results/perf_plan1_j10_dim16_ldrs16.csv` =
  `4368 / 14187 / 92799`.
- DIM16 reverted-source rebuild:
  `DOCS_MX/scripts/results/perf_plan1_j10_dim16_reverted.csv` =
  `4368 / 14187 / 92799`.
- Conclusion:
  doubling DIM16 MX LD reservation entries did not change any measured shape.
  LD pool pressure is an observed symptom, but not a useful standalone knob for
  this benchmark and current schedule.

Correctness:

- The candidate and reverted performance runs ended with `mx_bench: PASS`.
- Full regression was not run for the candidate because it was rejected after
  exact no-gain performance. The source tree was reverted to the J09 config and
  the DIM16 simulator was rebuilt from the reverted source.

Synthesis:

- Not run. A hardware/config candidate with zero cycle gain and likely area
  increase fails the acceptance criteria before synthesis.

Commands run:

```bash
MX_BENCH_COUNTER_SET=2 MX_BENCH_PRINT_GEOM=1 DOCS_MX/scripts/run_perf.sh --dims 16 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j10_dim16_debug.csv
MX_BENCH_COUNTER_SET=1 MX_BENCH_PRINT_GEOM=1 DOCS_MX/scripts/run_perf.sh --dims 16 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j10_dim16_part.csv
DOCS_MX/scripts/run_perf.sh --dims 32 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j10_dim32.csv
DOCS_MX/scripts/run_perf.sh --build-sims --dims 16 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j10_dim16_ldrs16.csv
DOCS_MX/scripts/run_perf.sh --build-sims --dims 16 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j10_dim16_reverted.csv
```

Notes:

- All long simulations, Verilator builds, logs, generated headers, and scratch
  stayed under `sims/verilator/gemmini`.
- No `/tmp` Verilator/Vivado campaign directory was used.
- The reverted source still keeps J09's banked DIM16 4KB scale SRAM:
  `MX_SCALE_SP_ROWS 256`.

Online research recorded for J10:

- https://arxiv.org/abs/1911.09925 - The Gemmini paper motivates evaluating
  accelerator changes in the full stack, including shared SoC resources and
  software overheads. The J10 takeaway is to treat queue-depth changes as
  generator DSE points that need measured cycle evidence, not as assumed wins.
- https://arxiv.org/abs/2212.03034 - The TVM/Gemmini integration paper shows
  that schedules can outperform fixed hand-tuned mappings on Gemmini. The J10
  takeaway is that the remaining DIM16 loss is more likely in schedule/order and
  dependency behavior than in simply making one queue deeper.
- https://arxiv.org/abs/2110.01752 - RASA discusses hiding systolic fill/drain
  overhead by overlapping stages. The J10 takeaway is that future work should
  look for dependency/order changes that increase overlap, not just larger
  buffers.
- https://docs.amd.com/v/u/en-US/ug473_7Series_Memory_Resources - AMD/Xilinx
  UG473 documents 7-series block RAM resources as coarse 36Kb/18Kb primitives.
  The J10 takeaway is that small storage/control changes need synthesis to prove
  area wins, and more reservation entries are unlikely to be free.
- https://docs.amd.com/v/u/en-US/ug901-vivado-synthesis - AMD/Xilinx UG901
  documents Vivado RAM inference and HDL coding styles. The J10 takeaway is to
  avoid assuming source-level storage reductions map linearly to FPGA area.

### J11 Detail

Starting point:

- J10 rejected deeper DIM16 LD reservation entries and restored the J09 baseline:
  DIM32 `4229 / 9689 / 37919`, DIM16 `4368 / 14187 / 92799`.
- Fresh J10 counters still showed high DIM16 `256^3` non-scale dependency and
  scheduling pressure:
  `ex_blocked_nonscale=85371`, `ex_pool_full=69094`, `no_cmd=30308`,
  `ld_pool_full=17772`, and `st_pool_full=8650`.
- The pair-J DIM16 schedule still had a software `gemmini_fence()` at the start
  of the second J-pair group.

Code inspected:

- `gemmini.h` pair-J scheduling, B/A scratchpad reuse, scale residency, and
  `do_fence` calculation.
- `ReservationStation.scala` dependency construction for load, execute, and
  store queues. The relevant observation is that load commands depend on
  overlapping in-flight execute/store ranges, and execute commands depend on
  overlapping in-flight loads and stores.
- `MXScaleLoadController.scala` ping-pong credit interlock, which blocks a new
  A-scale load when both scale-SRAM halves are occupied.
- `ExecuteController.scala` loop-drained signal, which releases the scale
  ping-pong credit when a chunk has finished reading its half.

Candidate note:

- Classification: software-only.
- Hypothesis: remove the pair-J group-boundary fence and rely on existing
  hardware dependency machinery to serialize only real scratchpad or scale-SRAM
  conflicts. This could improve DIM16 `256^3` by overlapping the setup of the
  second pair with the drain of the first pair.
- Expected DIM32 effect: none, because the branch is guarded by
  `DIM < MX_BLOCK_SIZE`. The required DIM32 gate was still run first.
- Expected DIM16 effect: possible `256^3` improvement; possible `64^3`/`128^3`
  noise or regression from changed code layout and fence cadence.
- Expected area effect: none.
- Correctness risk: stale B payload or stale B scales if the boundary fence was
  still protecting a dependency not represented by scratchpad overlap or scale
  credit. `mx_bench` correctness and regression would catch this before banking.
- Rollback: restore `pair_group_barrier` in the `do_fence` expression and rerun
  DIM16 perf to confirm the baseline returned.

Implementation:

- Temporarily changed the pair-J path in `gemmini.h` from:
  `k_chunked || first_chunk || !geom_same || pair_group_barrier`
  to:
  `k_chunked || first_chunk || !geom_same`.
- Updated the local comment to describe relying on scratchpad overlap deps and
  the scale ping-pong credit.
- After measurement, restored the original `pair_group_barrier` source.

Performance evidence:

- DIM32 gate:
  `DOCS_MX/scripts/results/perf_plan1_j11_dim32.csv` =
  `4229 / 9689 / 37919`, preserving J09/J10 and staying below I20 limits.
- DIM16 candidate:
  `DOCS_MX/scripts/results/perf_plan1_j11_dim16_nopairfence.csv` =
  `4438 / 14386 / 92516`.
- DIM16 reverted-source check:
  `DOCS_MX/scripts/results/perf_plan1_j11_dim16_reverted.csv` =
  `4368 / 14187 / 92799`.
- Conclusion:
  removing the pair-group fence improves `256^3` by 283 cycles, but regresses
  `64^3` by 70 cycles and `128^3` by 199 cycles. Since the campaign is trying
  to improve DIM16 large shapes without destabilizing the smaller accepted
  wins, this was not worth banking.

Correctness:

- Candidate DIM32 and DIM16 performance runs ended with `mx_bench: PASS`.
- Reverted DIM16 performance run ended with `mx_bench: PASS`.
- Full regression was not run because the candidate was rejected after the
  performance tradeoff and source was restored.

Synthesis:

- Not run. The candidate was software-only and rejected.

Commands run:

```bash
DOCS_MX/scripts/run_perf.sh --dims 32 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j11_dim32.csv
DOCS_MX/scripts/run_perf.sh --dims 16 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j11_dim16_nopairfence.csv
DOCS_MX/scripts/run_perf.sh --dims 16 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j11_dim16_reverted.csv
```

Notes:

- All long simulations, Verilator builds, logs, generated headers, and scratch
  stayed under `sims/verilator/gemmini`.
- No `/tmp` Verilator/Vivado campaign directory was used.
- The source tree is restored to the J09/J10 banked behavior for the pair-J
  boundary fence.

Online research recorded for J11:

- https://arxiv.org/abs/2603.19057 - MatrixFlow argues for explicit overlap of
  DMA, compute, and output transfer in matrix accelerators. The J11 takeaway is
  that reducing broad serialization can help, but only if the local dependency
  machinery preserves correctness and the measured shape mix benefits.
- https://arxiv.org/abs/2501.13553 - Decoupled access/execute work shows that
  dependencies can destroy decoupling and force synchronization. The J11
  takeaway is that removing a synchronization point is reasonable only when the
  remaining dependency model covers the true hazards.
- https://arxiv.org/abs/2110.01752 - RASA uses overlap to hide systolic-array
  fill/drain overhead with low area cost. The J11 takeaway is that overlap is a
  good target, but the measured `128^3` regression means this specific overlap
  point is too blunt.
- https://arxiv.org/abs/2212.03034 - TVM/Gemmini schedule tuning shows that
  schedule choices can dominate accelerator performance. The J11 takeaway is to
  keep searching in scheduling/order space, but evaluate the whole shape set, not
  only one large point.

### J12 Detail

Starting point:

- J11 showed that removing the pair-J group fence logically targets the `256^3`
  pair-J branch, yet the resulting binary also regressed DIM16 `128^3`.
- This suggested that the shared inline MX tiler may be sensitive to branch
  layout, code size, or compiler scheduling even when a branch is not taken for
  a given shape.
- The J11 restored baseline remained DIM32 `4229 / 9689 / 37919` and DIM16
  `4368 / 14187 / 92799`.

Code inspected:

- `gemmini.h` pair-J/fallback split and the outer `if (mx_pairj_b_reuse)`.
- `mx_bench.c` geometry printer, which confirmed that the existing diagnostic
  helper does not fully model the pair-J group-boundary fence and should not be
  used alone to reason about this path.
- `DOCS_MX/scripts/run_perf.sh` C-flag forwarding and build location handling.
- `bareMetalC/Makefile` to confirm that `EXTRA_CFLAGS` are passed into the
  bare-metal benchmark build.

Candidate note:

- Classification: software-only.
- Hypothesis: mark the pair-J branch as unlikely with GCC's
  `__builtin_expect`. For `64^3` and `128^3`, this could keep the fallback path
  layout hotter without changing the actual schedule or correctness behavior.
- Expected DIM32 effect: none, but because `gemmini.h` is shared, the required
  DIM32 gate was still run.
- Expected DIM16 effect: possible `64^3`/`128^3` recovery; possible neutral or
  small `256^3` movement from code layout.
- Expected area effect: none.
- Correctness risk: low, because the generated command sequence is intended to
  be semantically identical.
- Rollback: restore `if (mx_pairj_b_reuse)`.

Implementation:

- Temporarily changed:
  `if (mx_pairj_b_reuse)`
  to:
  `if (__builtin_expect(mx_pairj_b_reuse, 0))`.
- After measurement, restored the original source.

Performance evidence:

- DIM32 gate:
  `DOCS_MX/scripts/results/perf_plan1_j12_dim32.csv` =
  `4229 / 9689 / 37919`, exactly preserving J11/J10/J09.
- DIM16 candidate:
  `DOCS_MX/scripts/results/perf_plan1_j12_dim16_branchhint.csv` =
  `4338 / 14238 / 92641`.
- Baseline for restore:
  `DOCS_MX/scripts/results/perf_plan1_j11_dim16_reverted.csv` =
  `4368 / 14187 / 92799`.
- Conclusion:
  the hint improved `64^3` by 30 cycles and `256^3` by 158 cycles, but
  regressed `128^3` by 51 cycles. Because `128^3` is one of the explicit DIM16
  problem shapes and the `256^3` gain is small, this was rejected.

Correctness:

- Candidate DIM32 and DIM16 performance runs ended with `mx_bench: PASS`.
- Full regression was not run because the candidate was rejected after the
  performance tradeoff and source was restored.

Synthesis:

- Not run. The candidate was software-only and rejected.

Commands run:

```bash
DOCS_MX/scripts/run_perf.sh --dims 32 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j12_dim32.csv
DOCS_MX/scripts/run_perf.sh --dims 16 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j12_dim16_branchhint.csv
```

Notes:

- All long simulations, Verilator builds, logs, generated headers, and scratch
  stayed under `sims/verilator/gemmini`.
- No `/tmp` Verilator/Vivado campaign directory was used.
- The source tree is restored to the J11/J10/J09 banked branch condition.

Online research recorded for J12:

- https://gcc.gnu.org/onlinedocs/gcc/Other-Builtins.html - GCC documents
  `__builtin_expect` as branch prediction information, while warning that
  profile feedback is generally preferable. The J12 takeaway is that it is an
  acceptable small experiment when collecting representative profiles is not
  practical in the bare-metal Verilator loop.
- https://gcc.gnu.org/onlinedocs/gcc/Optimize-Options.html - GCC optimization
  behavior can change code layout and branch handling under optimization. The
  J12 takeaway is that software schedule experiments can move cycles even when
  semantics are unchanged, so every candidate needs measured gates.
- https://arxiv.org/abs/2212.03034 - TVM/Gemmini work shows that software
  schedules and generated code matter for Gemmini performance. The J12 takeaway
  is that code generation/layout is worth testing, but the whole shape set must
  win.
- https://arxiv.org/abs/1911.09925 - The Gemmini paper frames Gemmini as a
  full-stack generator, where software and hardware choices interact. The J12
  takeaway is that low-level software hints are valid DSE points, but only
  bankable with robust cycle evidence.

### J13 Detail

Starting point:

- J11 and J12 exposed shape-sensitive behavior around the DIM16 pair-J path.
- The existing `MX_BENCH_PRINT_GEOM` diagnostic was stale: it iterated the old
  nested `(i,j,k)` order, did not model the banked pair-J schedule, did not count
  the pair-group boundary fence, and over-counted B-scale loads after J05's
  B-scale residency change.
- Without fixing this, future iterations could chase the wrong bottleneck.

Code inspected:

- `mx_bench.c` `print_mx_geom()` diagnostic function.
- `gemmini.h` banked pair-J ordering, pair-group fence, A/B payload reuse, and
  B-scale residency logic.
- `run_perf.sh` forwarding of `MX_BENCH_PRINT_GEOM=1` into `EXTRA_CFLAGS`.

Candidate note:

- Classification: software-only diagnostic.
- Hypothesis: update `print_mx_geom()` to mirror the actual banked schedule so
  the agent can reason about true chunk count, fence count, payload load count,
  scale load count, pair-J activation, and B-scale residency.
- Expected DIM32 effect: none for default performance because the code is behind
  `#if MX_ENABLED && MX_BENCH_PRINT_GEOM`. The required DIM32 gate was still run.
- Expected DIM16 effect: no default performance effect; diagnostic builds may
  move cycle numbers due to code layout and extra prints outside the timed
  region, so those cycles are not used as the acceptance baseline.
- Expected area effect: none.
- Correctness risk: low; diagnostic-only code does not change the GEMM schedule.
- Rollback: restore the old nested-loop geometry printer.

Implementation:

- Added pair-J predicate mirroring `gemmini.h`.
- Counted pair-J sequence order, including the pair-group boundary fence.
- Counted A and B payload loads using the same reuse conditions as the tiler.
- Counted B-scale loads with the same per-slot/per-parity residency model used
  by the banked DIM16/DIM32 path.
- Added `pairj_reuse` and `bscale_residency` fields to `MXGEOM`.

Performance and diagnostic evidence:

- Default DIM32 gate:
  `DOCS_MX/scripts/results/perf_plan1_j13_dim32.csv` =
  `4229 / 9689 / 37919`, exactly preserving J12/J11/J10/J09.
- DIM16 diagnostic run:
  `DOCS_MX/scripts/results/perf_plan1_j13_dim16_geom.csv` =
  `4354 / 14065 / 92781`. This run is diagnostic because
  `MX_BENCH_PRINT_GEOM=1` changes the benchmark binary.
- Corrected DIM16 geometry:
  - `64^3`: `chunks=1`, `fences=1`, `pairj_reuse=0`,
    `bscale_residency=0`.
  - `128^3`: `chunks=4`, `fences=1`, `a_payload_loads=2`,
    `b_payload_loads=2`, `a_scale_loads=4`, `b_scale_loads=2`,
    `pairj_reuse=0`, `bscale_residency=1`.
  - `256^3`: `chunks=16`, `fences=2`, `a_payload_loads=8`,
    `b_payload_loads=4`, `a_scale_loads=16`, `b_scale_loads=4`,
    `pairj_reuse=1`, `bscale_residency=1`.
- Conclusion:
  B-scale traffic is already low for `128^3` and `256^3`; future work should
  look more at payload movement, accumulator/store pressure, command scheduling,
  or DIM16 hardware-side output behavior rather than assuming scale-load count
  is still the main residual bottleneck.

Correctness:

- Default DIM32 performance run ended with `mx_bench: PASS`.
- DIM16 diagnostic performance run ended with `mx_bench: PASS`.
- Full regression was not run because no default GEMM schedule, RTL, or config
  behavior changed.

Synthesis:

- Not run. This was diagnostic-only software.

Commands run:

```bash
DOCS_MX/scripts/run_perf.sh --dims 32 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j13_dim32.csv
MX_BENCH_PRINT_GEOM=1 DOCS_MX/scripts/run_perf.sh --dims 16 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j13_dim16_geom.csv
```

Notes:

- All long simulations, Verilator builds, logs, generated headers, and scratch
  stayed under `sims/verilator/gemmini`.
- No `/tmp` Verilator/Vivado campaign directory was used.
- This measurement fix is banked. Future iterations should prefer the corrected
  `MXGEOM` fields over older geometry notes.

Online research recorded for J13:

- https://docs.riscv.org/reference/isa/unpriv/counters.html - RISC-V counter
  documentation reinforces that performance measurement should be explicit and
  tied to known events. The J13 takeaway is that geometry diagnostics must match
  the actual command sequence before counters are interpreted.
- https://arxiv.org/abs/1911.09925 - The Gemmini paper emphasizes full-stack
  evaluation rather than isolated accelerator reasoning. The J13 takeaway is
  that software schedule diagnostics are part of the measurement stack.
- https://arxiv.org/abs/2212.03034 - TVM/Gemmini schedule tuning depends on
  measuring generated schedules. The J13 takeaway is that stale schedule
  summaries can mislead tuning decisions.
- https://gcc.gnu.org/onlinedocs/gcc/Other-Builtins.html - GCC documents
  compile-time builtins and reminds us that build-time flags change what code is
  present. The J13 takeaway is to keep diagnostic builds separate from default
  acceptance runs.

### J14 Detail

Starting point:

- J09 through J13 baseline remained DIM32 `4229 / 9689 / 37919` and DIM16
  `4368 / 14187 / 92799`.
- J13 fixed geometry diagnostics and showed that B-scale load count is already
  low for the large DIM16 shapes.
- Fresh J14 `MXSCALEDEP` counters still showed accelerator-side pressure:
  - `128^3`: `ex_blocked_on_scale=2527`,
    `ex_blocked_scale_only=2194`, `ld_pool_full=1677`,
    `st_pool_full=1856`, `st_inflight=6442`,
    `scale_dma_active=2680`.
  - `256^3`: `ex_blocked_on_scale=11933`,
    `ex_blocked_scale_only=11072`, `ld_pool_full=17805`,
    `st_pool_full=8562`, `st_inflight=26268`,
    `scale_dma_active=12300`.
- J10 already showed that LD RS depth was not useful, and J02 showed EX RS depth
  was not useful. The remaining measured pressure pointed toward output/store
  overlap rather than raw command count.

Code inspected:

- `ReservationStation.scala` store pool allocation, validity, dependency, and
  `st_pool_full` counter behavior.
- `StoreController.scala` store queue and in-flight store handling.
- `Configs.scala` DIM32/DIM16/stock config split.
- `CounterFile.scala` and the existing `MXSCALEDEP` counter wiring.
- DIM16 generated headers to confirm the active MX scale SRAM rows and stock
  `MX_ENABLED` state.

Candidate note:

- Classification: hardware/config, DIM16-only.
- Hypothesis: DIM16 large shapes are held back partly by store/output command
  residency. Increasing only the DIM16 MX store reservation-station entries can
  let more output-side work remain visible to dependency scheduling without
  changing the store-controller queue length.
- Expected DIM32 effect: none, because DIM32 keeps
  `reservation_station_entries_st=8`.
- Expected DIM16 effect: improve `128^3` and `256^3`; `64^3` may be neutral
  because it has little store-pool pressure.
- Expected area effect: possible FF/LUT increase from a deeper ST RS pool; no
  intended BRAM or DSP increase.
- Correctness risk: moderate. More in-flight store commands can expose ordering
  issues if dependencies are incomplete, so DIM16 regression is required before
  banking.
- Rollback: restore DIM16 `reservation_station_entries_st=8`.

Implementation:

- In `mxint8DIM16Config`, changed:
  `reservation_station_entries_st = 8`
  to:
  `reservation_station_entries_st = 16`.
- Kept `st_queue_length = 4`.
- Kept DIM32 MX, stock DIM32, and stock DIM16 configs unchanged.

Performance evidence:

- DIM32 gate:
  `DOCS_MX/scripts/results/perf_plan1_j14_dim32.csv` =
  `4229 / 9689 / 37919`, exactly preserving J09-J13 and staying below the I20
  acceptance thresholds.
- DIM16 candidate:
  `DOCS_MX/scripts/results/perf_plan1_j14_dim16_strs16.csv` =
  `4368 / 13768 / 89310`.
- Versus the J09-J13 DIM16 baseline `4368 / 14187 / 92799`, this is:
  - `64^3`: unchanged.
  - `128^3`: 419 cycles faster.
  - `256^3`: 3,489 cycles faster.
- Versus I20 DIM16 MX `4390 / 14349 / 101565`, this is:
  - `64^3`: 22 cycles faster.
  - `128^3`: 581 cycles faster.
  - `256^3`: 12,255 cycles faster.
- The current DIM16 MX `256^3` result is still slower than stock DIM16
  `78,453`, but the gap is reduced from 23,112 cycles at I20 to 10,857 cycles.

Correctness:

- Candidate DIM32 and DIM16 performance runs ended with `mx_bench: PASS`.
- DIM16 regression passed:
  - `build:tests-dim16 PASS`
  - `dim16:mxint8_matmul_dim16 PASS`
  - `dim16:mxint8_matmul_nphase PASS`
  - `dim16:mxint8_multitile PASS`
  - `OVERALL: PASS`

Synthesis:

- OOC synthesis result:
  `DOCS_MX/scripts/results/synth_plan1_j14_dim16.csv` =
  `117643 LUT / 63162 FF / 167.5 BRAM36 / 239 DSP`,
  `WNS=-39.464 ns`, estimated `Fmax=20.2167 MHz`.
- Versus J09 MX DIM16
  `121197 LUT / 60946 FF / 168 BRAM36 / 239 DSP`:
  - `-3554 LUT`
  - `+2216 FF`
  - `-0.5 BRAM36`
  - `0 DSP`
- Versus the cached pure stock DIM16 baseline
  `92992 LUT / 53058 FF / 160 BRAM36 / 236 DSP`:
  - `+24651 LUT`
  - `+10104 FF`
  - `+7.5 BRAM36`
  - `+3 DSP`
- Versus I20 MX DIM16 area, this is also lower LUT and slightly lower BRAM, with
  higher FF count. The FF increase is accepted for now because the large-shape
  cycle improvement is the best observed in PLAN_1 and LUT/BRAM move in the
  right direction.

Stock purity:

- `Configs.scala` still defines `stockDIM16Config = chipConfig.copy(...)` and
  does not give stock the MX DIM16 ST-entry override.
- The staged stock DIM16 header still has `#define MX_ENABLED 0`.
- The staged MX DIM16 header has `#define MX_ENABLED 1` and
  `#define MX_SCALE_SP_ROWS 256`, as expected from J09.
- Stock perf/synthesis rows were not rerun because no stock config, stock RTL,
  timed benchmark region, compiler flags, or simulator flags changed.

Commands run:

```bash
MX_BENCH_COUNTER_SET=2 MX_BENCH_PRINT_GEOM=1 DOCS_MX/scripts/run_perf.sh --dims 16 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j14_dim16_debug.csv
DOCS_MX/scripts/run_perf.sh --dims 32 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j14_dim32.csv
DOCS_MX/scripts/run_perf.sh --build-sims --dims 16 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j14_dim16_strs16.csv
DOCS_MX/scripts/run_regression.sh --dims=16 --skip-stock-elab --timeout-cycles=300000000
DOCS_MX/scripts/run_synth.sh --skip-verilog --csv DOCS_MX/scripts/results/synth_plan1_j14_dim16.csv GemminiMXINT8DIM16RocketConfig
```

Notes:

- All long simulations, Verilator builds, logs, generated headers, Vivado logs,
  and scratch stayed under `sims/verilator/gemmini` or the repository-local
  `DOCS_MX/scripts/results` tree.
- No `/tmp` Verilator/Vivado campaign directory was used.
- The candidate is banked. J15 should use `4368 / 13768 / 89310` as the new
  DIM16 MX baseline and must keep DIM32 at or below `4229 / 9689 / 37919`.
- A future MX custom/RoCC macro-instruction remains possible, but J07/J14
  evidence says it should be pursued only if counters show host/RoCC command
  issue is the real limiter. J14's win came from accelerator-side store/output
  capacity, not from reducing retired host instruction count.

Online research recorded for J14:

- https://arxiv.org/abs/2603.19057 - MatrixFlow emphasizes page/tile streaming
  and explicit overlap of DMA, compute, and DMA-out around a 16x16 systolic
  array. The J14 takeaway is to target output/transfer overlap with a bounded
  hardware queue change rather than only adding local storage.
- https://arxiv.org/abs/2605.07881 - AccelSync models accelerator pipeline
  correctness around synchronization and happens-before relations. The J14
  takeaway is that increasing in-flight store-side work needs regression
  coverage before banking because ordering hazards may not appear in a single
  benchmark.
- https://arxiv.org/abs/1911.09925 - The Gemmini paper presents Gemmini as a
  full-stack generator with tunable queues and system effects. The J14 takeaway
  is that a DIM16-only config knob is a defensible DSE lever when the stock and
  DIM32 configs remain isolated.
- https://arxiv.org/abs/2212.03034 - TVM/Gemmini work shows that schedules and
  hardware parameters interact strongly for delivered Gemmini performance. The
  J14 takeaway is to judge the deeper ST pool by measured end-to-end cycles, not
  by one counter alone.

### J15 Detail

Starting point:

- J14 is banked. The current baseline is DIM32 `4229 / 9689 / 37919` and DIM16
  `4368 / 13768 / 89310`.
- The user asked whether high instruction count could be solved with a custom
  MX instruction. J15 therefore refreshed instruction-count evidence before
  choosing the next hardware candidate.
- Post-J14 diagnostic counters showed that the J14 ST RS change removed the
  explicit store-pool-full bottleneck:
  - `64^3`: `st_pool_full=0`, `ld_pool_full=169`,
    `ex_blocked_nonscale=2000`.
  - `128^3`: `st_pool_full=0`, `st_inflight=6618`,
    `ld_pool_full=1789`, `ex_blocked_on_scale=2506`,
    `ex_blocked_scale_only=2102`, `ex_blocked_nonscale=11792`.
  - `256^3`: `st_pool_full=0`, `st_inflight=28467`,
    `ld_pool_full=17890`, `ex_blocked_on_scale=11377`,
    `ex_blocked_scale_only=10484`, `ex_blocked_nonscale=85100`.
- Fresh instruction-count diagnostic:
  - `64^3`: `instret=472`, `cyc_per_inst_x1000=9343`.
  - `128^3`: `instret=1395`, `cyc_per_inst_x1000=9770`.
  - `256^3`: `instret=3738`, `cyc_per_inst_x1000=23883`.
- Interpretation:
  the custom-instruction idea is feasible, but the current evidence says the
  remaining gap is not dominated by retired host instruction count. At `256^3`,
  a few thousand retired instructions cover almost ninety thousand cycles, so
  the first target should remain accelerator-side dependency/queue/memory
  behavior.

Code inspected:

- `ReservationStation.scala` LD/ST pool fullness, dependency clearing, and MX
  debug counters.
- `LoadController.scala` command queue and DMA command tracker behavior.
- `StoreController.scala` command queue, in-flight store completion, and why
  J14 affected ST RS but not store queue length.
- `Configs.scala` DIM16-only config overrides.
- `mx_bench.c` `MXINST` and `MXSCALEDEP` diagnostic paths.

Candidate note:

- Classification: hardware/config trial, DIM16-only.
- Hypothesis: J10's LD RS trial may have been masked by the store-side pressure
  that J14 removed. Retesting `reservation_station_entries_ld=16` on top of the
  banked `reservation_station_entries_st=16` might expose a second-order LD
  overlap gain.
- Expected DIM32 effect: none, because DIM32 config is unchanged.
- Expected DIM16 effect: possible improvement in `128^3`/`256^3` if LD pool
  fullness is blocking command allocation.
- Expected area effect: likely FF/LUT increase from wider LD entries and wider
  dependency vectors; no intended BRAM/DSP change.
- Correctness risk: moderate, because more live LD commands increase in-flight
  dependency pressure.
- Rollback: remove the temporary DIM16 `reservation_station_entries_ld=16`
  override and rebuild DIM16.

Implementation:

- Temporarily added `reservation_station_entries_ld = 16` to
  `mxint8DIM16Config`.
- Kept J14's `reservation_station_entries_st = 16`.
- After measurement, removed the temporary LD override and rebuilt DIM16 back to
  the banked J14 config.

Performance evidence:

- Post-J14 diagnostic run:
  `DOCS_MX/scripts/results/perf_plan1_j15_dim16_debug.csv` =
  `4366 / 13538 / 88797`. This is diagnostic only because
  `MX_BENCH_COUNTER_SET=2` and `MX_BENCH_PRINT_GEOM=1` change the benchmark
  binary.
- Instruction-count diagnostic:
  `DOCS_MX/scripts/results/perf_plan1_j15_dim16_inst.csv` =
  `4410 / 13630 / 89277`. This is diagnostic only because
  `MX_BENCH_COUNTER_SET=3` changes the benchmark binary.
- DIM32 gate:
  `DOCS_MX/scripts/results/perf_plan1_j15_dim32.csv` =
  `4229 / 9689 / 37919`, exactly preserving J14.
- DIM16 candidate:
  `DOCS_MX/scripts/results/perf_plan1_j15_dim16_ldrs16_after_st16.csv` =
  `4368 / 13768 / 89310`, exactly unchanged from J14.
- Reverted-source rebuild:
  `DOCS_MX/scripts/results/perf_plan1_j15_dim16_reverted.csv` =
  `4368 / 13768 / 89310`, confirming the generated DIM16 simulator/header state
  is back on the banked J14 source.

Correctness:

- `sbt "project gemmini" compile` passed before performance gates.
- Candidate DIM32, candidate DIM16, and reverted DIM16 performance runs ended
  with `mx_bench: PASS`.
- Full regression was not run because the candidate produced no cycle gain and
  was rejected/reverted.

Synthesis:

- Not run. The LD RS candidate was rejected before area because it had no cycle
  benefit and would likely increase FF/LUT area.

Stock purity:

- The temporary change was scoped to `mxint8DIM16Config`.
- After revert, the only `Configs.scala` diff versus the starting tree remains
  the banked J09/J14 MX DIM16 changes: `mx_scale_sp_capacity=4KB` and
  `reservation_station_entries_st=16`.
- Stock config source was not touched.

Commands run:

```bash
MX_BENCH_COUNTER_SET=2 MX_BENCH_PRINT_GEOM=1 DOCS_MX/scripts/run_perf.sh --dims 16 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j15_dim16_debug.csv
MX_BENCH_COUNTER_SET=3 DOCS_MX/scripts/run_perf.sh --dims 16 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j15_dim16_inst.csv
source /home/nikolap/Research/2026/chipyard/env.sh && sbt "project gemmini" compile
DOCS_MX/scripts/run_perf.sh --dims 32 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j15_dim32.csv
DOCS_MX/scripts/run_perf.sh --build-sims --dims 16 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j15_dim16_ldrs16_after_st16.csv
DOCS_MX/scripts/run_perf.sh --build-sims --dims 16 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j15_dim16_reverted.csv
```

Notes:

- All long simulations, generated headers, logs, and scratch stayed under
  `sims/verilator/gemmini`.
- No `/tmp` Verilator/Vivado campaign directory was used.
- J15 is rejected and reverted. J16 should not retry LD RS depth unless new
  counters change the bottleneck story.
- A custom MX/RoCC macro-instruction can still be considered later, but only
  behind a measurement gate showing host/RoCC command issue is the limiter.

Online research recorded for J15:

- https://docs.riscv.org/reference/isa/unpriv/counters.html - RISC-V counter
  documentation supports using `instret` and cycle counters for attribution.
  The J15 takeaway is that the custom-instruction hypothesis needs dynamic
  instruction evidence, not just intuition.
- https://arxiv.org/abs/2106.07456 - Custom RISC-V SIMD instruction work shows
  that custom instructions can reduce instruction count, but also highlights
  memory/cache bandwidth as a co-equal limiter. The J15 takeaway is that a
  custom MX command is plausible only if command count is the dominant measured
  cost.
- https://arxiv.org/abs/1911.09925 - The Gemmini paper motivates tuning
  generator parameters such as queue capacities. The J15 takeaway is that
  retesting LD depth after J14 is a valid DSE point, but it must earn its area.
- https://arxiv.org/abs/2603.19057 - MatrixFlow emphasizes overlapping data
  movement and compute. The J15 takeaway is that LD pool pressure is worth
  probing, but simply adding entries did not improve measured overlap here.
- https://arxiv.org/abs/2212.03034 - TVM/Gemmini work shows that schedule and
  hardware parameters interact. The J15 takeaway is that a previously rejected
  hardware knob can be retested after the bottleneck changes, but rejected again
  when end-to-end cycles do not move.

### J16 Detail

Starting point:

- J15 answered the custom-instruction question with fresh evidence:
  DIM16 `256^3` retired only `3738` host instructions over `89277` diagnostic
  cycles, so a large custom MX instruction is not the first lever.
- Still, `gemmini_mvin_mxscale_a()` and `gemmini_mvin_mxscale_b()` each emitted
  a stride config command before every scale mvin, even when the A/B scale
  stride did not change.
- J16 tested a much smaller software-only command-count reduction before
  considering any hardware ISA/macro-op work.

Code inspected:

- `gemmini.h` MX scale-load helper functions.
- `gemmini.h` `gemmini_loop_ws_mxint8()` scale load order and call sites.
- J15 `MXINST` diagnostic log.
- DIM32 performance log during the failed candidate gate.

Candidate note:

- Classification: software-only.
- Hypothesis: cache the most recent A-scale and B-scale stride in C static state.
  If the next scale mvin uses the same stride, skip the redundant
  `CONFIG_MXINT8 SET_STRIDE` RoCC command.
- Expected DIM32 effect: intended neutral or slightly faster, but DIM32 must be
  measured first because `gemmini.h` is shared.
- Expected DIM16 effect: possible small improvement from fewer RoCC commands,
  especially around scale-load issue.
- Expected area effect: none.
- Correctness risk: low-to-moderate. The scale controller stride state is
  stateful, so stale cached stride state could be risky if any path changes it
  outside the helper.
- Rollback: remove the static cache state and restore unconditional stride
  config emission.

Implementation:

- Temporarily added MX-only static cache variables:
  `g_mx_scale_a_stride_valid`, `g_mx_scale_b_stride_valid`,
  `g_mx_scale_a_stride`, and `g_mx_scale_b_stride`.
- Temporarily changed `gemmini_mvin_mxscale_a/b()` to emit the stride config
  only when the requested stride changed.
- Reverted the entire change after the DIM32 gate regressed.

Performance evidence:

- DIM32 candidate gate was started with:
  `DOCS_MX/scripts/results/perf_plan1_j16_dim32_stridecache.csv`.
- The run was interrupted after the second row because the DIM32 acceptance gate
  had already failed:
  - `64^3`: `4407` cycles, worse than J14/J15 `4229` and worse than the I20
    threshold `4317`.
  - `128^3`: `9754` cycles, worse than J14/J15 `9689`, though still below the
    I20 threshold `9855`.
  - `256^3`: skipped because the candidate had already failed at `64^3`.
- DIM16 was skipped because the protocol requires DIM32 to be same or better
  before measuring DIM16.

Correctness:

- The two completed DIM32 rows reported `result=PASS`.
- The full benchmark was intentionally interrupted after DIM32 regression was
  observed.
- No full regression was run because the candidate was rejected and reverted.

Synthesis:

- Not run. The candidate was software-only and rejected before DIM16.

Stock purity:

- No Scala RTL/config or stock path changed.
- The temporary code was guarded by `#if MX_ENABLED` and was removed.
- After revert, `git diff` for `gemmini.h` contains no J16 stride-cache change.

Commands run:

```bash
DOCS_MX/scripts/run_perf.sh --dims 32 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j16_dim32_stridecache.csv
```

Notes:

- The failed run stayed under `sims/verilator/gemmini`.
- No `/tmp` Verilator/Vivado campaign directory was used.
- J16 reinforces the J15 conclusion: command-count tweaks can change code layout
  or command timing in ways that hurt DIM32. Do not attempt a custom MX
  macro-instruction until a future diagnostic clearly shows command issue, not
  accelerator-side dependency/memory pressure, dominates the remaining gap.

Online research recorded for J16:

- https://docs.riscv.org/reference/isa/unpriv/counters.html - Counter
  attribution is the basis for deciding whether instruction-count reduction is
  worth pursuing. The J16 takeaway is to gate command-count changes with DIM32
  and `instret`, not intuition.
- https://arxiv.org/abs/2106.07456 - Custom RISC-V instruction exploration shows
  custom instructions can reduce dynamic instruction count, but memory behavior
  can still dominate. The J16 takeaway is that a smaller software command-count
  test should precede any expensive MX ISA/macro-op work.
- https://arxiv.org/abs/1911.09925 - Gemmini exposes a full-stack DSE surface.
  The J16 takeaway is that even software command helpers are part of the DSE and
  must be measured against DIM32 preservation.
- https://arxiv.org/abs/2212.03034 - TVM/Gemmini results show generated schedule
  and command order can strongly affect Gemmini cycles. The J16 takeaway is that
  fewer commands is not automatically faster if ordering/layout changes disturb
  the working schedule.

### J17 Detail

Starting point:

- Banked baseline entering J17 was the J14/J15 state:
  DIM32 MX `4229 / 9689 / 37919`, DIM16 MX `4368 / 13768 / 89310`.
- J14 eliminated the visible store reservation station bottleneck:
  post-J14 diagnostics showed `st_pool_full=0`.
- The same diagnostics still showed high `st_inflight`, especially at
  DIM16 `256^3`, so J17 tested whether the downstream store command queue was
  the next output-side limiter.

Code inspected:

- `StoreController.scala`, including `cmd = Queue(io.cmd, st_queue_length)`,
  `DMACommandTracker`, and `cmd.ready` behavior.
- `ReservationStation.scala`, including the existing MX debug counters and
  store-pool accounting.
- `Configs.scala`, including the banked DIM16 MX overrides.
- J15/J17 logs for `MXCOUNT` and post-J14 store-pressure evidence.

Candidate note:

- Classification: hardware/config-only DIM16 experiment.
- Hypothesis: increasing DIM16 `st_queue_length` from 4 to 8 might let the
  StoreController absorb more queued output DMA work after the deeper ST RS
  improvement, improving large-shape overlap.
- Expected DIM32 effect: none, because the candidate touched only
  `mxint8DIM16Config`, but DIM32 was still measured first by protocol.
- Expected DIM16 effect: lower output-side serialization if the store queue was
  full behind the RS.
- Expected area effect: negative if banked, because a deeper queue/tracker path
  costs state and muxing; it needed a cycle gain to justify synthesis.
- Rollback: restore DIM16 `st_queue_length = 4` and rebuild the DIM16 simulator.

Implementation:

- Temporarily set `mxint8DIM16Config.st_queue_length = 8` on top of the banked
  J14/J09 DIM16 config:
  `mx_scale_sp_capacity = 4KB`, `reservation_station_entries_st = 16`.
- Compiled the Gemmini project successfully.
- Reverted the candidate after no performance movement.

Performance evidence:

- DIM32 gate:
  `DOCS_MX/scripts/results/perf_plan1_j17_dim32.csv` =
  `4229 / 9689 / 37919`.
- DIM16 candidate:
  `DOCS_MX/scripts/results/perf_plan1_j17_dim16_stq8.csv` =
  `4368 / 13768 / 89310`.
- Reverted-source rebuild:
  `DOCS_MX/scripts/results/perf_plan1_j17_dim16_reverted.csv` =
  `4368 / 13768 / 89310`.
- The identical result shows the store command queue depth is not the active
  limiter after J14. Remaining pressure is more likely scale/dependency/order
  related, or hidden behind another queue/tracker not yet exposed by counters.

Correctness:

- `sbt "project gemmini" compile` passed.
- Candidate and reverted `mx_bench` rows passed.
- No full regression was run because the hardware/config candidate produced no
  cycle gain and was reverted.

Synthesis:

- Not run. The candidate was rejected before area because it had no speed gain
  and would likely add queue/state cost.

Stock purity:

- The candidate touched only the MX DIM16 config and was removed.
- Final source remains the banked J14/J09 config; no stock INT8 path changed.

Commands run:

```bash
source /home/nikolap/Research/2026/chipyard/env.sh && sbt "project gemmini" compile
DOCS_MX/scripts/run_perf.sh --dims 32 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j17_dim32.csv
DOCS_MX/scripts/run_perf.sh --build-sims --dims 16 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j17_dim16_stq8.csv
DOCS_MX/scripts/run_perf.sh --build-sims --dims 16 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j17_dim16_reverted.csv
```

Notes:

- All long simulations, generated headers, logs, and scratch stayed under
  `sims/verilator/gemmini`.
- No `/tmp` Verilator/Vivado campaign directory was used.
- J17 is rejected and reverted. J18 should not retry store queue depth unless
  new counters show actual StoreController queue backpressure.

Online research recorded for J17:

- https://arxiv.org/abs/2603.19057 - MatrixFlow emphasizes synchronized data
  streaming and overlapping movement with array execution. The J17 takeaway is
  that output-side buffering is a valid hypothesis only if it improves measured
  overlap.
- https://arxiv.org/abs/2310.03161 - AccelSync focuses on accelerator
  synchronization and dependency management. The J17 takeaway is that deeper
  queues do not help if the dependency/credit point is elsewhere.
- https://arxiv.org/abs/1911.09925 - Gemmini exposes queue depths and
  generator parameters as tunable design points. The J17 takeaway is that such
  knobs need end-to-end perf evidence before accepting area cost.
- https://arxiv.org/abs/2212.03034 - TVM/Gemmini work shows that schedule,
  queueing, and hardware parameters interact. The J17 takeaway is that a
  no-change queue-depth result points the next iteration back toward
  schedule/dependency diagnostics.

### J18 Detail

Starting point:

- Banked baseline entering J18 remained:
  DIM32 MX `4229 / 9689 / 37919`, DIM16 MX `4368 / 13768 / 89310`.
- Fresh DIM16 debug used counter set 2 plus geometry printing:
  `DOCS_MX/scripts/results/perf_plan1_j18_dim16_debug.csv` =
  `4366 / 13538 / 88797`.
- The corresponding counters showed `st_pool_full=0` for all three shapes:
  `64^3`, `128^3`, and `256^3`.
- Since J14 fixed performance by increasing DIM16 ST RS from 8 to 16, and J18
  showed no remaining store-pool fullness, the iteration tested whether 16 was
  over-provisioned for area.

Code inspected:

- `ReservationStation.scala`, including the per-type entry vectors and the
  `assert(isPow2(reservation_station_entries_st))` constraint.
- `Configs.scala`, including the banked DIM16 MX overrides.
- `StoreController.scala`, because J17 had already ruled out the store command
  queue as the next limiter.
- J18 debug/perf/build logs.

Candidate note:

- Classification: hardware/config-only DIM16 area experiment.
- Hypothesis: reducing DIM16 `reservation_station_entries_st` from 16 to 12
  might preserve cycles while reducing FF/LUT area versus J14.
- Expected DIM32 effect: none, because the change touched only
  `mxint8DIM16Config`; DIM32 was still measured first.
- Expected DIM16 effect: intended cycle-neutral if 12 entries were enough to
  avoid the old ST pool pressure.
- Expected area effect: positive if legal and cycle-neutral, because four fewer
  store RS entries would reduce queue/dependency state.
- Rollback: restore DIM16 `reservation_station_entries_st = 16`.

Implementation and failure:

- Temporarily set DIM16 `reservation_station_entries_st = 12`.
- `sbt "project gemmini" compile` passed because it does not elaborate the full
  Chipyard config.
- DIM32 performance gate passed exactly:
  `DOCS_MX/scripts/results/perf_plan1_j18_dim32.csv` =
  `4229 / 9689 / 37919`.
- DIM16 simulator build failed during Chisel elaboration before any candidate
  cycles were produced:
  `ReservationStation.scala:126` asserts
  `isPow2(reservation_station_entries_st)`.
- Therefore `12` is not a legal design point in the current implementation.
  The only legal smaller value is `8`, and J14 already showed ST RS 8 was
  slower than ST RS 16.

Reverted-source evidence:

- Restored DIM16 `reservation_station_entries_st = 16`.
- Recompiled the Gemmini project successfully.
- Rebuilt/ran the restored DIM16 simulator:
  `DOCS_MX/scripts/results/perf_plan1_j18_dim16_reverted.csv` =
  `4368 / 13768 / 89310`, with `mx_bench: PASS`.

Correctness:

- Candidate did not elaborate, so there was no candidate correctness run.
- Reverted source compiled and the restored `mx_bench` rows passed.
- No full regression was run because no candidate was accepted.

Synthesis:

- Not run. `reservation_station_entries_st = 12` is illegal and produced no
  valid hardware candidate.

Stock purity:

- The temporary change touched only `mxint8DIM16Config` and was removed.
- Final source remains the banked J14/J09 config; no stock INT8 path changed.

Commands run:

```bash
MX_BENCH_COUNTER_SET=2 MX_BENCH_PRINT_GEOM=1 DOCS_MX/scripts/run_perf.sh --dims 16 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j18_dim16_debug.csv
source /home/nikolap/Research/2026/chipyard/env.sh && sbt "project gemmini" compile
DOCS_MX/scripts/run_perf.sh --dims 32 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j18_dim32.csv
DOCS_MX/scripts/run_perf.sh --build-sims --dims 16 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j18_dim16_st12.csv
source /home/nikolap/Research/2026/chipyard/env.sh && sbt "project gemmini" compile
DOCS_MX/scripts/run_perf.sh --dims 16 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j18_dim16_reverted.csv
```

Notes:

- All long simulations, generated headers, logs, and scratch stayed under
  `sims/verilator/gemmini`.
- No `/tmp` Verilator/Vivado campaign directory was used.
- J18 is rejected and reverted. Do not retry non-power-of-two RS sizes unless
  the ReservationStation implementation itself is intentionally redesigned.

Online research recorded for J18:

- https://arxiv.org/abs/1911.09925 - The Gemmini paper motivates generator
  parameter DSE, including queue-like microarchitectural parameters. The J18
  takeaway is that right-sizing a banked queue is a valid area experiment, but
  the implementation's legal parameter set must be respected.
- https://arxiv.org/abs/2212.03034 - TVM/Gemmini work shows that schedule and
  hardware parameters interact. The J18 takeaway is that a parameter reduction
  can only be accepted after cycle gates, and here it failed before DIM16
  measurement.
- https://arxiv.org/abs/2603.19057 - MatrixFlow emphasizes dataflow/resource
  balance for efficient systolic execution. The J18 takeaway is that removing
  unused buffering is desirable only when the legal hardware point exists.
- https://docs.amd.com/r/en-US/ug901-vivado-synthesis - AMD Vivado synthesis
  documentation motivates checking area after accepted hardware changes. The
  J18 takeaway is no synthesis is meaningful for an illegal elaboration point.

### J19 Detail

Starting point:

- Banked baseline entering J19 remained:
  DIM32 MX `4229 / 9689 / 37919`, DIM16 MX `4368 / 13768 / 89310`.
- J18 debug showed DIM16 `st_pool_full=0`, so the J14 store-RS change had
  removed the earlier store-pool bottleneck.
- J15 instruction-count diagnostics showed DIM16 `256^3` retired only about
  `3738` host instructions over about `89k` measured cycles, so a custom MX
  instruction remains possible but is not the first-order explanation for the
  current gap.
- J11 had already tried removing the pair-J group fence before the J14 ST RS
  fix, but the tradeoff was poor. J19 retested the same synchronization idea
  after output-side pressure had been reduced.

Code inspected:

- `gemmini.h` around the DIM16 pair-J software schedule, especially the
  `pair_group_barrier` and `do_fence` logic.
- `MXScaleLoadController.scala`, because the scale SRAM ping-pong credit
  interlock should prevent reuse of a live scale half.
- `ExecuteController.scala`, including loop-drain behavior.
- `ReservationStation.scala`, because scratchpad dependencies and issue
  backpressure decide whether a software fence is truly needed.
- J18/J19 performance, diagnostic, and regression logs.

Candidate note:

- Classification: software-only scheduling/synchronization.
- Hypothesis: after the J14 DIM16 ST RS increase, the second pair-J group fence
  may be redundant. Removing it should let the dependency-controlled hardware
  overlap the two pair-J groups more aggressively.
- Expected DIM32 effect: none, because DIM32 does not enter the DIM16 pair-J
  branch; still, DIM32 was measured first because the header path is shared.
- Expected DIM16 effect: improve `128^3` and/or `256^3` by reducing broad
  software serialization between pair-J groups.
- Expected area effect: none.
- Correctness risk: moderate. If hardware dependencies do not fully cover the
  removed fence, a later B payload or B-scale load could clobber data still
  needed by an in-flight loop.
- Rollback: restore `pair_group_barrier` and include it in the pair-J
  `do_fence` condition.

Implementation:

- Removed the pair-group-only fence from the pair-J path:
  `do_fence = k_chunked || first_chunk || !geom_same`.
- Kept all other fences intact: first chunk, K-chunked shapes, and geometry
  changes still fence.
- No Scala RTL/config file changed.

Performance evidence:

- DIM32 gate passed exactly:
  `DOCS_MX/scripts/results/perf_plan1_j19_dim32.csv` =
  `4229 / 9689 / 37919`.
- DIM16 gate:
  `DOCS_MX/scripts/results/perf_plan1_j19_dim16_no_pair_barrier.csv` =
  `4438 / 13724 / 88379`.
- Versus the J14/J18 banked DIM16 baseline, J19 changes cycles by:
  `+70 / -44 / -931` for `64^3 / 128^3 / 256^3`.
- Interpretation: the candidate is useful for the campaign target because it
  improves both large DIM16 problem shapes and keeps DIM32 unchanged. The
  `64^3` regression is documented as an accepted small-shape tradeoff for now.

Correctness:

- DIM16 regression:
  `DOCS_MX/scripts/run_regression.sh --dims=16 --skip-stock-elab --timeout-cycles=300000000`
  passed `mxint8_matmul_dim16`, `mxint8_matmul_nphase`, and
  `mxint8_multitile`.
- DIM32 regression:
  `DOCS_MX/scripts/run_regression.sh --dims=32 --skip-stock-elab --timeout-cycles=300000000`
  passed `mxint8_golden`, `mxint8_matmul_dim32`, `mxint8_corner`,
  `mxint8_matmul_partial`, `mxint8_tiled`, `mxint8_btb`,
  `mxint8_multitile`, and `mxint8_matmul_nphase`.
- `mx_bench` rows for DIM32 and DIM16 all reported `PASS`.

Synthesis:

- Not run for J19. This is a software-only scheduling change, so area remains
  the banked J14 hardware area:
  `117643 LUT / 63162 FF / 167.5 BRAM36 / 239 DSP`.

Stock purity:

- The candidate touched only MX software scheduling in `gemmini.h`.
- No stock INT8 Scala config or generated stock hardware path changed.
- Pure stock INT8 purity remains governed by the existing `MX_ENABLED 0` stock
  headers and stock configs.

Commands run:

```bash
DOCS_MX/scripts/run_perf.sh --dims 32 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j19_dim32.csv
DOCS_MX/scripts/run_perf.sh --dims 16 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j19_dim16_no_pair_barrier.csv
DOCS_MX/scripts/run_regression.sh --dims=16 --skip-stock-elab --timeout-cycles=300000000
DOCS_MX/scripts/run_regression.sh --dims=32 --skip-stock-elab --timeout-cycles=300000000
```

Notes:

- All long simulations, generated headers, logs, and scratch stayed under
  `sims/verilator/gemmini`.
- No `/tmp` Verilator/Vivado campaign directory was used.
- J19 is banked. J20 should perform final selection/verification from the
  best banked implementation rather than starting another risky broad change
  unless fresh measurements justify it.

Online research recorded for J19:

- https://arxiv.org/abs/2110.01752 - RASA focuses on scheduling systolic array
  work to improve utilization while respecting limited local storage. The J19
  takeaway is that reducing conservative serialization is reasonable only when
  local-storage dependencies still protect live data.
- https://arxiv.org/abs/1911.09925 - The Gemmini paper frames Gemmini as a
  hardware/software generator where software scheduling and generated queues
  interact. The J19 takeaway is to retest software synchronization after the
  J14 hardware/config queue fix.
- https://arxiv.org/abs/2212.03034 - TVM/Gemmini scheduling work shows that
  loop ordering and overlap can materially change Gemmini utilization. The J19
  takeaway is to prefer the smallest schedule change that reduces measured
  serialization and then verify correctness broadly.
- https://arxiv.org/abs/2603.19057 - MatrixFlow emphasizes mapping and dataflow
  choices for data streaming through systolic arrays. The J19 takeaway is that
  broad barriers should be removed only when downstream dependency mechanisms
  still preserve the stream order needed by the array.

### J20 Detail

Starting point:

- Banked baseline entering J20 was the J19 candidate:
  DIM32 MX `4229 / 9689 / 37919`, DIM16 MX `4438 / 13724 / 88379`.
- J19 improved both DIM16 large problem shapes versus J14/J18, but regressed
  the small `64^3` shape by 70 cycles versus the J14/J18 baseline.
- J20 tested one final narrow software candidate to recover that small-shape
  regression without touching hardware/config or DIM32 behavior.

Code inspected:

- `gemmini.h` pair-J schedule and `do_fence` logic.
- J19 and J20 performance logs.
- Prior dependency notes for `MXScaleLoadController.scala`,
  `ExecuteController.scala`, and `ReservationStation.scala`.

Candidate note:

- Classification: software-only shape-gated scheduling.
- Hypothesis: the relaxed J19 pair-group barrier is beneficial for larger
  DIM16 shapes, but the small `64^3` shape may prefer the older explicit
  pair-group fence because it has less work to amortize extra overlap.
- Expected DIM32 effect: none, because DIM32 does not enter the DIM16 pair-J
  branch. DIM32 was still measured first.
- Expected DIM16 effect: recover some or all of the `64^3` regression while
  preserving most of the `128^3`/`256^3` J19 improvement.
- Expected area effect: none.
- Correctness risk: low-to-moderate. The candidate reintroduced a conservative
  fence for small shapes and kept J19 behavior for large shapes.
- Rollback: remove the temporary shape gate and return to J19's
  `do_fence = k_chunked || first_chunk || !geom_same`.

Implementation and result:

- Temporarily added:
  `relax_pair_group_barrier = (M >= 128) && (N >= 128) && (K >= 128)`.
- Reintroduced `pair_group_barrier` only when that shape gate was false.
- DIM32 gate passed exactly:
  `DOCS_MX/scripts/results/perf_plan1_j20_dim32.csv` =
  `4229 / 9689 / 37919`.
- DIM16 candidate:
  `DOCS_MX/scripts/results/perf_plan1_j20_dim16_shape_gated.csv` =
  `4426 / 13866 / 88555`.
- Versus J19, the candidate changes cycles by:
  `-12 / +142 / +176` for `64^3 / 128^3 / 256^3`.
- Versus the J14/J18 baseline, it remains worse on `64^3` and `128^3`, and
  gives a smaller `256^3` win than J19.

Decision:

- Rejected and reverted. The shape gate recovered too little of the `64^3`
  regression and sacrificed the target large-shape performance.
- Final selection remains J19 software scheduling on top of the banked J14/J09
  hardware/config changes.

Correctness:

- J20 candidate `mx_bench` rows passed, but no full regression was run because
  the candidate was rejected after performance.
- After revert, final source returned to the already-regressed J19 candidate.
  J19 had passed both DIM16 and DIM32 regression suites.

Synthesis:

- Not run. J20 was a rejected software-only trial.
- Final hardware area remains the banked J14 synthesis point:
  `117643 LUT / 63162 FF / 167.5 BRAM36 / 239 DSP`.

Stock purity:

- The temporary candidate touched only MX software scheduling and was removed.
- Final source keeps stock INT8 purity unchanged; no stock config generates MX
  hardware.

Commands run:

```bash
DOCS_MX/scripts/run_perf.sh --dims 32 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j20_dim32.csv
DOCS_MX/scripts/run_perf.sh --dims 16 --impl mx --csv DOCS_MX/scripts/results/perf_plan1_j20_dim16_shape_gated.csv
```

Notes:

- All long simulations, generated headers, logs, and scratch stayed under
  `sims/verilator/gemmini`.
- No `/tmp` Verilator/Vivado campaign directory was used.
- The final source was checked after revert and the pair-J path again contains:
  `do_fence = k_chunked || first_chunk || !geom_same`.

Online research recorded for J20:

- https://arxiv.org/abs/2110.01752 - RASA shows that overlap and local-storage
  awareness can hide systolic-array overhead. The J20 takeaway is that overlap
  policy should be shape-sensitive when small shapes have less work to amortize
  synchronization changes.
- https://arxiv.org/abs/2212.03034 - TVM/Gemmini scheduling work demonstrates
  that Gemmini schedules must be tuned on measured hardware behavior. The J20
  takeaway is that a plausible shape gate must still beat the measured J19
  schedule across the target shapes.
- https://arxiv.org/abs/1911.09925 - The Gemmini paper emphasizes generated
  hardware/software co-design. The J20 takeaway is that a software-only final
  candidate is attractive only if it preserves the hardware result and improves
  measured cycles.
- https://arxiv.org/abs/2603.29913 - SISA discusses small/skewed GEMM shapes
  and systolic-array utilization limits. The J20 takeaway is that small-shape
  behavior can differ from large-shape behavior, but this candidate did not
  produce a good enough measured tradeoff.

## 8. Correctness And Stock-Purity Gates

Minimum focused correctness:

- Run the smallest MX regression test that covers the changed behavior.
- Run DIM32 regression if any shared MX path changed.
- Run DIM16 regression for every banked candidate.

Minimum final correctness:

```bash
DOCS_MX/scripts/run_regression.sh --dims=32 --skip-stock-elab --timeout-cycles=300000000
DOCS_MX/scripts/run_regression.sh --dims=16 --skip-stock-elab --timeout-cycles=300000000
```

For final hardware/config candidates, also run:

```bash
DOCS_MX/scripts/run_regression.sh --build-sims --dims=32,16 --timeout-cycles=300000000
```

Stock-purity audit is required if any Scala RTL/config/shared-path code changes:

- Regenerate `GemminiStockDIM32RocketConfig` and `GemminiStockDIM16RocketConfig`.
- Confirm stock headers contain `MX_ENABLED 0`.
- Search generated stock collateral for MX scale modules, MX scale SRAM, MX scale
  loads, MX datapath logic, and MX-named generated files.
- Record results in the iteration log.

If packer or golden semantics change, also run:

```bash
cd generators/gemmini/software/gemmini-rocc-tests
python3 tools/mxint8_external_diff.py --sweep --json-out build/diff_sweep_plan1_jXX.json
```

## 9. Final Selection After J20

Selected final implementation:

- Keep the banked J09 DIM16 scale-SRAM capacity reduction:
  `mx_scale_sp_capacity = CapacityInKilobytes(4)`.
- Keep the banked J14 DIM16 store reservation station increase:
  `reservation_station_entries_st = 16`.
- Keep the banked J19 pair-J software schedule without the second pair-group
  barrier:
  `do_fence = k_chunked || first_chunk || !geom_same`.
- Keep the earlier banked software/diagnostic fixes from J01, J03, J05, J08,
  and J13.
- Reject and leave reverted all no-gain or worse candidates from J02, J04,
  J06, J10, J11, J12, J15, J16, J17, J18, and J20.

Final performance selection:

| DIM | Impl | Baseline cycles | Final cycles | Change |
|---|---|---:|---:|---:|
| 32 | MX `64^3` | 4,317 | 4,229 | -88 |
| 32 | MX `128^3` | 9,855 | 9,689 | -166 |
| 32 | MX `256^3` | 37,921 | 37,919 | -2 |
| 16 | MX `64^3` | 4,390 | 4,438 | +48 |
| 16 | MX `128^3` | 14,349 | 13,724 | -625 |
| 16 | MX `256^3` | 101,565 | 88,379 | -13,186 |

Final DIM16 versus pure stock INT8 baseline:

| Shape | Stock DIM16 | Final MX DIM16 | MX minus stock |
|---|---:|---:|---:|
| `64^3` | 6,088 | 4,438 | -1,650 |
| `128^3` | 12,900 | 13,724 | +824 |
| `256^3` | 78,453 | 88,379 | +9,926 |

Final DIM16 instruction-count diagnostic:

- Command:
  `MX_BENCH_COUNTER_SET=3 DOCS_MX/scripts/run_perf.sh --dims 16 --impl both --csv DOCS_MX/scripts/results/perf_plan1_final_dim16_inst.csv --force`.
- The CSV records the diagnostic `MXBENCH` cycle rows. The retired-instruction
  counts are printed as `MXINST` lines in the corresponding simulator logs:
  `sims/verilator/output/chipyard.harness.TestHarness.GemminiStockDIM16RocketConfig/mx_bench-baremetal.log`
  and
  `sims/verilator/output/chipyard.harness.TestHarness.GemminiMXINT8DIM16RocketConfig/mx_bench-baremetal.log`.

| Shape | Stock instret | MX instret | MX/stock instret | Stock cycles | MX cycles | MX/stock cycles |
|---|---:|---:|---:|---:|---:|---:|
| `64^3` | 351 | 473 | 1.348x | 5,153 | 4,400 | 0.854x |
| `128^3` | 832 | 1,394 | 1.675x | 11,649 | 13,738 | 1.179x |
| `256^3` | 2,551 | 3,710 | 1.454x | 76,185 | 88,206 | 1.158x |

- Interpretation: final MX DIM16 retires about `1.35x` to `1.68x` as many
  host instructions as stock on these square shapes. This is real overhead, but
  it is still not enough to make a custom MX instruction the first lever: at
  `256^3`, only 3,710 retired host instructions cover 88,206 measured cycles,
  so accelerator-side scheduling/backpressure remains the larger issue.

Final area selection:

- Final hardware area is the J14 synthesis point because J19 is software-only:
  `117643 LUT / 63162 FF / 167.5 BRAM36 / 239 DSP`.
- Versus I20 MX DIM16 (`120825 LUT / 61005 FF / 168 BRAM36 / 239 DSP`):
  `-3182 LUT / +2157 FF / -0.5 BRAM36 / 0 DSP`.
- Versus stock DIM16 (`92992 LUT / 53058 FF / 160 BRAM36 / 236 DSP`):
  `+24651 LUT / +10104 FF / +7.5 BRAM36 / +3 DSP`.
- J14 OOC timing record:
  WNS `-39.464`, Fmax estimate `20.2167`.

Final correctness and purity:

- Final selected J19 source passed DIM16 regression:
  `mxint8_matmul_dim16`, `mxint8_matmul_nphase`, `mxint8_multitile`.
- Final selected J19 source passed DIM32 regression:
  `mxint8_golden`, `mxint8_matmul_dim32`, `mxint8_corner`,
  `mxint8_matmul_partial`, `mxint8_tiled`, `mxint8_btb`,
  `mxint8_multitile`, `mxint8_matmul_nphase`.
- Stock INT8 purity remains preserved. Stock configs must keep `MX_ENABLED 0`
  and must not generate MX scale SRAM, MX scale-load controller, or MX datapath
  logic.

Publication-facing summary:

- The second campaign reduced the main DIM16 `256^3` gap from 23,112 cycles
  slower than stock to 9,926 cycles slower than stock.
- DIM16 `128^3` improved from 1,449 cycles slower than stock to 824 cycles
  slower than stock.
- DIM32 MX remained at or better than the I20 target for all measured shapes.
- The best final result is a hardware/software co-optimization: a smaller
  DIM16 MX scale SRAM, deeper DIM16 store reservation station, and a less
  conservative pair-J software barrier policy.

## 10. Initial Agent Checklist For J01

Before starting `J01`, the agent must:

1. Read this file and `PLAN.md` I20.
2. Confirm no local uncommitted changes will be accidentally reverted.
3. Record current hashes.
4. Run or inspect the latest I20 DIM32/DIM16 perf and synthesis CSVs.
5. Run a DIM16 diagnostic counter pass unless an equivalent fresh debug CSV already
   exists for the exact current tree.
6. Inspect the code implicated by the DIM16 counters.
7. Research the specific measured bottleneck online.
8. Only then choose the first candidate modification.
