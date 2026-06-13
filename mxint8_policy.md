# MXINT8 Numerical Policy

Version: **v1.1** (2026-06-11). v1.0 froze the GEMM contract; v1.1 adds the normative
mapping to OCP MX v1.0, switches the packer's scale selection to the spec §6.3
recommended algorithm, and documents the profile deviations. **The GEMM semantics below
are unchanged from v1.0.**

This repository's MXINT8 work targets OCP MX block size `B = 32` and Gemmini's existing
signed INT8 payload datapath. The normative standard is
`DOCS_MX/OCP_Microscaling Formats (MX).pdf` (v1.0, Sep 2023); the clause-by-clause
conformance matrix is `DOCS_MX/OCP_CONFORMANCE.md`.

## Format

- MX format: `MXINT8` only for v1 (OCP Table 1: INT8 elements, `d = 8`, `k = 32`, E8M0 scale).
- Payload: signed int8 values with implicit fractional scale `2^-6` (1 sign + 1 integer + 6 fraction bits, OCP §5.3.4/Table 6).
- Scale metadata: one E8M0 byte per logical 32-element K block.
- E8M0 decode: finite scale byte `s` decodes to exponent `e = s - 127` (OCP §5.4.1/Table 7).
- Scale byte `0xff` is the E8M0 NaN encoding and is rejected at ingress (deviation D1 below).

## Conversion (packing)

The packer (`mxint8_pack.h`) implements the OCP §6.3 recommended conversion:

- Scale: `X = 2^e` with `e = floor(log2(amax))` over the block (the largest power of two
  representable in the INT8 element type is 1.0, so there is no further offset); `e` is
  clamped to the E8M0 range `[-127, 127]`.
- Elements: `P = round_ties_to_even(V / X * 2^6)`, clamped to `[-127, +127]` preserving
  sign (clamp-to-max-normal, OCP §5.3.4/§6.3; the clamp is reachable by design when
  `V/X in (127.5/64, 2)` rounds to 128).
- The maximum negative encoding `-128` (value −2.0) is never emitted (OCP §5.3.4 *may*),
  but `-128` in third-party conformant inputs is accepted and computes correctly
  (consumer side; `neg128*` cases in `mxint8_corner.c`).
- All-zero block: neutral scale `e = 0` with all-zero payloads (the spec leaves this
  case undefined; any scale represents the block exactly).

## GEMM Semantics

For payload matrices `A_payload[M][K]` and `B_payload[K][N]`, scales are provided as:

- `A_scale[M][ceil(K/32)]`
- `B_scale[ceil(K/32)][N]`

For each output element:

```text
C[i][j] = sum over MX blocks b:
  round_nearest_even_sat(
    sum over t in block b:
      A_payload[i][k] * B_payload[k][j]
    scaled by 2^(eA[i][b] + eB[b][j] - 12)
  )
```

The `-12` term is the product of the two implicit `2^-6` payload scales. This is exactly
the OCP §6.1 Dot semantics with the scales factored out of the reduction (internal
precision is implementation-defined per §6.1), summed across blocks per §6.2 DotGeneral.

## Execution Rules

- Logical MX K blocks are always 32 payload lanes.
- On `DIM=32`, one physical K phase equals one logical MX block.
- On `DIM=16`, two physical K phases form one logical MX block and must use the same A/B scale vectors.
- K-tail lanes outside the problem shape are zero-padded before raw block accumulation (OCP §6.2 padding assumption).
- A raw block partial is scaled before it is merged with another logical K block.
- Stock Gemmini INT8 behavior is unchanged when `MX_ENABLED == 0`.

## Rounding And Saturation

- Use nearest-even rounding for fractional scaled block contributions (OCP §5.3.4 roundTiesToEven, a *must*).
- Saturate final integer results to the destination type.
- Reference (golden) accumulator semantics: cross-block contributions accumulate in a
  wide (int64) internal accumulator and saturate **once at readout** to int32
  (`mxint8_golden.h`). The RTL merges scaled blocks into Gemmini's int32 accumulator;
  equality against the golden is established by the regression suite, and the
  intermediate-saturation equivalence envelope is workload-dependent (quantified by the
  P4 wide-accumulator option and saturation study).

## Documented deviations from OCP MX v1.0

See `DOCS_MX/OCP_CONFORMANCE.md` for the full matrix and rationale.

- **D1 — NaN scale rejected, not propagated** (§5.1/§5.4.1): a `0xff` scale byte makes
  the golden return `-1` and fires the `MXScaleSRAM` assert in RTL. The integer-only
  output has no NaN encoding; element encodings under a NaN scale are out of the spec's
  scope (§4). Conformant producers never emit `0xff`.
- **D2 — Integer cross-block accumulation** (§6.2 *should*-Float32): the narrow profile
  keeps the int32 accumulator (exact where Float32 would round, saturating outside it);
  the P4 wide-accumulator config option complements this.
- **D3 — −128 accepted only within the RES-OPT raw envelope** (§5.3.4 *may*): the packer
  never emits −128, and the mesh computes −128 products correctly for any block whose raw
  partial is `≤ 2¹⁹−1` (the resource-optimized mesh output `SInt(20.W)`, sized for the
  packer's `32·127² = 516128` bound). A maximum-magnitude all-±128 block reaches
  `32·128² = 524288`, one LSB out of range; this is unreachable from conformant packing
  and is not handled (widening the mesh would regress RES-OPT). The int64 golden is
  unaffected. See `DOCS_MX/OCP_CONFORMANCE.md` D3.
- **Convention — all-zero block scale `e = 0`**: spec-undefined case; external tools may
  pick a different (equally exact) zero representation, so value-level comparisons, not
  scale-byte comparisons, apply to all-zero blocks.

## External verification

`tools/mxint8_external_diff.py` (gemmini-rocc-tests) cross-checks the packer and golden
against **microsoft/microxcaling** (the spec authors' reference library) on CPU under
this policy: spec-§6.3 scale rule, bit-exact dequantized elements, and an exact-integer
GEMM over externally quantized tensors, including the pre-v1.1 divergence band
`amax in (1.984375*2^j, 2*2^j)`. Run it after any packer/golden change.

## Appendix A — Multi-tile scale layout and ordering (P3, 2026-06-12)

The GEMM semantics above are unchanged; this appendix extends the *metadata layout and
ordering contract* from a single output tile (`I = J = 1`) to a tiled invocation with
`I_tiles x J_tiles` output tiles of `DIM x DIM` each (`M = I_tiles*DIM - pad_I`,
`N = J_tiles*DIM - pad_J`). It binds the loop wrapper (`gemmini_loop_ws_mxint8`), the
host-side scale packing, and the RTL scale-read addressing.

### A.1 Padded power-of-two output-tile pitch

- Software computes `Jp = 2^ceil(log2(J_tiles))` and configures `log2_Jp` via
  `CONFIG_MXINT8` (together with `I_tiles`, `J_tiles`).
- When MX is active, the loop unroller addresses output tile `(ti, tj)` at accumulator
  row `(ti*Jp + tj) * DIM` (instead of the stock dense pitch `ti*J_tiles + tj`).
- Rationale: `(ti, tj)` is then recoverable from any output's C accumulator row by pure
  bit slicing — `t = acc_row >> log2(DIM)`, `tj = t & (Jp-1)`, `ti = t >> log2_Jp` —
  stateless per the no-runtime-resets rule. The padding (`Jp - J_tiles` unused tile
  slots per `ti`) costs accumulator capacity only when `J_tiles` is not a power of two.
- Capacity constraint: `I_tiles * Jp * DIM <= ACC_ROWS` per invocation.

### A.2 Scale SRAM layout (tiled)

A region (rows `0 .. MX_SCALE_SP_ROWS/2 - 1`), one row per *global* output row:

- row = `ti*DIM + r` (global M index of the output row), lane = `kb` (logical K-block
  index). This is the natural contiguous `M x k_blocks` host layout; the existing
  `gemmini_mvin_mxscale_a` with `rows = M` already produces it.
- Constraints: `k_blocks <= DIM` (lane width of one scale row) and
  `I_tiles*DIM <= MX_SCALE_SP_ROWS/2` per invocation.

B region (rows `MX_SCALE_SP_ROWS/2 ..`), one row per `(kb, tj)` pair:

- row = `MX_SCALE_SP_ROWS/2 + kb*Jp + tj`, holding that tile's `DIM` column-scale bytes
  (lane = column within the tile).
- Lanes beyond `N` in an edge tile, and whole rows for padding tile slots
  (`tj >= J_tiles`), are filled with the neutral E8M0 byte `127` (`e = 0`) so every
  stored lane decodes as valid.
- The host repacks `B_scale[kb][N]` into this padded `(k_blocks*Jp) x DIM` image
  (`mxint8_repack_b_scales_tiled`) and loads it with a single `gemmini_mvin_mxscale_b`.
- Constraint: `k_blocks*Jp <= MX_SCALE_SP_ROWS/2` per invocation.

### A.3 Issue order and phase adjacency (normative for DIM < 32)

- At `DIM == 32` the stock loop order (i fastest, then j, then k) is retained; one
  physical K phase is one logical block.
- At `DIM < mx_block_size` with more than one output tile, the unroller issues the
  `N = mx_block_size/DIM` physical phases of one `(tile, block)` pair **back-to-back**
  (order: `kb` slowest, then `j`, then `i`, then phase `kp` fastest). This keeps the
  raw-partial buffer requirement at exactly one `DIM x DIM` tile (one in-flight block
  per tile at a time), the same invariant the verified single-tile path relies on.
- The drain order therefore equals the issue order, and the RTL re-derives
  `(ti, tj, kb, kp)` for each drained output from the strict drain order (chained
  wrapping counters), cross-checked by assertion against the tag-derived `(ti, tj)`
  of A.1.

### A.4 Accumulate-bit rule (logical block, not physical phase)

- The C-address accumulate bit for an MX matmul is keyed by the **logical block**:
  `accumulate = ex_accumulate || kb != 0`. All phases of one block share the block's C
  address and accumulate bit; the non-final phases' accumulator writes are suppressed
  in hardware, so the block's single committed write (final phase) initializes the row
  for `kb == 0` and accumulates for `kb > 0`.
- Note: keying by the physical k iterator (`k != 0`, the stock rule) is wrong at
  `DIM < 32` — block 0's committed write would carry `accumulate = 1` and add to stale
  accumulator contents. (The pre-P3 single-tile DIM=16 test already followed the
  logical-block rule with raw intrinsics; P3 moves the rule into the MX loop path.)

### A.5 Invocation envelope

One hardware-loop invocation must satisfy: `k_blocks <= DIM`,
`I_tiles*DIM <= MX_SCALE_SP_ROWS/2`, `k_blocks*Jp <= MX_SCALE_SP_ROWS/2`,
`I_tiles*Jp*DIM <= ACC_ROWS`, plus the stock spad capacity bounds. Larger problems are
chunked by the software outer tiler (`tiled_matmul_mxint8`), using `ex_accumulate` for
K continuation; the per-block scale-then-accumulate semantics across chunks follow the
GEMM contract above unchanged.
