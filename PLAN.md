# MXINT8 Gemmini Optimization Plan

Status snapshot: 2026-07-03.

This file is the execution plan for optimizing the MXINT8 Gemmini variant against a
real stock INT8 Gemmini baseline. The goal is publication-quality evidence: MXINT8
should be as close as possible to stock INT8 in cycles, latency, and area, while
remaining bit-exact against the golden model.

The first task is not optimization. The first task is proving that the stock baseline is
really stock: when an INT8 stock variant is elaborated, no MX hardware, MX sidecar SRAM,
MX scale-load controller, MX datapath, or MX-specific control logic should be generated.

## 1. Goal And Non-Negotiable Rules

Optimize MXINT8 Gemmini so that:

- MXINT8 performance is compared against pure stock INT8 Gemmini, not against a stock
  config polluted by MX hardware.
- The comparison uses the same DIM, same matrix shape, same payload distribution, same
  output mode, and the same timed region.
- MX functionality remains bit-exact against `mxint8_golden.h`.
- Stock INT8 functionality remains unchanged and must not elaborate MX hardware.
- All accepted changes are measured, documented, and either banked or reverted.
- The first pass contains 20 full optimization iterations. After iteration 20, choose the
  best implementation and finish with a final regression/performance/synthesis report.

Current canonical starting point from `DOCS_MX/WORK_DONE.md`:

- Latest banked DIM32 MX result: 45,483 cycles on 256^3.
- In-harness stock DIM32 result: 38,632 cycles on 256^3.
- Current gap: about 1.18x stock for that shape.

The checked-in `DOCS_MX/scripts/results/perf.csv` predates the July 3 fixes and must not
be treated as canonical until regenerated.

## 2. Required Context To Read First

Before editing code, the agent must read:

- `AGENT.md`
- `mxint8_policy.md`
- all Markdown files in `DOCS_MX/`
- `DOCS_MX/mxint8_gemmini_plan_updated.pdf`
- this `PLAN.md`

Important current code anchors:

- Stock/MX configs: `generators/gemmini/src/main/scala/gemmini/Configs.scala`
- Chipyard top configs: `generators/gemmini/chipyard/GemminiConfigs.scala`
- Main controller: `generators/gemmini/src/main/scala/gemmini/Controller.scala`
- Reservation station: `generators/gemmini/src/main/scala/gemmini/ReservationStation.scala`
- MX scale sidecar: `MXScaleSRAM.scala`, `MXScaleLoadController.scala`
- MX block scaling and drain walk: `ExecuteController.scala`
- Loop unroller: `LoopMatmul.scala`
- Software API/tiler: `software/gemmini-rocc-tests/include/gemmini.h`
- Benchmark: `software/gemmini-rocc-tests/bareMetalC/mx_bench.c`

## 3. Phase 0: Prove Or Fix Pure Stock INT8

This phase blocks all optimization work. Do not compare MXINT8 against stock until this
phase passes.

### 3.1 Define The Baselines

Use three names consistently:

- **Pure stock INT8**: stock Gemmini generated with no MX hardware or MX-specific RTL.
- **MXINT8**: Gemmini with `mx_enabled=true`.
- **Pristine reference**: upstream/pre-MX Gemmini, used only to sanity-check that pure
  stock INT8 has not drifted in area/timing or behavior.

The final paper/evaluation must compare MXINT8 against **pure stock INT8**.

### 3.2 Audit Current Stock Configs

Check these configs first:

- `GemminiStockDIM32RocketConfig`
- `GemminiStockDIM16RocketConfig`

They currently derive from:

- `stockDIM32Config`
- `stockDIM16Config`

Confirm their generated headers contain:

```text
#define MX_ENABLED 0
```

Then elaborate stock RTL and search generated sources.

Required commands, from repo root after environment setup:

```bash
source /home/nikolap/Research/2026/chipyard/env.sh
export PATH="$CONDA_PREFIX/bin:$PATH"
export FIRTOOL="$HOME/.cache/llvm-firtool/1.62.1/bin/firtool"

make -C sims/verilator CONFIG=GemminiStockDIM32RocketConfig FIRTOOL_BIN="$FIRTOOL" verilog
make -C sims/verilator CONFIG=GemminiStockDIM16RocketConfig FIRTOOL_BIN="$FIRTOOL" verilog
```

For each generated stock directory, run grep checks for MX artifacts:

```bash
rg -n "MXScale|MXSCALE|mx_scale|mxint|MXINT|CONFIG_MXINT8|LOAD_MX_SCALE|LOOP_WS_MXINT8|is_mx_scale" \
  sims/verilator/generated-src/chipyard.harness.TestHarness.GemminiStockDIM32RocketConfig

rg -n "MXScale|MXSCALE|mx_scale|mxint|MXINT|CONFIG_MXINT8|LOAD_MX_SCALE|LOOP_WS_MXINT8|is_mx_scale" \
  sims/verilator/generated-src/chipyard.harness.TestHarness.GemminiStockDIM16RocketConfig
```

Pass criterion:

- No generated MX modules.
- No generated MX scale SRAM.
- No generated MX scale DMA path.
- No generated MX datapath, drain-walk, or block-scale logic.
- No generated MX-specific reservation-station state.
- No stock benchmark includes or calls MX functions when built with `MX_ENABLED=0`.

If the grep finds only dead strings in comments, debug metadata, or source locators, record
that separately. The hard requirement is no synthesized MX hardware or control logic.

### 3.3 If Stock Is Not Pure, Fix It First

If stock generated RTL contains MX hardware, refactor before any optimization.

Required direction:

- In `Controller.scala`, instantiate `MXScaleLoadController` only when `mx_enabled`.
  When disabled, no MX load-controller module should exist in emitted RTL.
- Keep `MXScaleSRAM` guarded by `if (mx_enabled)`; verify it does not elaborate for stock.
- In `ReservationStation.scala`, ensure MX scale-load decode, `is_mx_scale` state, and
  MX dependency logic do not elaborate for stock configs. If necessary, parameterize the
  reservation-station entry bundle by `mx_enabled` or drive all MX-only logic through Scala
  `if (mx_enabled)` branches so FIRRTL removes it.
- In `Controller.scala`, ensure MX config decode and scale-load routing do not elaborate
  into stock RTL.
- In `LoopMatmul.scala` and `ExecuteController.scala`, keep all MX path logic under Scala
  `if (mx_enabled)` guards. Verify by generated RTL grep, not by reading Scala only.
- In `CounterFile.scala` and `gemmini_counter.h`, MX diagnostic event names may exist in
  software/header constants, but stock RTL must not generate MX event producers unless the
  signal is also meaningful for pure stock.

After the refactor:

1. `sbt "project gemmini" compile`
2. Stock DIM32/DIM16 Verilog elaboration.
3. MX DIM32/DIM16 Verilog elaboration.
4. Generated stock grep audit.
5. Focused stock baremetal smoke test.
6. Focused MX regression.

Only when the audit passes can the plan proceed.

### 3.4 Pristine Reference Sanity Check

Use local git history first to identify a pre-MX Gemmini commit or tag. If local history is
insufficient, fetch upstream only with explicit approval.

Create a temporary worktree under `sims/verilator/gemmini/pristine-ref` or another
workspace-local scratch directory for the pristine reference. Do not use `/tmp` for
simulation, synthesis, or reference-checkout artifacts, and do not reset or modify the
active checkout.

Compare pure stock INT8 against pristine reference for:

- DIM32 and DIM16 generated module inventory.
- `mx_bench` stock-path cycles for 64^3, 128^3, 256^3.
- Vivado OOC LUT/FF/BRAM/DSP/Fmax for DIM32 and DIM16.

Acceptance:

- Pure stock INT8 may differ only by intentional non-MX config choices needed for fair
  same-DIM comparison.
- Any residual difference from pristine reference must be documented in this file before
  publication claims use it.

## 4. Measurement Discipline

Always measure DIM32 first. DIM16 performance runs are conditional.

Per candidate:

1. Run focused correctness.
2. Run DIM32 stock-vs-MX performance.
3. If DIM32 MX is correct and improves or stays the same versus the previous banked MX
   implementation, run DIM16 stock-vs-MX performance.
4. If DIM32 MX regresses, skip DIM16 performance, inspect counters, and revise or revert.
5. Run Vivado synthesis only for the baseline, accepted hardware changes, and final
   candidate implementations.

Once a pure-stock baseline has been regenerated for a stable benchmark harness, stock rows do
not need to be rerun for every MX-only candidate. Reuse the cached stock rows unless one of
these changed: `mx_bench.c` timed region, input generation, stock config/header, compiler/sim
flags, stock Gemmini RTL, or the common software path used by stock. For MX-only tiler/hardware
experiments, run the candidate with `--impl mx` and compare against the cached stock baseline.

"Improves or stays the same" means:

- Primary shape 256^3 is no worse than the previous banked MX cycles.
- 64^3 and 128^3 do not show a large unexplained regression.
- Correctness remains `PASS`.

Use exact commit hashes in every recorded measurement:

```bash
git rev-parse --short HEAD
git -C generators/gemmini rev-parse --short HEAD
git -C generators/gemmini/software/gemmini-rocc-tests rev-parse --short HEAD
```

If the rocc-tests directory is not a separate git repository, record the Gemmini submodule
hash and the top-level Chipyard hash only.

## 5. Standard Commands

Environment:

```bash
cd /home/nikolap/Research/2026/chipyard
source /home/nikolap/Research/2026/chipyard/env.sh
export PATH="$CONDA_PREFIX/bin:$PATH"
export FIRTOOL="$HOME/.cache/llvm-firtool/1.62.1/bin/firtool"
```

Fast compile:

```bash
sbt "project gemmini" compile
```

Full regression:

```bash
DOCS_MX/scripts/run_regression.sh --build-sims --dims=32,16
```

DIM32 performance first:

```bash
DOCS_MX/scripts/run_perf.sh --build-sims --dims 32
```

MX-only DIM32 candidate after stock baseline is stable:

```bash
DOCS_MX/scripts/run_perf.sh --dims 32 --impl mx --csv DOCS_MX/scripts/results/perf_iXX.csv
```

Conditional DIM16 performance:

```bash
DOCS_MX/scripts/run_perf.sh --dims 16
```

MX-only conditional DIM16 candidate after stock baseline is stable:

```bash
DOCS_MX/scripts/run_perf.sh --dims 16 --impl mx --csv DOCS_MX/scripts/results/perf_iXX.csv
```

Vivado OOC synthesis, when required:

```bash
source ~/Programs/Xilinx/Vivado/2022.2/settings64.sh
DOCS_MX/scripts/run_synth.sh
```

Run artifacts:

- By default, MX scripts write logs and temporary header backups under
  `sims/verilator/gemmini`.
- Override that location only with `MX_RUN_DIR=/path/to/workspace-local/run-dir`.
- The scripts export `TMPDIR` to the same workspace-local run tree. Do not use `/tmp`
  for long Verilator or Vivado campaigns, because it has limited free space on this host.

Generated report from fresh CSVs only:

```bash
DOCS_MX/scripts/make_report.py
```

Use `LOADMEM=1` for large Verilator baremetal runs. Do not trust stale
`DOCS_MX/scripts/results/*.csv`; delete or archive rows for configs that are rerun.

## 6. Metrics To Record

For every accepted or rejected candidate, record:

- correctness result;
- cycles for stock and MX;
- MX/stock cycle ratio;
- useful MACs/cycle;
- ideal cycles and utilization;
- `MXCOUNT` counters from `mx_bench`;
- `MXCPU` counters for MX host issue/repack/fence/config/scale costs;
- whether DIM16 was run or skipped;
- if skipped, the DIM32 reason;
- if hardware changed, Vivado LUT/FF/BRAM36/DSP/WNS/Fmax estimate;
- whether stock generated RTL stayed MX-free.

Use a table like this in the iteration log:

| Iter | Hash | Change | Type | DIM32 256^3 MX | DIM32 stock | Ratio | DIM16 run? | Correct? | Synth? | Decision |
|---|---|---|---|---:|---:|---:|---|---|---|---|
| 00 | top db5df054d / gemmini 359a0860 / rocc-tests 5388da5 | Pure-stock audit + fresh DIM32 baseline | audit + baseline | 48,827 | 41,022 | 1.19x | skipped: MX 256^3 regressed versus prior banked 45,483 | PASS on sampled perf checks; stock RTL audit pass | not run | reject current perf state; inspect MX issue/scale overhead |

## 7. Optimization Hypotheses

Investigate in this order unless measurements clearly indicate otherwise.

### 7.1 Software And Scheduling Parity

Prefer software/scheduling fixes before hardware:

- Compare `tiled_matmul_mxint8_impl` against stock `tiled_matmul_auto` / stock outer
  tiler for chunk order, operand reuse, fences, and loop issue patterns.
- Confirm resident A/B reuse matches stock behavior whenever scratchpad capacity allows it.
- Confirm B-scale pretiling is outside the timed region only when the equivalent stock
  setup cost is also outside the timed region.
- Tune `mxint8_compute_geom` to reduce chunk count without violating scale SRAM,
  accumulator, or scratchpad bounds.
- Reduce unnecessary `CONFIG_MXINT8` and scale-stride config commands.
- Verify scale mvins are overlapped with useful compute; if not, identify whether the
  blocker is software issue order, reservation-station dependencies, or hardware capacity.

### 7.2 Measurement Improvements

If counters cannot explain a regression or residual gap, improve measurement before
changing datapath logic:

- Add or repurpose counters for scale-load active/solo cycles, RS blocked-on-scale cycles,
  load queue fullness, loop unroller active cycles, mesh busy cycles, and scratchpad
  A/B wait cycles.
- Keep counter changes inert for pure stock, or guard them so stock RTL remains MX-free.
- Extend `mx_bench` only with stable, machine-readable lines.

### 7.3 Hardware Candidates

Only attempt hardware changes after counters justify them:

- Deeper scale-SRAM ping-pong only if two halves are proven to throttle a real workload.
- Relax scale-load dependencies only if correctness can be maintained by an explicit
  ready/valid or credit protocol.
- Improve scale-load overlap if `MX_SCALE_DMA_SOLO` or blocked-on-scale counters show
  scale DMA on the critical path.
- Consider a load-scales-once variant only if it beats the current banked result by a
  meaningful margin and the area/timing cost is acceptable. A prior Path B experiment
  improved 256^3 only modestly and was reverted, so this is low priority.
- Do not widen accumulators or raw buffers unless saturation data proves it is needed
  for correctness or publishability.

## 8. Correctness Gates

Minimum gates before banking:

- Focused test for changed behavior.
- `DOCS_MX/scripts/run_regression.sh --dims=32,16`
- Full regression with rebuilt sims before finalizing a banked hardware change:
  `DOCS_MX/scripts/run_regression.sh --build-sims --dims=32,16`
- Stock elaboration grep remains MX-free.

If packer or golden semantics change, also run:

```bash
cd generators/gemmini/software/gemmini-rocc-tests
python3 tools/mxint8_external_diff.py --sweep --json-out build/diff_sweep.json
```

Do not change `mxint8_policy.md` semantics as part of performance optimization unless the
project owner explicitly approves it.

## 9. Twenty-Iteration Campaign

Iteration 0 is the stock-purity and baseline evidence pass. Iterations 1-20 are
optimization passes.

For each iteration:

1. Write the hypothesis in this file before editing.
2. Classify the candidate as software-only, hardware-only, or co-designed.
3. Make the smallest change that tests the hypothesis.
4. Run compile/elaboration as appropriate.
5. Run focused correctness.
6. Run DIM32 perf.
7. Run DIM16 perf only if DIM32 is not worse.
8. Run synthesis only if this is a baseline, accepted hardware change, or finalist.
9. Decide bank or revert.
10. Record the result in the iteration log.

Candidate iteration themes:

- I00: Pure stock audit and fresh baseline.
- I01: Reconcile stock tiler and MX tiler chunk geometry.
- I02: Measure and reduce remaining MX host issue overhead.
- I03: Measure scale-load criticality with current counters.
- I04: Improve B/A reuse parity where current geometry disables reuse.
- I05: Tune `mxint8_compute_geom` for fewer chunks at 256^3.
- I06: Add missing stable counters if I01-I05 leave unexplained stalls.
- I07: Evaluate whether scale mvin stride/config commands can be hoisted or reduced.
- I08: Investigate RS blocked-on-scale behavior after software fixes.
- I09: Try the smallest safe scale-load overlap improvement justified by counters.
- I10: Run synthesis for any accepted hardware path so far.
- I11: Extend benchmark shapes beyond square smoke tests if square results look good.
- I12: Revisit DIM16-specific phase/order overhead only after DIM32 is healthy.
- I13: Evaluate scale SRAM ping-pong depth only if proven capacity-bound.
- I14: Re-check pure stock RTL after accumulated refactors.
- I15: Compare against pristine reference again for area sanity.
- I16: Run a broader perf sweep for accepted candidates.
- I17: Remove failed experiment code and stale diagnostics.
- I18: Final synthesis for top candidates.
- I19: Final full regression and external diff if needed.
- I20: Choose winner, update docs, and freeze evidence.

The themes are allowed to change when measurements demand it, but every change must still
follow the DIM32-first rule.

## 10. Final Winner Criteria

Choose the winner after iteration 20 using:

1. Bit-exact correctness.
2. Pure stock INT8 baseline remains MX-free.
3. Best DIM32 and DIM16 MX/stock cycle ratio.
4. Area/Fmax overhead versus pure stock INT8.
5. Simplicity and publication defensibility.

Final deliverables:

- Updated `PLAN.md` with all iteration logs.
- Fresh `perf.csv` and `synth.csv`, or a note explaining why synthesis could not run.
- Regenerated comparison report from fresh data.
- Updated `DOCS_MX/WORK_DONE.md` and `DOCS_MX/WORK_TODO.md`.
- Clear list of reverted experiments and why they lost.
- Final statement of the exact stock baseline used for cycles and area.

## 11. Iteration Log

Add entries below as work proceeds.

| Iter | Date | Hash | Hypothesis | Change | DIM32 result | DIM16 run | Correctness | Area | Decision |
|---|---|---|---|---|---|---|---|---|---|
| 00 | 2026-07-03 | top db5df054d / gemmini 359a0860 / rocc-tests 5388da5 | Pure stock INT8 must generate no MX hardware before comparisons are trusted, then DIM32 must gate further work. | Guarded MX-only controller, reservation-station, completion, config, and counter logic so `mx_enabled=false` stock configs do not elaborate MX modules or MX state. Regenerated clean stock DIM32/DIM16 RTL and audited generated sources with `rg --no-ignore` plus MX filename search; both stock generated trees were MX-free and headers contain `#define MX_ENABLED 0`. Redirected MX script logs/tmp/header backups from `/tmp` to `sims/verilator/gemmini` and exported `TMPDIR` there. Improved `mx_bench` wall-clock time by removing a redundant post-timed fence, using sampled-cell checks, and preloading static payload/scale arrays with the ELF. | Fresh DIM32: stock 5,095 / 10,445 / 41,022 cycles; MX 4,159 / 10,006 / 48,827 cycles for 64^3 / 128^3 / 256^3. Primary 256^3 ratio is 1.19x stock, and current MX is worse than the prior banked 45,483-cycle MX result. | Skipped by rule because DIM32 256^3 regressed versus the prior banked MX result. Stale DIM16 CSV rows were removed. | `sbt "project gemmini" compile` passed; stock DIM32/DIM16 elaboration passed; stock RTL purity audit passed; DIM32 perf sampled checks passed for stock and MX. Full MX regression still required before banking any future candidate. | Vivado OOC not run yet. | Do not bank the current performance state. Next iteration should inspect MX 256^3 counters, especially `issue_cyc=38,150`, `loopmm_active=46,431`, `no_cmd=41,299`, `spadA_wait=40,909`, `spadB_wait=40,877`, and `rdma_active=20,467`. |
| 01 | 2026-07-03 | top db5df054d / gemmini 359a0860 / rocc-tests 5388da5 | Software geometry may be trading too much chunk-count overhead for fenceless scale-SRAM ping-pong. For DIM32 256^3, quarter-region ping-pong caps `i_chunk=2`, producing 8 chunks and `issue_cyc=38,150`; a fenced legacy mode can use full A/B scale halves, likely `i_chunk=4`, producing 4 chunks. | Tried a software-only geometry selector that compared ping-pong quarter-region chunks against legacy half-region chunks and selected legacy when it reduced total chunks. | Rejected result: MX 4,303 / 10,217 / 48,884 cycles for 64^3 / 128^3 / 256^3. 256^3 was slightly worse than I00 48,827; 64/128 also regressed. Reverted selector and reran MX, restoring 4,159 / 10,006 / 48,827. | Skipped by rule because DIM32 256^3 did not improve. | Sampled checks passed during the rejected run and after revert. | Not run. | Rejected and reverted. Do not retry full-half fenced geometry unless hardware can avoid the fence/drain penalty or counters change substantially. |
| 02 | 2026-07-03 | top db5df054d / gemmini 359a0860 / rocc-tests 5388da5 | The MX tiler may be leaving easy A-payload reuse on the table. In DIM32 256^3, each A `(i,k)` chunk is used for two `j` chunks, but `mx_a_reuse` is disabled because four A chunks exist across the whole `i` sweep. Reusing A within each `j` sweep could reduce payload loads and spad feed waits without changing hardware. | Enabled A reuse whenever there is more than one `j` chunk, with resident spad IDs alternating by `(ic,kc)` and `A_payload=NULL` for `jc>=1`. | DIM32 MX 4,319 / 9,995 / 45,148 cycles for 64^3 / 128^3 / 256^3. 256^3 improved by 3,679 cycles versus I00 and by 335 cycles versus prior banked 45,483. `rdma_active` dropped from 20,467 to 13,820 and `issue_cyc` from 38,150 to 35,460. | Completed after cleaning killed/stale MX DIM16 Verilator artifacts under `sims/verilator/generated-src` and rebuilding. Stock DIM16: 6,088 / 12,900 / 78,453 cycles. MX DIM16: 4,390 / 14,785 / 104,856 cycles, all PASS. The 64^3 case improves, but 128^3 and 256^3 regress versus stock, with 256^3 at 1.34x stock. | Sampled checks passed for DIM32 and DIM16 perf shapes. Full regression still required before banking. | Not run. | Keep only as a DIM32-positive diagnostic candidate; do not bank globally. Next iteration should make A reuse conditional by DIM/tile geometry or find a reuse strategy that does not inflate DIM16 host issue time (`issue_cyc=91,278` at 256^3). |
| 03 | 2026-07-04 | top db5df054d / gemmini 359a0860 / rocc-tests 5388da5 | The I02 A-reuse policy may be DIM32-positive but DIM16-negative; a compile-time DIM guard could preserve DIM32 wins while returning DIM16 to the prior no-A-reuse path. | Tried `mx_a_reuse = (DIM >= 32 && mx_JC > 1)` and added `--csv`/`--force` support to `DOCS_MX/scripts/run_perf.sh` so each iteration can write a separate fair stock-vs-MX CSV without deleting older evidence. | DIM32 reproduced I02 exactly in `DOCS_MX/scripts/results/perf_i03.csv`: stock 5,095 / 10,445 / 41,022 cycles; MX 4,319 / 9,995 / 45,148 cycles for 64^3 / 128^3 / 256^3, all PASS. | DIM16 completed because DIM32 qualified. Stock 6,088 / 12,900 / 78,453 cycles; MX 4,138 / 15,823 / 116,206 cycles, all PASS. The guard improves 64^3 versus I02 but makes 128^3 and 256^3 worse; `issue_cyc` rises to 102,664 and `rdma_active` to 42,033 at 256^3. | Sampled checks passed for all perf shapes. Full regression not run because the candidate lost. | Not run. | Rejected and revert the DIM guard. Keep the `run_perf.sh` measurement improvement. A reuse actually reduces DIM16 issue/RDMA versus no-A-reuse, but the DIM16 large-shape policy remains much slower than stock; next iteration should target DIM16 host issue count/command batching or scale-load scheduling rather than disabling A reuse. |
| 04 | 2026-07-04 | top db5df054d / gemmini 359a0860 / rocc-tests 5388da5 | `issue_cyc` is dominated by repeated MX LOOP_WS command emission. LoopMatmul retains per-slot bounds/stride config after a slot resets, so steady-state same-geometry chunks may only need fresh A/B/D/C addresses plus the run command. | Tried `gemmini_loop_ws_mx_reuse_config` and a conservative tiler flag: after two same-geometry chunks since the last fence, emit only ADDRS_AB, ADDRS_DC, and LOOP_WS instead of the full six-command LOOP_WS sequence. Kept the public `gemmini_loop_ws_mxint8` signature so direct MX tests still build. Added `--impl stock|mx|both` to `run_perf.sh` and `--skip-stock-elab` to `run_regression.sh`; future MX-only iterations should reuse stable stock rows instead of rerunning stock. | `DOCS_MX/scripts/results/perf_i04.csv`: stock 5,095 / 10,445 / 41,022 cycles; MX 4,267 / 10,001 / 44,974 cycles for 64^3 / 128^3 / 256^3, all PASS. 256^3 improved 174 cycles versus I02; `issue_cyc` dropped from 35,460 to 6,326, but much of the wait shifted to `config_cyc=23,009`, so total gain was small. | DIM16 completed because DIM32 qualified. Stock 6,088 / 12,900 / 78,453 cycles; MX 4,486 / 14,947 / 104,661 cycles, all PASS. 256^3 improved 195 cycles versus I02, while 64^3 and 128^3 regressed mildly. `issue_cyc` dropped to 6,269 at 256^3, but `config_cyc=78,373` became dominant. The wrapper exited nonzero after results because `run_perf.sh` was edited while it was running; the simulator log shows `mx_bench: PASS` and the CSV rows were appended. | Sampled perf checks passed, but focused regression hung in `mxint8_matmul_dim32` after the first `K=32` probe (`PASS` printed, then no progress for several minutes). This shows the retained-config shortcut is unsafe for broader MX direct tests. | Not run; software-only candidate. | Rejected and reverted the retained-config shortcut. Keep only the script improvements. Do not rely on implicit LoopMatmul retained config; a future address-only fast path must be explicit hardware/protocol support with direct-regression coverage. |
| 05 | 2026-07-04 | top db5df054d / gemmini 359a0860 / rocc-tests 5388da5 | MX scale mvins conservatively emit an A- or B-stride `CONFIG_MXINT8` before every scale load, but the benchmark uses stable A/B scale strides across chunks. Caching those explicit stride writes in software should reduce command pressure without skipping any scale mvin or relying on hidden LoopMatmul state. | Tried per-helper cached A/B scale stride values in `gemmini_mvin_mxscale_a/b`, emitting the stride config only on the first call or when the stride changes. Also added `--tests`, `--timeout-cycles`, and startup run/log/tmp directory prints to the regression/perf scripts so focused checks remain repo-local and bounded. | `DOCS_MX/scripts/results/perf_i05.csv`: MX 4,511 / 10,096 / 45,282 cycles for 64^3 / 128^3 / 256^3, all PASS. This regresses every DIM32 point versus I02 (4,319 / 9,995 / 45,148). At 256^3 the `issue_cyc` is 35,305, close to I02, and the saved stride configs do not translate into total-cycle improvement. | Skipped by DIM32-first rule because DIM32 regressed. | Sampled perf checks passed. Focused direct `mxint8_matmul_dim32` under the current post-I04 tree reached `probe K=32 PASS`, then remained in the K=64 phase for several minutes and was stopped. This path is treated as a pre-existing direct-test issue until isolated; I05 correctness gate was the sampled `mx_bench` perf check. | Not run; software-only candidate. | Rejected and reverted the scale-stride cache. Keep only the script measurement/usability changes. Do not spend more iterations on tiny ROCC-count reductions unless counters show they move the large-shape total. |
| 06 | 2026-07-04 | top db5df054d / gemmini 359a0860 / rocc-tests 5388da5 | The remaining DIM32 gap is not explained well enough by total cycles alone. Before another geometry or hardware attempt, the benchmark should print the exact MX chunk grid, fence count, and payload reuse counts outside the timed window so candidate changes can be tied to loop-invocation structure. | Added an `MXGEOM` line in `mx_bench` for MX builds, then guarded it behind disabled-by-default `MX_BENCH_PRINT_GEOM` because the extra benchmark code/printf perturbed host-issue timing even though it was outside the explicit timed window. | `DOCS_MX/scripts/results/perf_i06.csv`: MX 4,281 / 9,967 / 45,515 cycles for 64^3 / 128^3 / 256^3, all PASS. The 256^3 timing is worse than I02, so do not compare future candidates with geometry printing enabled. The useful 256^3 geometry is: `i_tiles=8,j_tiles=8,k_tiles=8,i_chunk=2,j_chunk=4,k_chunk=8,mx_IC=4,mx_JC=2,mx_KC=1,chunks=8,fences=1,a_payload_loads=4,b_payload_loads=2,a_scale_loads=8,b_scale_loads=8`. | Skipped; measurement-only and DIM32 timing was perturbed. | Sampled perf checks passed. The geometry data shows B payload reuse is already working, but B scales are still reloaded on all eight chunks even though only two unique `(j,k)` B-scale images exist for DIM32 256^3. | Not run. | Keep the disabled geometry instrumentation for opt-in diagnosis only. Next iteration should test software B-scale residency across the i sweep: skip B-scale mvin when the same `(j,k,parity)` image is already resident in MXScaleSRAM. |
| 07 | 2026-07-04 | top db5df054d / gemmini 359a0860 / rocc-tests 5388da5 | For DIM32 256^3, B payload reuse already reduces B payload loads to two, but B-scale mvins still run on all eight chunks. Because MXScaleSRAM contents persist after reads, the tiler should be able to skip a B-scale mvin when the same `(j,k)` image is already resident in the same ping-pong parity half. | Added a private tiler-controlled `g_mx_bscale_resident` flag and per-GEMM resident table keyed by `(mx_slot, parity)`. Direct callers keep the old behavior because the flag defaults false. Restricted the optimization to non-K-chunked cached-B-scale cases with B payload reuse. | `DOCS_MX/scripts/results/perf_i07.csv`: MX 4,338 / 10,183 / 44,302 cycles for 64^3 / 128^3 / 256^3, all PASS. 256^3 improves by 846 cycles versus I02 and by 1,213 versus I06; `scaleb_cyc` drops to 110 and `issue_cyc` to 34,496. 64^3 and 128^3 regress versus I02. | DIM16 ran because DIM32 qualified. MX 4,496 / 15,033 / 104,843 cycles, all PASS. 256^3 is essentially flat/slightly better than I02 (104,856), but 64^3 and 128^3 regress. | Sampled perf checks passed. Full regression not run yet; the direct `mxint8_matmul_dim32` K=64 path appears pre-existing-stuck and must be isolated separately. | Not run; software-only candidate. | Tentatively keep as a large-shape candidate, but not final-bank as-is. I08 should narrow the residency path so small shapes and DIM16 do not pay overhead when no B-scale skip can occur. |
| 08 | 2026-07-04 | top db5df054d / gemmini 359a0860 / rocc-tests 5388da5 | I07 should only exist in binaries where it can pay off. DIM16 did not benefit materially, and small DIM32 shapes have too few i chunks to skip a resident parity. Compile the B-scale residency path only for DIM32+ and arm it only when `mx_IC > 2`. | Guarded the private B-scale residency state and skip branch with `#if DIM >= 32`, restored the local `mx_IC` count, and added `mx_IC > 2` to the runtime enable condition. | `DOCS_MX/scripts/results/perf_i08.csv`: MX 4,317 / 9,968 / 44,356 cycles for 64^3 / 128^3 / 256^3, all PASS. Versus I02, DIM32 improves by 2 / 27 / 792 cycles; versus stock 256^3, MX ratio improves from 1.10x to 1.08x. `scaleb_cyc=111`, `issue_cyc=34,402`, and `rdma_active=13,596` at 256^3. | DIM16 ran because DIM32 qualified. MX 4,390 / 14,785 / 104,856 cycles, all PASS, exactly matching I02 cycle values; the DIM guard removed the I07 small-shape penalty. | Sampled perf checks passed for DIM32 and DIM16. A focused `mxint8_multitile` DIM32 regression attempt was stopped after several minutes with the UART log still at boot text, so it is inconclusive. Full regression still pending; the direct/regression boot-or-K64 issue should be isolated before final banking. | Not run; software-only candidate. | Keep as current best software candidate. Next iteration should target the larger remaining `issue_cyc/no_cmd/spad wait` gap, not more B-scale mvin reductions. |
| 09 | 2026-07-04 | top db5df054d / gemmini 359a0860 / rocc-tests 5388da5 | I04 showed that repeated LoopMatmul bounds/stride config commands dominate host issue time, but the implicit retained-config shortcut was not defensible. A measured explicit fast-run opcode could test whether skipping repeated bounds/stride commands is worth hardware support. | Tried an MX-only `LOOP_WS_MXINT8` fast-run command in `LoopMatmul.scala` plus a guarded `gemmini_loop_ws_mx_fast` helper that emitted only ADDRS_AB, ADDRS_DC, and the fast run command after warmed same-geometry DIM32 chunks. The run stayed repo-local under `sims/verilator/gemmini`; `/tmp` was not used for simulator artifacts. | `DOCS_MX/scripts/results/perf_i09.csv`: MX 4,466 / 10,128 / 44,368 cycles for 64^3 / 128^3 / 256^3, all PASS. This regressed versus I08 at 64^3 and 128^3, and was 12 cycles worse at 256^3. | Skipped by DIM32-first rule because DIM32 did not improve or stay equal. | `sbt "project gemmini" compile` passed before the perf gate; sampled `mx_bench` checks passed. | Not run; hardware change rejected before synthesis. | Rejected and reverted the fast opcode path. Keep I08 as current best; future hardware attempts must show a larger counter-backed benefit before paying ISA/RTL complexity. |
| 10 | 2026-07-04 | top db5df054d / gemmini 359a0860 / rocc-tests 5388da5 | DIM32 256^3 is limited to `i_chunk=2` partly by MX scale-SRAM ping-pong capacity. Doubling DIM32 MX scale SRAM from 8KB to 16KB might allow larger chunks and reduce per-chunk issue overhead, at an area cost that must be justified by cycles. | Tried `mx_scale_sp_capacity = CapacityInKilobytes(16)` for DIM32 MX only. Also fixed `run_perf.sh --build-sims` ordering so Verilator elaboration refreshes the generated params header before `mx_bench` is compiled; this prevents stale-header measurements for future hardware/config experiments. | `DOCS_MX/scripts/results/perf_i10.csv`: MX 4,402 / 9,994 / 44,375 cycles for 64^3 / 128^3 / 256^3, all PASS. The generated header correctly changed to `MX_SCALE_SP_ROWS 512`, but 256^3 still regressed by 19 cycles versus I08 and small shapes regressed more. Counters showed `issue_cyc=34,798`, so the chunk geometry did not improve; `ACC_ROWS=512` remains limiting. | Skipped by DIM32-first rule because DIM32 regressed. | `sbt "project gemmini" compile` passed before the perf gate; sampled `mx_bench` checks passed. | Not run; larger SRAM was rejected before synthesis. | Rejected and reverted DIM32 scale-SRAM capacity/header back to 8KB/256 rows. Keep the `run_perf.sh` header-refresh ordering fix. Do not increase MX scale SRAM alone; any geometry-capacity experiment must account for accumulator capacity too, and stock/MX fairness makes that a poor next lever. |
| 11 | 2026-07-04 | top db5df054d / gemmini 359a0860 / rocc-tests 5388da5 | MX A-payload reuse can make the A-loader idle while B is still progressing. The execute unroller's B-load-ahead predicate uses `ld_ka` in the same-K check, which could conservatively stall MX when A was skipped. | Tried an MX-only guard in `LoopMatmulExecute`: for `req.mx`, compare `ld_kb === k` before using `ld_j > j`; stock/non-MX loops kept the original predicate so pure INT8 stock elaboration was not changed. | `DOCS_MX/scripts/results/perf_i11.csv`: DIM32 MX 4,317 / 9,968 / 44,356 cycles for 64^3 / 128^3 / 256^3, all PASS. This exactly matched I08; no measured speed gain. | DIM16 ran because DIM32 stayed equal: MX 4,390 / 14,785 / 104,856 cycles, all PASS, also exactly matching I08. | `sbt "project gemmini" compile` passed; sampled `mx_bench` checks passed for DIM32 and DIM16. | Not run; no speed benefit. | Rejected and reverted. The predicate was a plausible stall source, but measured cycles did not move; do not add even small MX-only area without a performance or correctness payoff. |
| 12 | 2026-07-04 | top db5df054d / gemmini 359a0860 / rocc-tests 5388da5 | The next optimization should be counter-driven: I08 still has a 3,334-cycle DIM32 256^3 gap to stock, but the existing counter set merges too many causes into `no_cmd` and wait counters. | Added an opt-in `MX_BENCH_COUNTER_SET=1` build path through `EXTRA_CFLAGS` so `mx_bench` can replace the default `MXCOUNT` print with `MXPART` split counters: `no_cmd`, `ex_pool_empty`, `ex_ready`, `ex_blocked`, `ex_inflight`, `ex_pool_full`, `ld_inflight`, and `loopmm_active`. The run stayed under `sims/verilator/gemmini`; `/tmp` remained at 9.7 MB used / 7.4 GB free. | `DOCS_MX/scripts/results/perf_i12.csv`: DIM32 MX diagnostic 4,404 / 9,969 / 44,480 cycles for 64^3 / 128^3 / 256^3, all PASS. The diagnostic binary perturbs cycles slightly, so it is measurement evidence, not a new candidate. For 256^3: `ex_pool_empty=24,512`, `ex_ready=11,698`, `ex_blocked=30,570`, `ex_inflight=23,849`, `ex_pool_full=28,000`, `ld_inflight=24,094`, `loopmm_active=42,007`, `issue_cyc=34,624`. | Skipped; measurement-only diagnostic and cycles were perturbed relative to I08. | Sampled `mx_bench` checks passed for DIM32. | Not run; no hardware candidate. | Keep the instrumentation path, keep I08 as current best implementation. The next candidate should investigate execute/RS blockage and load/inflight overlap, because `ex_blocked`, `ex_pool_full`, and `ld_inflight` are all large during 256^3. |
| 13 | 2026-07-04 | top db5df054d / gemmini 359a0860 / rocc-tests 5388da5 | I12 showed large EX blocking, but not whether it was caused by the conservative scale-mvin dependency or ordinary load/store/execute dependencies. Before relaxing hardware dependencies, split the blocked cycles. | Added opt-in `MX_BENCH_COUNTER_SET=2`, printing `MXSCALEDEP` with `ex_blocked_on_scale`, `ex_blocked_scale_only`, `ex_blocked_nonscale`, LD/ST pool fullness/inflight, and scale-DMA active cycles. The run used `TMPDIR=sims/verilator/gemmini/tmp`; `/tmp` remained unchanged at 9.7 MB used / 7.4 GB free. | `DOCS_MX/scripts/results/perf_i13.csv`: DIM32 MX diagnostic 4,382 / 10,063 / 44,472 cycles for 64^3 / 128^3 / 256^3, all PASS. The diagnostic binary perturbs cycles, so it is not a candidate. For 256^3: `ex_blocked_on_scale=5,820`, `ex_blocked_scale_only=5,197`, `ex_blocked_nonscale=30,757`, `ld_pool_full=9,406`, `ld_blocked=0`, `st_pool_full=17,771`, `st_inflight=27,430`, `scale_dma_active=6,019`, `issue_cyc=34,965`. | Skipped; measurement-only diagnostic. | Sampled `mx_bench` checks passed for DIM32. | Not run; no hardware candidate. | Keep the diagnostic path. Do not make scale-only dependency relaxation the primary next bet: it could recover some cycles, but non-scale EX blocking plus LD/ST pool pressure dominate. Next iteration should target command/pool pressure from the MX loop schedule or verify whether a small RS-capacity/config change can absorb the fenceless MX command burst without hurting stock purity. |
| 14 | 2026-07-04 | top db5df054d / gemmini 359a0860 / rocc-tests 5388da5 | I13 showed `st_pool_full=17,771` and `st_inflight=27,430` at DIM32 256^3, so the fenceless MX command burst may be throttled by stock store-side buffering rather than math. A small MX-only store-buffer increase could improve overlap while leaving pure stock INT8 untouched. | Increased MX DIM32 and DIM16 only: `reservation_station_entries_st` 4->8 and `st_queue_length` 2->4. Stock configs remain unchanged and MX enable guards still preserve pure stock generation. | `DOCS_MX/scripts/results/perf_i14.csv`: DIM32 MX 4,317 / 9,855 / 37,921 cycles for 64^3 / 128^3 / 256^3, all PASS. Versus I08 this is +0 / -113 / -6,435 cycles, and versus stock DIM32 5,095 / 10,445 / 41,022 it is now faster on all three points. At 256^3, `loopmm_active` drops to 33,985, `no_cmd` to 31,274, and `issue_cyc` to 27,859. | DIM16 ran because DIM32 improved: MX 4,390 / 14,349 / 101,565 cycles, all PASS. Versus I08 this is +0 / -436 / -3,291 cycles. DIM16 still trails stock at 128^3 and 256^3 (`stock 12,900 / 78,453`), but the gap narrowed. | `sbt "project gemmini" compile` passed; sampled `mx_bench` checks passed for DIM32 and DIM16. Full MX regression still pending. | Not run yet. This is a hardware/config candidate and needs Vivado area/Fmax before final banking. | Keep as current best candidate pending synthesis and full regression. Next iteration should run split counters on I14 to confirm the store-buffer hypothesis and decide whether further LD/ST/EX buffering is justified or whether to stop capacity increases to protect area. |
| 15 | 2026-07-04 | top db5df054d / gemmini 359a0860 / rocc-tests 5388da5 | Validate I14's mechanism before adding more buffering. If the store-pool counter falls on the I14 hardware, the large speedup is likely real overlap relief rather than measurement noise. | Re-ran DIM32 MX on the I14 simulator with `MX_BENCH_COUNTER_SET=2`; no hardware change. The run reused the repo-local simulator and `TMPDIR=sims/verilator/gemmini/tmp`; `/tmp` stayed at 9.7 MB used. | `DOCS_MX/scripts/results/perf_i15.csv`: DIM32 MX diagnostic 4,382 / 9,856 / 38,081 cycles, all PASS. Diagnostic cycles are slightly perturbed versus I14, but close. For 256^3, `st_pool_full` dropped from I13's 17,771 to 7,373, `ex_blocked_scale_only` dropped 5,197->3,359, and `scale_dma_active` dropped 6,019->5,381. `ld_pool_full` is still high at 10,075 and `ex_blocked_nonscale` remains about 31,250. | Skipped; measurement-only diagnostic and DIM32 already validated I14. | Sampled `mx_bench` checks passed for DIM32. | Not run. | Keep I14. The store-buffer increase solved a real throttle, but the remaining DIM32 bottleneck is now LD pool pressure plus non-scale EX dependencies. I16 may test MX-only LD buffering, but stop if area risk starts outrunning cycle gain. |
| 16 | 2026-07-04 | top db5df054d / gemmini 359a0860 / rocc-tests 5388da5 | After I15, `ld_pool_full=10,075` suggested MX might still be load-RS-entry limited. Increasing only MX LD RS entries from 8 to 16 could reduce load-side command pressure without changing stock. | Tried `reservation_station_entries_ld = 16` for MX DIM32 and DIM16 only, keeping the LD controller queue unchanged. | `DOCS_MX/scripts/results/perf_i16.csv`: DIM32 MX 4,317 / 9,855 / 37,921 cycles, all PASS; exactly identical to I14. | DIM16 ran because DIM32 stayed equal: MX 4,390 / 14,349 / 101,565 cycles, all PASS; exactly identical to I14. | `sbt "project gemmini" compile` passed; sampled `mx_bench` checks passed for DIM32 and DIM16. | Not run; no speed benefit. | Rejected and reverted the LD RS bump. Keep I14 as current best. The remaining `ld_pool_full` counter was not cycle-limiting under this benchmark, or another downstream dependency hid it. Do not add LD/EX capacity without a new counter-backed reason and area plan. |
| 17 | 2026-07-04 | top db5df054d / gemmini 359a0860 / rocc-tests 5388da5 | I14 is the current best speed candidate, but it changes hardware buffering. Before banking it, measure OOC area/timing against pure stock INT8 for the same DIM32 and DIM16 configs on the Nexys Video part. | Ran `DOCS_MX/scripts/run_synth.sh` with `TMPDIR` and run artifacts under `sims/verilator/gemmini`/`DOCS_MX/scripts/results`; `/tmp` stayed at 9.7 MB used. Fixed the synthesis flow so Vivado reads only Verilog/SystemVerilog collateral, skips simulator C++ files, and includes generated `*.top.mems.v` memory wrappers. | DIM32 speed unchanged from I14: MX 4,317 / 9,855 / 37,921 cycles versus stock 5,095 / 10,445 / 41,022. OOC area/timing: stock LUT=211,458 FF=118,470 BRAM36=320 DSP=269 WNS=-39.157 ns Fmax_est=20.343 MHz; MX LUT=243,494 FF=121,270 BRAM36=352 DSP=272 WNS=-39.026 ns Fmax_est=20.397 MHz. Deltas: +32,036 LUT (+15.15%), +2,800 FF (+2.36%), +32 BRAM36 (+10.00%), +3 DSP (+1.12%); relative timing is not worse. | DIM16 speed unchanged from I14: MX 4,390 / 14,349 / 101,565 cycles versus stock 6,088 / 12,900 / 78,453. OOC area/timing: stock LUT=92,992 FF=53,058 BRAM36=160 DSP=236 WNS=-41.159 ns Fmax_est=19.547 MHz; MX LUT=120,825 FF=61,005 BRAM36=168 DSP=239 WNS=-39.464 ns Fmax_est=20.217 MHz. Deltas: +27,833 LUT (+29.93%), +7,947 FF (+14.98%), +8 BRAM36 (+5.00%), +3 DSP (+1.27%); relative timing is not worse. | `synth_design` completed with 0 errors for all four configs. This is an OOC synthesis gate only; full regression is still required before final banking. | `DOCS_MX/scripts/results/synth.csv` now contains all four rows. Absolute OOC Fmax is far below 100 MHz for both stock and MX, so use these numbers for relative area/timing deltas, not final timing closure. | Keep I14 as current best speed candidate, but the DIM16 LUT/FF overhead is too high to ignore for a publication-quality final. I18 should do a DIM32-first area-aware minimization of the I14 store-side buffering, such as separating `reservation_station_entries_st` from `st_queue_length`, and run DIM16 only when DIM32 is equal or better. |
| 18 | 2026-07-04 | top db5df054d / gemmini 359a0860 / rocc-tests 5388da5 | I14 changed two store-side depths at once. The store RS entries are expensive because each entry stores command/dependency metadata; the store-controller queue is smaller. If the speedup mostly came from the queue, we may recover area by returning `reservation_station_entries_st` to stock 4 while keeping `st_queue_length=4`. | Tried MX DIM32/DIM16 configs with `reservation_station_entries_st=4` and `st_queue_length=4`, then rebuilt the DIM32 simulator and ran `mx_bench`. The run stayed under `sims/verilator/gemmini`; `/tmp` stayed effectively unchanged. | `DOCS_MX/scripts/results/perf_i18.csv`: DIM32 MX 4,317 / 9,968 / 44,356 cycles, all PASS. This matches I08 rather than I14, losing 113 cycles at 128^3 and 6,435 cycles at 256^3 versus I14. | Skipped by DIM32-first rule because DIM32 regressed. | `sbt "project gemmini" compile` passed; sampled `mx_bench` checks passed for DIM32. | Not run; candidate lost before synthesis. | Rejected and reverted. The I14 speedup requires deeper store RS entries; deeper store-controller queue alone is not enough. I19 should test the complementary area minimization: keep `reservation_station_entries_st=8` but return `st_queue_length` to stock 2, then run DIM32 first. |
| 19 | 2026-07-04 | top db5df054d / gemmini 359a0860 / rocc-tests 5388da5 | I18 proved deeper store RS entries are necessary. The remaining I14 area-saving question is whether the deeper store-controller queue is also needed, or whether `reservation_station_entries_st=8` with stock `st_queue_length=2` preserves speed. | Tried the stock-depth store-queue direction and rebuilt/reran under `sims/verilator/gemmini`; `/tmp` stayed effectively unchanged. This pass also exposed a config-hygiene issue from the working tree: stock default store RS depth had temporarily drifted from upstream 4 to 8 in the local tree, and the DIM16 I19 trial did not preserve the intended I14 deep-store settings. | `DOCS_MX/scripts/results/perf_i19.csv`: DIM32 MX 4,317 / 9,855 / 37,921 cycles, all PASS. DIM32 matched I14 even with the shallower store queue. | DIM16 ran because DIM32 stayed equal, but it regressed to 4,390 / 14,785 / 104,856 cycles, all PASS, losing 436 cycles at 128^3 and 3,291 cycles at 256^3 versus I14. | `sbt "project gemmini" compile` passed before the perf gate; sampled `mx_bench` checks passed for DIM32 and DIM16. | Not run; the candidate lost before synthesis and the config-hygiene issue made it unsuitable to bank. | Rejected. Restore the I14 finalist explicitly in the MX DIM32 and DIM16 configs: `reservation_station_entries_st=8` and `st_queue_length=4`, while restoring upstream pure stock default `reservation_station_entries_st=4`. I20 must rerun the corrected stock/MX baseline once; after that stock rows can be cached again unless stock changes. |
| 20 | 2026-07-04 | top db5df054d / gemmini 359a0860 / rocc-tests 5388da5 | After I19, the final pass must choose the best measured implementation and revalidate it against a truly pure INT8 stock baseline. The likely winner is I14's MX-only store-buffer increase, but stock must not inherit MX/config changes. | Restored pure stock `defaultConfig.reservation_station_entries_st=4` and made the winner's MX-only overrides explicit for DIM32 and DIM16: `reservation_station_entries_st=8`, `st_queue_length=4`. Added `run_synth.sh --csv` so corrected final synthesis can write `DOCS_MX/scripts/results/synth_i20.csv` without reusing stale rows. Shortened slow baremetal MX regression tests by replacing target-side random packing/full golden loops with deterministic scalar expected values or sampled per-tile checks; hardware paths and bit-exact scalar helpers remain unchanged. Rebuilt/reran corrected DIM32 first, then DIM16 because DIM32 stayed better. All runs used `RUN_DIR`/`TMPDIR` under `sims/verilator/gemmini`; `/tmp` remained about 9.7 MB used. | `DOCS_MX/scripts/results/perf_i20.csv`: corrected DIM32 stock 5,095 / 10,445 / 41,022 cycles; MX 4,317 / 9,855 / 37,921 cycles, all PASS. MX/stock ratios: 0.847 / 0.944 / 0.924, so MX is faster than pure stock on all DIM32 points. | Corrected DIM16 stock 6,088 / 12,900 / 78,453 cycles; MX 4,390 / 14,349 / 101,565 cycles, all PASS. MX is faster at 64^3, but slower at 128^3 by 1,449 cycles and at 256^3 by 23,112 cycles. | `sbt "project gemmini" compile` passed. `mx_bench` sampled checks passed for all corrected DIM32/DIM16 stock and MX perf points. Stock generated DIM32/DIM16 trees have no `MXScale`, `MXScaleSRAM`, `MXScaleLoad`, `mx_scale`, or MX-named files; stock headers contain `MX_ENABLED 0`. Final MX regression passed with `DOCS_MX/scripts/run_regression.sh --dims=32 --skip-stock-elab --timeout-cycles=300000000` and `DOCS_MX/scripts/run_regression.sh --dims=16 --skip-stock-elab --timeout-cycles=300000000`. DIM32 passed `mxint8_golden`, `mxint8_matmul_dim32`, `mxint8_corner`, `mxint8_matmul_partial`, `mxint8_tiled`, `mxint8_btb`, `mxint8_multitile`, and `mxint8_matmul_nphase`; DIM16 passed `mxint8_matmul_dim16`, `mxint8_multitile`, and `mxint8_matmul_nphase`. | `DOCS_MX/scripts/results/synth_i20.csv`: corrected OOC rows match I17. DIM32 stock LUT=211,458 FF=118,470 BRAM36=320 DSP=269 WNS=-39.157 ns; DIM32 MX LUT=243,494 FF=121,270 BRAM36=352 DSP=272 WNS=-39.026 ns, deltas +32,036 LUT (+15.15%), +2,800 FF (+2.36%), +32 BRAM36 (+10.00%), +3 DSP (+1.12%). DIM16 stock LUT=92,992 FF=53,058 BRAM36=160 DSP=236 WNS=-41.159 ns; DIM16 MX LUT=120,825 FF=61,005 BRAM36=168 DSP=239 WNS=-39.464 ns, deltas +27,833 LUT (+29.93%), +7,947 FF (+14.98%), +8 BRAM36 (+5.00%), +3 DSP (+1.27%). Relative OOC timing is not worse, but absolute OOC Fmax is not timing closure. | Choose the restored I14 implementation as the best measured candidate: MX-only store RS 8 plus store queue 4, with stock default untouched. It is the DIM32 speed winner and has acceptable DIM32 area overhead. DIM16 large shapes remain slower than stock and are the main residual performance caveat. Do not rerun stock again unless stock source/config changes. |
