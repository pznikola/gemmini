# MXINT8 Work Done

Status snapshot: 2026-07-04.

This file is the canonical history of completed MXINT8 work in this tree. It was
consolidated from the older `DOCS_MX` notes, then checked against the current
Gemmini implementation rather than assuming those notes were correct.

Current code anchors:

- Gemmini: `359a0860 mxint8: bump gemmini-rocc-tests (tiler B/A payload reuse, -19% at 256^3, bit-exact)`
- gemmini-rocc-tests: `5388da5 mxint8: reuse resident B/A payloads across the tiler i/j-sweep`
- Latest documentation commits before this cleanup: `ff06b850` and `9127ff78` in `DOCS_MX/PERF_ANALYSIS.md`

Long Verilator/Vivado jobs were rerun for the July 4 DIM32/DIM16 final-candidate
pass; older sections below are retained as historical context.

## July 4 PLAN_1 DIM16 Campaign Final Candidate

The second 20-iteration campaign is documented in `PLAN_1.md`. It focused on
the DIM16 large-shape slowdown while preserving DIM32 MX behavior and stock INT8
purity.

Selected implementation:

- DIM16 MX scale SRAM capacity reduced to 4KB:
  `mx_scale_sp_capacity = CapacityInKilobytes(4)`.
- DIM16 MX store reservation station increased to 16 entries:
  `reservation_station_entries_st = 16`.
- DIM16 pair-J MX software schedule keeps the J19 relaxed pair-group barrier:
  `do_fence = k_chunked || first_chunk || !geom_same`.
- Stock INT8 configs remain pure and must keep `MX_ENABLED 0`.

Final PLAN_1 performance evidence:

| DIM | Impl | 64^3 | 128^3 | 256^3 |
|---|---|---:|---:|---:|
| 32 | MX final | 4,229 | 9,689 | 37,919 |
| 16 | MX final | 4,438 | 13,724 | 88,379 |

Compared with the I20 MX starting point, DIM16 changes are `+48`, `-625`, and
`-13,186` cycles for `64^3`, `128^3`, and `256^3`. Compared with pure stock
DIM16, final MX is 1,650 cycles faster at `64^3`, 824 cycles slower at `128^3`,
and 9,926 cycles slower at `256^3`.

Final DIM16 instruction-count evidence was collected with
`MX_BENCH_COUNTER_SET=3` using
`DOCS_MX/scripts/results/perf_plan1_final_dim16_inst.csv` and the matching
`MXINST` simulator log lines. MX retires more host instructions than stock, but
the count remains small relative to accelerator cycles:

| Shape | Stock instret | MX instret | MX/stock instret |
|---|---:|---:|---:|
| `64^3` | 351 | 473 | 1.348x |
| `128^3` | 832 | 1,394 | 1.675x |
| `256^3` | 2,551 | 3,710 | 1.454x |

This supports keeping custom MX/RoCC macro-instructions as a possible later
co-design topic, but not as the first explanation for the final DIM16 gap: at
`256^3`, 3,710 retired MX host instructions span 88,206 measured cycles.

Final PLAN_1 area evidence is the banked J14 hardware/config synthesis point:
`DOCS_MX/scripts/results/synth_plan1_j14_dim16.csv` reports
`117643 LUT / 63162 FF / 167.5 BRAM36 / 239 DSP`. The J19 final software
schedule does not change hardware area.

Final PLAN_1 correctness evidence:

- DIM16 regression passed `mxint8_matmul_dim16`, `mxint8_multitile`, and
  `mxint8_matmul_nphase`.
- DIM32 regression passed `mxint8_golden`, `mxint8_matmul_dim32`,
  `mxint8_corner`, `mxint8_matmul_partial`, `mxint8_tiled`, `mxint8_btb`,
  `mxint8_multitile`, and `mxint8_matmul_nphase`.
- All long simulations, generated headers, logs, and scratch stayed under
  `sims/verilator/gemmini`.

J20 tested a small-shape shape gate for the J19 barrier relaxation, but it was
reverted because it recovered only 12 cycles at DIM16 `64^3` while losing
142/176 cycles on the target `128^3`/`256^3` shapes.

## July 4 I20 Baseline Before PLAN_1

The best measured implementation after the earlier `PLAN.md` I20 campaign was
the restored I14 candidate: pure stock kept upstream
`reservation_station_entries_st=4`, while MX DIM32/DIM16 explicitly used
`reservation_station_entries_st=8` and `st_queue_length=4`.

Fresh corrected performance evidence is in `DOCS_MX/scripts/results/perf_i20.csv`:

| DIM | Impl | 64^3 | 128^3 | 256^3 |
|---|---|---:|---:|---:|
| 32 | stock | 5,095 | 10,445 | 41,022 |
| 32 | MX | 4,317 | 9,855 | 37,921 |
| 16 | stock | 6,088 | 12,900 | 78,453 |
| 16 | MX | 4,390 | 14,349 | 101,565 |

Corrected OOC synthesis evidence is in `DOCS_MX/scripts/results/synth_i20.csv`.
The rows match the earlier I17 synthesis data: DIM32 MX is +15.15% LUT, +2.36%
FF, +10.00% BRAM36, +1.12% DSP versus stock; DIM16 MX is +29.93% LUT,
+14.98% FF, +5.00% BRAM36, +1.27% DSP. Relative OOC timing is not worse, but
absolute OOC Fmax is not timing closure.

Stock-purity audit: corrected generated stock DIM32/DIM16 trees contain no
`MXScale`, `MXScaleSRAM`, `MXScaleLoad`, `mx_scale`, or MX-named files. The
stock headers contain `MX_ENABLED 0`. Shared queue/tag signals still contain a
generic `mx_enabled` bit name, but the MX scale sidecar is not generated.

Final MX regression evidence:

- `DOCS_MX/scripts/run_regression.sh --dims=32 --skip-stock-elab --timeout-cycles=300000000`
  passed all DIM32 MX tests: `mxint8_golden`, `mxint8_matmul_dim32`,
  `mxint8_corner`, `mxint8_matmul_partial`, `mxint8_tiled`, `mxint8_btb`,
  `mxint8_multitile`, and `mxint8_matmul_nphase`.
- `DOCS_MX/scripts/run_regression.sh --dims=16 --skip-stock-elab --timeout-cycles=300000000`
  passed all DIM16 MX tests: `mxint8_matmul_dim16`, `mxint8_multitile`, and
  `mxint8_matmul_nphase`.
- The previous apparent direct-test stalls were target-side regression-test
  runtime issues. The slow tests now use deterministic scalar expected values
  and sampled per-tile checks where appropriate, while the hardware paths and
  MX scalar helpers remain unchanged.

## Implemented Capability

MXINT8 is implemented as a sidecar around the existing int8 weight-stationary
Gemmini datapath.

- The supported profile is MXINT8 only: OCP block size 32, E8M0 scale bytes,
  signed int8 payloads with implicit `2^-6`, signed integer accumulators,
  weight-stationary dataflow, and power-of-two DIM values in `{4,8,16,32}`.
  These constraints are elaboration guards in `GemminiConfigs.scala`.
- The ISA extension uses custom commands `CONFIG_MXINT8`, `LOAD_MX_SCALE_A`,
  `LOAD_MX_SCALE_B`, and `LOOP_WS_MXINT8` (`GemminiISA.scala`, `gemmini.h`).
- Scale bytes are loaded through a separate scale stream into `MXScaleSRAM`.
  The SRAM stores decoded exponents; masked-valid `0xff` NaN scale bytes assert
  rather than being propagated into integer outputs.
- `Controller.scala` routes MX scale loads separately from normal payload loads,
  wires the scale SRAM read ports to the execute path, and exposes the drain
  pulse used by the scale-half interlock.
- `ReservationStation.scala` distinguishes MX scale loads from normal load
  configuration commands. This fixed the earlier RAW hazard where scale mvins
  polluted the `CONFIG_LOAD` mirror and let dependent compute issue too early.
- `ExecuteController.scala` applies the MX block scale at the accumulator write
  boundary. It performs ties-to-even right shifts, saturating left shifts to the
  int64 software-golden envelope, final saturation to the accumulator type, and
  DIM<32 raw-partial buffering across physical K phases.
- The MX software layer provides `mxint8_pack.h`, `mxint8_golden.h`,
  scale-load helpers, `gemmini_loop_ws_mxint8`, `tiled_matmul_mxint8`, and the
  Phase C `mxint8_pretile_b_scales` / `tiled_matmul_mxint8_pretiled` API.
- The outer tiler now handles multi-tile problems, DIM<32 phase-adjacent walks,
  padded power-of-two C tile pitch, B-scale tile caching, offline B-scale
  pretiling, fenceless chunk issue with scale-half interlock, and resident A/B
  payload reuse when the chunks fit the scratchpad ping-pong regions.

## Completed Milestones

| Area | What changed | Outcome |
|---|---|---|
| Initial sidecar | Added MX config parameters, custom commands, scale SRAM/load path, execute-side block scaling, and C headers. | Stock path remains separately configurable; MX path is gated by `mx_enabled`. |
| OCP conformance pass | Fixed the packer to the OCP MX section 6.3 recommended scale rule and added external `microxcaling` cross-checks. | MXINT8 pack/golden behavior matches the intended profile; documented deviations are deliberate. |
| Reservation station RAW fix | Marked scale mvins as `is_mx_scale`, excluded them from the normal `CONFIG_LOAD` mirror, and completed them on real DMA completion. | Fixed the early stale-A failure described in the old bug note. |
| DIM generalization | Added DIM16/8/4 support with strict phase ordering, raw partial buffering, and multi-tile drain-walk checks. | Latest regression matrix reports DIM32 8/8, DIM16 3/3, DIM8 2/2, DIM4 2/2, plus stock elaborate PASS. |
| B-scale tile-cache fix | Repacked the tiled B-scale image once per `(j,k)` chunk instead of once per `i` chunk. | 256^3 improved from 181,703 cycles to 74,484 cycles in the measured DIM32 run. |
| Phase C offline pretiling | Added an untimed pretiler for constant B scales and a pretiled matmul entry point. | Timed B-scale repack became 0 cycles; 256^3 improved to 57,423 cycles, 1.63x the then-stock 35,238-cycle run. |
| Fenceless tiler and scale-half interlock | Replaced per-chunk fences with a hardware credit interlock between A-scale mvins and execute drain completion. | Full regression stayed green. Fence cycles collapsed, but depth-2 scale capacity moved the stall to issue backpressure; 256^3 became 56,330 cycles. |
| Operand reuse fix | Ported stock resident A/B payload reuse into the MX tiler using `a_spad_id`/`b_spad_id` and `NULL` payloads on reuse iterations. | Latest banked result: 256^3 is 45,483 cycles versus in-harness stock 38,632 cycles, or 1.18x stock. 128^3 is 9,359 versus stock 8,746. |
| Path B experiment | Built a load-scales-once design with larger scale SRAM and global offset decode, measured it, then reverted it. | 256^3 reached 44,529 cycles, only about 2.7% better than operand reuse, at the cost of extra scale SRAM and RTL complexity. Poor ROI; not banked. |

## Latest Performance Story

The current banked implementation is the software-only operand-reuse fix stacked
on the Phase C and interlock work.

| Shape | Phase C | Interlock only | Latest banked MX | In-harness stock |
|---|---:|---:|---:|---:|
| 128^3 | 10,102 | 10,172 | 9,359 | 8,746 |
| 256^3 | 57,423 | 56,330 | 45,483 | 38,632 |

The largest historical loss was not the scale DMA itself. The sequence was:

1. Redundant B-scale host repacking dominated the earliest 256^3 results.
2. Offline B-scale pretiling removed timed repack for constant weights.
3. The per-chunk fence was real, but removing it exposed the depth-2
   scale-SRAM/concurrent-loop ceiling rather than delivering parity by itself.
4. The remaining major gap was MX re-fetching B payloads across the `i` sweep.
   Reusing resident B/A payloads removed that 2x operand-read behavior.
5. Loading all scales once (Path B) was measured and reverted because it barely
   improved the latest banked result while adding hardware area and complexity.

## Validation Already Reported

The latest checked-in notes report the following green gates:

- `run_regression.sh --dims=32,16,8,4` after the operand-reuse software fix:
  DIM32 8/8, DIM16 3/3, DIM8 2/2, DIM4 2/2, stock elaborate PASS.
- `run_regression.sh --build-sims --dims=32,16,8,4` after the hardware scale-half
  interlock: all MX sims re-elaborated, all listed tests passed, stock elaborate PASS.
- `mxint8_external_diff.py` cross-checked packer/golden behavior against
  `microxcaling` for the P1 conformance work.
- `mx_bench` was used for the measured performance progression above with
  `LOADMEM=1` / `+loadmem` to avoid TSI serial-loader artifacts.

## Superseded Findings

These older claims were intentionally not carried forward as live facts:

- "Scale DMA is 72% of runtime" was overturned by instrumentation. Scale-mvin
  issue cost became small after the real host-side repack and reuse issues were fixed.
- "Drain walk or BlockScaleUnit serialization is the root cause" was refuted by
  counters and later Path B/RES-OPT work. The BlockScaleUnit is combinational in the
  output cycle; it does not add a separate drain-latency pipeline.
- "The simulator hangs" was a measurement artifact from large `.bss` binaries,
  TSI loading, stdio buffering, short timeouts, stale auto-rebuilds, and stray sims.
  Large baremetal runs should use `LOADMEM=1`.
- "MX only supports a single output tile" is obsolete for the public tiled path.
  The current outer tiler supports multi-tile MX loops within the documented
  wrapper envelopes.
- The old `DOCS_MX/scripts/results/comparison_report.md` and `perf.csv` numbers
  predate the latest July 3 fixes and are not canonical.
