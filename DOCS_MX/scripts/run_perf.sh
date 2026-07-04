#!/usr/bin/env bash
# run_perf.sh — stock-vs-MXINT8 Verilator benchmark runner (plan Stage C).
#
# For each DIM pair: stages the matching params header, builds the baremetal tests
# (mx_bench picks the implementation from MX_ENABLED at compile time), runs mx_bench on
# the stock and MX configs, and collects the machine-readable MXBENCH lines into
# results/perf.csv. Resumable: (config) rows already in the CSV are skipped.
#
# Usage:
#   ./run_perf.sh [--build-sims] [--dims 32,16,8,4] [--impl both|stock|mx]
#                 [--csv results/perf_i03.csv] [--force]
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
RUN_DIR="${MX_RUN_DIR:-$REPO/sims/verilator/gemmini}"
LOG_DIR="$RUN_DIR/logs"
TMP_DIR="$RUN_DIR/tmp"
CSV="$RESULTS_DIR/perf.csv"
TIMEOUT_CYCLES=2000000000   # BERT shapes far exceed the regression default

BUILD_SIMS=0
FORCE=0
DIMS="32,16,8,4"
IMPLS="stock,mx"
prev=""
for arg in "$@"; do
  case "$prev" in
    --dims) DIMS="$arg"; prev=""; continue ;;
    --csv) CSV="$arg"; prev=""; continue ;;
    --impl) IMPLS="$arg"; prev=""; continue ;;
  esac
  case "$arg" in
    --build-sims) BUILD_SIMS=1 ;;
    --force) FORCE=1 ;;
    --dims) prev="--dims" ;;
    --dims=*) DIMS="${arg#--dims=}" ;;
    --csv) prev="--csv" ;;
    --csv=*) CSV="${arg#--csv=}" ;;
    --impl) prev="--impl" ;;
    --impl=*) IMPLS="${arg#--impl=}" ;;
    *) echo "unknown arg: $arg" >&2; exit 2 ;;
  esac
done

case "$IMPLS" in
  both) IMPLS="stock,mx" ;;
  stock|mx|stock,mx|mx,stock) ;;
  *) echo "unknown --impl value: $IMPLS (use both, stock, mx, stock,mx, or mx,stock)" >&2; exit 2 ;;
esac

mkdir -p "$RESULTS_DIR" "$(dirname "$CSV")" "$LOG_DIR" "$TMP_DIR"
export TMPDIR="$TMP_DIR"

# --- Environment (JDK trap: system JDK 21 shadows conda JDK 20) --------------------
set +u
source "$REPO/env.sh" || { echo "FATAL: env.sh failed"; exit 1; }
set -u
export PATH="$CONDA_PREFIX/bin:$PATH"
java -version 2>&1 | grep -q 'version "20' \
  || { echo "FATAL: JDK 20 not active (JDK trap — see AGENT.md §1)"; exit 1; }
[ -x "$FIRTOOL" ] || { echo "FATAL: pinned firtool missing at $FIRTOOL"; exit 1; }

echo "RUN_DIR=$RUN_DIR"
echo "LOG_DIR=$LOG_DIR"
echo "TMPDIR=$TMPDIR"
echo "CSV=$CSV"
# Write the header if the file is missing or empty (a truncated/reset CSV must still
# get a header, or make_report.py's DictReader mis-keys the first data row).
[ -s "$CSV" ] || echo "config,impl,dim,M,N,K,cycles,macs,ideal_cycles,util_pct,result" > "$CSV"

FAILED=0

build_bench() {  # build_bench <header-file>
  local hdr="$TESTS_DIR/include/$1"
  local safe_hdr="${1//[^A-Za-z0-9_.-]/_}"
  local saved="$TMP_DIR/gemmini_params.saved.h"
  local extra_cflags="${MX_EXTRA_CFLAGS:-}"
  [ -f "$hdr" ] || { echo "  missing header $hdr"; return 1; }
  if [ -n "${MX_BENCH_COUNTER_SET:-}" ]; then
    case "$MX_BENCH_COUNTER_SET" in
      0|1|2|3) ;;
      *) echo "  invalid MX_BENCH_COUNTER_SET=$MX_BENCH_COUNTER_SET (use 0, 1, 2, or 3)" >&2; return 1 ;;
    esac
    extra_cflags="${extra_cflags:+$extra_cflags }-DMX_BENCH_COUNTER_SET=$MX_BENCH_COUNTER_SET"
  fi
  if [ -n "${MX_BENCH_PRINT_GEOM:-}" ]; then
    case "$MX_BENCH_PRINT_GEOM" in
      0|1) ;;
      *) echo "  invalid MX_BENCH_PRINT_GEOM=$MX_BENCH_PRINT_GEOM (use 0 or 1)" >&2; return 1 ;;
    esac
    extra_cflags="${extra_cflags:+$extra_cflags }-DMX_BENCH_PRINT_GEOM=$MX_BENCH_PRINT_GEOM"
  fi
  if [ -n "${MX_BENCH_CPU_TIMING:-}" ]; then
    case "$MX_BENCH_CPU_TIMING" in
      0|1) ;;
      *) echo "  invalid MX_BENCH_CPU_TIMING=$MX_BENCH_CPU_TIMING (use 0 or 1)" >&2; return 1 ;;
    esac
    extra_cflags="${extra_cflags:+$extra_cflags }-DMX_BENCH_CPU_TIMING=$MX_BENCH_CPU_TIMING"
  fi
  cp "$TESTS_DIR/include/gemmini_params.h" "$saved"
  cp "$hdr" "$TESTS_DIR/include/gemmini_params.h"
  if [ -n "$extra_cflags" ]; then
    ( cd "$TESTS_DIR" && EXTRA_CFLAGS="$extra_cflags" ./build.sh ) > "$LOG_DIR/mx_perf_build_$safe_hdr.log" 2>&1
  else
    ( cd "$TESTS_DIR" && ./build.sh ) > "$LOG_DIR/mx_perf_build_$safe_hdr.log" 2>&1
  fi
  local rc=$?
  cp "$saved" "$TESTS_DIR/include/gemmini_params.h"
  return $rc
}

run_bench() {  # run_bench <Config> <header-file>
  local cfg="$1" hdr="$2"
  if [ "$FORCE" -eq 1 ] && grep -q "^$cfg," "$CSV"; then
    local tmp_csv="$TMP_DIR/perf.$cfg.csv"
    awk -F, -v cfg="$cfg" 'NR == 1 || $1 != cfg' "$CSV" > "$tmp_csv" && mv "$tmp_csv" "$CSV"
  fi
  if grep -q "^$cfg," "$CSV"; then
    echo "=== $cfg: already in $CSV, skipping (delete its rows to re-run)"
    return 0
  fi
  if [ "$BUILD_SIMS" -eq 1 ]; then
    echo "=== $cfg: building Verilator sim and refreshing generated params header"
    make -C "$REPO/sims/verilator" CONFIG="$cfg" FIRTOOL_BIN="$FIRTOOL" \
      > "$LOG_DIR/mx_perf_sim_$cfg.log" 2>&1 || { echo "  SIM BUILD FAILED"; return 1; }
  fi

  echo "=== $cfg: building mx_bench against $hdr"
  build_bench "$hdr" || { echo "  TEST BUILD FAILED"; return 1; }

  echo "=== $cfg: running mx_bench (this can take hours for the BERT shapes)"
  # LOADMEM=1 preloads DRAM with the ELF (common.mk get_loadmem_flag), bypassing the slow TSI
  # serial loader so mx_bench's large .bss does not gate boot time.
  make -C "$REPO/sims/verilator" CONFIG="$cfg" FIRTOOL_BIN="$FIRTOOL" run-binary-fast \
    BINARY="$BUILD_DIR/mx_bench-baremetal" LOADMEM=1 timeout_cycles=$TIMEOUT_CYCLES \
    > "$LOG_DIR/mx_perf_run_$cfg.log" 2>&1
  local rc=$?

  local log="$REPO/sims/verilator/output/chipyard.harness.TestHarness.$cfg/mx_bench-baremetal.log"
  if [ -f "$log" ]; then
    grep -a "^MXBENCH," "$log" | sed "s/^MXBENCH,/$cfg,/; s/impl=//; s/dim=//; s/M=//; s/N=//; s/K=//; s/cycles=//; s/macs=//; s/ideal_cycles=//; s/util_pct=//; s/result=//" >> "$CSV" || true
  fi
  [ "$rc" -eq 0 ] || { echo "  RUN FAILED (rc=$rc, see $LOG_DIR/mx_perf_run_$cfg.log)"; return 1; }
  return 0
}

IFS=',' read -ra DIM_LIST <<< "$DIMS"
IFS=',' read -ra IMPL_LIST <<< "$IMPLS"
for d in "${DIM_LIST[@]}"; do
  for impl in "${IMPL_LIST[@]}"; do
    case "$impl" in
      stock)
        run_bench "GemminiStockDIM${d}RocketConfig" "gemmini_params_stock_dim${d}.h" || FAILED=1
        ;;
      mx)
        run_bench "GemminiMXINT8DIM${d}RocketConfig" "gemmini_params_mxint8_dim${d}.h" || FAILED=1
        ;;
    esac
  done
done

echo
echo "results: $CSV"
exit $FAILED
