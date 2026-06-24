> **SUPERSEDED (2026-06-24).** Direct instrumentation overturned the premise of this plan:
> the scale-load DMA is only **2.6%** of the 256³ runtime, not 72%. Do **not** implement the
> burst. The true bottleneck is **MX matmul serialization** (single drain-walk + one
> BlockScaleUnit ⇒ consecutive matmuls don't pipeline ⇒ mesh feed starves ~86%). See the
> "CORRECTION (2026-06-24)" section at the end of `PERF_ANALYSIS.md`. This file is kept only
> as a record of the (correct) analysis of the scale-load path itself.

# MXINT8 scale-load DMA fix — batch the per-row mvin into one strided burst

## 1. Problem (proven, not guessed)

MXINT8 is bit-exact but ~5× slower than stock at 256³ (DIM32). The dominant cost is the
**per-row scale-load DMA**: **~129,000 cycles = 72% of the 180,278-cycle 256³ runtime**.

Evidence (no-rebuild skip-scales experiment, `PERF_ANALYSIS.md`):

| run                | cycles  | util | loopmm_active | spadB_wait |
|--------------------|---------|------|---------------|------------|
| MX 256³ (baseline) | 180,094 |  9%  | 23%           | 85%        |
| MX 256³ skip-scales|  50,853 | 32%  | 88%           | 47%        |

With the scale loads removed, MX is only **1.37× stock** — the compute/feed path is
stock-class. Eight other hypotheses were measured and ruled out (DIM<32 reorder, per-chunk
fence, deferred-pairing, spad_id, per-matmul drain, unroller gate, TLB, DMA bandwidth).

## 2. Why per-row is slow (RTL)

`MXScaleLoadController` loops `row_counter`, issuing **one DMA request per scale row**
(`MXScaleLoadController.scala:62-102`):

```
io.dma.req.bits.vaddr := vaddr + row_counter * actual_stride
io.dma.req.bits.laddr := localaddr + row_counter
row_counter := Mux(row_counter === rows - 1.U, 0.U, row_counter + 1.U)   // on req.fire
```

and `Scratchpad.scala:428-429` drives the mx `StreamReader` with `len = cols` (≤ DIM = 32
bytes), `repeats = 0`. So each scale row is one ≤32-byte request that pays a full DRAM
round-trip (~168 cyc/row), effectively serialized, even though `max_in_flight_mem_reqs = 16`
transactions are available. With ~64–96 rows/chunk × 8 chunks this is the ~129k idle, and
**no standard counter tracks it** (RDMA/LOAD counters are the main `reader`, not this one).

## 3. Why the two earlier burst attempts hung

Both set `repeats > 0` on the mx `StreamReader`. `repeats` is the **pixel/broadcast**
mechanism, not multi-row striding; under the mx reader's `meshRows = 1` it does not drive the
`BeatMerger`/`xactTracker` the way the `DMACommandTracker` completion logic expects, so
`cmd_tracker.bytes_to_read` was never satisfied → pipeline stall (`ReservationStation.scala:595`).
Lesson: the multi-row mechanism is **`len` + `block_stride`, never `repeats`.**

## 4. Why a real burst is feasible (the native, proven mechanism)

`StreamReaderCore` already fills **many destination rows from a single contiguous request**
via `len` + `block_stride`. The destination address steps once per spad-row-width of bytes
consumed, while the source `vaddr` advances contiguously:

```
io.reserve.entry.addr := req.spaddr + req.block_stride * (bytesRequested / spadWidthBytes)  // DMA.scala:267
next_vaddr            := req.vaddr  + read_bytes_read                                        // DMA.scala:277
```

This is exactly how the regular `reader`/`LoadController` mvins a whole multi-row matrix
block in one request (`Scratchpad.scala:411-417`). For the mx scale reader,
`spadWidthBytes == DIM` (one MXScaleSRAM row holds DIM scale bytes), so **one request with
`len = rows*DIM`, `block_stride = 1` fills `rows` consecutive scale-SRAM rows.**

The scale source is already **contiguous**:
`gemmini_mvin_mxscale_b(B_scale, addr, k_blocks*jp, DIM, /*stride=*/DIM)` → `stride == cols
== DIM`, and `mxint8_repack_b_scales_tiled` produces a packed `[k_blocks*jp][DIM]` buffer
(`gemmini.h:498-510, 636-642`); A-scales are a packed `[I*DIM][DIM]` image. So a single
strided request covers a whole scale image whenever `stride == cols`.

## 5. Fix design

**Primary:** when `actual_stride === cols` (contiguous, the normal case) issue **one** DMA
request covering all `rows` instead of the `row_counter` loop:
- `MXScaleLoadController.scala`: collapse the per-row FSM to a single request when contiguous;
  keep `cmd_tracker.bytes_to_read = rows*cols` (already sized via `maxBytesInMatRequest`) and
  complete when the tracker's accumulated `bytes_read` reaches it. Preserve the `nCmds>1` /
  DIM=16 combinational-`cmd_id` fix and the existing bounds asserts.
- `Scratchpad.scala:428-435`: drive `len = rows*cols`, `block_stride = 1`, `repeats = 0`;
  complete `mx.read.resp` for the multi-row request by mirroring the **regular
  `LoadController` cmd_tracker accounting** (sum resp `bytes_read` over the request, finish at
  the final beat) — the proven recipe, not the `.last`/`repeats` path that hung.

**Fallback (correctness safety):** if `stride != cols`, keep the existing per-row loop for
that one command (selected by a `Mux` on `stride === cols`). Correctness is never at risk.

**Additive (later, only after primary lands + is measured):** load A-scales once and reuse
across the K loop; overlap next-chunk scale-load with current compute (parity ping-pong
already present). Stacks on top of the burst.

Constraints: all changes `mx_enabled`-gated; stock bit-identical; Mesh/PE/MeshWithDelays
DO-NOT-TOUCH; no new runtime resets; bit-exact vs `mxint8_golden.h`.

## 6. Verification

1. **Bit-exact gate first** (non-negotiable), via `run-binary-fast`: `mxint8_multitile` 5/5,
   `mxint8_matmul_nphase`, `mx_fence_probe` at DIM32; then re-verify DIM16 (spot-check 8/4 —
   the multi-row/edge cases + `bytes_to_read` sizing history live there).
2. **Perf:** `mx_bench` 256³ should fall from 180,278 toward the **50,853** skip-scales floor,
   with the scale-load idle gone (loopmm_active climbing toward stock, spadB_wait falling).
3. **Stock unchanged:** stock elaboration bit-identical (MX changes are `mx_enabled`-gated).
4. Then the full `DOCS_MX/scripts` perf matrix (all DIMs + stock).

## 7. Critical files
- `generators/gemmini/src/main/scala/gemmini/MXScaleLoadController.scala` — per-row FSM →
  single contiguous burst (lines 62-126).
- `generators/gemmini/src/main/scala/gemmini/Scratchpad.scala` — mx `StreamReader` driving
  (421-448): `len`, `block_stride`, resp/cmd_tracker completion.
- `generators/gemmini/src/main/scala/gemmini/DMA.scala` — reference only: `len`+`block_stride`
  mechanism (267, 277), resp `.last`/`bytes_read` (88-108).
- `software/gemmini-rocc-tests/include/gemmini.h` — scale layout/contiguity (498-510, 628-642).
- `DOCS_MX/PERF_ANALYSIS.md` — full evidence trail and ruled-out hypotheses.
