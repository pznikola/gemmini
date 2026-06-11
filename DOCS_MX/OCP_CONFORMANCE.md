# OCP MX v1.0 Conformance Matrix — MXINT8 Gemmini

Normative reference: `DOCS_MX/OCP_Microscaling Formats (MX).pdf` — *OCP Microscaling
Formats (MX) Specification*, version 1.0, September 2023 (the only published version,
per its §3 version table). Requirement levels *must* / *should* / *may* are the spec's
own (§4.2 Word Usage).

Project profile under audit: **MXINT8 only** (`k = 32`, INT8 elements, E8M0 scale),
weight-stationary GEMM on an unmodified int8 systolic mesh, integer (int32) output.
Audited artifacts: the frozen numerical contract (`mxint8_policy.md` v1.1), the
software golden + packer (`gemmini-rocc-tests/include/mxint8_golden.h`,
`mxint8_pack.h`), and the RTL sidecar (`MXScaleSRAM.scala`,
`MXScaleLoadController.scala`, `ExecuteController.scala` BlockScaleUnit).

Verdicts: **CONFORM** / **DEVIATION** (documented profile deviation, justified below)
/ **N.A.** (clause cannot apply to this profile). Audit date: 2026-06-10 (P1); the
pre-P1 §6.3 divergence found by this audit and fixed in P1 is recorded in the
"Audit findings" section at the end.

## Conformance matrix

| # | Spec clause | Requirement (level) | Project behavior (evidence) | Verdict |
|---|---|---|---|---|
| 1 | §5.2 Table 1, §5.2.1 | An implementation *may* support any subset of the concrete formats; for each supported format it *must* support the Table 1 parameters (MXINT8: INT8 elements, d=8, k=32, E8M0 scale, w=8). | MXINT8 only; parameters enforced at elaboration: `mx_block_size == 32`, `mx_scale_bits == 8`, `mx_int_frac_bits == 6` (`GemminiConfigs.scala:104-106`, requires at `:213-215`). | CONFORM |
| 2 | §5.1 | Block = one shared scale `X` (w bits) + k elements `P_i` (d bits); values inferred as `v_i = X·P_i` for finite `X`. | One E8M0 byte per logical 32-element K block; golden applies `2^(eA+eB-12)` to the raw block dot, i.e. exactly the product of the factored `X·P` representations (`mxint8_golden.h:41-55,93`); RTL applies the same shift in BlockScaleUnit (`ExecuteController.scala:1124,1190`). | CONFORM |
| 3 | §5.1 | Physical memory layout is explicitly **not prescribed**; an implementation *can* store `X` "contiguously with or separately from the k elements". | Scales live in a separate sidecar stream (`A_scale[M][ceil(K/32)]`, `B_scale[ceil(K/32)][N]`) loaded by dedicated mvin commands into `MXScaleSRAM`; payloads stay in the unmodified scratchpad. The sidecar layout is therefore spec-sanctioned, not a deviation. | CONFORM |
| 4 | §5.1, §5.4.1 | If `X = NaN` (E8M0 byte `0xff`), every value in the block is NaN regardless of elements (element encodings under a NaN scale are out of scope, §4). | Rejected at ingress instead of propagated: golden returns −1 (`mxint8_golden.h:83-84`); RTL fires an assert on a masked-valid `0xff` byte (`MXScaleSRAM.scala:82`). An integer-only output (int8/int32) has no NaN encoding to propagate into. | DEVIATION (D1) |
| 5 | §5.1 | If `|X·P_i| > Vmax_Float32`, behavior is implementation-defined. | Unreachable for MXINT8: max `|v| = 1.984375·2^127 < (2−2^-23)·2^127 = Vmax_Float32`. | N.A. |
| 6 | §5.3.4 Table 6 | INT8 element encoding *must* follow Table 6: 2's complement, implicit scale `2^-6` (1 sign + 1 integer + 6 fraction bits), max symmetric ±63/64·2 = ±1.984375 (payload ±127), min ±1/64 (payload ±1), no Inf/NaN encodings. | `MX_INT_FRAC_BITS = 6` throughout; the `-12 = -2·6` shift term is the product of the two implicit scales (`mxint8_golden.h:42`, `mxint8_policy.md` §GEMM). Payload arithmetic is plain 2's-complement int8 on the stock mesh. | CONFORM |
| 7 | §5.3.4 | The maximum negative representation −2 (payload −128) *may* be left unused (symmetry). | Encoder side: the packer clamps to ±127 and never emits −128 (`mxint8_pack.h`). Decoder/consumer side: −128 from third-party conformant data computes correctly for every block whose raw partial stays within the mesh's RES-OPT raw envelope `SInt(20.W)` = `[−2¹⁹, 2¹⁹−1]` — verified by the `neg128_*` cases in `mxint8_corner.c` (P1). | DEVIATION (D3) |
| 8 | §5.3.4 | Conversion to INT8 *must* support roundTiesToEven; other modes *may* be supported. | `ROUND_NEAR_EVEN` is the only rounding used by the packer (`mxint8_pack.h`); the golden's scaled-block rounding is also ties-to-even (`mxint8_round_right_shift_nearest_even`, `mxint8_golden.h:21-39`). | CONFORM |
| 9 | §5.3.4 | On conversion overflow after rounding, the implementation *must* support clamping (saturating) to the maximum INT8 magnitude, preserving sign. | The P1 packer quantizes against `X = 2^floor(log2(amax))` and clamps payloads to ±127, preserving sign — the clamp is reachable by design for `amax/X·64` rounding to 128 (`mxint8_pack.h`, `mxint8_quantize_block`). | CONFORM |
| 10 | §5.4.1 Table 7 | E8M0: unsigned biased exponent, bias 127, supported exponent range −127…127, single NaN encoding `11111111₂`, no Inf, no zero. | Decode `e = byte − 127` (`mxint8_golden.h:17-19`); encode clamps exponents to [−127, 127] so `0xff` is never produced (`mxint8_pack.h:71-79`); `0xff` recognized as NaN and rejected (row 4). | CONFORM |
| 11 | §6.1 Dot | *Must* minimally support `C = X⁽ᴬ⁾X⁽ᴮ⁾ Σᵢ P⁽ᴬ⁾ᵢ·P⁽ᴮ⁾ᵢ` for k-length vectors; **internal precision and order of operations are implementation-defined**; by factoring out the scales, the reduction computes only on elements. | Exactly the implemented per-block semantics: raw int8×int8 products accumulate unscaled within the logical 32-block (on the stock mesh), then one power-of-two scale `2^(eA+eB-12)` is applied (`mxint8_golden.h:86-93`; `ExecuteController.scala:1190`). The implementation-defined internal precision covers the mesh's int-accumulation order. | CONFORM |
| 12 | §6.2 DotGeneral (padding) | Vectors are assumed padded to a multiple of k. | K-tail lanes outside the problem shape are zero-padded before raw accumulation (`mxint8_golden.h:87-91`; policy "Execution Rules"; `ktail` case in `mxint8_corner.c`). | CONFORM |
| 13 | §6.2 DotGeneral (result type) | The cross-block result *should* be a scalar Float32; `C = Σⱼ Dot(Aⱼ,Bⱼ)`. | Cross-block sum implemented per the formula, but the result is an integer: the golden accumulates in int64 and saturates once to int32 at readout (`mxint8_golden.h:77,96`); the RTL merges into Gemmini's int32 accumulator. A *should* clause; the integer narrow profile is deliberate (the whole point is reusing the int8/int32 datapath). | DEVIATION (D2) |
| 14 | §6.3 | A conversion mechanism from scalar vectors to MX *must* be provided. | `mxint8_pack_a` / `mxint8_pack_b` / `mxint8_quantize_block` (`mxint8_pack.h`). | CONFORM |
| 15 | §6.3 (1)-(2) | Recommended algorithm *should* be supported: `X` = largest power-of-two ≤ max|Vᵢ|, divided by the largest power-of-two representable in the element type (= 1.0 for INT8, so `X = 2^floor(log₂ amax)`); `Pᵢ = Vᵢ/X` quantized to the element type, clamping values beyond max normal to max normal, preserving sign. Other algorithms *may* be used; roundTiesToEven *must* be supported for the quantization. | The P1 packer implements exactly this algorithm (`mxint8_quantize_block`): `e = floor(log₂ amax)`, payloads `RNE(Vᵢ·2^(6−e))` clamped to ±127. roundTiesToEven per row 8. (Pre-P1 the packer used a different — *may*-sanctioned but interop-breaking — rule; see Audit findings.) | CONFORM |
| 16 | §4 Scope | "Detailed algorithms for computing the block scale" and "binary encodings for the element when the block scale is NaN" are explicitly out of the spec's scope. | Cited as the basis for rows 4 and 15's freedom: the zero-block convention and the NaN-reject profile occupy space the spec leaves open. | N.A. |

## Documented deviations

- **D1 — NaN scale `0xff` is rejected at ingress, not propagated.** The spec's NaN
  semantics presume a destination format with a NaN encoding; this profile's outputs
  (int32 accumulator, int8 results) have none, and the elements' encodings under a NaN
  scale are themselves out of the spec's scope (§4). Rejecting loudly (golden error
  code, RTL assert) is strictly safer than silently producing an arbitrary
  implementation-defined bit pattern. Conformant *producers* never emit `0xff`
  (Table 7 encode clamp, row 10).
- **D2 — Cross-block accumulation is integer, not Float32.** §6.2's Float32 result is a
  *should*. The narrow profile keeps Gemmini's int32 accumulator with documented
  saturation; the reference semantics use a wide (int64) internal accumulator with a
  single saturation at readout. For blocks whose scaled partials fit int32 the integer
  sum is *exact* where Float32 would round (24-bit mantissa), so inside the
  non-saturating envelope this profile is numerically stronger than the recommendation;
  outside it, saturation incidence is workload-dependent — quantified by the P4
  wide-accumulator option and saturation study.
- **D3 — −128 payloads accepted only within the RES-OPT raw envelope.** The spec lets
  encoders leave −128 (value −2.0) unused (§5.3.4 *may*), and the packer does so. As a
  *consumer*, the int8 mesh computes −128 two's-complement products correctly, but the
  resource-optimized mesh output type is `SInt(20.W)`, sized for the packer's worst-case
  raw partial `32·127² = 516128`. A block with the maximum-magnitude all-±128 content
  reaches `32·128² = 2¹⁹ = 524288`, one LSB past the `+524287` representable max, so it
  overflows the narrowed raw width. This is unreachable from the conformant packer and
  from any block whose raw partial is `≤ 2¹⁹−1` (e.g. up to 31 saturating −128 lanes, or
  any realistic −128-bearing input); widening the mesh to 21 bits would regress the
  RES-OPT result purely to represent data the encoder never produces. The software golden
  accumulates the raw partial in int64 and is unaffected; the limit is the RTL datapath.
  Verified envelope cases: `neg128_exact`/`neg128_sat`/`neg128_round`/`neg128mix` in
  `mxint8_corner.c`.

- **Convention (not a deviation): all-zero block.** `amax = 0` makes `floor(log₂ amax)`
  undefined; the spec is silent. The packer emits the neutral scale `e = 0` with
  all-zero payloads, which represents the block exactly under §5.1 value inference.

## Audit findings (P1, 2026-06-10)

1. **Pre-P1 packer diverged from the §6.3 recommended algorithm.** It chose the
   smallest `e` with step `2^(e−6) ≥ amax/127`, which never clamps; the recommended
   algorithm chooses `e = floor(log₂ amax)` and clamps. The two pick different
   scale/payload bits exactly when `amax ∈ (1.984375·2^j, 2·2^j)` for some integer `j`
   (old: payload 64 at scale `2^(j+1)`; spec: payload 127 at scale `2^j` after
   clamping). Both encodings are *valid MX data* (§6.3 *may*), but the divergence
   breaks bit-level interoperability with spec-following converters
   (microsoft/microxcaling). **Resolved in P1**: packer switched to the recommended
   algorithm (row 15); cross-checked bit-exactly against microxcaling by
   `tools/mxint8_external_diff.py`, including the divergence band.
2. **−128 payloads were never exercised** (the packer cannot produce them, but
   conformant third-party data can). **Resolved in P1**: `neg128_*` corner cases added to
   `mxint8_corner.c`. The audit also surfaced a new finding (D3): the int64 software
   golden handles any −128 raw partial, but the RES-OPT mesh output `SInt(20.W)` holds
   the packer's `32·127² = 516128` bound, not the worst-case all-±128 `32·128² = 2¹⁹ =
   524288` (one LSB over). The corner cases therefore exercise −128 at 31 saturating
   lanes (raw 507904, near-max but in-envelope); the all-±128 overflow is documented as
   deviation D3 rather than fixed (fixing it would touch the DO-NOT-TOUCH mesh and
   regress RES-OPT, to represent data the conformant packer never emits).
3. **D1/D2/D3 were undocumented** before this audit; they are now normative in
   `mxint8_policy.md` v1.1.
