#!/usr/bin/env bash
# run_regression.sh — MXINT8 regression matrix runner (PLAN_MX_UPDATED.md §4).
#
# Usage:
#   ./run_regression.sh [--build-sims] [--dim32-only|--dim16-only]
#
# Without --build-sims the script reuses existing Verilator binaries (an RTL edit
# requires `rm -rf sims/verilator/generated-src/<Config>` + --build-sims).
# Building a simulator takes O(hours); running the full matrix takes O(hours) too.
#
# Exit code: 0 iff every matrix entry passes.

set -u

REPO="/home/nikolap/Research/2026/chipyard"
FIRTOOL="$HOME/.cache/llvm-firtool/1.62.1/bin/firtool"
TESTS_DIR="$REPO/generators/gemmini/software/gemmini-rocc-tests"
BUILD_DIR="$TESTS_DIR/build/bareMetalC"
TIMEOUT_CYCLES=300000000

STOCK_CONFIG="GemminiRocketConfig"

DIM32_TESTS="mxint8_golden mxint8_matmul_dim32 mxint8_corner mxint8_matmul_partial \
             mxint8_tiled mxint8_btb mxint8_multitile mxint8_matmul_nphase"
DIM16_TESTS="mxint8_matmul_dim16 mxint8_multitile mxint8_matmul_nphase"
# DIM=8/4 (Stage B) reuse the same DIM-agnostic tests; run them via --dim8/--dim4 once
# their sims are built (see B3). mxint8_matmul_nphase covers 4- and 8-phase blocks.
DIM8_TESTS="mxint8_multitile mxint8_matmul_nphase"
DIM4_TESTS="mxint8_multitile mxint8_matmul_nphase"

BUILD_SIMS=0
DIMS="32 16"          # default: the two original DIMs; pass --dims to widen/narrow
for arg in "$@"; do
  case "$arg" in
    --build-sims)  BUILD_SIMS=1 ;;
    --dim32-only)  DIMS="32" ;;
    --dim16-only)  DIMS="16" ;;
    --dims=*)      DIMS="${arg#--dims=}"; DIMS="${DIMS//,/ }" ;;
    *) echo "unknown arg: $arg" >&2; exit 2 ;;
  esac
done

mx_config_for_dim() { echo "GemminiMXINT8DIM${1}RocketConfig"; }
tests_for_dim() {  # echo the test list for a given DIM
  case "$1" in
    32) echo "$DIM32_TESTS" ;;
    16) echo "$DIM16_TESTS" ;;
    8)  echo "$DIM8_TESTS" ;;
    4)  echo "$DIM4_TESTS" ;;
    *)  echo "" ;;
  esac
}

# --- Environment (JDK trap: system JDK 21 shadows conda JDK 20) --------------------
# env.sh's conda activate scripts reference variables that are unset on first entry,
# so nounset must be off while sourcing it.
set +u
source "$REPO/env.sh" || { echo "FATAL: env.sh failed"; exit 1; }
set -u
export PATH="$CONDA_PREFIX/bin:$PATH"
java -version 2>&1 | grep -q 'version "20' \
  || { echo "FATAL: JDK 20 not active (JDK trap — see AGENT.md §1)"; exit 1; }
[ -x "$FIRTOOL" ] || { echo "FATAL: pinned firtool missing at $FIRTOOL"; exit 1; }

declare -A RESULT
FAILED=0

note() { printf '\n=== %s ===\n' "$*"; }
record() {  # record <name> <exit-code>
  if [ "$2" -eq 0 ]; then RESULT[$1]="PASS"; else RESULT[$1]="FAIL"; FAILED=1; fi
}

# --- Software build with header staging (always restore the stock header!) ---------
build_tests_for_dim() {  # build_tests_for_dim <32|16>
  local hdr="$TESTS_DIR/include/gemmini_params_mxint8_dim$1.h"
  note "Building baremetal tests against $(basename "$hdr")"
  cp "$TESTS_DIR/include/gemmini_params.h" /tmp/gemmini_params.stock.h
  cp "$hdr" "$TESTS_DIR/include/gemmini_params.h"
  ( cd "$TESTS_DIR" && ./build.sh ) > "/tmp/mx_build_dim$1.log" 2>&1
  local rc=$?
  cp /tmp/gemmini_params.stock.h "$TESTS_DIR/include/gemmini_params.h"
  return $rc
}

build_sim() {  # build_sim <Config>
  note "Building Verilator sim for $1"
  make -C "$REPO/sims/verilator" CONFIG="$1" FIRTOOL_BIN="$FIRTOOL" \
    > "/tmp/mx_sim_$1.log" 2>&1
}

run_one() {  # run_one <Config> <test>
  make -C "$REPO/sims/verilator" CONFIG="$1" run-binary-fast \
    BINARY="$BUILD_DIR/$2-baremetal" timeout_cycles=$TIMEOUT_CYCLES \
    > "/tmp/mx_run_$1_$2.log" 2>&1
}

# --- Per-DIM MX matrix --------------------------------------------------------------
for d in $DIMS; do
  cfg="$(mx_config_for_dim "$d")"
  [ "$BUILD_SIMS" -eq 1 ] && { build_sim "$cfg"; record "sim:$cfg" $?; }
  build_tests_for_dim "$d"; record "build:tests-dim$d" $?
  for t in $(tests_for_dim "$d"); do
    note "DIM$d $t"; run_one "$cfg" "$t"; record "dim$d:$t" $?
  done
done

# --- Stock elaboration gate (0 firtool errors expected) -----------------------------
note "Stock elaboration ($STOCK_CONFIG)"
make -C "$REPO/sims/verilator" CONFIG="$STOCK_CONFIG" FIRTOOL_BIN="$FIRTOOL" verilog \
  > /tmp/mx_stock_elab.log 2>&1
record "stock:elaborate" $?

# --- Report -------------------------------------------------------------------------
note "Regression matrix"
for k in $(printf '%s\n' "${!RESULT[@]}" | sort); do
  printf '  %-28s %s\n' "$k" "${RESULT[$k]}"
done
[ "$FAILED" -eq 0 ] && echo "OVERALL: PASS" || echo "OVERALL: FAIL (logs in /tmp/mx_*.log)"
exit $FAILED
