#!/usr/bin/env bash
# run_synth.sh — out-of-context Vivado synthesis comparison (plan Stage C / P2 numbers).
#
# For each config: elaborate to Verilog (pinned firtool), then OOC-synthesize the Gemmini
# tile against the Nexys Video part with identical constraints, and parse the reports
# into results/synth.csv. Vivado runs take O(1h) each; configs run sequentially (the
# chipyard jar build is not parallel-safe).
#
# Usage:
#   ./run_synth.sh [--skip-verilog] [config ...]
# Default config list: the stock-vs-MX pairs at each DIM.

set -u

REPO="/home/nikolap/Research/2026/chipyard"
FIRTOOL="$HOME/.cache/llvm-firtool/1.62.1/bin/firtool"
VIVADO_SETTINGS="$HOME/Programs/Xilinx/Vivado/2022.2/settings64.sh"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESULTS_DIR="$SCRIPT_DIR/results"
SYNTH_DIR="$RESULTS_DIR/synth"
CSV="$RESULTS_DIR/synth.csv"

PART="xc7a200tsbg484-1"   # Nexys Video XC7A200T
CLK_NS="10.0"             # identical 100 MHz target for every config
TOP="Gemmini"

DEFAULT_CONFIGS="\
GemminiStockDIM32RocketConfig GemminiMXINT8DIM32RocketConfig \
GemminiStockDIM16RocketConfig GemminiMXINT8DIM16RocketConfig \
GemminiStockDIM8RocketConfig  GemminiMXINT8DIM8RocketConfig \
GemminiStockDIM4RocketConfig  GemminiMXINT8DIM4RocketConfig"

SKIP_VERILOG=0
CONFIGS=""
for arg in "$@"; do
  case "$arg" in
    --skip-verilog) SKIP_VERILOG=1 ;;
    *) CONFIGS="$CONFIGS $arg" ;;
  esac
done
[ -z "$CONFIGS" ] && CONFIGS="$DEFAULT_CONFIGS"

# --- Environment (JDK trap: system JDK 21 shadows conda JDK 20) --------------------
set +u
source "$REPO/env.sh" || { echo "FATAL: env.sh failed"; exit 1; }
set -u
export PATH="$CONDA_PREFIX/bin:$PATH"
java -version 2>&1 | grep -q 'version "20' \
  || { echo "FATAL: JDK 20 not active (JDK trap — see AGENT.md §1)"; exit 1; }
[ -x "$FIRTOOL" ] || { echo "FATAL: pinned firtool missing at $FIRTOOL"; exit 1; }
[ -f "$VIVADO_SETTINGS" ] || { echo "FATAL: Vivado settings64.sh missing at $VIVADO_SETTINGS"; exit 1; }
set +u
source "$VIVADO_SETTINGS"
set -u
command -v vivado >/dev/null || { echo "FATAL: vivado not on PATH after settings64.sh"; exit 1; }

mkdir -p "$SYNTH_DIR"
# Write the header if the file is missing or empty (so a reset CSV still parses).
[ -s "$CSV" ] || echo "config,part,clk_ns,lut,ff,bram36,dsp,wns_ns,fmax_mhz" > "$CSV"

FAILED=0
for cfg in $CONFIGS; do
  if grep -q "^$cfg," "$CSV"; then
    echo "=== $cfg: already in $CSV, skipping (delete the row to re-run)"
    continue
  fi

  gen_src="$REPO/sims/verilator/generated-src/chipyard.harness.TestHarness.$cfg"
  if [ "$SKIP_VERILOG" -eq 0 ]; then
    echo "=== $cfg: elaborating to Verilog"
    make -C "$REPO/sims/verilator" CONFIG="$cfg" FIRTOOL_BIN="$FIRTOOL" verilog \
      > "/tmp/mx_synth_elab_$cfg.log" 2>&1 \
      || { echo "  ELABORATION FAILED (see /tmp/mx_synth_elab_$cfg.log)"; FAILED=1; continue; }
  fi
  [ -f "$gen_src/gen-collateral/filelist.f" ] \
    || { echo "  missing $gen_src/gen-collateral/filelist.f"; FAILED=1; continue; }

  out="$SYNTH_DIR/$cfg"
  mkdir -p "$out"
  echo "=== $cfg: OOC synthesis (part=$PART clk=${CLK_NS}ns)"
  ( cd "$out" && vivado -mode batch -nojournal -log "$out/vivado.log" \
      -source "$SCRIPT_DIR/synth_ooc.tcl" \
      -tclargs "$gen_src" "$TOP" "$PART" "$CLK_NS" "$out" ) \
      > "/tmp/mx_synth_vivado_$cfg.log" 2>&1 \
    || { echo "  VIVADO FAILED (see $out/vivado.log)"; FAILED=1; continue; }

  # --- Parse reports -> CSV row -----------------------------------------------------
  util="$out/utilization.rpt"
  timing="$out/timing_summary.rpt"
  lut=$(awk -F'|' '/^\| (Slice|CLB) LUTs/ {gsub(/ /,"",$3); print $3; exit}' "$util")
  ff=$(awk -F'|' '/^\| (Slice|CLB) Registers/ {gsub(/ /,"",$3); print $3; exit}' "$util")
  bram=$(awk -F'|' '/^\| Block RAM Tile/ {gsub(/ /,"",$3); print $3; exit}' "$util")
  dsp=$(awk -F'|' '/^\| DSPs/ {gsub(/ /,"",$3); print $3; exit}' "$util")
  # First data line after the "WNS(ns)" header of the setup summary.
  wns=$(awk '/WNS\(ns\)/ {getline; getline; print $1; exit}' "$timing")
  fmax=$(awk -v t="$CLK_NS" -v w="$wns" 'BEGIN { if (w == "" ) { print "" } else { print 1000.0 / (t - w) } }')

  if [ -z "$lut" ] || [ -z "$wns" ]; then
    echo "  REPORT PARSE FAILED (check $util / $timing)"; FAILED=1; continue
  fi
  echo "$cfg,$PART,$CLK_NS,$lut,$ff,$bram,$dsp,$wns,$fmax" >> "$CSV"
  echo "  LUT=$lut FF=$ff BRAM36=$bram DSP=$dsp WNS=${wns}ns Fmax_est=${fmax}MHz"
done

echo
echo "results: $CSV"
exit $FAILED
