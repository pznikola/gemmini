> **⚠️ SUPERSEDED (2026-06-27) — DO NOT IMPLEMENT THIS PLAN.** Its premise (the drain-walk
> serializes matmuls) was refuted by measurement: the drain walk is provably equal to the mesh
> tag (assert `ExecuteController.scala:1292`) and never gates issue/feed, so "Fix B / Option A"
> (tag-addressed scales + retiring the walk) is a **NO-OP**. The real root cause is the
> **host-side B-scale repack = 84% of runtime** (a software loop, NOT the datapath); MX-minus-
> repack ≈ 29.5k cyc < stock 35.2k. See `DOCS_MX/PERF_ANALYSIS.md` "DEFINITIVE ROOT CAUSE
> (2026-06-26/27)" and the implemented per-(j,k) B-scale tile-cache fix in `gemmini.h`. This
> file is kept only as a record of a ruled-out hypothesis.

# MXINT8 matmul-pipelining fix (Option A) — implementation plan

## 1. What we know (diagnosis, fully measured)

MXINT8 is bit-exact but **4.9× slower than stock at 256³ DIM32**. Through ~10 instrumented
sim builds (`PERF_ANALYSIS.md` "CORRECTION 2026-06-24" + later RS/LD-ST probes) we
established:

- **Signature:** MX runs matmuls **serially at ~full per-matmul latency** (~350 cyc/matmul,
  flat with problem size) where **stock pipelines** (~72 cyc/matmul at 256³, improving with
  size). The mesh is **empty ~86%** of cycles (`matmul_inprog≈14%`), the execute controller
  sits in `waiting_for_cmd` with **no command ~86%** (`no_cmd`), and `exe_active`(compute)≈14%
  vs stock ~55%. Between matmuls: the prior has drained (mesh empty) and the next is not yet
  fed → a bubble of ~full pipeline latency.

- **Ruled OUT by measurement** (none is the cause): scale-load DMA (2.6%); drain *throughput*
  (`draining`=ideal, mesh efficient when active); stock RAW/preload/overlap hazards (all 0,
  `ex_read_from_acc=false`); per-chunk CONFIG barrier (`cmd_blocked`≈1.5%); RS issue handshake
  (`ex_ready≈0` — the RS never holds a ready-unissued EX command, so issue is instant); RS
  pool fullness (LD 6-12%, ST 8-14%, EX 14-16% — never saturated; `rs_full=99%` is an
  `!alloc.ready` artifact when the unroller isn't presenting); **load dependency blocking**
  (`ld_blocked=0`, loads are never dep-blocked); the **unroller** (at DIM=32 `mx_reorder=false`
  ⇒ it emits the *identical* command stream to stock — COMPUTE_AND_STAY B-reuse, i-fastest
  walk, same C addressing); and **RS dependencies** (the only MX-specific dep, the
  `is_mx_scale` term at `ReservationStation.scala:350`, clears on the scale-mvin's *issue*, a
  per-chunk gate, not per-matmul; the EX→EX dep at `:356` clears on *issue* and is identical
  for stock).

- **What remains, by elimination:** the **MX execute-controller output/completion path** —
  the scale-read + single drain-order walk + single BlockScaleUnit that wrap each matmul.
  The code comment at `ExecuteController.scala:786` names it: *"the chunks still do not
  overlap — single drain walk + one BlockScaleUnit."*

- **Why no waveform:** the traced (debug) sim **hangs** on this MX workload (a one-line
  `printf` flips pass↔hang; debug build hangs every run) — a **latent uninitialized-state /
  X-prop bug** in the MX path. It blocks VCD capture and is logged as a separate item.

## 2. Root-cause hypothesis (what the fix targets)

The MX output path tracks **one** matmul's drain at a time via a **single global walk**
(`mx_out_i/j/kb/parity`, `ExecuteController.scala:1041-1080`) that advances on
`mesh_resp.last`. The scale reads (`mx.read_a/read_b`, `:1104-1124`) are addressed **from that
global walk position**, so a draining output is scaled by *whatever the walk currently points
at* — which is only correct if outputs drain **strictly one matmul at a time, in walk order**.
That requirement prevents the next matmul from being in flight (computing/draining) while the
current one drains, forcing the serial, mesh-empty-between-matmuls behavior we measure.

**Key enabler for the fix:** the mesh response **tag already carries the output identity** —
`mesh_resp.tag.addr` → `(i,j)` (slicing already present for the cross-check at `:1288-1291`),
`mesh_resp.tag.mx_block` → `kb`, `mesh_resp.tag.mx_second_half` → phase. The walk is therefore
*redundant* with the tag (the existing assert at `:1294` proves `mx_tag_ti===mx_out_i`). If
scales are addressed **per-output from its own tag** instead of a shared global pointer, the
in-order/one-at-a-time requirement disappears and consecutive matmuls can overlap.

## 3. The fix — tag-addressed scales + a one-stage output pipeline

All changes in `ExecuteController.scala` (the MX output/drain block) + the tag bundle. **Mesh
/ PE / MeshWithDelays stay DO-NOT-TOUCH.**

1. **Carry the scale ping-pong parity in the tag.** The tag has everything except the
   scale-SRAM ping-pong **parity**. Add a 1-bit `mx_parity` to the mesh-request Tag bundle
   (`:76-82`) and set it at matmul issue (`:993-998`) from the issue-time chunk parity
   (the value the walk would have had — derived from the config/`mx_runtime` ping-pong state,
   not the drain-side `mx_out_parity`). It rides the mesh and returns in `mesh_resp.tag`.
   *(Implementation note: confirm the issue-time parity source; for single-chunk / DIM=32
   non-pipelined cases parity is 0, so this is inert there and bit-exactness is trivially
   preserved.)*

2. **Address the scale reads from `mesh_resp.tag`, not the walk.** Replace `mx_out_i/j/kb/
   parity` in the read-address computation (`:1104-1124`) with the tag-derived `(i,j,kb,
   parity)`. Because the read can no longer be issued a cycle ahead of a *predicted* next tile,
   **add one output pipeline stage**: register `mesh_resp` (data + tag + `output_counter` +
   `write_this_row`/`last`) for one cycle, issue the tag-addressed MXScaleSRAM read in that
   cycle, then apply the scale (BlockScaleUnit, `:1255-1271`), write the accumulator
   (`:1322-1340`), and fire `io.completed` (`:1348-1358`) the next cycle. +1 cycle of latency
   per matmul, **no throughput cost**; outputs of different matmuls may now be in flight
   simultaneously, each scaled by its own tag.

3. **Retire the global walk.** Remove `mx_out_*` advance logic (or keep it solely to drive the
   existing tag-vs-walk debug assert; once scales are tag-addressed the assert is redundant and
   can be dropped). Nothing downstream should gate matmul entry on the walk position any more —
   that is the serialization we are removing.

4. **DIM<32 two-phase path** (`mx_raw_buf`, `:1166-1252`): the raw-partial buffer is per-matmul,
   indexed by `output_counter`; the uniform +1 output stage must not change its
   first/last-phase timing. Re-verify carefully at DIM 16/8/4.

## 4. Bit-exactness (the non-negotiable gate)

- The scale **applied** to each output element is unchanged — step 2 reads the **same** E8M0
  A/B exponents for tile `(i,j,kb)`, only *addressed from the tag instead of the walk* (the
  existing assert proves they are equal today). `scalePowerOfTwo` / round-nearest-even /
  saturate are untouched.
- The accumulate ordering across K is unchanged (the +1 stage delays every output uniformly).
- Gate, via `run-binary-fast`: `mxint8_multitile` (5/5) + `mxint8_matmul_nphase` at **DIM32,
  then DIM16, then DIM8/DIM4** (the two-phase `mx_raw_buf` path is the highest risk).

## 5. Validation & confirmation of the mechanism

- **Perf:** `mx_bench` 64/128/256³ — success = `no_cmd` collapses, `matmul_inprog`/`exe_active`
  climb toward stock, cyc/matmul drops from ~350 toward stock's ~72, 256³ cycles fall from
  ~180k toward stock's ~37k. The diagnostic counters are already wired, so the same MXCOUNT
  line confirms the bubble closed (this both fixes *and* proves the hypothesis).
- If `no_cmd`/mesh-empty do **not** close after the fix, the hypothesis is wrong elsewhere in
  the execute path; the next step is to **fix the traced-sim hang** (§7) and get a real VCD to
  pinpoint, rather than guess again.

## 6. Critical files
- `generators/gemmini/src/main/scala/gemmini/ExecuteController.scala` — Tag bundle (76-82),
  matmul issue tag set (993-998), drain walk (1041-1080), scale read-ahead (1104-1124), scale
  consume (1129-1134), BlockScaleUnit/acc-write/completion (1255-1358), DIM<32 buffer
  (1166-1252). All edits here.
- Diagnostic counters already in `CounterFile.scala` / `ReservationStation.scala` / `mx_bench.c`
  (observation-only) confirm the result; revert the temp `mx_bench` shape/golden edits before
  the final perf matrix (already reverted).

## 7. Separate follow-up (not blocking): the traced-sim deadlock
The debug (`+define+DEBUG`) sim hangs on this MX workload (intermittent on the fast sim too;
flips with a one-line code change). Likely an uninitialized MX register/SRAM read (X-prop).
Worth a dedicated fix because it (a) blocks waveform debugging and (b) is a latent correctness
risk. Track separately from this perf fix.

## 8. Iteration cost & constraints
Per change: `sbt "project gemmini" compile` → `make verilog` (pinned `FIRTOOL_BIN`) → rebuild
`GemminiMXINT8DIM32RocketConfig` (~1h) → bit-exact gate → `mx_bench`. Env: activate `.conda-env`
and prepend `.conda-env/riscv-tools/bin` (flaky activation otherwise grabs system gcc 10.2.0).
Expect 2–4 iterations. Standing constraints: Mesh/PE/MeshWithDelays DO-NOT-TOUCH; all MX logic
`mx_enabled`-gated; stock bit-identical; bit-exact vs `mxint8_golden.h`; user commits git.
