# MXINT8 Work TODO

Status snapshot: 2026-07-03.

This file tracks open work only. Completed work belongs in `WORK_DONE.md`; hard
limitations and known caveats belong in `KNOWN_ISSUES.md`.

## Refresh Evidence

- [ ] Re-run the full bit-exact regression from the cleaned documentation state:
  `DOCS_MX/scripts/run_regression.sh --build-sims --dims=32,16,8,4`.
- [ ] Re-run performance after the July 3 operand-reuse fix and replace stale
  generated data. With the currently checked-in stock headers, use
  `DOCS_MX/scripts/run_perf.sh --build-sims --dims 32,16`; extend to
  `--dims 32,16,8,4` after stock DIM8/DIM4 params headers are present.
- [ ] Re-run OOC synthesis/resource data when Vivado is available:
  `DOCS_MX/scripts/run_synth.sh`.
- [ ] If fresh `perf.csv` and `synth.csv` are intentionally produced, regenerate
  the comparison report with `DOCS_MX/scripts/make_report.py`, but do not treat the
  generated Markdown as a live canonical document unless the documentation policy changes.
- [ ] Capture the exact Gemmini and gemmini-rocc-tests commit hashes next to any
  new published performance or synthesis numbers.

## Open Engineering Work

- [ ] P2 counters/resource pass: decide the final MX counter set, avoid
  double-connecting the scale reader's shared `CounterEvent` IDs, and add dedicated
  MX metadata counters if they are still useful.
- [ ] P2 synthesis pass: quantify LUT/FF/BRAM/DSP/Fmax deltas for stock and MX
  DIM32/16/8/4 using the existing OOC scripts, with the "synthesis only, no P&R"
  caveat carried into any report.
- [ ] P4 wide-accumulator study: quantify saturation incidence for real workloads
  and decide whether any wider-accumulator option is worth the area/timing cost.
- [ ] P5 transpose/OS decision: either add one supported transpose/output-stationary
  path or explicitly scope the project and paper claims to untransposed WS GEMM.
- [ ] Evaluate whether the remaining 1.18x stock gap matters for the thesis. If it
  does, investigate stock-tiler parity and mesh-feed scheduling before reviving any
  hardware-heavy Path B style design.
- [ ] Study deeper scale-SRAM ping-pong only if there is a concrete workload that
  needs it. The existing depth-2 interlock is correct, but 256^3 DIM32 is scale
  capacity bound with two halves.
- [ ] DIM64 remains a separate design project. It needs mesh-internal per-block
  accumulation taps, half-rate block processing, or another datapath change; it is
  not a documentation-only cleanup.

## Evaluation And Artifact Work

- [ ] Build an evaluation harness with clear baselines: stock int8 Gemmini,
  MXINT8 Gemmini, software MX conversion cost, and amortized constant-weight
  pretiling where applicable.
- [ ] Add realistic matrix/workload shapes beyond the square smoke tests, including
  the BERT-style shapes already anticipated by `run_perf.sh`.
- [ ] Run accuracy studies against the software golden and, where useful, external
  `microxcaling` behavior. Include saturation-rate reporting for D2.
- [ ] Run FireSim/FPGA or a documented OOC fallback flow before making hardware
  resource claims in a paper or artifact.
- [ ] Package the artifact: exact commands, expected runtime, required JDK/firtool
  versions, staged header flow, and known simulator caveats.

## Documentation Maintenance

- [ ] Keep `WORK_DONE.md`, `WORK_TODO.md`, `KNOWN_ISSUES.md`, and `COMMANDS.md`
  as the live hand-maintained Markdown documentation under `DOCS_MX`.
- [ ] When new measurements land, update `WORK_DONE.md` and this TODO in the same
  commit as the measurement or code change.
- [ ] Keep generated reports out of the canonical docs unless they are regenerated
  from fresh data and intentionally reviewed.
