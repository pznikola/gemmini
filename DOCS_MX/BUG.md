# BUG (FIXED): MXINT8 cross-block GEMM corrupted through the hardware loop unroller (`gemmini_loop_ws_mxint8`)

**Status:** **FIXED 2026-06-10.** Root cause found and corrected with a one-line decode guard in
`ReservationStation.scala`. No mesh changes; the Mesh/PE/MeshWithDelays DO-NOT-TOUCH constraint
was never actually in the way — the 2026-06-09 "mesh-internal state leakage" diagnosis was wrong.

**Severity (historical):** Medium. Only the loop-wrapper path was affected, and only for a loop
GEMM whose A-payload mvin lost a race (in practice: a multi-block loop GEMM preceded by another
loop GEMM). The manual preload/compute path was never affected.

**Dates:** investigated 2026-06-08 → 2026-06-09 (wrong conclusion), re-investigated and fixed
2026-06-10.

---

## 1. One-paragraph summary

A GEMM issued through the MXINT8 loop wrapper `gemmini_loop_ws_mxint8` produced wrong results
when its **A-payload mvin was still in flight while the first compute issued** — a reservation
station RAW-hazard miss. The MX scale-vector mvins (`LOAD_MX_SCALE_A/B`) are marked `is_config`
in the reservation station (correct: they carry no scratchpad address range), but the RS's
allocation hook decoded **every** `is_config` LD-queue entry as a `CONFIG_LOAD` — and a scale
mvin's `rs1` is the scale buffer's **DRAM pointer**, not a config word. Pointer bits were thus
written into the RS's `ld_block_strides(id)` / `ld_pixel_repeats(id)` mirror (with a 64-aligned
scale buffer, `id = ptr[4:3] = 0` — the A mvin's load id). Subsequent A-payload-mvin allocations
then computed their **dependency address range** with the garbage `pixel_repeats`
(`dst.start.floorSub(pixel_repeats)` shifted the range, e.g. [2048, 2080) → [1998, 2030)), the
compute's RAW overlap check missed the in-flight A mvin, and the compute issued while the A tile
was still being DMA-written. The mesh then multiplied **stale scratchpad rows** (uninitialized
or leftover memory — identical on every read in practice) against the correct B, producing the
historical "first K-tile frozen after ~1 row" symptom. Fixed by excluding `is_mx_scale` entries
from the CONFIG_LOAD decode hook.

---

## 2. The fix

`generators/gemmini/src/main/scala/gemmini/ReservationStation.scala`, allocation hook:

```scala
// before
}.elsewhen(new_entry.is_config && new_entry.q === ldqu) {
// after
}.elsewhen(new_entry.is_config && new_entry.q === ldqu && !new_entry.is_mx_scale) {
  // (comment in code explains the pointer-bits-as-config pollution)
```

Properties of the fix:
- **Pure decode gating** — no runtime resets anywhere, no new state, no mesh changes (the user
  constraint "never reset datapath state at runtime" is trivially satisfied).
- **Stock-invisible** — stock Gemmini never allocates `is_mx_scale` entries, so stock behavior
  is bit-identical.
- The scale mvins' own ordering remains fully handled by the existing catch-all (every EX op
  depends on every in-flight scale mvin) and their real-completion semantics
  (`complete_on_issue = false`).

## 3. Why the old diagnosis was wrong (and what its observations actually were)

The 2026-06-09 investigation concluded "inter-GEMM state leakage in the systolic mesh's internal
output pipeline; unfixable without touching the Mesh". Its **observations** were largely
accurate; the **interpretation** was wrong:

- *"Raw mesh output frozen after ~2 rows"* — CONFIRMED on the current RTL, but the mesh was
  faithfully computing `stale_row × B_new` every cycle: the **fed A rows** were one correct row
  followed by the same stale scratchpad row repeated (uninitialized memory reads return the same
  junk pattern row after row). Nothing inside the mesh ever held wrong state.
- *"Only fails when preceded by another loop GEMM"* — the race only bites when the A mvin's DMA
  is slow. The first GEMM of a program wins the race (cold but uncontended); a second GEMM loses
  it (TLB flushed by `gemmini_flush`, more memory traffic). The "inter-GEMM" character was a
  race-timing artifact, not state leakage.
- *"Timing-sensitive wrong values"* — exactly what a lost DMA race looks like.
- *"Dummy matmuls between GEMMs don't fix it"* — of course: they don't make the A mvin faster.
- *"Tags / scale reads / accumulate / mvout all correct"* — all true. The 2026-06-10
  re-investigation additionally verified: `mx_reset` pulses and counters, feed-side
  `mx_k_lane_counter`/`tag.mx_block`, drain-side `mx_matmul`/`mx_drain_block`, MXScaleSRAM
  writes and per-row eA/eB consumption — **every MX-sidecar element was correct** in the failing
  run.

The decisive experiment that broke the old conclusion: a **shape-matched stock control**
(`stock_btb_untr.c`: untransposed WS loop, GEMM1 K=DIM then GEMM2 K=2·DIM — the exact MX
sequence minus scales) **passes**, so the mesh + loop unroller handle the exact failing
instruction shape; only the MX run failed. The earlier version of that control used K=2·DIM for
both GEMMs (not shape-matched), which is why 2026-06-09 could not separate the two.

## 4. The evidence chain (2026-06-10, temporary `MX-DBG` instrumentation, since removed)

1. **Drain-side trace:** GEMM2 block0's raw mesh data: row 0 correct, rows 1–31 constant stale
   (probe decode: `-231` = stale row · all-ones B; random run: `30448`). Block1 fully correct.
   All MX scale indices/values correct. ⇒ corruption upstream of the mesh response.
2. **Feed trace (`mesh.io.a.fire`):** row 0 = correct A row (bytes 192,16), rows 1–31 = the
   same stale vector (199,250,…) fired repeatedly. ⇒ corruption upstream of the mesh feed.
3. **Scratchpad read trace:** the A reads issue perfect ascending addresses (bank 2, rows
   0–31, one/cycle, popped every cycle) — but return identical data for distinct addresses.
   ⇒ the bank *content* was stale, not the read path.
4. **Scratchpad write trace:** the A-payload mvin's DMA write for row 0 lands just before the
   read of row 0; the write for row 1 lands *after* the read of row 1 already happened; the
   burst completes long after the whole read sweep. ⇒ textbook RAW hazard.
5. **Reservation-station trace:** the compute allocated with `deps_ld = 00000011` — depending
   only on the two scale mvins, **not** on the A mvin (slot 3), whose dependency range had been
   decoded as [1998, 2030) instead of [2048, 2080) — shifted by exactly the garbage
   `pixel_repeats = 50` = scale-pointer bits [15:8] − 1.
6. **Post-fix trace:** the compute allocates with `deps_ld = 00001011` (A-mvin bit present);
   `mxint8_btb` random and probe runs **PASS** bit-exact.

## 5. Regression coverage

- `bareMetalC/mxint8_btb.c` — **registered** in the bareMetalC Makefile: loop GEMM K=32 then
  K=64 back-to-back (the exact historical failure), random data, bit-exact vs
  `mxint8_golden.h`. A `-DMXINT8_BTB_PROBE=1` build (unregistered binary) replaces GEMM2 with a
  deterministic probe whose wrong values decode to the exact scale/data consumed.
- `bareMetalC/mxint8_tiled.c` — now also runs the multi-block K=64 case back-to-back after the
  K=32 case (previously documented as a deferred limitation; limitation lifted).
- Unregistered investigation artifacts kept for reference: `mxint8_iso.c` (isolated K=64 loop
  GEMM), `stock_btb.c` / `stock_btb16.c` (stock transposed BtB), `stock_btb_untr.c`
  (shape-matched stock untransposed BtB — the decisive control).

## 6. Remaining (genuine) limitations

- MX v1 loop wrapper supports a **single output tile** (I = J = 1): the A-scale read address has
  no output-tile component. The wrapper fails loudly for I/J ≠ 1. (Unchanged; a later phase.)
- The int32 accumulator clamp vs int64 reference for very large K partials (documented in
  `mxint8_policy.md`). (Unchanged.)

## 7. Key file references

- `src/main/scala/gemmini/ReservationStation.scala` — the fix (CONFIG_LOAD decode hook guard) +
  the `is_mx_scale` catch-all dependency and real-completion semantics (pre-existing, correct).
- `software/gemmini-rocc-tests/bareMetalC/mxint8_btb.c`, `mxint8_tiled.c` — regressions.
- `DOCS_MX/PLAN_MX.md` — dated progress-log entries for the 2026-06-10 investigation (hypothesis
  kills, trace evidence, fix verification).
- `Mesh.scala` / `PE.scala` / `MeshWithDelays.scala` — **untouched**, and exonerated.
