# MXINT8 Known Issues

Status snapshot: 2026-07-03.

This file lists current limitations, deviations, and operational caveats that were
validated against the current Gemmini code. It is not a TODO list; proposed work
belongs in `WORK_TODO.md`.

## Supported Profile Limits

- MX support is limited to MXINT8: OCP block size 32, E8M0 scale bytes, signed
  int8 payloads, implicit payload scale `2^-6`, and signed integer accumulators.
- MX is scoped to weight-stationary Gemmini configs. `GemminiConfigs.scala` rejects
  non-WS MX configs.
- Runtime MX execute is untransposed WS GEMM only. `ExecuteController.scala` asserts
  if MX compute is active with A or B/D transpose enabled.
- Supported MX DIM values are power-of-two DIMs from 4 through 32. DIM64 is not
  supported because the current mesh reduces across DIM lanes before a per-32-lane
  MX block scale can be applied.
- The implementation is a Gemmini sidecar, not a general OCP MX accelerator. Other
  MX formats such as MXFP, MXINT4, MXINT6, or MXINT16 are not implemented.

## Numeric And OCP Profile Deviations

- E8M0 `0xff` NaN scale bytes are rejected at ingress rather than propagated. The
  integer output profile has no NaN encoding; RTL asserts on masked-valid `0xff`
  scale bytes and the software golden returns an error.
- Cross-block accumulation is integer, not Float32. The software golden accumulates
  in int64 and saturates once to the integer accumulator output; RTL uses Gemmini's
  signed integer accumulator path.
- Payload `-128` is not emitted by the packer. As a consumer, RTL handles `-128`
  only when the raw 32-lane partial stays inside the narrowed `SInt(20.W)` raw
  envelope. An all-maximum-magnitude 32-lane `-128` block can overflow that raw
  envelope by one LSB; this is documented as the D3 profile deviation.
- Accumulator saturation is intentional, but workload-level saturation incidence has
  not yet been quantified. That study is tracked as wide-accumulator/P4 work.
- The all-zero block convention is implementation-defined by policy: the packer emits
  neutral exponent `e = 0` with zero payloads.

## Lower-Level Wrapper Envelopes

The public `tiled_matmul_mxint8` path computes chunk geometry and padding for normal
use. Direct lower-level calls to `gemmini_loop_ws_mxint8` must still obey its
fail-loudly envelopes:

- At DIM<32, the K tile count must cover whole logical MX blocks:
  `K % (MX_BLOCK_SIZE / DIM) == 0`.
- `k_blocks <= DIM`, because A-scale blocks are carried in DIM lanes.
- `I * DIM <= MX_SCALE_SP_ROWS / 2`, so A scales fit their scale-SRAM region.
- `k_blocks * Jp <= MX_SCALE_SP_ROWS / 2`, so B scales fit their scale-SRAM region.
- `I * Jp * DIM <= ACC_ROWS / 2`, so the padded output tile image fits the
  accumulator half used by MX loops.
- Multi-tile MX loops use a padded power-of-two J pitch (`Jp`) for C tile layout.
  Code that bypasses the public tiler must match that layout.

## Performance Caveats

- The latest banked 256^3 DIM32 result is 45,483 cycles versus in-harness stock
  38,632 cycles, or 1.18x stock. This is a major improvement over the original
  repack-dominated path, but not parity.
- Path B, which loaded all scales once, measured 44,529 cycles at 256^3 but was
  reverted. It cost extra scale SRAM and RTL complexity for only a small improvement
  over the operand-reuse fix.
- The scale sidecar still performs per-chunk scale loads in the banked design. After
  operand reuse, those loads are not the dominant historical problem, but they remain
  part of the residual overhead.
- The current fenceless interlock is correct for two scale-SRAM halves, but deeper
  overlap needs more scale capacity and likely more concurrent loop depth.
- `DOCS_MX/scripts/results/perf.csv` predates the latest July 3 fixes unless it has
  been regenerated. Do not use stale generated results as canonical performance data.

## Simulation And Tooling Caveats

- Large baremetal binaries should be run with `LOADMEM=1` / `+loadmem`; otherwise TSI
  serial loading can look like a simulator hang.
- Use the pinned firtool expected by the scripts (`1.62.1`). A stale Verilator sim can
  silently re-elaborate during a run path, and the wrong firtool version can fail on
  the emitted FIRRTL dialect.
- The scripts expect the Chipyard environment with JDK 20 active. System JDK 21 has
  caused false failures.
- Header staging for stock versus MX builds must restore `gemmini_params.h` after
  each build. The provided scripts do this; ad hoc commands should copy the pattern.
- Verilator and Vivado runs are long. Treat timeouts, buffered stdio, and stray old
  simulator processes as measurement hazards before concluding the design hung.
- The old Markdown notes were removed from live documentation during cleanup. Use git
  history for historical design archaeology.
