# PLAN_TEST.md — MXINT8 Gold Model and Verification Plan for Gemmini/Chipyard

## 0. Purpose

This file is the test plan for AI agents working on the MXINT8 extension of the Gemmini accelerator in a Chipyard-based repository. The goal is to verify the architecture against a bit-exact OCP-style MXINT8 golden model, then prove that the DIM=32 bring-up path and the DIM=16 logical-K two-phase path are correct, reproducible, and measurable under Verilator and Vivado.

The project contribution is not “add another datatype.” The contribution is correct metadata execution for block-scaled MXINT8: sidecar scale storage, logical 32-element K-block tracking, correct scale-vector delivery, and block-boundary scaling before cross-block accumulation.

## 1. Golden model decision

### 1.1 Primary golden model to use

Use an in-repository, bit-exact C or C++ golden model as the **primary** checker for RTL and baremetal tests.

Required functions:

- `mxint8_e8m0_decode(uint8_t s) -> int`: returns `s - 127`; rejects `0xff` in v1.
- `mxint8_round_right_shift_nearest_even(int64_t x, unsigned shift) -> int64_t`.
- `mxint8_scale_raw_block(int32_t raw, int e_a, int e_b) -> int64_t`: computes `raw * 2^(e_a + e_b - 12)` with nearest-even right shifts, saturated left shifts, and deterministic edge behavior.
- `mxint8_ref_gemm_acc(...)`: block-loop GEMM over logical K blocks of size 32.
- `mxint8_pack_a_rowwise(...)`: creates `A_payload[M][K]` and `A_scale[M][ceil(K/32)]`.
- `mxint8_pack_b_kblock_colwise(...)`: creates `B_payload[K][N]` and `B_scale[ceil(K/32)][N]`.

The primary hardware checker must compare Gemmini output against this reference, not against PyTorch GPU output. The reference must be deterministic, integer-based, and independent of accelerator timing.

### 1.2 External models to cross-check, not blindly replace the primary golden

Use these as independent references and sanity checks:

| Rank | Repo/tool | Use for | Caveat |
|---|---|---|---|
| 1 | `microsoft/microxcaling` | Best external software reference for value-level MX emulation and CPU-vs-GPU sanity. Configure `scale_bits=8`, `w_elem_format='int8'`, `a_elem_format='int8'`, `block_size=32`, `round='even'`, and prefer CPU for golden checks. | It emulates quantized values in PyTorch rather than exporting the exact payload/scale layout Gemmini needs. Use it to validate numerical semantics, not sidecar memory addresses. |
| 2 | `ROCm/tensorcast` | Independent AMD/ROCm reference for datatype and virtual-cast behavior. Use `tcast.mxint8` or `datatype('int8', 'e8m0_t32')`, roundmode `even`, scalemode `max`. | Version 1 is virtual casting only; also watch padding limitations. It is useful as a second semantic oracle, not a Gemmini tile-layout oracle. |
| 3 | `amd/Quark` | Model-level and ONNX/PyTorch MX quantization sanity, especially `OCP_MXINT8Spec` and ONNX `MXQuantizeDequantize` with `element_dtype='int8'`, `block_size=32`, `rounding_mode=2`. | More of a quantization framework than a low-level GEMM oracle. Use it for model export/accuracy sanity, not bit-exact RTL block partials. |
| 4 | `KULeuven-MICAS/Precision-Scalable_MX` | Hardware-oriented RTL comparison for MX MAC datapaths and reduction-tree approaches. | It targets a different accelerator/SNAX integration. Useful for architecture comparison and testbench ideas, not as the primary Gemmini oracle. |
| 5 | `NVIDIA/Model-Optimizer` | Optional simulated MX-format exploration. | Do not use as the primary OCP MXINT8 oracle unless its INT8 element encoding is audited. A public issue discusses possible mismatch between OCP 1.6 INT8 and a PSX-style interpretation. |
| 6 | Intel Neural Compressor MX docs/code | High-level quantization recipe comparison. | Good for PTQ recipe context, not enough for bit-exact Gemmini verification. |

### 1.3 Required external cross-check script

Add `generators/gemmini/software/gemmini-rocc-tests/tools/mxint8_external_diff.py`.

It must:

1. Generate seeded FP32 or int8 tensors.
2. Run the in-repo packer and `mxint8_ref_gemm_acc`.
3. Run at least one external reference path: `microxcaling` first, `tensorcast` second if installed.
4. Compare dequantized values or fixed-point outputs under the frozen policy.
5. Emit a JSON record with seed, shape, K-block count, exponent range, rounding mode, and pass/fail.

Minimum command shape:

```bash
python3 tools/mxint8_external_diff.py \
  --backend microxcaling \
  --seed 1 \
  --m 16 --n 16 --k 64 \
  --round even \
  --json-out build/mxint8_external_diff_seed1.json
```

## 2. Frozen MXINT8 numerical contract

All agents must preserve this contract until explicitly changed in `mxint8_policy.md`.

For payload matrices `A_payload[M][K]` and `B_payload[K][N]`, and scale matrices `A_scale[M][ceil(K/32)]` and `B_scale[ceil(K/32)][N]`:

```text
raw_b(i,j) = sum_{t=0..valid_k_b-1} int32(A_payload[i][32*b+t]) * int32(B_payload[32*b+t][j])
C[i][j]   = sum_b round_nearest_even_sat(raw_b(i,j) * 2^(eA[i][b] + eB[b][j] - 12))
e        = E8M0_byte - 127
```

Required details:

- MX block size is 32.
- Scale type is E8M0, bias 127.
- Scale byte `0xff` is invalid/NaN in v1 and must be rejected by tests.
- MXINT8 payload is signed int8 with implicit `2^-6` fractional scaling, so each dot-product block has the `-12` correction for A and B payloads.
- Raw partials are accumulated within one logical 32-element K block.
- The scale product is applied once per logical block before cross-block accumulation.
- Tail K lanes are zero-padded and must not affect the raw partial.
- Identity-scale tests must collapse to stock int8 semantics when `eA + eB - 12 == 0` for every block. For example, set both scales to encode exponent `6`, i.e. scale byte `133`, so `6 + 6 - 12 = 0`.
- Accumulator comparison must document whether RTL accumulates in int32 while the reference uses int64. Tests that intentionally exceed int32 dynamic range must be classified as saturation/limitation tests, not ordinary equality tests.

## 3. Repository and tool setup requirements

Before running tests, each agent must record:

```bash
git rev-parse HEAD
git submodule status
verilator --version
vivado -version || true
java -version
sbt --version
which firtool || true
```

Use the Chipyard environment and ensure the Conda JDK and tools take precedence:

```bash
source /path/to/chipyard/env.sh
export PATH="$CONDA_PREFIX/bin:$PATH"
```

If the repo has a pinned CIRCT/firtool, pass it explicitly when generating Verilog:

```bash
export FIRTOOL_BIN="$HOME/.cache/llvm-firtool/1.62.1/bin/firtool"
```

Never mix logs from different commits, generated headers, or Gemmini configs in the same result directory.

## 4. Build gates

### M0.1 Scala compile gate

Run:

```bash
sbt "project gemmini" clean compile
```

Pass criteria:

- Zero compile errors.
- Non-MX stock config still compiles.
- MX-enabled DIM=32 and DIM=16 configs compile.

### M0.2 Verilog elaboration gate

Run both stock and MX configs:

```bash
make -C sims/verilator CONFIG=GemminiRocketConfig \
  FIRTOOL_BIN="$FIRTOOL_BIN" verilog

make -C sims/verilator CONFIG=GemminiMXINT8DIM32RocketConfig \
  FIRTOOL_BIN="$FIRTOOL_BIN" verilog

make -C sims/verilator CONFIG=GemminiMXINT8DIM16RocketConfig \
  FIRTOOL_BIN="$FIRTOOL_BIN" verilog
```

Pass criteria:

- Zero firtool errors.
- Stock output is unchanged except for expected build timestamps.
- MX outputs contain the expected MX modules, including scale SRAM/load/controller paths.
- Warnings are saved but not ignored; any warning mentioning undriven sinks, invalid connections, or inferred latches is a blocker.

## 5. Software-only test gates

### S1. Golden model unit test

Add or extend:

- `bareMetalC/mxint8_golden.c`
- `include/mxint8_golden.h`
- host-side `tests/test_mxint8_golden.cc` or equivalent if available

Test vectors:

1. E8M0 decode: `0x00`, `0x01`, `0x7f`, `0x80`, `0xfe`, `0xff`.
2. Nearest-even right shift: positive and negative values; ties and non-ties.
3. Left-shift saturation: near int64 limits.
4. Single raw block: `raw=0`, `raw=1`, `raw=-1`, `raw=32*127*127`, `raw=-32*127*127`.
5. K tails: `K=1, 15, 16, 17, 31, 32, 33, 47, 63, 64, 65`.
6. Cross-block scaling: `K=64/96/128` with different scales per block.

Pass criteria:

- Exact expected output for deterministic vectors.
- At least 10,000 randomized vectors pass with fixed seed list.
- `0xff` scale is rejected, not silently consumed.

### S2. Packer/depacker test

Add host-side test:

```bash
python3 tools/gen_mxint8_vectors.py --seed 0 --m 16 --n 16 --k 64 --out build/vectors_seed0
```

Required checks:

- A scale layout is `A_scale[m][k_block]`.
- B scale layout is `B_scale[k_block][n]`.
- Metadata byte count equals `M * ceil(K/32) + ceil(K/32) * N`.
- Payload tail lanes are zero in generated buffers.
- Dequantize(pack(x)) is close to external reference under the same rounding policy.

Pass criteria:

- Deterministic binary files are identical across repeated runs with the same seed.
- Different seeds generate different payloads and scales.
- Shape metadata is emitted in JSON next to binaries.

## 6. Baremetal and RTL tests under Verilator

All baremetal tests must:

- Be registered in `generators/gemmini/software/gemmini-rocc-tests/bareMetalC/Makefile`.
- Return `0` on pass and nonzero on fail.
- Print seed, shape, DIM, K-block count, and first mismatch.
- Use manual scale mvins first; use loop wrappers only after single-tile tests pass.
- Save simulator logs under `build/mxint8_logs/<config>/<test>/<seed>/`.

### H1. Scale mvin/mvout round-trip

File: `bareMetalC/mx_scale_mvin_mvout.c`

Purpose: verify sidecar metadata load/store path without GEMM.

Cases:

- A-scale rows: `rows = 1, DIM, DIM+1` if supported by scale SRAM capacity.
- B-scale rows: `rows = 1, 2, 4` and `cols = 1, DIM/2, DIM`.
- Tail columns: `cols < DIM` must not corrupt valid lanes.
- Invalid scale: write `0xff` only in a negative test that expects an assertion/trap or explicit failure.

Pass criteria:

- Readback matches all valid scale bytes.
- Masked lanes are either ignored or known-zero by design.
- No legacy scratchpad payload address is modified by scale mvin.

### H2. DIM=32 identity-scale GEMM

File: `bareMetalC/mxint8_matmul_dim32_identity.c`

Purpose: verify that MX path can behave exactly like stock int8 when block scale product cancels the two `2^-6` payload factors.

Configuration:

- `CONFIG=GemminiMXINT8DIM32RocketConfig`
- `M,N ∈ {1, 2, 15, 16, 31, 32}`
- `K ∈ {32}` first, then `K ∈ {64, 96, 128}`.
- `A_scale = B_scale = 133` for every block, so `eA = eB = 6`.

Pass criteria:

- Exact match to stock int8 software GEMM for K=32.
- Exact match to `mxint8_ref_gemm_acc` for K=64/96/128.
- No scale invalid assertions.
- No metadata-stall deadlock.

### H3. DIM=32 scaled GEMM

File: `bareMetalC/mxint8_matmul_dim32_scaled.c`

Purpose: verify BlockScaleUnit and cross-block scale-before-accumulate semantics.

Shapes:

```text
M,N ∈ {1, 3, 8, 15, 16, 31, 32}
K   ∈ {32, 33, 63, 64, 65, 96, 127, 128}
```

Scale patterns:

- Uniform exponent: all blocks same.
- Per-row A exponents vary.
- Per-column B exponents vary.
- Per-block exponents vary.
- Alternating large/small exponents.
- Random exponents in a safe range, e.g. `[-8, 8]`, unless testing saturation.

Pass criteria:

- Hardware output exactly equals `mxint8_ref_gemm_acc` for safe dynamic-range tests.
- Saturation tests match the documented RTL accumulator policy.
- If K > 32, a deliberately wrong “scale once at end” software checker must fail, proving the test can catch the main bug class.

### H4. DIM=16 two-phase GEMM

File: `bareMetalC/mxint8_matmul_dim16_two_phase.c`

Purpose: verify the research contribution: one logical MX block spans two physical 16-wide K episodes.

Configuration:

- `CONFIG=GemminiMXINT8DIM16RocketConfig`
- `M,N ∈ {1, 7, 15, 16}` initially.
- `K ∈ {16, 17, 31, 32, 33, 48, 64, 96}`.

Required probes/counters/asserts:

- `mx_second_half` toggles correctly.
- Scale vectors are held constant across half 0 and half 1 of the same logical block.
- Raw half partials are combined before scale application.
- Accumulator write is suppressed for half 0 if that is the design policy.
- Logical K-block advances every 32 lanes, not every 16 lanes.

Pass criteria:

- Exact match to `mxint8_ref_gemm_acc`.
- Counter `logical_k_block_transitions` equals `ceil(K/32)` per output tile.
- Counter `dim16_half_count` equals expected number of half episodes.
- A negative build with intentional scale advance every 16 lanes must fail at least one test.

### H5. Corner-case matrix

File: `bareMetalC/mxint8_corner.c`

Must include:

- All-zero payload blocks.
- All-zero block with nonzero scale.
- Maximum positive payloads.
- Maximum negative payloads under chosen symmetric rule.
- Alternating signs.
- Single outlier in a block.
- Different A scale per output row.
- Different B scale per output column.
- K not divisible by 32.
- M/N not divisible by DIM.
- K less than DIM.
- `0xff` scale negative test.
- Repeated run without clearing accumulator to catch stale state.
- Scale load immediately followed by compute to catch missing reservation-station dependency.

Pass criteria:

- All positive tests match reference.
- Negative invalid-scale test fails in the expected way.
- No stale metadata across independent tests.

### H6. Tiled wrapper test

File: `bareMetalC/mxint8_tiled.c`

Only start after H1-H5 pass.

Purpose: verify `gemmini_loop_ws_mxint8` or its replacement.

Required pre-check:

- Confirm scale mvins use element counts, not tile counts.
- Confirm A scale address contains the output tile row component; otherwise restrict test to `I=J=1` and document the limitation.

Shapes:

```text
M,N ∈ {DIM, 2*DIM, 2*DIM+3}
K   ∈ {32, 64, 96, 128, 160}
```

Pass criteria:

- Single-tile path remains passing.
- Multi-tile path either passes or is explicitly disabled with a compile-time assertion and a documented issue.

## 7. Regression tests for non-MX Gemmini

Every MX patch must run a stock regression subset:

- Stock int8 matmul smoke test.
- Stock mvin/mvout smoke test.
- Stock config elaboration.
- Any existing Gemmini baremetal test suite that is affordable under Verilator.

Pass criteria:

- MX disabled path has no undriven wires, dead logic errors, or behavior changes.
- No new warnings that mention MX paths when `MX_ENABLED=0`.

## 8. Differential bug-injection tests

Agents should add a small simulation-only mode or compile-time macro to intentionally break each invariant and prove the tests catch it.

Required injected bugs:

1. Apply scale once after all K blocks instead of per block.
2. Advance scale every 16 lanes in DIM=16.
3. Use A scale from row `i-1`.
4. Ignore tail K mask.
5. Treat `0xff` scale as exponent 128 instead of invalid.
6. Complete scale loads on issue instead of real DMA completion.

Pass criteria:

- Each injected bug fails at least one targeted test.
- The failure message identifies the likely invariant.

## 9. Performance and counter plan

Add FireSim AutoCounters or equivalent Verilator counters for:

- Kernel cycles.
- Useful MACs.
- Payload DMA bytes.
- Metadata DMA bytes.
- MXScaleSRAM reads and writes.
- Metadata stall cycles.
- Logical K-block transitions.
- DIM=16 first-half and second-half counts.
- BlockScaleUnit active rows/cycles.
- Accumulator writes.
- Transposer active cycles if claiming transposer-aware support.

Minimum CSV schema:

```text
commit,config,test,seed,M,N,K,DIM,k_blocks,cycles,useful_macs,payload_bytes,metadata_bytes,mxscale_reads,mxscale_writes,metadata_stall_cycles,kblock_transitions,dim16_halves,bsu_active_cycles,acc_writes,pass
```

Pass criteria:

- Counters are nonzero when they should be nonzero.
- Metadata byte count matches generated vector metadata size.
- DIM=16 K-block transition count proves 32-lane logical grouping.

## 10. Baseline and ablation matrix

Run at least these configurations before making performance claims:

| Config | DIM | MX mode | Purpose |
|---|---:|---|---|
| Stock Gemmini int8 | 32 | off | Aligned int8 lower bound. |
| Proposed MXINT8 | 32 | sidecar | Bring-up path. |
| Stock Gemmini int8 | 16 | off | Default-style baseline. |
| Software-repack MXINT8 | 16 | software | Realistic workaround baseline. |
| Duplicate-metadata baseline | 16 | duplicate | Cost of naive metadata layout. |
| Proposed MXINT8 | 16 | logical-K | Main research result. |
| Oracle no-stall | 16 | logical-K | Upper-bound/headroom. |

Required ablations:

- DIM=32 vs DIM=16.
- Metadata stalls enabled vs oracle no-stall.
- Duplicate metadata vs logical remapping.
- int32 RTL accumulator vs int64 software reference.
- Tail K behavior.
- Identity-scale mode vs stock int8.
- Transpose-aware path only if implemented; otherwise state that v1 is WS/untransposed.

## 11. Vivado/synthesis test plan

Run synthesis only after Verilator correctness is stable.

For each relevant config:

```bash
make -C sims/verilator CONFIG=<CONFIG> FIRTOOL_BIN="$FIRTOOL_BIN" verilog
# Then use the project Vivado flow or a standalone synthesis script for generated RTL.
```

Collect:

- LUTs.
- FFs.
- BRAMs/URAMs.
- DSPs.
- Fmax or worst negative slack.
- Critical path module.
- Area/timing delta against stock Gemmini.

Pass criteria:

- Synthesis completes.
- MX path overhead is reported honestly, even if negative.
- If timing fails, identify whether critical path is `BlockScaleUnit`, scale SRAM read/latch, accumulator ingress, or unrelated stock logic.

## 12. Required result artifacts

Each completed run must leave:

```text
results/
  <date>_<commit>/
    env.txt
    git_status.txt
    build_logs/
    verilator_logs/
    vectors/
    csv/
    vivado/
    summary.md
```

`summary.md` must include:

- Commit and submodule hash.
- Tool versions.
- Config name.
- Tests run.
- Pass/fail table.
- First failing seed if any.
- Known limitations.
- Whether results are safe to use in a paper plot.

## 13. Stop/go gates

| Gate | Required pass condition | If it fails |
|---|---|---|
| G0 semantics | `mxint8_policy.md` and golden model agree with OCP MXINT8 contract. | Stop RTL work; fix policy/reference. |
| G1 compile/elab | Stock, DIM32 MX, DIM16 MX compile and elaborate. | Fix build/config before behavior tests. |
| G2 external cross-check | In-repo golden agrees with at least one external reference on safe cases. | Audit rounding, INT8 implicit `2^-6`, scale selection. |
| G3 scale memory | `mx_scale_mvin_mvout` passes. | Do not debug GEMM yet. |
| G4 DIM32 identity | Identity-scale equals stock int8. | Debug payload path and scale neutralization. |
| G5 DIM32 scaled | K=32/64/96/128 exact vs reference. | Debug BlockScaleUnit and per-block accumulation. |
| G6 DIM16 | Two-phase exact vs reference and counters prove 32-lane grouping. | Debug logical K controller; do not claim research result. |
| G7 tiled | Loop wrapper passes or is explicitly scoped out. | Restrict paper to single-tile/explicit software scheduling. |
| G8 evaluation | Counters and Vivado data collected. | Do not make performance/area claims. |

## 14. Agent rules

1. Always start from the smallest failing seed and smallest shape.
2. Do not change rounding or saturation to make hardware pass; update policy only through an explicit review.
3. Do not use PyTorch GPU as a bit-exact oracle.
4. Do not treat scale bytes as payload in scratchpad/transposer tests.
5. Every new RTL feature must have a matching software reference test and at least one negative/injected-bug test.
6. A passing compile/elaboration gate is not behavioral proof.
7. Keep DIM=32 passing while developing DIM=16.
8. Do not claim transposer-aware or OS support unless a test explicitly exercises it.
9. Preserve stock Gemmini behavior with MX disabled.
10. Save every seed and generated vector that exposes a bug.

## 15. References for agents to inspect

- OCP Microscaling Formats (MX) Specification v1.0.
- `microsoft/microxcaling`: PyTorch MX emulation library.
- `ROCm/tensorcast`: datatype/casting reference for OCP MX and related formats.
- `amd/Quark`: PyTorch/ONNX OCP MX quantization APIs.
- `KULeuven-MICAS/Precision-Scalable_MX`: SystemVerilog MX MAC datapaths and SNAX integration.
- `NVIDIA/Model-Optimizer`: simulated MX support; audit INT8 encoding before use as a reference.
- Existing repository files: `include/mxint8_golden.h`, `bareMetalC/mxint8_golden.c`, `include/gemmini.h`, MX Chisel modules, and Chipyard Gemmini MX configs.
