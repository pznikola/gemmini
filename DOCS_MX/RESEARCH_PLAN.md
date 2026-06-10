# RESEARCH_PLAN.md — MXINT8 Metadata Execution in Gemmini: Journal Research Plan

**Status:** Plan of record for the *research/publication* track (supersedes
`mxint8_gemmini_plan_updated.pdf` and `research-report 4.md` for forward planning; both are
retained as historical inputs). The *execution* counterpart for AI agents is
`DOCS_MX/PLAN_MX_UPDATED.md`.

**Date:** 2026-06-10. **Target venue:** ACM TRETS (journal-first; user decision).
**Normative standard:** `DOCS_MX/OCP_Microscaling Formats (MX).pdf` (OCP MX v1.0, Sep 2023).

---

## 1. Viability verdict and the 2026 landscape

**Verdict: viable and publishable, but the window is narrowing and the framing must be
sharper than both prior plans.** The topic was timely in April 2026; by June 2026 the field
is visibly crowding, which changes *what* must be claimed, not *whether* to publish.

### 1.1 Industry state (motivation is current, not speculative)

- NVIDIA Blackwell executes MXFP8/NVFP4 natively; NVFP4 is the default 4-bit inference path
  on B200-class parts in 2026 (developer.nvidia.com NVFP4 blog; PyTorch/TorchAO Blackwell
  MXFP8+NVFP4 results).
- AMD Instinct MI350/MI355 implement MXFP4/MXFP6 via MFMA; AMD Quark/ROCm document MX
  quantization flows (rocm.blogs.amd.com MXFP4/6 blog).
- Microsoft Maia 100 supports MX; OCP MX v1.0 (AMD/Arm/Intel/Meta/Microsoft/NVIDIA/Qualcomm)
  is the common standard. MXINT8 is positioned as the near-lossless direct-cast inference
  format.
- FPGA vendor *deployment* flows still center INT8/BF16 — the honest claim remains
  "forward-looking architecture research on open hardware", exactly as research-report 4
  advised. Do not overclaim FPGA product proximity.

### 1.2 Competitive landscape (2025–26) and the open gap

| Work | Venue | What it builds | What it does NOT do |
|---|---|---|---|
| MX+ (arxiv.org/abs/2510.14557) | MICRO 2025 | MX-compliant 32×32 systolic array + format extension; FSU per PE, BCU per column | Modifies the array; no SoC/generator integration; no block-vs-width mismatch problem |
| MX minifloat systolic arrays on FPGAs (dl.acm.org/doi/10.1145/3773041; github.com/accl-kaust/mx-systolic-fpga) | **TRETS 2025** | Standalone MXFP6/8 systolic arrays on UltraScale+, new PE designs, area/Fmax DSE | No SoC integration, no full-system eval, no logical-block/physical-width mismatch treatment, no INT path |
| Precision-scalable MX datapaths (arxiv.org/abs/2511.06313) | ASP-DAC 2026 | New MX MAC array (SNAX NPU), MXINT8/MXFP8/6/4 reduction trees | New datapath; no retrofit story; no full-stack |
| MXDOTP / VMXDOTP (arxiv.org/pdf/2505.13159; arxiv.org/pdf/2603.04979) | 2025–26 | RISC-V scalar/vector ISA extensions for MX dot products | CPU pipelines, not systolic accelerators |
| MicroScopiQ, M²XFP (arxiv.org/pdf/2601.19213), MX-SAFE, MXFormer | 2024–26 | Format/codesign, CIM accelerators | Different problem class |
| Exploring FPGA designs for MX (arxiv.org/pdf/2407.01475) | 2024 | MX arithmetic blocks on FPGA | Component-level only |

**The open gap (our thesis):** every published MX hardware work *builds a new datapath*.
Nobody has shown that OCP MX semantics can be **retrofitted as a pure control-plane /
metadata mechanism around an unmodified integer systolic mesh** — Gemmini's mesh, PEs, and
transposer byte-for-byte untouched — bit-exact to a frozen, spec-mapped numerical contract,
at zero throughput cost, validated full-stack (Chipyard SoC → Verilator → FireSim/VCU118 →
FPGA prototype), with the **logical 32-element K-block vs physical array width mismatch**
(DIM=16 two-phase) solved as the hard case. That retrofit story is also the practically
important one: the installed base of int8 accelerators (and accelerator generators) is
enormous; "what is the *minimal control-plane delta* to make them MX-capable?" is a question
none of the new-datapath papers answers.

**Scoop risk and mitigation:** the gap is real today but plausibly 6–12 months from being
filled. Mitigations: (a) move the evaluation fast (phases P0–P7 of `PLAN_MX_UPDATED.md`),
(b) post an arXiv preprint as soon as the first full evaluation cut exists, (c) commit and
tag the artifact early (P0) so priority is demonstrable.

### 1.3 What changed since the April 2026 plans

| April 2026 plan assumption | June 2026 reality |
|---|---|
| Two PhDs, design freeze month 1, DIM=32 by month 4, DIM=16 by month 6 | One researcher + AI agents; DIM=32 **and** DIM=16 already RTL-verified bit-exact; loop multi-block fixed |
| Tile-buffered two-step scaling needed to close timing | Streaming undelayed scaling + 1-read/cycle read-ahead implemented and verified; ~20K FF *removed* |
| Metadata stalls expected; "oracle no-stall" baseline needed | Zero metadata stalls by construction (read-ahead paces the drain 1:1) — a headline result, not a baseline |
| 48/64-bit shadow accumulator "must-have" | int32 + documented saturation contract shipped; wide-acc now a planned config option + ablation (P4) |
| AWS F1-era FireSim assumptions | On-prem VCU118 (FireSim) + Nexys Video (prototype) in hand; both Chipyard harnesses exist in-tree |
| Conference-first (FCCM), journal later | **TRETS journal-first** (user decision); CAL letter as fallback |

---

## 2. Faults found in the prior plans, and their resolutions

This section is the critical review *of* the prior plans (`research-report 4.md`,
`mxint8_gemmini_plan_updated.pdf`), with the resolution each fault gets in this plan.

1. **Stale by reality.** All schedule/work-split content predates implementation; the
   project is months ahead of both documents. → Replaced by §8 timeline.
2. **Architecture prescriptions contradicted by the verified build.** The PDF mandates
   separate `MXKBlockController`/`ScaleVectorGenerator` modules and a tile-buffered
   scaling stage; the verified implementation is an inline BlockScaleUnit with undelayed
   streaming scaling and a per-cycle scale read-ahead (RES-OPT Part B), which *removed*
   the single largest MX register (~20K FF at DIM=32). → The paper presents the streaming
   design **as a finding**: the prescribed tile buffer is unnecessary; metadata delivery
   can pace a one-row-per-cycle drain exactly with one SRAM read per cycle.
3. **The "must-have" wide shadow accumulator was never built.** → Resolved two ways (P4):
   a wider-`accType` MX config variant, plus a quantitative saturation-incidence study on
   real workload exponent distributions. Either outcome is publishable; together they are
   the accuracy-vs-area ablation.
4. **Transposer-claim overshoot** (the report's own #1 weakness, still unresolved in the
   repo): RTL asserts untransposed-WS-only while the framing says "transposer-based
   systolic arrays". → P5 attempts exactly one validated transpose/OS case with a **hard
   4-week gate**; on failure the claim is narrowed *by us, in writing, before reviewers
   do it* (the fallback both old plans sanction).
5. **Baseline set partially obsolete.** "Duplicate-transposed-metadata" and "oracle
   no-stall" baselines presupposed metadata stalls and transposer remapping problems the
   final design does not have. → Redefined baseline set in §5.1.
6. **Workloads never concretized; zero accuracy sanity in-repo.** → §5.2 names the shape
   lists; P9 adds a software-only model-level sanity via microsoft/microxcaling;
   `PLAN_TEST.md`'s specified-but-never-implemented `mxint8_external_diff.py` becomes a
   P1 deliverable.
7. **Single-output-tile (I=J=1) loop-wrapper limitation** was unknown to both plans and
   blocks realistic shapes through the hardware loop unroller. → P3 lifts it (multi-tile
   scale addressing), the highest-payoff RTL item for the evaluation.
8. **Artifact discipline preached, not practiced.** The entire MX implementation is
   uncommitted WIP in the `generators/gemmini` submodule. → P0 (commit/branch/tag,
   reproduction scripts) is the **first** execution phase; nothing else proceeds before it.
9. **Venue ordering conflict** (report: conference-first; goal: journal). → TRETS
   journal-first: the closest related work is in TRETS, the full-stack FPGA story fits its
   scope, there is no submission deadline to force premature cuts, and it runs artifact
   evaluation. CAL letter remains the fast fallback if scooped (§9).
10. **A normative-conformance check against the OCP spec was never required by either
    plan.** The golden model, packer, and policy were only ever validated against *each
    other*. This session's clause-by-clause read of OCP MX v1.0 found real divergences —
    see §3.1. → P1 (conformance audit) runs **before any new RTL**.
11. Minor: the report's `citeturn…`/`entity[…]` rendering artifacts make it unciteable
    as-is; its FireSim assumptions predate the F1 deprecation guidance it itself cites;
    neither plan knew about the two boards actually available (Nexys Video, VCU118).

---

## 3. Thesis and contributions

**Thesis.** Block-scaled (OCP MX) numerics do not require a new datapath: a systolic
accelerator whose physical inner dimension does not match the logical 32-element MX block
can preserve exact MXINT8 semantics through a *sidecar metadata mechanism alone* — sidecar
scale storage, logical K-block tracking derived from drain order, and block-boundary
scaling before cross-block accumulation — with the multiplier array left untouched and
zero added throughput cost.

**Contributions (paper claims):**

- **C1 — OCP-conformant control-plane retrofit.** MXINT8 execution added to Gemmini with
  the mesh/PEs/transposer byte-for-byte unmodified; an explicit OCP MX v1.0 conformance
  matrix with documented profile deviations (no competitor publishes one). Stock behavior
  provably bit-identical when MX is disabled.
- **C2 — Logical/physical K-block mismatch mechanism.** Formal correctness invariant
  (reuse the math of `mxint8_gemmini_plan_updated.pdf` §3: `β(k) = ⌊k/B⌋`; all physical
  episodes mapping to the same `β` must use the same scale vectors; each block scaled once
  before merging), realized for DIM=16 via two-phase raw-partial accumulation with
  drain-order-derived `(block, half)` tracking — no per-GEMM runtime resets, state derived
  from the instruction stream and strict FIFO drain order.
- **C3 — Zero-overhead streaming metadata delivery.** One predecoded-exponent SRAM read
  per cycle paces a one-row-per-cycle drain exactly (read-ahead); no stall states exist.
  Measured full-stack: cycles (FireSim/VCU118), LUT/FF/BRAM/DSP/Fmax deltas (Vivado), and
  the negative-FF result of the resource-optimization pass vs the naive delayed-register
  sidecar (a real, documented design iteration — presented as an ablation).
- **C4 — Verification methodology + metadata-hazard taxonomy.** Bit-exact layered
  verification against a frozen golden model, cross-checked against
  microsoft/microxcaling (the spec authors' reference); a taxonomy of 12+ real
  metadata-execution hazards found only by behavioral RTL verification (reservation
  -station config-mirror pollution by metadata mvins → missed RAW hazard; feed-sampled
  tag drift at block boundaries; two-phase buffer clobber by pipeline bubbles; scale-latch
  overwrite mid-drain; DMA cmd-id misattribution; delayed/undelayed domain off-by-one…).
  The lesson generalizes: **MX bugs are metadata/ordering bugs, not arithmetic bugs.**
- **C5 (stretch, gated on P10) — Format-parametric MXINT(d) generator.** Element width as
  a generator parameter; MXINT8 (OCP-concrete) and MXINT16 (MX-consistent generalization
  within the spec's §5.1 framework — *not* an OCP format, stated plainly) demonstrated
  from one parameterized implementation.

**What the paper does not claim:** state-of-the-art quantization accuracy; MXFP support;
training; arbitrary-DIM generality beyond {16, 32}; OS/transpose support unless P5 lands
(otherwise the claim is explicitly narrowed to untransposed weight-stationary).

### 3.1 OCP MX v1.0 conformance position (new; the spec is the normative anchor)

Clause-by-clause review of `OCP_Microscaling Formats (MX).pdf` against the frozen contract
(`mxint8_policy.md`, `mxint8_golden.h`, `mxint8_pack.h`):

**Conformant on inspection:** E8M0 encoding (Table 7: bias 127, range −127..127, 0xff NaN,
no Inf/zero) ⇔ `e = s−127` decode + encode clamp; INT8 element type (Table 6/§5.3.4:
implicit 2⁻⁶ = 1 sign + 1 integer + 6 fraction bits; saturate-to-max-magnitude on
conversion is a *must*; roundTiesToEven is a *must*) ⇔ policy/golden/packer; per-block dot
product (§6.1, *must*): scales factored out, reduction on elements only, internal precision
explicitly implementation-defined ⇔ raw-int-sum-then-shift; K-tail zero-padding to a
multiple of k (§6.2 assumption) ⇔ policy.

**Divergences (P1 fixes or documents):**

1. **Packer scale-selection ≠ spec §6.3 recommended algorithm.** Spec: `X =
   2^floor(log2(amax))` (over the largest power-of-two representable in the element type,
   = 1 for INT8) and clamp overflowing elements to max normal. Our packer picks the
   smallest exponent whose step avoids clamping. The two differ whenever
   `amax ∈ (1.984·2^j, 2·2^j)` → different bits than every spec-following tool
   (microxcaling). §6.3 permits alternatives (*may*), but interop demands the spec
   algorithm. **Fix in P1** — packer/test-vector change only; golden GEMM and RTL consume
   whatever payload+scale they are given, so nothing ripples into hardware.
2. **NaN scale (0xff).** Spec §5.1: scale = NaN ⇒ every value in the block is NaN. The
   implementation rejects at load (assert; golden returns −1). An integer-only output has
   no NaN representation; reject-loudly is the chosen **documented profile deviation**.
3. **§6.2 DotGeneral *should* return Float32.** The int32 saturating accumulator deviates
   from the recommendation. The P4 wide-acc option *exceeds* the recommended FP32
   accuracy (exact int64 accumulation vs 24-bit-mantissa FP32 summation) — the conformance
   table will state: int32 = documented narrow profile; wide mode ≥ spec recommendation.
4. **−128 payload.** Spec: the maximum-negative encoding (−2.0) *may* be left unused (our
   packer never emits it — allowed), but conformant third-party data may contain it; the
   datapath handles it arithmetically (raw bound 32·128² < 2²⁰) yet no test ever fed it.
   **P1 adds the corner test** and footnotes the `32·127²` bound used in the docs.

Deliverable: `DOCS_MX/OCP_CONFORMANCE.md` (matrix: spec clause → project behavior →
conform / documented deviation / N.A.) — also a paper table.

---

## 4. Evidence already in hand (done inventory)

All RTL-verified bit-exact on Verilator against the frozen golden, on
`GemminiMXINT8DIM32RocketConfig` and `GemminiMXINT8DIM16RocketConfig`
(full log: `DOCS_MX/PLAN_MX.md` §12):

- Frozen numerical contract (`mxint8_policy.md`) + bit-exact golden (`mxint8_golden.h`)
  + fp32 packer (`mxint8_pack.h`, host-validated).
- Sidecar pipeline: `MXScaleSRAM` (predecoded signed exponents; 2 SyncReadMems after
  RES-OPT A), `MXScaleLoadController` (dedicated DMA), reservation-station ordering with
  real DMA completion (no fences needed), `CONFIG_MXINT8`/`LOAD_MX_SCALE_A/B` ISA ops.
- DIM=32 aligned path: probe + random K=32/64, corner matrix (zero, ±sat in int32 and
  int64 regimes, negative-shift rounding, random exponents, K-tail, 0xff reject), partial
  tiles (1×32 … 13×20, cross-block partial).
- DIM=16 two-phase path (the contribution): hold invariant + single scale application per
  logical block, 1/2/3/4 logical blocks, deterministic cross-block probes — after fixing
  four behavioral bugs (D1–D4) that only bit-exact RTL runs could catch.
- Resource optimization (RES-OPT A+B): scale SRAM 6→2 memories; the ~20K-FF (DIM=32)
  output-delay register removed via undelayed scaling + per-cycle read-ahead; throughput
  unchanged; bit-exactness re-proven.
- Hardware loop-unroller wrapper: multi-block K supported and regression-tested
  (`mxint8_btb` K=32→K=64 back-to-back; root cause was an RS RAW-hazard miss from scale
  mvins polluting the CONFIG_LOAD decode mirror — fixed with a one-line decode guard,
  `DOCS_MX/BUG.md`).
- Stock parity: stock `GemminiRocketConfig` elaborates with 0 errors; every MX change is
  `mx_enabled`-gated or provably decode-unreachable for stock.
- 12+ documented metadata-execution hazards with root causes and trace evidence (C4 raw
  material; see PLAN_MX.md log entries R1 A/B/C, R2-1/2, B1/B2/B3, D1–D4, 2026-06-10).

**Remaining v1 constraints:** loop wrapper is single-output-tile (I=J=1) → P3; int32
accumulator profile → P4; untransposed-WS-only → P5; counters/synthesis numbers absent →
P2; nothing committed to git → P0.

---

## 5. Evaluation plan

### 5.1 Baselines (redefined; see fault #5)

| Baseline | Definition | Purpose |
|---|---|---|
| Stock Gemmini int8 (DIM=32 and DIM=16) | Unmodified Gemmini, same shapes, no scaling | Lower bound; proves zero-throughput-cost claim (cycle parity) and resource delta denominator |
| Software-MX on stock Gemmini | Per-32-block GEMM calls on stock hardware + host-side E8M0 scale-and-accumulate | The realistic "do MX in software" alternative; measures what the sidecar saves |
| MXINT8 DIM=32 aligned (ours) | One physical episode = one logical block | Separates plumbing cost from the mismatch mechanism |
| MXINT8 DIM=16 logical-K (ours) | One block = two physical phases | The contribution under test |
| (dropped) Oracle no-stall | — | Metadata stalls are zero by construction; replaced by a counter proof (stall counter ≡ 0) |
| (dropped) Duplicate-transposed metadata | — | Presupposed a transposer-remapping problem v1 does not have; revisit only if P5 lands |

### 5.2 Workloads (named, frozen at P6)

- **Synthetic GEMM suite:** square / skinny / fat / K-tail; K ∈ {32, 64, 96, 128, 256,
  512}; M, N ∈ {DIM, 2·DIM, 4·DIM, odd partials}; seeds recorded.
- **Transformer-derived shapes (named, not "BERT-like"):** BERT-Base and BERT-Large
  attention QKV/output and MLP GEMMs (e.g. Base: 768×768, 768×3072, 3072×768 at
  sequence-length-tiled M; Large: 1024/4096 analogues), lowered to the supported tile
  sizes; exact shape list checked into the harness as data files.
- **ResNet50 lowered-conv GEMM shapes:** include if low-friction after P3 (optional).
- **Accuracy sanity (software-only, P9):** one public checkpoint (BERT-Base layer or a
  small ViT/MLP) through microxcaling MXINT8 emulation (`block_size=32, scale_bits=8,
  elem_format='int8', round='even'`) vs FP32 — a delta statement, not an accuracy paper.

### 5.3 Ablations

| Ablation | Question | Evidence |
|---|---|---|
| DIM=32 vs DIM=16 | Cost of the mismatch mechanism proper | cycles, counters, resource delta |
| int32 vs wide accumulator (P4) | When does the narrow profile clamp? | saturation-incidence counts (golden sweep) + RTL config pair |
| Read-ahead streaming vs delayed-register sidecar | Was the prescribed tile buffer needed? | RES-OPT before/after: −20K FF, 0 cycle delta (retrospective, fully documented) |
| Identity-scale ≡ stock | Does MX collapse to stock semantics? | shift-0 probe ≡ raw int8 partials |
| K-tail / odd shapes | Silent-bug class | corner + partial suites |
| Multi-tile on/off (P3) | Wrapper vs per-tile software calls | cycles per realistic shape |
| Transpose/OS case (only if P5 lands) | Claim breadth | the single validated case |

### 5.4 Metrics and counters (P2)

Kernel cycles; useful MACs; payload vs metadata DMA bytes; MXScaleSRAM reads/writes;
metadata stall cycles (expected ≡ 0 — the claim is the counter); logical K-block
transitions; DIM=16 half count; BlockScaleUnit active rows; accumulator writes;
LUT/FF/BRAM/DSP/Fmax. AutoCounter for performance counters; TracerV only for debugging,
never for throughput claims.

---

## 6. Platforms (both boards are in hand)

| Platform | Role | Config |
|---|---|---|
| Verilator | All correctness/regression; counter bring-up | `GemminiMXINT8DIM32/16RocketConfig`, stock controls |
| **VCU118** (FireSim, on-prem XDMA flow) | Performance counters on full workload suite; full-system runs too long for Verilator | FireSim targets built from the frozen commits (P7) |
| **Nexys Video** (Artix-7 XC7A200T, Chipyard `fpga/` flow — harness exists in-tree) | Physical prototype + Vivado resource/Fmax evidence | DIM=16 MX config (DIM=32 will not fit); fallbacks: shrunk Rocket/spad → Vivado OOC synthesis numbers only |

Commits, tool versions (Vivado, firtool 1.62.1, conda env), and seeds are frozen at P0/P7
and recorded in the artifact.

---

## 7. Publication path

- **Primary: ACM TRETS** (journal-first). Rationale: the closest related work (KAUST MX
  systolic arrays) is in TRETS — primed reviewers and a citable contrast; full-stack
  FPGA/generator work is squarely in scope; no deadline pressure; artifact evaluation
  available. Frame C1–C3 as the architecture core, C4 as methodology, C5 if it lands.
- **Fallback: IEEE CAL letter** if a scooping publication appears mid-stream — the DIM=16
  mismatch mechanism + zero-overhead result compresses to 4 pages.
- **arXiv preprint** immediately after the first full evaluation cut (post-P7) to
  timestamp the contribution.
- A later conference paper (FCCM) remains possible *from* the journal material if desired,
  not the other way around.

---

## 8. Timeline (now → submission; replaces both old month plans)

| Window | Phases (see PLAN_MX_UPDATED.md) | Exit |
|---|---|---|
| Jun 2026 | **P0** artifact hygiene; **P1** OCP conformance audit | Tagged baseline; conformance matrix; external diff green; regression green with spec packer |
| Jul 2026 | **P2** AutoCounters + OOC synthesis deltas | Counter CSVs; resource table draft |
| Aug 2026 | **P3** multi-tile I/J>1; **P4** wide-acc option + saturation study | BERT-shape GEMM through wrapper bit-exact; ablation data |
| Sep 2026 | **P5** OS/transpose single case — **hard 4-week gate** | One validated case OR written claim narrowing |
| Sep–Oct 2026 | **P6** eval harness + baselines; **P7** FireSim/VCU118 | Reproducible baseline-vs-proposed data; FPGA-host counter CSVs; **arXiv preprint** |
| Nov 2026 | **P8** Nexys Video prototype; **P9** accuracy sanity | Bitstream + resource/Fmax report (or OOC fallback); accuracy delta statement |
| Nov–Dec 2026 | **P10** MXINT(d)/MXINT16 parameterization — **gated**: drop to future work if it threatens the freeze | MXINT16 bit-exact on both DIMs, or future-work section |
| Dec 2026–Feb 2027 | **P11** paper + artifact packaging | Submission-ready paper, reproduction README |
| ~Mar 2027 | **Submit to TRETS** | — |

---

## 9. Risk register (with stop/fallback gates)

| Risk | Trigger | Mitigation / fallback |
|---|---|---|
| Scooped (someone publishes an MX-retrofit-on-generator paper) | Any month | arXiv preprint after P7; pivot to CAL letter emphasizing the DIM=16 mechanism + hazard taxonomy |
| OS/transpose case doesn't land | P5 gate (4 weeks) | Narrow the claim in writing (untransposed-WS), include the mechanism analysis as honest scoping — sanctioned by both prior plans |
| Wide accumulator too invasive (AccumulatorMem width assumptions) | P4 investigation | Ship the golden-side saturation-incidence study alone; document int32 profile quantitatively |
| Nexys Video doesn't fit DIM=16 SoC | P8 | Shrink Rocket/spad; final fallback Vivado OOC synthesis numbers (still real resource evidence) |
| FireSim/Chipyard version drift | P7 bring-up | Freeze commits at P0; record exact manager/bitstream versions; budget bring-up time before counter runs |
| Multi-tile RTL destabilizes the verified core | P3 | Strict regression matrix after every change (PLAN_MX_UPDATED.md §4); the I=J=1 per-tile-call path remains the fallback eval route |
| MXINT16 parameterization slips | P10 gate | Future-work section; C5 dropped; no slip into P11 permitted |
| Benchmark sprawl | P6 | Workload list frozen in §5.2; additions require removing something |

---

## 10. Deliverables

1. `DOCS_MX/OCP_CONFORMANCE.md` — conformance matrix (also a paper table).
2. `mxint8_policy.md` v1.1 — spec-mapping + documented deviations.
3. Spec-conformant packer + `tools/mxint8_external_diff.py` (microxcaling cross-check).
4. Counter + synthesis data (CSV), Vivado reports, FireSim configs/workloads.
5. Multi-tile, wide-acc, and (gated) transpose/OS + MXINT16 RTL with tests.
6. Tagged, committed, reproducible artifact (scripts, seeds, commit SHAs, env pins).
7. TRETS manuscript + artifact appendix; arXiv preprint after P7.
