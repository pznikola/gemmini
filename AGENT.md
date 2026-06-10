# AGENT.md — Working guide for AI agents on this repo

This repo is a **Chipyard** RISC-V SoC generator. The active work here is adding **OCP
MXINT8** (microscaling block-scaled int8) support to the **Gemmini** systolic-array
accelerator, framed as a research contribution targeting an **ACM TRETS journal paper**.
If you are picking this up cold, read this file, then `DOCS_MX/PLAN_MX_UPDATED.md` (the
execution plan of record, phases P0–P11), then `DOCS_MX/RESEARCH_PLAN.md` (the research/
publication plan), then `mxint8_policy.md` (the frozen numerical contract).
`DOCS_MX/PLAN_MX.md` is the historical log of the completed R0–R3/S1–S3/RES-OPT work.

- Repo root: `/home/nikolap/Research/2026/chipyard`
- Git: chipyard work branch `mx_dev`; base/PR branch `dissertation`. `generators/gemmini`
  is a submodule (fork `pznikola/gemmini`, branch **`mxint8_dev`**) — the MXINT8 work is
  **committed** there (baseline tag `mxint8-rtl-verified-20260610`); commit after every
  verified step.
- Current status (2026-06-10): **DIM=32 + DIM=16 MX GEMM verified bit-exact on Verilator RTL**
  (R0–R3, S1–S3 done; M0–M6 closed). **HW resource-optimization pass COMPLETE + RTL-verified**
  (see `DOCS_MX/PLAN_MX.md` "RES-OPT Part A/B"): **Part A** — MXScaleSRAM 6→2 mems, `mx_raw_half`
  narrowed 64→20b and removed at DIM=32; **Part B** — removed the ~20K-FF (DIM=32) / ~5K (DIM=16)
  `mx_mesh_resp_bits` output-delay register via undelayed scaling + a per-cycle scale read-ahead
  (one drain-order matmul counter `mx_matmul`). **Loop multi-block "inter-GEMM" bug FIXED**
  (2026-06-10, see `DOCS_MX/BUG.md`): root cause was an RS RAW-hazard miss (scale mvins polluted
  the RS CONFIG_LOAD decode mirror → A-mvin dependency range mis-decoded → compute raced the A
  DMA); fixed with a one-line `!is_mx_scale` decode guard in `ReservationStation.scala` — no
  resets, no mesh changes; the mesh was exonerated. Loop-wrapper multi-block K now supported and
  regression-tested (`mxint8_btb` registered; `mxint8_tiled` runs K=32→K=64 BtB). All MX tests
  bit-exact; stock unaffected; throughput unchanged. **Plan reset (2026-06-10):** research
  track replanned for an **ACM TRETS journal paper** — see `DOCS_MX/RESEARCH_PLAN.md` +
  `DOCS_MX/PLAN_MX_UPDATED.md` (phases P0–P11). **P0 done 2026-06-10** (submodule committed on
  `mxint8_dev` + tagged `mxint8-rtl-verified-20260610`; docs committed; regression script at
  `DOCS_MX/scripts/run_regression.sh`; fresh-checkout re-run deferred). Next: **P1** OCP MX
  v1.0 conformance audit (spec-§6.3 packer algorithm, −128 corner test, microxcaling external
  diff, policy v1.1), then **P2** counters/synthesis (the old R4).

---

## 1. Environment & how to run tools

### Toolchain activation (do this first, every session)
The conda env `.conda-env` pins **JDK 20 + sbt 1.8.2**. Two traps:
- The default PATH puts system **JDK 21** (`/usr/bin`) ahead of `.conda-env/bin`; JDK 21
  breaks sbt's build parser (`Could not initialize class sbt.internal.parser.SbtParser$`).
- If the shell cwd has drifted, `source ./env.sh` silently no-ops (file not found) and you
  fall back to JDK 21 + system sbt → sbt exits 1 with **no output**. Always use the
  **absolute** path.

```bash
source /home/nikolap/Research/2026/chipyard/env.sh && export PATH="$CONDA_PREFIX/bin:$PATH"
# verify: `which java` -> .conda-env/bin/java ; `java -version` -> 20.x
```
Run heavy builds in the background (sandbox disabled). Background tasks run from the repo-root
cwd regardless of foreground drift.

### Compile the Gemmini RTL (fast type-check gate)
```bash
sbt "project gemmini" compile          # from repo root, after sourcing env
sbt "project gemmini" clean compile    # force full rebuild (Zinc invalidates by content
                                       # hash, NOT mtime — `touch` does nothing; use clean)
```

### Elaborate to Verilog (full elaboration gate)
Gemmini has no standalone elaborator. Use a Chipyard top-config (these were added for MX):
```bash
make -C sims/verilator \
  CONFIG=GemminiMXINT8DIM32RocketConfig \
  FIRTOOL_BIN=$HOME/.cache/llvm-firtool/1.62.1/bin/firtool \
  verilog
```
- `verilog` runs Chisel elaboration → FIRRTL → firtool (catches width/connect bugs) without
  building a Verilator binary. Output: `sims/verilator/generated-src/<TopName>/gen-collateral/*.sv`.
- **Firtool trap:** bare `firtool` on PATH is a stale `/usr/local/bin/firtool` (LLVM-17) that
  cannot parse this repo's FIRRTL (`error: unexpected character` on backtick fields like
  `` `0` `` in `mem_axi4`), failing **both stock and MX**. Always pass `FIRTOOL_BIN` pointing
  at the chipyard-pinned `~/.cache/llvm-firtool/1.62.1/bin/firtool`.
- Stock comparison config: `CONFIG=GemminiRocketConfig` (non-MX baseline).

### Build the software tests (golden model + baremetal)
```bash
cd generators/gemmini/software/gemmini-rocc-tests
./build.sh                              # autoconf + make; binaries -> build/bareMetalC/<test>-baremetal
```
Add a new test: drop `bareMetalC/<name>.c`, add `<name>` to the `tests` list in
`bareMetalC/Makefile`, rerun `./build.sh`.

### Run a baremetal test
- On the Gemmini ISA simulator (spike + gemmini extension; needs `esp-tools` toolchain via
  `./scripts/build-toolchains.sh esp-tools`):
  ```bash
  cd build/bareMetalC && spike --extension=gemmini <test>-baremetal
  ```
- On RTL (Verilator), after building the sim for a config:
  ```bash
  make -C sims/verilator CONFIG=<Config> run-binary BINARY=<abs path to *-baremetal>
  ```

---

## 2. Where the important documents are

| Path | What it is |
|---|---|
| `DOCS_MX/PLAN_MX_UPDATED.md` | **Execution plan of record** (phases P0–P11: artifact hygiene → OCP conformance → counters → multi-tile → wide-acc → OS/transpose gate → eval → FireSim/FPGA → MXINT16 → paper). Keep its §5 log updated as steps complete. |
| `DOCS_MX/RESEARCH_PLAN.md` | **Research/publication plan** — TRETS thesis, contributions C1–C5, 2026 landscape, baselines/workloads/ablations, timeline, risks. |
| `DOCS_MX/PLAN_MX.md` | Historical log of the completed R0–R3/S1–S3/RES-OPT/bug-fix work (superseded for forward planning; its §12 log is the verification evidence base). |
| `mxint8_policy.md` | **Frozen numerical contract** (E8M0 decode, block scale `2^(eA+eB-12)`, rounding, saturation, `0xff` reject). Do not change semantics without updating this (v1.1 spec-mapping lands in P1). |
| `DOCS_MX/OCP_Microscaling Formats (MX).pdf` | **OCP MX v1.0 spec — the normative standard** (P1 conformance matrix maps to it). |
| `DOCS_MX/PLAN_TEST.md` | Golden-model/verification test plan (external cross-check spec → P1's `mxint8_external_diff.py`). |
| `DOCS_MX/mxint8_gemmini_plan_updated.pdf` | April 2026 detailed research plan (historical input; superseded). |
| `DOCS_MX/research-report 4.md` | April 2026 critical review (historical input; superseded). |
| `CODING_STYLES/scala_coding_style.md` | Chisel/Scala style (mandatory). |
| `CODING_STYLES/c_cpp_coding_style.md` | C style (mandatory). |

---

## 3. Where the code is

All MXINT8 work lives in the **`generators/gemmini`** submodule.

**RTL — `generators/gemmini/src/main/scala/gemmini/`**
- `MXScaleSRAM.scala` — sidecar metadata SRAM (predecoded E8M0 exponents, A/B ports).
- `MXScaleLoadController.scala` — DMA loader for scale rows.
- `ExecuteController.scala` — MX integration: logical-K-block tracking, B/A scale read,
  inline **BlockScaleUnit** (round/shift/saturate), DIM=16 two-phase accumulation.
- `Scratchpad.scala`, `Controller.scala`, `ReservationStation.scala` — MX wiring, opcode
  decode, load routing, scale-reader instantiation.
- `GemminiISA.scala` — MX opcodes: `CONFIG_MXINT8_CMD=26`, `LOAD_MX_SCALE_A_CMD=27`,
  `LOAD_MX_SCALE_B_CMD=28`, `LOOP_WS_MXINT8=29`.
- `Configs.scala` / `GemminiConfigs.scala` — `mxint8DIM32Config`/`DIM16`,
  `GemminiMXINT8DIM32Config`/`DIM16` (RoCC mixins) + derived MX params.
- `AccumulatorMem.scala` — stock accumulator (R0b fixed a latent `None.get` here).
- **`generators/gemmini/chipyard/GemminiConfigs.scala`** — Chipyard top-configs:
  `GemminiMXINT8DIM32RocketConfig`, `GemminiMXINT8DIM16RocketConfig` (added in R0b).

**Software — `generators/gemmini/software/gemmini-rocc-tests/`**
- `include/mxint8_golden.h` — bit-exact reference (matches `mxint8_policy.md`).
- `include/gemmini.h` — MX intrinsics (`gemmini_config_mxint8`, `gemmini_mvin_mxscale_a/b`,
  `gemmini_loop_ws_mxint8`).
- `include/gemmini_params_mxint8_dim32.h` — generated MX params header.
- `bareMetalC/mxint8_golden.c` — software golden unit test (no hardware path yet).
- `bareMetalC/Makefile` — test registration.

**Broader repo:** `generators/` (all accelerators/cores incl. `rocket-chip`, `boom`,
`gemmini`), `sims/{verilator,vcs,firesim}` (sim flows), `software/`, `toolchains/`,
`tools/`, `scripts/`, `fpga/`, `vlsi/`.

---

## 4. What the code does

**Gemmini** is a RoCC-attached systolic-array GEMM/conv accelerator: payload matrices stream
DRAM → DMA → scratchpad → transposer → int8 MAC mesh → accumulator.

**MXINT8 addition (the research):** OCP microscaling — a 32-element K-block shares one 8-bit
**E8M0** power-of-two scale; payloads are signed int8 with an implicit `2^-6`. The design is a
**sidecar metadata pipeline**: scales never enter the payload transposer. `MXScaleSRAM` holds
predecoded exponents; `ExecuteController` tracks the logical 32-K block independent of the
physical array width `DIM`; the **BlockScaleUnit** applies `2^(eA+eB-12)` (round-nearest-even
shift + saturate) to each raw block partial before cross-block accumulation. Staged:
**DIM=32** (one physical K episode = one logical block, the MVP) → **DIM=16** (one logical
block spans two physical K phases; held scales + two-phase accumulation — the contribution).

---

## 5. The plan

`DOCS_MX/PLAN_MX_UPDATED.md` is authoritative for new work. Phases: **P0** artifact
hygiene (commit/tag the WIP) → **P1** OCP MX v1.0 conformance audit → **P2** AutoCounters +
synthesis deltas → **P3** multi-tile I/J>1 → **P4** wide-accumulator option → **P5**
OS/transpose single case (hard 4-week gate) → **P6** eval harness/baselines → **P7**
FireSim VCU118 → **P8** Nexys Video prototype → **P9** accuracy sanity → **P10** MXINT(d)/
MXINT16 parameterization (gated stretch) → **P11** TRETS paper + artifact. The research
rationale lives in `DOCS_MX/RESEARCH_PLAN.md`. The old R0–R4/S1–S3 phases are **complete**
and logged in `DOCS_MX/PLAN_MX.md` §12. **Standing rule: after completing each step, update
`PLAN_MX_UPDATED.md` §5** (dated log entry) and this file's status line.

---

## 6. Coding styles (mandatory)

- **Scala/Chisel** (`CODING_STYLES/scala_coding_style.md`, Databricks): 2-space indent,
  ≤100-char lines, `PascalCase` types / `camelCase` methods / `UPPER_CASE` constants in
  companion objects, JavaDoc `/** */`, braces on all conditionals/loops, `override` on
  overrides, explicit public return types, avoid implicits/symbolic methods, Rule-of-30.
- **C** (`CODING_STYLES/c_cpp_coding_style.md`, Google/lowRISC): C11, pointer `*` by name,
  braces everywhere, `//` comments, Doxygen `/** */` with `@param`/`@return`,
  `lower_snake_case`, enum constants prefixed by type, one shared prefix per header
  (`mxint8_`, `gemmini_`), hygienic `do { } while (false)` macros. Macros are correct for the
  RoCC asm opcode encodings.

---

## 7. Gotchas & operating notes (read before building)

- **Always source env.sh by absolute path** and prepend `$CONDA_PREFIX/bin` to PATH
  (JDK-20-vs-21 trap). Verify `java -version` is 20.x before trusting a build.
- **firtool**: always pass `FIRTOOL_BIN=$HOME/.cache/llvm-firtool/1.62.1/bin/firtool` to
  `make … verilog`; the PATH default is a broken LLVM-17 firtool.
- **Zinc** invalidates by content hash → `touch` won't trigger recompile; use `clean`.
- **Background `make`** with `&&` chains: if env sourcing fails the chain short-circuits and
  `make` never runs (no log) — check the real `MAKE_EXIT` value, not the wrapper's exit code.
- **Submodule**: `generators/gemmini` has uncommitted WIP — treat it as
  audit/complete/verify, not greenfield. Don't reset/clean it.
- Most MX bugs are **metadata/addressing** bugs, not arithmetic bugs (per the plan's risk
  notes). When debugging, suspect scale read addresses, K-block advance, and tail masking
  first.
- The R0b elaboration fixed two real bugs the compile gate missed: `AccumulatorMem.scala`
  `None.get` (`ext_mem.get` in the non-shared-ext-mem branch) and a `Scratchpad.scala`
  counter double-connect (MX scale reader reusing the main reader's `CounterEvent` IDs).
