# MXINT8 performance analysis (P-A attribution record)

Measured on Verilator, `run_perf.sh`, square shapes, run-binary-fast. Cycle counts from
`read_cycles()` around the timed region (scale+payload mvin + compute + mvout).
`ideal = M·N·K/DIM²` (one MAC per PE per cycle). `util = ideal/cycles`.

## Data

### DIM = 16 (2 phases/block, multi-tile reorder engaged)
| shape | stock cyc | MX cyc | MX/stock | stock util | MX util | MX cyc/tile |
|---|---:|---:|---:|---:|---:|---:|
| 64³  | 5,420  | 6,053   | 1.12× | 18% | 16% | 94.6 |
| 128³ | 11,639 | 35,829  | 3.08× | 70% | 22% | 70.0 |
| 256³ | 76,914 | 268,721 | 3.49× | 85% | 24% | 65.6 |

### DIM = 32 (1 phase/block — NO phase split, NO reorder, weight reuse preserved)
| shape | stock cyc | MX cyc | MX/stock | stock util | MX util | MX cyc/tile |
|---|---:|---:|---:|---:|---:|---:|
| 64³  | 5,096  | 5,742   | 1.13× | 5%  | 4% | 717 |
| 128³ | 9,950  | 27,614  | 2.78× | 20% | 7% | 431 |
| 256³ | 36,876 | 193,893 | **5.26×** | 44% | **8%** | 378 |

(cyc/tile uses tiles = (M/DIM)(N/DIM)(K/DIM); DIM32 tile ideal = 32 cyc, DIM16 = 16.)

## Key finding — the original DIM<32 hypothesis is WRONG

The first analysis blamed the DIM<32 phase-adjacent reorder (lost weight reuse). The
DIM=32 run refutes that as the **dominant** cause:

1. **MX is ~5× slower even at DIM=32**, where `mx_phases==1`, `mx_reorder=false`, and B
   weight reuse across `i` is fully preserved (same loop order as stock). So the reorder
   is *not* the main lever.
2. **The gap grows with K-depth**: 1.13× (K=64) → 2.78× (K=128) → 5.26× (K=256). The
   penalty is incurred *per K-step* (per logical block), and accumulates with the
   reduction extent.
3. **It is not the fence (software).** A single, fenceless MX hardware loop is already
   ~7% util: 128³ DIM32 = 2 invocations, each I=2,J=4,K=4 = 32 tiles in 13,807 cyc =
   431 cyc/tile vs 32 ideal. The per-tile penalty exists *inside* one invocation.
4. **MX util is flat (~4–8%) and does not amortize with problem size**, whereas stock
   climbs (5→20→44% at DIM32, 18→70→85% at DIM16). A per-K-step fixed stall fits;
   pure per-invocation overhead does not.

### Working hypothesis (to be confirmed by P-A.2 diagnostic)
A per-K-step serialization in the MX execute/drain path that stock does not have —
candidates, in order of suspicion:
- **No cross-matmul overlap in MX mode**: the mesh cannot accept matmul *N+1* until *N*
  has fully drained, because the strict drain-order scale walk
  (`ExecuteController.scala:1021–1062`) and/or `mx_reset` require non-overlapping outputs.
  Each k-step then costs feed + full systolic fill/drain (~2–3·DIM) with no pipelining,
  and this recurs every k-step → grows with K, scales with DIM. (Stock overlaps these.)
- Accumulator RAW across K lengthened by the added BlockScaleUnit drain latency
  (each k-step's accumulate-read waits on the previous scaled write).
- Scale-SRAM read-ahead / prefetch not keeping pace, stalling the drain.

The reorder/weight-reuse loss (original P-C) is now demoted to a **secondary, DIM<32-only
additive** effect (it explains why DIM16 256³ is 3.5× while a hypothetical reuse-preserving
DIM16 would be closer to the DIM32 5×-class penalty per-block but with cheaper tiles).

## P-A.2 RESULT — root cause CONFIRMED via CounterFile (DIM=32)

Ran `mx_bench` instrumented with hardware counters on the DIM=32 sims.

### Pass 1 — execute-pipeline hazards (MX DIM32)
| shape | cycles | exe_active | flush | cq_block | preload_haz | overlap_haz | rs_active |
|---|---:|---:|---:|---:|---:|---:|---:|
| 64³  | 5,738   | 381 (7%)    | 0 | 0 | 0 | 0 | 2,420 |
| 128³ | 27,827  | 3,077 (11%) | 0 | 0 | 0 | 0 | 9,645 |
| 256³ | 193,893 | 24,718 (13%)| 0 | 0 | 0 | 0 | 52,929 |

→ Kills the "no cross-matmul overlap / preload serialization" theory: **all execute
hazard counters are flat zero**. The array computes its ~ideal cycles then idles ~88%.

### Pass 2 — byte volume + DMA wait, MX vs stock at identical shapes (DIM32)
| shape | impl | cycles | rd_bytes | wr_bytes | exe_active | ld_active | st_active | ld_wait | st_wait | rs_active |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 64³  | stock | 3,826 | 32,768  | 16,384 | 366   | 285   | 1,224 | 0 | 0 | 2,346 |
| 64³  | mx    | 5,713 | 32,768  | 16,384 | 381   | 249   | 1,188 | 0 | 0 | 2,419 |
| 128³ | stock | 8,430 | 131,072 | 65,536 | 2,680 | 1,454 | 5,067 | 0 | 0 | 7,715 |
| 128³ | mx    | 27,827| (n/c)   | (n/c)  | 3,077 | (n/c) | (n/c) | (n/c)|(n/c)| 9,645 |

(n/c = not captured; the new-counter MX 128³/256³ run was killed after 64³. Old-counter
MX values reused for cycles/exe_active.)

### The controlled result (64³, single chunk)
At 64³ MX and stock are a **single invocation with byte-for-byte identical traffic**
(rd 32,768 / wr 16,384) and **identical** exe_active, ld/st_active, **zero** DMA wait, and
near-identical rs_active — yet **MX takes 5,713 vs 3,826 cycles (1.49×)**. The ~1,900-cycle
delta is **invisible to every DMA/compute/RS counter**. (Note `rd_bytes` is *identical*
even though MX additionally loads E8M0 scales — so the scale-DMA path is **not counted** by
`RDMA_BYTES_REC`/`LOAD_ACTIVE_CYCLE`.)

### Conclusion — confirmed root cause
The MX overhead is the **per-software-chunk, MX-only, serialized prologue**: the
`gemmini_fence()` + `CONFIG_MXINT8` reconfigure + the **separate scale-load DMA**
(`mx_scale_load_controller`), executed *before* the matmul and **not overlapped** with
compute or payload DMA. It is invisible to the main-path counters and recurs **once per
chunk**:

- chunks per shape (tiler): 64³ = 1, 128³ = 2, 256³ = 8
- MX/stock ratio:            1.49×,     3.3×,      5.26×

i.e. ratio tracks **chunk count**, not MACs. Secondary contributor: with `i_chunk=2`
(DIM32) the B payload is re-fetched once per i-chunk (small-chunk re-fetch tax) — to be
quantified once MX 128³/256³ byte counters are captured.

**Ruled out:** DIM<32 reorder / weight-reuse (DIM32 has neither, still 5×); compute
hazards (zero); DMA bandwidth / memory latency (`*_wait` = 0); output-width unfairness
(both use `full_C` int32 — identical `wr_bytes`).

## Fix direction (supersedes the original plan's P-C-first ordering)
1. **Eliminate the per-chunk serialized prologue** — make `CONFIG_MXINT8` RS-ordered and
   the scale mvins ordered/overlapped so consecutive chunks pipeline like stock and the
   `gemmini_fence()` in `gemmini_loop_ws_mxint8` is removed. (PRIMARY — this is what the
   64³ controlled result isolates.)
2. **Load scales once per problem** when they fit `MX_SCALE_SP_ROWS`, and **enlarge chunks**
   (raise the Jp cap / `i_chunk`) so there are far fewer invocations and no B re-fetch.
3. DIM<32 reorder/weight-reuse work is a **distant secondary** (only relevant once the
   prologue cost is gone), and even then favor the accumulator-SRAM partials (no FF).

## P1 RESULT (2026-06-18→19) — zero-RTL chunk enlargement is a DEAD END at DIM32

Hypothesis: the tiler reserves only `ACC_ROWS/2` for output tiles (policy A.1 says the
limit is `ACC_ROWS`), so relaxing it to the full accumulator would halve the chunk count
(8→4 at 256³) and thus the per-chunk prologue — pure software.

Tried it (`i_chunk = ACC_ROWS/(jp*DIM)`, wrapper guard → `ACC_ROWS`). Result on MX DIM32:
- 64³ **PASS** bit-exact (small; output = 128 acc rows, never enters the upper half).
- 128³ **ASSERT** `ExecuteController.scala:1269` — "tag-derived output tile disagrees with
  the drain-order walk" — the instant output spanned past `ACC_ROWS/2`.

Cause: the MX tag→tile recovery masks the tile index with
`mx_acc_tiles_half = (acc_banks*acc_bank_entries)/(2*DIM)` (ExecuteController.scala:1262).
**The upper half of the accumulator is the loop-unroller's double-buffer**, masked off by
design; the policy-A.1 "`ACC_ROWS`" wording is aspirational, the RTL implements `ACC_ROWS/2`.
So the `/2` is a hardware constraint, and **chunk count is RTL-floored**: ≤ `ACC_ROWS/(2*DIM)`
output tiles per invocation (8 at DIM32) ⇒ 8 chunks at 256³ regardless of i/j/Jp shape.
Reverted (kept an explanatory comment at the tiler).

Corollary — **P2 (load scales once) is also capacity-limited at 256³ DIM32**: the A-scale
region is `MX_SCALE_SP_ROWS/2 = 128` rows = exactly `i_chunk*DIM = 4*32`, i.e. only one
i-chunk's A-scales fit, so they must reload per i-chunk anyway. (P2 still helps where the
whole scale image fits, e.g. smaller N or DIM16.)

⇒ The remaining lever is **P3 (RTL): remove the per-chunk fence by RS-ordering CONFIG_MXINT8
and overlap the scale-DMA**, so the (RTL-floored) chunk count stops mattering — the chunks
pipeline like stock's single fenceless loop. The half-accumulator double-buffer is itself an
RTL knob that could be revisited alongside P3, but the fence is the first-order fix.

**Safety note:** the bit-exact golden + the tag-vs-walk assert caught the bad relaxation on
the very next shape — the self-checking design did its job; nothing was committed.

## P3 Step 0 (fast-test harness) + ceiling experiment (2026-06-19)

`bareMetalC/mx_fence_probe.c` (new): tiny MX-only probe, N=DIM (Jp=1), K=MX_BLOCK_SIZE
(1 block), M forces 1/2/4 i-chunks of identical per-chunk work. Reads `MXFENCE` cycles +
CounterFile. Baseline on the built MX DIM32 sim:

| chunks | M | cycles | cyc/chunk | exe_active | rs_active |
|---|---:|---:|---:|---:|---:|
| 1 | 128 | 2,613 | 2,613 | 166 | 1,735 |
| 2 | 256 | 3,745 | 1,872 | 333 | 3,246 |
| 4 | 512 | 7,167 | 1,791 | 693 | 6,363 |

Steady-state ≈ **1,700 cyc/chunk for ~166 cyc of compute** (~90% prologue/drain). (This
shape hits the dense scale fast-path, so the repack "Job B" is not even exercised — the
entire per-chunk cost is "Job A": fence + CONFIG_MXINT8 + scale-DMA drain.)

**Ceiling experiment (fence physically removed, SW only):** 1-chunk still PASSES at 2,618
(no prior work to race with); the **2-chunk case immediately tripped the tag-vs-walk assert
(`ExecuteController.scala:1269`)** — the RESET_K race (chunk 2's CONFIG_MXINT8 zeroes the
drain walk mid-drain of chunk 1). Reverted. This **confirms the P3 premise end-to-end**:
the fence is the serializer, and removing it naively hits exactly the walk-reset hazard that
Step 3 (drain-ordered boundary re-init, replacing the unordered pulse) must fix. The
existing assert is a reliable instant tripwire for the regression.

**Success target for P3** (this probe): 4-chunk cycles 7,167 → ≪ 7,167 (cyc/chunk falling
toward exe_active≈166-bound), still bit-exact PASS.

## P3 IMPLEMENTED — reset-free fenceless pairing (2026-06-19/20)

Mechanism (DOCS_MX/P3_DESIGN.md): the drain-order walk self-cycles per loop via a new
`k_blocks` field (wraps `mx_out_kb` to 0 at the loop boundary — **no reset signal**), a
parity bit toggles on that wrap and selects a scale-SRAM ping-pong half, and the software
tiler drops the per-chunk `gemmini_fence()`, running independent output-tile chunks as
**depth-2 fenceless pairs** (RESET_K only at the first/fenced chunk). All gated so legacy /
`k_blocks==0` is bit-for-bit unchanged (validated by an inert rebuild).

**Correctness: fully bit-exact.** `mxint8_multitile` 5/5 (probe2x2/2x3, edges 61x59x64,
rand2x2 K=128, and the `tiled_matmul_mxint8` tiler case), `mxint8_matmul_nphase`, and the
probe all PASS; the tag-vs-walk assert stays silent.

**Performance (DIM32 mx_bench, bit-exact):**
| shape | baseline | P3 | Δ |
|---|---:|---:|---:|
| 64³  | 5,742   | 5,953→5,792 | ~flat (1 chunk; nothing to overlap) |
| 128³ | 27,614  | 26,361 | −4.5% |
| 256³ | 193,893 | **180,278** | **−7.0%** |

**Modest, not the large win the chunk-ratio analysis implied.** The gain ≈ the removed
fence-drains (4 of 8 fences at 256³ ≈ 3.4k cyc each ≈ the 13.6k saved). The paired loops do
**not** overlap much more than that: alternating the scratchpad halves by parity
(`spad_id` 1/2) was measured to **not** help (256³ 181,446, noise-level) — so the scratchpad
is not the limiter. The residual per-chunk cost is the **scale-mvin + CONFIG prologue**,
which depth-2 pairing does not hide, plus the 4 remaining fences.

### Deeper-overlap root cause (counters + unroller code)
P3 256³ counters vs stock: **rs_active 30% (MX) vs 97% (stock)** — the reservation station
is idle 70% of the time, so the mesh is *command-starved*, not compute/DMA-bound
(ld_wait/st_wait = 0; MX also reads 2× the bytes from small-i_chunk B re-fetch). The
serializer is `LoopMatmul.scala:1082`: a **non-loop** command is accepted only when
`!loop_configured` (no loop in flight). MX injects `CONFIG_MXINT8` + scale-mvins **between**
loops, so chunk N+1's setup blocks until chunk N drains — re-serializing despite
`concurrent_loops=2`. Stock has nothing between its `LOOP_WS` calls, hence rs_active 97%.

**Fix (software, no rebuild):** issue a same-geometry *pair*'s setup (one config + both
chunks' scales into the two ping-pong halves) **first**, then the two `LOOP_WS` back-to-back
with no command between them, so the unroller configures both slots and they pipeline.

**Why not just more partitions:** more in-flight chunks would need more scale-SRAM partitions, but each
partition shrinks per-chunk scale capacity (→ more chunks), a capacity-vs-overlap wash on
the small scale SRAM. Genuinely larger gains need either (a) RS-ordering CONFIG_MXINT8 +
overlapping the scale-DMA with prior compute (so the prologue hides), or (b) investigating
why the 2 paired loops don't overlap further (likely the interleaved CONFIG/scale-mvin
instructions serializing the unroller, or the single scale-load DMA controller) — a
follow-up. P3 as landed is a **correct, bit-exact ~7% win and the foundation** for those.

## D0/D1 DECISIVE CONFIRMATION (2026-06-21) — unroller starvation, ~4× ceiling

**D0** single-chunk sweep (no rebuild): an isolated 64-matmul chunk = 7,663 cyc (~27% util),
but in 256³ each such chunk costs ~22,535 (180k/8) — a **~3× inter-chunk inflation that grows
with chunk count** (2.4× at 128³ → 2.9× at 256³). So the cost is neither per-matmul nor
per-chunk-isolated-setup.

**D1** existing counters, MX vs stock 256³ DIM32:
| counter | MX | stock |
|---|---:|---:|
| cycles | 180,565 | 37,101 |
| loopmm_active (unroller busy) | 41,414 (**23%**) | 35,050 (**94%**) |
| spadB_wait (mesh starved on B) | 153,395 (**85%**) | 13,777 (37%) |
| rdma_active | 15,978 (9%) | 24,043 (65%) |
| tlb_miss | 0 | 0 |

⇒ **Confirmed: loop-unroller starvation.** The unroller does ~the same *absolute* work
(41k vs 35k active cyc) but idles **77%** in MX → mesh waits 85% for B. Cause = the
`LoopMatmul.scala:1082` gate (already hypothesized above), now *proven* by the counters.
Ruled out: TLB (0), DMA bandwidth (~9–11%), per-matmul drain (isolated chunk fine).
**Ceiling: ~4×** (180k → ~45k, near stock 37k) if the unroller never stalls.

### D2 fix being implemented: un-gate MX setup in the unroller
Let `CONFIG_MXINT8` (no RESET_K) + `MVIN_MXSCALE_A/B` pass through `LoopMatmul` *while a loop
is in flight* (arbitrated below the unrolled stream), so chunk N+1's setup issues during
chunk N's compute → they pipeline via `concurrent_loops=2`. Safe: such configs are
idempotent (same geometry, no reset — walk self-cycles via `k_blocks`) and scales hit the
other ping-pong half; the every-other-pair fence guarantees a reused scale half is drained
first, and geometry changes still fence. (The earlier SW deferred-pairing failed *because*
it could not remove this gate — setup still waited for `!loop_configured`.)

### D2 RESULT — un-gate IMPLEMENTED, bit-exact, but ZERO perf gain (gate hypothesis DISPROVEN)
Implemented the un-gate in `LoopMatmul.scala` (MX CONFIG-no-reset + scale-mvins pass into
unrolled-stream bubbles, `mx_enabled`-gated). `mxint8_multitile` 5/5 + `nphase` bit-exact.
But 256³ = 180,094 (was 180,565) — **unchanged**; `loopmm_active` 41,401 and `spadB_wait`
153,060 also unchanged. So the inter-chunk command gate is **NOT** the bottleneck.

**Precise meaning of the bottleneck** (`SCRATCHPAD_B_WAIT_CYCLE`, ExecuteController.scala
:1401): fires when `cntl.b_fire && mesh.io.b.ready && !mesh.io.b.fire` — i.e. the controller
wants to feed the **B (weights) operand**, the mesh can accept it, but **`mesh.io.b.valid`
is false because the B scratchpad-bank read response isn't valid** (data not in/returning
from the scratchpad). 85% of cycles. And read-DMA is only 9% busy → the B weights aren't
arriving in the scratchpad in time, but **not** because DMA is saturated.

**Hypotheses now DISPROVEN by measurement:** (1) DIM<32 reorder/weight-reuse; (2) per-chunk
fence (−7% only); (3) SW deferred-pairing (worse); (4) spad_id alternation (no change);
(5) per-matmul drain (isolated chunk fine); (6) inter-chunk command gate / unroller
starvation (un-gate no change); (7) TLB (0); (8) DMA bandwidth (9%).

**Remaining live hypotheses (need instrumentation, not guessing):**
- The two `concurrent_loops` slots may **not actually overlap** for MX (loop1's ldB not
  prefetching during loop0) — needs a "≥2 loops configured" counter to confirm overlap
  even happens.
- **Scratchpad B-bank read/write contention** (the 2× B re-fetch DMA writing the bank while
  the mesh reads it) — needs a bank-conflict counter.
- Intra-loop ldB issue starvation (the unroller not issuing B-loads fast enough *within* a
  loop) vs inter-loop.

**Status: 6 fixes attempted, all ineffective or marginal. The B-weights-feed starvation is
real but its cause is not yet pinned. Continuing requires targeted RTL instrumentation of
the load/scratchpad path, not more speculative fixes.**

## ROOT CAUSE PROVEN (2026-06-22) — the per-row scale-load DMA

No-rebuild experiment (skip the scale mvins, measure cycles; results FAIL, that's expected):
| 256³ DIM32 | with scales | scales skipped |
|---|---:|---:|
| cycles | 180,094 | **50,853** |
| util | 9% | **32%** |
| loopmm_active | 41k (23%) | **44.7k (88%)** |
| spadB_wait | 153k (85%) | 24k (47%) |

⇒ **The scale load is ~129,000 cycles = 72% of the runtime.** With it removed MX is only
**1.37× stock** (50,853 vs 37,101) and the unroller runs 88% busy — the compute/feed path is
stock-class; the scale load is the whole gap. (The earlier "spadB_wait / unroller-idle" were
*symptoms*: the per-pair `gemmini_fence()` waits on `io.busy`, which includes the slow scale
DMA, so the CPU stalls issuing the next chunk → unroller idle → mesh starves.)

**Exact RTL cause:** `MXScaleLoadController` issues **one DMA request per scale row** (the
`row_counter` loop), because `Scratchpad.scala:428` drives the MX `StreamReader` with
`len := cols` (one row, DIM bytes) and `repeats := 0`. So a scale mvin of R rows = R tiny
serialized DMA round-trips (TLB + TL each); 256³ ≈ 768 of them. The `StreamReader` supports
multi-row bursts (the main mvin uses them); the MX path simply isn't.

**Fix (D2-real): batch the scale mvin into one multi-row burst.** Drive the MX StreamReader
to transfer all R rows in one request (contiguous when `stride == cols`, which holds for the
dense A/B scale layouts) instead of the per-row loop. Expected: 256³ → ~50–60k (~3.5× vs the
current 180k, ~1.4× stock). Bit-exact gated; per-row loop kept as the strided fallback.

### Batch attempt (B-scale, cols==DIM) — HUNG (completion-tracking bug); reverted
First cut: `MXScaleLoadController` issues ONE request (`n_reqs=1`) and the Scratchpad mx
StreamReader uses `repeats := rows-1` to stream all rows, but **only for `cols==DIM` rows**
(B-scale) — A-scale (`cols=k_blocks<DIM`) can't burst because the StreamReader advances the
dest SRAM row every `spadWidthBytes`(=DIM), so narrower rows would collapse. sbt-clean, but
the run **timed out** (`ReservationStation.scala:595 "pipeline stall"`): the single request's
`cmd_tracker.bytes_to_read = rows*cols`, but the burst's `mx.read.resp` fires only at
`.last` (Scratchpad.scala:446) and reports too few bytes, so `cmd_completed` never fires →
the scale mvin never retires → the dependent matmul stalls. Reverted to the working per-row
load (RTL back to the bit-exact P3 baseline).

**Precise follow-up to make the burst work:** reconcile the burst's byte accounting —
either set `cmd_tracker.bytes_to_read` to what `mx_reader.resp.bits.bytes_read` reports at
`.last`, or make the resp report the cumulative burst bytes; also verify the
`mx_scale_reader` StreamReader is configured to allow `repeats>0` (its constructor passes a
`1` for what may be a per-request row cap). Then extend to A-scale via a DIM-wide host repack
(pad each A-scale row to DIM bytes) so it also bursts. This is delicate DMA-internal work but
the payoff is proven (~3.5×).

### Burst attempt v2 (per-beat completion) — ALSO HUNG; reverted
Changed the Scratchpad mx `resp` to fire per beat (not only `.last`) so the cmd_tracker
accumulates `rows*cols` across the burst. Still `pipeline stall` timeout. So the
`StreamReader` `repeats>0` path does not drive the BeatMerger/xactTracker the way the
cmd_tracker expects under the MX scale reader's config (`meshRows = 1`, `aligned_to = 1`,
constructed at Scratchpad.scala:211). Two burst variants, two hangs ⇒ the multi-row burst
needs real understanding of `StreamReaderCore`/`BeatMerger` beat & `bytes_read`/`.last`
semantics (and likely a `meshRows`/`repeats` config change), not a surface tweak. Reverted
to the working per-row load (RTL at the bit-exact P3 baseline).

### Recommended next path (two options, lower-risk first)
1. **Pipeline the per-row loads instead of bursting** (likely simpler & safe): the per-row
   path is *correct* but ~serialized (~168 cyc/row ⇒ ~1 in flight). Check whether
   `MXScaleLoadController` / the mx `StreamReader`'s in-flight depth (`nXacts =
   max_in_flight_mem_reqs`, `nCmds`) is the limiter, and let many row requests be outstanding
   at once. If the rows pipeline at the DMA latency, the ~129k collapses without touching the
   burst/BeatMerger semantics.
2. **Make the burst complete correctly**: study `StreamReaderCore` (DMA.scala:124+) +
   `BeatMerger` to learn how `repeats`/`meshRows` produce beats and what `resp.bytes_read`/
   `.last` report, then set `cmd_tracker.bytes_to_read` (or the resp) to match; extend to
   A-scale via a DIM-wide host repack.

**Bottom line: root cause is PROVEN (per-row scale load = 72% of MX runtime, fix → ~3.5×,
near stock). The RTL is in a clean, bit-exact, working state. The fix is well-scoped DMA
work (option 1 first) but needs a focused pass — 8 surface attempts were spent localizing,
not fixing.**
