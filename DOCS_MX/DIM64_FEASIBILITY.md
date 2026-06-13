# DIM > 32 (e.g., DIM = 64) feasibility analysis — why v1 is scoped to DIM ≤ 32

**Status:** document-only (decided 2026-06-12). No DIM=64 config is created. Full-rate
MXINT8 support is provided for `DIM ∈ {4, 8, 16, 32}`; this note records the mechanism
that makes `DIM > mx_block_size` structurally different, not merely untested.

## The mechanism

OCP MX v1.0 fixes the scaling block at `k = 32` elements sharing one E8M0 scale
(`mxint8_policy.md`). In Gemmini's weight-stationary dataflow, one mesh K-episode
computes, for every output element, a dot product over **DIM** K-lanes — the reduction
over those lanes happens **inside the mesh** (`Mesh.scala`/`PE.scala`), which is
DO-NOT-TOUCH under this project's retrofit constraint (RESEARCH_PLAN.md C1: the MX
sidecar must not modify the verified stock compute array).

- At `DIM == 32`, one episode = one logical block: the BlockScaleUnit applies the block
  scale `2^(eA+eB-12)` (round-nearest-even, once per block — the frozen contract) to each
  raw episode partial at drain time. No buffering needed (RES-OPT removed it here).
- At `DIM < 32`, one block spans `32/DIM` episodes whose **unscaled** partials are summed
  in the `DIM×DIM×20b` raw-partial buffer before the single scale application.
  Exact-integer designs in the literature do the same (MXDOTP holds raw sub-block
  partials in a wide fixed-point accumulator across its 4 passes per 32-block).
- At `DIM > 32` the containment **inverts**: one episode reduces `DIM/32 ≥ 2` logical
  blocks *with different scales* into a single raw partial **inside the mesh**, before
  any scale can be applied. The information needed for exact per-block scaling (the
  separate per-block partial sums) is destroyed at the PE accumulation chain.

## Why the workarounds violate v1 constraints

1. **Per-block partial-sum taps in the mesh** — expose the partial sum at every 32-lane
   boundary of the PE column chain. Exact, full-rate, but modifies Mesh/PE: violates
   DO-NOT-TOUCH, forfeits the retrofit claim, and forces full re-verification of the
   stock array. (This is the natural v2 design if the constraint is ever lifted: at
   DIM=64 it is one extra output tap per column.)
2. **Per-lane pre-scaling of payloads before the mesh** — fold each block's scale into
   the int8 payloads so the mesh sums same-scale data. Power-of-two re-quantization of
   int8 payloads loses low bits (or widens the payload datapath = mesh change): breaks
   bit-exactness against the golden/microxcaling oracle.
3. **Half-rate episode splitting** — issue K-episodes with only 32 valid lanes
   (zero-padding the other `DIM−32`), so one episode = one block again. Functionally
   correct and mesh-untouched, but wastes `1 − 32/DIM` of the array's K dimension
   (50% at DIM=64): the configuration is then strictly worse in throughput/area than a
   DIM=32 array of the same byte bandwidth, so it has no evaluation value beyond
   demonstrating functional portability.
4. **FP accumulation with per-episode scale application** (the NVIDIA Blackwell pattern:
   per-partial power-of-two scaling is exact in FP32) does not apply: it requires the
   scale *before* cross-block accumulation too — the merge in (3) happens before drain,
   inside the integer mesh, so there is nothing to scale separately.

## Bound from the contract side

The frozen numerical contract (`mxint8_policy.md` GEMM semantics) requires
`round_nearest_even(2^(eA+eB-12) · Σ_block)` per block. Any datapath that cannot observe
`Σ_block` separately per block cannot implement the contract exactly. With the mesh
opaque (DO-NOT-TOUCH) and episodes of width DIM, `Σ_block` is observable iff
`DIM ≤ 32`. Hence the v1 require:

```scala
require(!mx_enabled || (isPow2(DIM) && DIM >= 4 && DIM <= mx_block_size))
```

## Disposition

- `DIM ∈ {4, 8, 16, 32}`: full-rate, bit-exact (N-phase generalization of the verified
  two-phase DIM=16 design; raw-partial buffer is `DIM²×20b`, shrinking quadratically).
- `DIM = 64+`: out of v1 scope. Recorded here as a limitation with mechanism analysis;
  the v2 option is (1) above — one partial-sum tap per 32-lane boundary — quantifiable
  as future work in the paper.
