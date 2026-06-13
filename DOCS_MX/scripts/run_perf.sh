#!/usr/bin/env bash
# run_perf.sh — stock-vs-MXINT8 Verilator benchmark runner (plan Stage C).
#
# For each DIM pair: stages the matching params header, builds the baremetal tests
# (mx_bench picks the implementation from MX_ENABLED at compile time), runs mx_bench on
# the stock and MX configs, and collects the machine-readable MXBENCH lines into
# results/perf.csv. Resumable: (config) rows already in the CSV are skipped.
#
# Usage:
#   ./run_perf.sh [--build-sims] [--dims 32,16,8,4]
#
# Prereqs: the per-DIM params headers exist (gemmini_params_mxint8_dim<D>.h and
# gemmini_params_stock_dim<D>.h in gemmini-rocc-tests/include), and the Verilator sims
# are built unless --build-sims is given. BERT shapes take hours per config on Verilator.

set -u

REPO="/home/nikolap/Research/2026/chipyard"
FIRTOOL="$HOME/.cache/llvm-firtool/1.62.1/bin/firtool"
TESTS_DIR="$REPO/generators/gemmini/software/gemmini-rocc-tests"
BUILD_DIR="$TESTS_DIR/build/bareMetalC"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESULTS_DIR="$SCRIPT_DIR/results"
CSV="$RESULTS_DIR/perf.csv"
TIMEOUT_CYCLES=2000000000   # BERT shapes far exceed the regression default

BUILD_SIMS=0
DIMS="32,16,8,4"
prev=""
for arg in "$@"; do
  case "$prev" in
    --dims) DIMS="$arg"; prev=""; continue ;;
  esac
  case "$arg" in
    --build-sims) BUILD_SIMS=1 ;;
    --dims) prev="--dims" ;;
    *) echo "unknown arg: $arg" >&2; exit 2 ;;
  esac
done

# --- Environment (JDK trap: system JDK 21 shadows conda JDK 20) --------------------
set +u
source "$REPO/env.sh" || { echo "FATAL: env.sh failed"; exit 1; }
set -u
export PATH="$CONDA_PREFIX/bin:$PATH"
java -version 2>&1 | grep -q 'version "20' \
  || { echo "FATAL: JDK 20 not active (JDK trap — see AGENT.md §1)"; exit 1; }
[ -x "$FIRTOOL" ] || { echo "FATAL: pinned firtool missing at $FIRTOOL"; exit 1; }

mkdir -p "$RESULTS_DIR"
[ -f "$CSV" ] || echo "config,impl,dim,M,N,K,cycles,macs,ideal_cycles,util_pct,result" > "$CSV"

FAILED=0

build_bench() {  # build_bench <header-file>
  local hdr="$TESTS_DIR/include/$1"
  [ -f "$hdr" ] || { echo "  missing header $hdr"; return 1; }
  cp "$TESTS_DIR/include/gemmini_params.h" /tmp/gemmini_params.stock.h
  cp "$hdr" "$TESTS_DIR/include/gemmini_params.h"
  ( cd "$TESTS_DIR" && ./build.sh ) > "/tmp/mx_perf_build_$1.log" 2>&1
  local rc=$?
  cp /tmp/gemmini_params.stock.h "$TESTS_DIR/include/gemmini_params.h"
  return $rc
}

run_bench() {  # run_bench <Config> <header-file>
  local cfg="$1" hdr="$2"
  if grep -q "^$cfg," "$CSV"; then
    echo "=== $cfg: already in $CSV, skipping (delete its rows to re-run)"
    return 0
  fi
  echo "=== $cfg: building mx_bench against $hdr"
  build_bench "$hdr" || { echo "  TEST BUILD FAILED"; return 1; }

  if [ "$BUILD_SIMS" -eq 1 ]; then
    echo "=== $cfg: building Verilator sim"
    make -C "$REPO/sims/verilator" CONFIG="$cfg" FIRTOOL_BIN="$FIRTOOL" \
      > "/tmp/mx_perf_sim_$cfg.log" 2>&1 || { echo "  SIM BUILD FAILED"; return 1; }
  fi

  echo "=== $cfg: running mx_bench (this can take hours for the BERT shapes)"
  make -C "$REPO/sims/verilator" CONFIG="$cfg" FIRTOOL_BIN="$FIRTOOL" run-binary-fast \
    BINARY="$BUILD_DIR/mx_bench-baremetal" timeout_cycles=$TIMEOUT_CYCLES \
    > "/tmp/mx_perf_run_$cfg.log" 2>&1
  local rc=$?

  local log="$REPO/sims/verilator/output/chipyard.harness.TestHarness.$cfg/mx_bench-baremetal.log"
  if [ -f "$log" ]; then
    grep -a "^MXBENCH," "$log" | sed "s/^MXBENCH,/$cfg,/; s/impl=//; s/dim=//; s/M=//; s/N=//; s/K=//; s/cycles=//; s/macs=//; s/ideal_cycles=//; s/util_pct=//; s/result=//" >> "$CSV" || true
  fi
  [ $rc -eq 0 ] || { echo "  RUN FAILED (rc=$rc, see /tmp/mx_perf_run_$cfg.log)"; return 1; }
  return 0
}

IFS=',' read -ra DIM_LIST <<< "$DIMS"
for d in "${DIM_LIST[@]}"; do
  run_bench "GemminiStockDIM${d}RocketConfig" "gemmini_params_stock_dim${d}.h" || FAILED=1
  run_bench "GemminiMXINT8DIM${d}RocketConfig" "gemmini_params_mxint8_dim${d}.h" || FAILED=1
done

echo
echo "results: $CSV"
exit $FAILED
