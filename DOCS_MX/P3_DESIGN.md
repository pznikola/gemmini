# P3 design note — remove the per-chunk MX fence (RS-order CONFIG_MXINT8)

Status: **design for review, no RTL written.** Companion to `DOCS_MX/PERF_ANALYSIS.md`
(root-cause evidence) and the working plan. Goal: let consecutive `gemmini_loop_ws_mxint8`
invocations **pipeline** like stock instead of serializing behind a full `gemmini_fence()`,
without violating the standing rules (no runtime resets; `mx_enabled`-gated; stock
bit-identical; Mesh/PE/MeshWithDelays DO-NOT-TOUCH; bit-exact vs `mxint8_golden.h`).

## 1. Why the fence exists today (current mechanics)

`gemmini_loop_ws_mxint8` (gemmini.h:594) issues `gemmini_fence()` before every chunk. The
fence does **two** jobs (its own comment, gemmini.h:589–593):

**Job A — order the drain-walk reset.** `CONFIG_MXINT8` is handled at the controller
**front-end**, NOT through the reservation station (Controller.scala:558–586): it writes
global regs (`mx_runtime_enabled`, `mx_loop_{i,j}_tiles`, `mx_loop_log2_jp`,
`mx_scale_stride_{a,b}`) and fires a **1-cycle `mx_config_pulse`** (= `enable && RESET_K`,
Controller.scala:565) wired to `ex_mx.reset` (Controller.scala:395) →
`ExecuteController.mx_reset` (148). `mx_reset` zeroes the drain-order walk
(`mx_out_{kp,i,j,kb}`, ExecuteController.scala:1050) and the input phase counters (746/767).
Because the pulse fires at decode — unordered against in-flight drains — a previous chunk's
MX outputs still draining would have their walk zeroed mid-flight. The fence forces all
prior drains to finish first.

**Job B — protect the shared host repack buffer.** When B scales need repacking, the host
writes the static `mxint8_b_scale_tiled[]` image (gemmini.h:533) and a scale-mvin DMA reads
it; the next chunk repacks into the **same** buffer. The fence ensures the prior DMA
finished reading before the overwrite. (The dense fast path, gemmini.h:602, skips repack
and so does not need Job B.)

**What the fence is NOT needed for:** the scale loads themselves are **already RS-ordered**
— `LOAD_MX_SCALE_A/B` issue from `reservation_station.io.issue.ld`
(Controller.scala:360–370). So scale DMA already serializes correctly vs the matmul; the
fence is overkill for it.

### Cost (from PERF_ANALYSIS.md)
The fence is a full pipeline drain (LD+ST+EX) executed once per software chunk. At DIM32
the chunk count is RTL-floored at `ACC_ROWS/(2·DIM)` output tiles/invocation (the upper
accumulator half is the `concurrent_loops=2` double-buffer; P1 confirmed this), so 256³ =
8 chunks. The MX/stock ratio tracks chunk count (1→2→8 ⇒ 1.49×→3.3×→5.26×). Removing the
per-chunk drain is the first-order fix.

## 2. The enabling fact: `concurrent_loops = 2` already exists

The loop unroller (LoopMatmul.scala:1000) already supports **2 in-flight loops**, double-
buffering the scratchpad and the **accumulator** (each loop gets `max_addr/concurrent_loops`
/ half the acc — this *is* the half we hit in P1) and tagging work with a `loop_id`. Stock
uses this to pipeline consecutive `gemmini_loop_ws` invocations with no fence. **MX cannot,
only because its per-loop state is global instead of `loop_id`-indexed.** The fix is to make
MX's per-loop state ride the existing 2-loop machinery.

## 3. Proposed design

### 3.1 Job A — replace the RESET_K pulse with a tag-derived, drain-ordered reset (no runtime reset)
- Carry the **MX loop geometry** (`i_tiles`, `j_tiles`, `log2_jp`) and a **loop generation
  id** per in-flight loop, indexed by the existing `loop_id` (2 entries). `CONFIG_MXINT8`
  writes the geometry into the slot for the loop it configures (still a front-end reg write,
  but now a 2-deep table, not a single global). No `mx_config_pulse`/`mx_reset`.
- The mesh tag already carries `mx_enabled`/`mx_block`/`mx_second_half`
  (ExecuteController.scala:75–77, 831–833). **Add `loop_id` (1 bit at `concurrent_loops=2`)
  to the tag.** The drain side reads the geometry for the draining output from the table by
  its tag `loop_id`.
- **Reset the drain walk in drain order, at the loop boundary:** when the draining matmul's
  tag `loop_id` differs from the walk's current `loop_id`, re-zero `mx_out_{kp,i,j,kb}` and
  adopt the new loop's geometry. This is derived purely from the drain stream (satisfies
  "no runtime resets" — it is the same class as the existing tag-derived walk), and it is
  intrinsically ordered after the previous loop's last drain. No global fence for Job A.

### 3.2 Scale SRAM must be per-loop (the real hazard once the fence is gone)
Today both chunks mvin A scales to rows `[0, m_rows)` and B scales to
`[MX_SCALE_SP_ROWS/2, …)`. With the fence gone, chunk N+1's scale mvin would overwrite rows
that chunk N is still reading at drain. Two options:
- **(S1, preferred) ping-pong the scale SRAM by `loop_id`**: loop-even uses the low half of
  each region, loop-odd the high half — mirroring the spad/acc double-buffer. Cost: halves
  the per-loop scale capacity, tightening the Appendix-A.5 envelope (e.g. DIM32 A-scale
  drops to `MX_SCALE_SP_ROWS/4` = 64 rows = 2 i-tiles), which **raises** chunk count — a
  direct trade vs the pipelining win; must be measured. Requires `MXScaleLoadController` +
  the drain-side scale-read base to add a `loop_id` offset.
- **(S2) keep one scale region but add a fine-grained dependency** so chunk N+1's scale
  mvin waits only for chunk N's *drain* (not a full LD/ST/EX fence). Lower SRAM cost, but
  needs a scoreboard between the scale-load controller and the drain — more bespoke RTL.

Decision pending measurement; S1 is simpler and reuses the double-buffer idiom.

### 3.3 Job B — double-buffer the host repack image (software, trivial)
Make `mxint8_b_scale_tiled[]` a 2-entry ping-pong indexed by chunk parity, or always take
the dense fast path where possible. Pure software; removes Job B's need for the fence.

### 3.4 What stays the same
Mesh/PE untouched. Stock path bit-identical (`mx_enabled`-gated; `loop_id` already in the
unroller). The BlockScaleUnit, packer, golden, and scale layout (Appendix A.1/A.2) are
unchanged — only *when/whence* the geometry+walk are sourced changes.

## 4. Risks & mitigations
- **Highest risk:** the drain-ordered `loop_id` reset + per-loop geometry table — this is the
  metadata-association class where every prior bug in this project lived. Mitigation: keep
  the existing tag-vs-walk assert (ExecuteController.scala:1269) and add a tag-`loop_id`-vs-
  table assert; verify single-tile and DIM=32 (degenerate, no reorder) first.
- **Scale SRAM ping-pong (S1) raises chunk count**, partially offsetting the win — net effect
  must be measured (MXCOUNT). If it regresses, evaluate S2.
- **Envelope shrink** at DIM<8 (scale region already tight; the P-stage `maxBytesInMatRequest`
  fix area) — re-check the A.5 bounds with the halved regions.
- Possible interaction with the `concurrent_loops` acc/spad double-buffer base masking used
  by the tag→tile recovery (`mx_acc_tiles_half`) — the added `loop_id` bit must compose with
  that masking, not fight it.

## 5. Incremental implementation plan (each step bit-exact gated on §4 matrix)
1. **SW-only first:** double-buffer the repack image (3.3) + the dense fast path. Re-measure;
   isolates Job B's share. Zero RTL.
2. Add `loop_id` to the mesh tag + the 2-deep geometry table; drive the drain-side geometry
   from the tag (3.1) — but **keep the fence** and the pulse. Pure refactor: behavior
   identical, asserts must stay silent. Proves the plumbing before removing the fence.
3. Switch the walk reset to the tag-`loop_id`-boundary form (3.1); **remove `mx_config_pulse`**.
   Still single scale region + keep a fence only between same-`loop_id` reuse (every 2nd
   chunk). Verify bit-exact at DIM32/16, single- and multi-tile.
4. Per-loop scale SRAM (S1) + drop the fence entirely. Measure MXCOUNT; compare chunk-count
   vs pipelining trade.
5. Re-run full §4 matrix at all DIMs + stock elaboration; update PERF_ANALYSIS.md,
   comparison_report.md, PLAN_MX_UPDATED.md §5, AGENT.md.

## 5b. Mechanism findings verified in RTL (2026-06-19) — refines §3

Reading the unroller + ceiling experiment pinned the exact mechanism:
- **Ceiling experiment** (fence removed, SW-only): 1-chunk still PASSes; the 2-chunk case
  immediately trips the tag-vs-walk assert (ExecuteController.scala:1269) — the RESET_K race.
  Confirms the fence is the serializer and the walk reset is the correctness blocker.
- **Drains are sequential per loop.** The unroller issues all of loop N's matmuls before
  loop N+1's (program order via the arbiter), so outputs drain N-then-N+1, never
  interleaved. ⇒ a **single drain walk plus a reset at the loop boundary** is sufficient;
  no per-loop walk needed. The overlap we want is loop N+1's *loads/computes* hiding under
  loop N's *drain/mvout* (different pipeline stages), which the existing `concurrent_loops=2`
  machinery already provides for stock.
- **The acc/spad double-buffer halves are software-selected**, not auto-alternated: C acc
  base = `c_spad_addr` (`LOOP_WS` rs2[63:32], LoopMatmul.scala:1187); spad halves via
  `a/b_ex_spad_id` (rs1[19:16], used at 1227); `mx_acc_tiles_half`'s `/2` is
  `concurrent_loops`. The MX tiler currently passes `spad_id=0` and a fixed base + fence, so
  it does **not** double-buffer across chunks. Stock `tiled_matmul` *does* alternate these
  internally — that is exactly how it pipelines fenceless consecutive loops.
- **loop_id is derivable on the drain side from the C acc address**: the bit just above the
  within-loop tile index (`(acc_row >> log2(DIM)) >> log2(mx_acc_tiles_half)`) is the
  double-buffer half = loop parity. No new tag field needed for the boundary detection.

### Refined, coupled step plan (replaces §5 steps 2–4)
Removing the fence is a **combined SW+RTL change** (the three global resources must become
per-loop together, or the SW must serialize on whichever isn't):
- **RTL-a (drain walk) — RESET-FREE natural wrap (preferred, replaces the parity-flip-reset
  idea):** the one-cycle-ahead scale read (ExecuteController.scala:1082) cannot anticipate a
  loop boundary unless the walk knows the loop's K-block extent. So plumb **`k_blocks`** per
  loop (add to CONFIG_MXINT8 rs2 + `io.mx`) and make `mx_out_kb` **wrap to 0 at `k_blocks`**
  (today it only increments), exactly like `mx_out_i`/`mx_out_j` wrap at their tile counts.
  The walk then **self-cycles per loop with no reset wire** — `mx_config_pulse`/`RESET_K`
  drops out entirely (matches "set defaults, don't assert reset"). A 1-bit `mx_out_parity`
  **toggles on the kb-wrap** (the loop boundary); it selects the scale ping-pong half and is
  cross-checked against the acc-address parity (the bit above the tile index) by a new assert
  next to the existing tag-vs-walk assert (1269). Bubble-free: the read-ahead at a loop's
  last drain already computes the wrapped (0,0,0) next-state, now with the flipped parity.
- **RTL-b (scales):** ping-pong the scale-SRAM regions by loop parity
  (`MXScaleLoadController` write base + the drain-side scale-read base,
  ExecuteController.scala:1079/1092/1098), so loop N+1's scale mvin cannot clobber rows loop
  N is still draining. Halves per-loop scale capacity (measure envelope impact).
- **SW (tiler):** alternate `c_spad_addr` (acc half) + `a/b_ex_spad_id` (spad halves) +
  scale base per chunk (parity), mirroring stock `tiled_matmul`; drop `gemmini_fence()` for
  the common same-geometry case; keep a fence only when consecutive chunks differ in
  geometry (edge chunks) — avoids needing a per-loop geometry table in v1.
- **Verification staging to bound rebuild risk:** land RTL-a+RTL-b with the **fence still in**
  first (1 rebuild) and confirm behavior-identical on the §4 tests (asserts silent); then
  drop the fence in SW (no rebuild) + alternate halves, and measure on `mx_fence_probe`.

## 6. Expected payoff & open questions
- **Payoff:** removes the per-chunk drain; consecutive chunks overlap (compute of N+1 hides
  drain/mvout of N), targeting MX/stock from 5.26× toward the single-chunk floor (~1.1–1.5×),
  i.e. `exe_active`/cycles climbing toward stock's ~56%. No new array area (no FF buffer).
- **Open Q1:** S1 vs S2 for the scale-SRAM hazard (capacity vs bespoke scoreboard).
- **Open Q2:** is 2-loop overlap enough, or do edge-chunk geometry changes (partial I/J at
  matrix edges) need care in the 2-deep table? (Edge chunks have different `i_tiles` etc.)
- **Open Q3:** should the half-accumulator double-buffer itself be revisited for MX (a
  separate RTL lever to cut chunk count), or is fence-removal sufficient? Decide from §5.4
  data.
