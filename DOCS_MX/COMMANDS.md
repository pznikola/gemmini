# Gemmini Commands

Status snapshot: 2026-07-03.

This is the practical runbook for building and running Gemmini/MXINT8 tests in
this Chipyard checkout. Commands assume the repo root is:

```bash
/home/nikolap/Research/2026/chipyard
```

Prefer the wrapper scripts in `DOCS_MX/scripts` for MXINT8 regression, perf, and
synthesis runs. They encode the header-staging, JDK, firtool, and `LOADMEM=1`
details that have caused false failures in the past.

## One-Time Shell Setup

Run this first in every shell:

```bash
cd /home/nikolap/Research/2026/chipyard
source /home/nikolap/Research/2026/chipyard/env.sh
export PATH="$CONDA_PREFIX/bin:$PATH"
export FIRTOOL="$HOME/.cache/llvm-firtool/1.62.1/bin/firtool"
```

Verify the two common traps:

```bash
which java
java -version
test -x "$FIRTOOL"
```

Expected:

- `java -version` reports JDK 20 from the conda environment.
- `$FIRTOOL` exists and points at `~/.cache/llvm-firtool/1.62.1/bin/firtool`.

Do not rely on bare `firtool` from `PATH`; older system firtool builds have failed
on this repo's FIRRTL dialect. If `source env.sh` is run from the wrong directory
or without the absolute path, the shell can silently fall back to the wrong JDK.

## Useful Config Names

MXINT8 configs:

```text
GemminiMXINT8DIM32RocketConfig
GemminiMXINT8DIM16RocketConfig
GemminiMXINT8DIM8RocketConfig
GemminiMXINT8DIM4RocketConfig
```

Stock comparison configs:

```text
GemminiRocketConfig
GemminiStockDIM32RocketConfig
GemminiStockDIM16RocketConfig
GemminiStockDIM8RocketConfig
GemminiStockDIM4RocketConfig
```

Current checked-in params headers:

```text
gemmini_params_mxint8_dim32.h
gemmini_params_mxint8_dim16.h
gemmini_params_mxint8_dim8.h
gemmini_params_mxint8_dim4.h
gemmini_params_stock_dim32.h
gemmini_params_stock_dim16.h
```

As of this snapshot, stock DIM8/DIM4 params headers are not present in
`gemmini-rocc-tests/include`, even though the Chipyard stock configs exist. A full
stock-vs-MX perf sweep over 8/4 needs those headers regenerated or added first.

## Fast RTL Compile Gate

Use this for a quick Scala/Chisel compile check:

```bash
cd /home/nikolap/Research/2026/chipyard
sbt "project gemmini" compile
```

Force a clean compile if Zinc cache behavior is suspicious:

```bash
cd /home/nikolap/Research/2026/chipyard
sbt "project gemmini" clean compile
```

## Verilog Elaboration Gate

Elaborate MX DIM32 without building a Verilator binary:

```bash
cd /home/nikolap/Research/2026/chipyard
make -C sims/verilator \
  CONFIG=GemminiMXINT8DIM32RocketConfig \
  FIRTOOL_BIN="$FIRTOOL" \
  verilog
```

Elaborate stock:

```bash
cd /home/nikolap/Research/2026/chipyard
make -C sims/verilator \
  CONFIG=GemminiRocketConfig \
  FIRTOOL_BIN="$FIRTOOL" \
  verilog
```

Build a full Verilator simulator:

```bash
cd /home/nikolap/Research/2026/chipyard
make -C sims/verilator \
  CONFIG=GemminiMXINT8DIM32RocketConfig \
  FIRTOOL_BIN="$FIRTOOL"
```

If an RTL edit is not picked up, remove the generated source for that one config
and rebuild the sim:

```bash
cd /home/nikolap/Research/2026/chipyard
rm -rf sims/verilator/generated-src/chipyard.harness.TestHarness.GemminiMXINT8DIM32RocketConfig
make -C sims/verilator \
  CONFIG=GemminiMXINT8DIM32RocketConfig \
  FIRTOOL_BIN="$FIRTOOL"
```

## Build Gemmini Software Tests

Build with the current `include/gemmini_params.h`:

```bash
cd /home/nikolap/Research/2026/chipyard/generators/gemmini/software/gemmini-rocc-tests
./build.sh
```

Build MX DIM32 tests by staging the DIM32 MX params header:

```bash
cd /home/nikolap/Research/2026/chipyard/generators/gemmini/software/gemmini-rocc-tests
cp include/gemmini_params.h /tmp/gemmini_params.saved.h
cp include/gemmini_params_mxint8_dim32.h include/gemmini_params.h
./build.sh
cp /tmp/gemmini_params.saved.h include/gemmini_params.h
```

Build MX DIM16/DIM8/DIM4 by replacing the staged header with:

```text
include/gemmini_params_mxint8_dim16.h
include/gemmini_params_mxint8_dim8.h
include/gemmini_params_mxint8_dim4.h
```

Build stock DIM32 or DIM16 benchmarks by staging:

```text
include/gemmini_params_stock_dim32.h
include/gemmini_params_stock_dim16.h
```

Always restore `include/gemmini_params.h` after a staged build. The wrapper
scripts do this automatically.

## MXINT8 Regression

Full MX regression using existing Verilator binaries:

```bash
cd /home/nikolap/Research/2026/chipyard
DOCS_MX/scripts/run_regression.sh --dims=32,16,8,4
```

Full MX regression and rebuild all MX sims first:

```bash
cd /home/nikolap/Research/2026/chipyard
DOCS_MX/scripts/run_regression.sh --build-sims --dims=32,16,8,4
```

Faster focused runs:

```bash
cd /home/nikolap/Research/2026/chipyard
DOCS_MX/scripts/run_regression.sh --dim32-only
DOCS_MX/scripts/run_regression.sh --dim16-only
DOCS_MX/scripts/run_regression.sh --dims=8,4
```

The script logs to `/tmp/mx_*.log` and prints `OVERALL: PASS` only if every
matrix entry passes. It also runs a stock elaboration gate.

Regression test matrix currently used by the script:

```text
DIM32: mxint8_golden mxint8_matmul_dim32 mxint8_corner mxint8_matmul_partial
       mxint8_tiled mxint8_btb mxint8_multitile mxint8_matmul_nphase
DIM16: mxint8_matmul_dim16 mxint8_multitile mxint8_matmul_nphase
DIM8:  mxint8_multitile mxint8_matmul_nphase
DIM4:  mxint8_multitile mxint8_matmul_nphase
```

## Run One Baremetal Test On Verilator

Example: build DIM32 MX tests, build the DIM32 MX sim, then run `mxint8_tiled`:

```bash
cd /home/nikolap/Research/2026/chipyard
TESTS_DIR="$PWD/generators/gemmini/software/gemmini-rocc-tests"
BUILD_DIR="$TESTS_DIR/build/bareMetalC"
CONFIG=GemminiMXINT8DIM32RocketConfig
TEST=mxint8_tiled

cd "$TESTS_DIR"
cp include/gemmini_params.h /tmp/gemmini_params.saved.h
cp include/gemmini_params_mxint8_dim32.h include/gemmini_params.h
./build.sh
cp /tmp/gemmini_params.saved.h include/gemmini_params.h

cd /home/nikolap/Research/2026/chipyard
make -C sims/verilator CONFIG="$CONFIG" FIRTOOL_BIN="$FIRTOOL"
make -C sims/verilator CONFIG="$CONFIG" FIRTOOL_BIN="$FIRTOOL" run-binary-fast \
  BINARY="$BUILD_DIR/$TEST-baremetal" \
  LOADMEM=1 \
  timeout_cycles=300000000
```

Use `LOADMEM=1` for large baremetal binaries. Without it, TSI serial loading can
look like a hang before the test prints anything.

The Verilator output log usually lands under:

```text
sims/verilator/output/chipyard.harness.TestHarness.<Config>/<test>-baremetal.log
```

## Run Basic Gemmini Tests On Spike

Use Spike for basic Gemmini ISA simulator smoke tests:

```bash
cd /home/nikolap/Research/2026/chipyard/generators/gemmini/software/gemmini-rocc-tests
./build.sh
cd build/bareMetalC
spike --extension=gemmini mvin_mvout-baremetal
spike --extension=gemmini matmul_ws-baremetal
```

Do not use Spike as the final validator for the MXINT8 sidecar unless the local
Gemmini Spike extension has been updated for those custom MX commands. MXINT8
hardware validation should use Verilator.

## Performance Runs

Current safe perf run with the checked-in stock headers:

```bash
cd /home/nikolap/Research/2026/chipyard
DOCS_MX/scripts/run_perf.sh --build-sims --dims 32,16
```

Full intended stock-vs-MX perf sweep, after stock DIM8/DIM4 headers exist:

```bash
cd /home/nikolap/Research/2026/chipyard
DOCS_MX/scripts/run_perf.sh --build-sims --dims 32,16,8,4
```

Reuse existing sims:

```bash
cd /home/nikolap/Research/2026/chipyard
DOCS_MX/scripts/run_perf.sh --dims 32,16
```

Results are appended to:

```text
DOCS_MX/scripts/results/perf.csv
```

The perf script is resumable: if a config already has rows in `perf.csv`, it skips
that config. Remove that config's rows before rerunning it.

## Synthesis Runs

Run OOC synthesis for the default stock/MX config pairs:

```bash
cd /home/nikolap/Research/2026/chipyard
DOCS_MX/scripts/run_synth.sh
```

Use already-generated Verilog and skip elaboration:

```bash
cd /home/nikolap/Research/2026/chipyard
DOCS_MX/scripts/run_synth.sh --skip-verilog
```

Run one config:

```bash
cd /home/nikolap/Research/2026/chipyard
DOCS_MX/scripts/run_synth.sh GemminiMXINT8DIM32RocketConfig
```

Prerequisites:

- Vivado settings at `$HOME/Programs/Xilinx/Vivado/2022.2/settings64.sh`.
- Pinned firtool and JDK 20 active.

Results are written under:

```text
DOCS_MX/scripts/results/synth.csv
DOCS_MX/scripts/results/synth/<Config>/
```

## Generated Reports

Generate a comparison report from fresh `perf.csv` and `synth.csv`:

```bash
cd /home/nikolap/Research/2026/chipyard
python3 DOCS_MX/scripts/make_report.py
```

This writes:

```text
DOCS_MX/scripts/results/comparison_report.md
```

That file is generated output, not canonical documentation. Review it before
committing, and regenerate it from fresh data rather than preserving stale numbers.

## External MXINT8 Packer/Golden Cross-Check

Run one external diff against `microxcaling` from the rocc-tests directory:

```bash
cd /home/nikolap/Research/2026/chipyard/generators/gemmini/software/gemmini-rocc-tests
python3 tools/mxint8_external_diff.py \
  --backend microxcaling \
  --seed 1 \
  --m 16 --n 16 --k 64 \
  --round even \
  --json-out build/diff_seed1.json
```

Run the sweep:

```bash
cd /home/nikolap/Research/2026/chipyard/generators/gemmini/software/gemmini-rocc-tests
python3 tools/mxint8_external_diff.py --sweep --json-out build/diff_sweep.json
```

This requires a Python environment with CPU torch and `microxcaling`, such as the
project's `.mx-venv` if it has been set up.

## Common Troubleshooting

- `sbt` exits with little or no output: re-run the setup block and confirm JDK 20.
- Firtool reports unexpected characters or fails on both stock and MX: pass
  `FIRTOOL_BIN="$FIRTOOL"` explicitly.
- A large baremetal test appears stuck before printing: rerun with `LOADMEM=1`.
- `run-binary-fast` rebuilds a stale sim and then fails: make sure `FIRTOOL_BIN` is
  passed on the run command, not just the build command.
- A perf run skips a config: delete that config's rows from
  `DOCS_MX/scripts/results/perf.csv`.
- A staged-header build produces confusing behavior: restore `include/gemmini_params.h`
  and rebuild with the intended params header.
- DIM8/DIM4 stock perf fails due to missing headers: add or regenerate
  `gemmini_params_stock_dim8.h` and `gemmini_params_stock_dim4.h`, or run perf with
  `--dims 32,16`.
