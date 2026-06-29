# MXINT8 drain-pipelining fix — design for review

## 1. Confirmed problem (see PERF_ANALYSIS.md "CORRECTION 2026-06-24")

256³ DIM32: MX is 4.9× slower than stock. Scale-load DMA is **2.6%** (not the cause). The
mesh does the *same* absolute compute as stock (`exe_active` ~25k both) and drains exactly
the ideal 16,384 rows, but is **busy only 9–14% of cycles**. The execute controller sits in
`waiting_for_cmd` **86%** of cycles with the mesh idle: `req_stall=0` (mesh entry not the
gate), `enq_not_ready=0` (control queue not the gate), no RAW hazard (both configs set
`ex_read_from_acc=false`/`ex_write_to_spad=false`). So the **reservation station is not
issuing the next matmul**, and MX matmuls **do not pipeline** — each completes (drains) before
the next runs. Stock pipelines (44–55% util).

## 2. The MX-specific global state: the drain-order walk

`ExecuteController.scala:1041-1080`. A single set of registers `mx_out_i / mx_out_j /
mx_out_kb / mx_out_parity` is a **global pointer to the output tile currently draining**. It
advances **only** on `mesh_resp_valid && mesh_resp.last && rob_id.valid && mx_enabled`
(1071) — i.e. once per *completed* matmul. The one-cycle-ahead scale reads
(`mx.read_a/read_b`, 1112-1124) address MXScaleSRAM **from this global pointer**
(`mx_out_i/j/kb` and `mx_next_*`), and the BlockScaleUnit applies those scales to the drained
output. So a draining output is scaled by **whatever the global walk currently points at** —
which is only correct if outputs drain **strictly one matmul at a time, in walk order**.

Key fact for the fix: the output tile is *also* recoverable from the mesh response **tag**
— `mesh_resp.tag.addr` (C accumulator row) yields `mx_tag_ti/mx_tag_tj` and
`mesh_resp.tag.mx_block` carries `kb` — and lines 1288-1294 already **assert the tag-derived
tile equals the walk** on every committed row. So the walk is *redundant* with the tag; the
scales could be addressed per-output from its own tag instead of a shared ordered pointer.

## 3. The one open question (resolve FIRST — step 0, cheap)

Two mechanisms could produce "RS doesn't issue the next matmul; mesh idle 86%", and they need
*different* fixes. Current counters cannot tell them apart:

- **(W) Walk-gated drains.** The mesh *could* compute the next matmul, but the design forces
  strictly serial, in-order drains (one matmul fully drains + walk advances before the next
  may drain), so completions — and thus the RS C-dependency release — serialize. Fix = §4.
- **(C) Completion/issue latency.** The mesh already pipelines, but each MX matmul's
  `io.completed` (= `mesh_resp.last`) lands much later after its compute than in stock
  (extra output-path latency), so the RS C-dependency (`deps_ex`, ReservationStation:338,
  released on completion) clears late and the next same-C matmul issues late. Fix = §5.

**Step 0 probe (one test-only reconfig on the *already-built* sim — minutes, no rebuild):**
add/აddress two existing-style signals via the spare counter slots — `matmul_in_progress`
(mesh holds ≥1 tag) and a "mesh tag in progress AND not draining" cycle. If the mesh holds
multiple tags but isn't draining ⇒ (W). If the mesh holds exactly one tag at a time and the
gap is compute-end→last latency ⇒ (C). (These two need a tiny RTL counter add, so they ride
the *next* rebuild — which is also the fix build; until then we reason from §2.)

## 4. Fix for (W): address scales by the draining output's tag, not the global walk

Replace the global-walk scale addressing with **per-output tag addressing**, so outputs may
drain in any order / back-to-back and each is scaled correctly from its own tag.

- Derive `(i, j, kb)` for the *draining* output from `mesh_resp.tag` (the slicing already
  present at 1288-1291 for the cross-check, plus `tag.mx_block` for `kb`).
- The scale read is currently 1-cycle-ahead off the walk's predicted next position. With tag
  addressing the next tag isn't known a cycle early, so **add one output pipeline stage**:
  buffer the raw mesh partial + its tag for one cycle, issue the MXScaleSRAM read addressed
  by that tag, then apply the scale and write the accumulator the following cycle. One extra
  stage of latency, no extra throughput cost; drains may now overlap across matmuls.
- Keep `mx_out_parity`/ping-pong derivation from the tag too (the tag must carry or imply the
  loop parity; if not, add 1 bit to the tag — tag fields are set at enqueue, 893-901).
- Retire the global walk (or keep it only as a debug assert).

## 5. Fix for (C): make MX completion fire as early as stock

If the mesh already pipelines but completion is late, shorten the path from "output computed"
to `io.completed` (e.g. fire completion when the matmul's last row *enters* the scale/commit
pipe rather than after the extra MX output stages), preserving the accumulator-write ordering.
Smaller change, but only helps if (C) is the true gate.

## 6. Bit-exactness (the non-negotiable gate)

- The scale *applied* to each output element is unchanged — §4 reads the **same** E8M0
  A/B exponents for tile `(i,j,kb)`, just addressed from the tag instead of the walk (the
  existing assert proves they are equal today). `scalePowerOfTwo`/round/saturate untouched.
- DIM<32 two-phase raw-partial buffering (`mx_raw_buf`, 1166-1252) is indexed by
  `output_counter` within a matmul and is unaffected by inter-matmul drain order; verify the
  buffer lifetime still spans only one logical block (it does — per-matmul).
- Validate bit-exact vs `mxint8_golden.h`: `mxint8_multitile` (5/5), `mxint8_matmul_nphase`
  at DIM32 then DIM16/8/4 (the two-phase path is most at risk), via `run-binary-fast`.

## 7. Risk & scope

- Mesh / PE / MeshWithDelays stay DO-NOT-TOUCH; all changes in the ExecuteController MX
  output/drain block (1041-1294) + possibly 1 tag bit at enqueue.
- Main risk: the one-stage output pipeline interacting with `start_array_outputting`,
  `output_counter`, the acc-write guards, and the DIM<32 buffer. Mitigated by the bit-exact
  gate at every DIM.
- Iteration cost: ~1h sim rebuild per change; expect 2–4 iterations.
- Success metric: 256³ DIM32 `wait_cmd` collapses, `exe_active`/util climb toward stock
  (44%+), cycles drop from ~180k toward stock's ~37k, all bit-exact.

## 8. Recommendation

Proceed in this order: (step 0) add the `matmul_in_progress` / mesh-holds-tag-not-draining
counters to the next rebuild to confirm (W) vs (C); if (W) — the expected case given §2 —
implement §4 (tag-addressed scales + one output stage); re-verify bit-exact, then measure.
