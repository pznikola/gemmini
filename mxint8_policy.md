# MXINT8 Numerical Policy

This repository's MXINT8 work targets OCP MX block size `B = 32` and Gemmini's existing signed INT8 payload datapath.

## Format

- MX format: `MXINT8` only for v1.
- Payload: signed int8 values with implicit fractional scale `2^-6`.
- Scale metadata: one E8M0 byte per logical 32-element K block.
- E8M0 decode: finite scale byte `s` decodes to exponent `e = s - 127`.
- Scale byte `0xff` is a NaN scale in the OCP encoding and is rejected in v1.

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

The `-12` term is the product of the two implicit `2^-6` payload scales.

## Execution Rules

- Logical MX K blocks are always 32 payload lanes.
- On `DIM=32`, one physical K phase equals one logical MX block.
- On `DIM=16`, two physical K phases form one logical MX block and must use the same A/B scale vectors.
- K-tail lanes outside the problem shape are zero-padded before raw block accumulation.
- A raw block partial is scaled before it is merged with another logical K block.
- Stock Gemmini INT8 behavior is unchanged when `MX_ENABLED == 0`.

## Rounding And Saturation

- Use nearest-even rounding for fractional scaled block contributions.
- Saturate final integer results to the destination type.
- Use a wide internal reference accumulator in software; the RTL shadow accumulator width is an implementation parameter for later phases.
