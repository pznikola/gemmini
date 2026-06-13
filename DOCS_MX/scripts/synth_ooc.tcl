# Out-of-context Vivado synthesis of the Gemmini tile (plan Stage C / P2 synthesis
# numbers). Reads the firtool gen-collateral of a Chipyard config, synthesizes ONLY the
# `Gemmini` hierarchy out-of-context against the Nexys Video part, and emits utilization
# + timing reports. Identical constraints for every config so deltas are comparable.
#
# Usage:
#   vivado -mode batch -source synth_ooc.tcl -tclargs <generated_src_dir> <top> <part> <clk_ns> <out_dir>
# e.g.
#   vivado -mode batch -source synth_ooc.tcl -tclargs \
#     sims/verilator/generated-src/chipyard.harness.TestHarness.GemminiMXINT8DIM32RocketConfig \
#     Gemmini xc7a200tsbg484-1 10.0 DOCS_MX/scripts/results/synth/GemminiMXINT8DIM32RocketConfig

if {$argc != 5} {
  puts "ERROR: expected 5 args: <generated_src_dir> <top> <part> <clk_ns> <out_dir>"
  exit 1
}
set gen_src_dir [lindex $argv 0]
set top         [lindex $argv 1]
set part        [lindex $argv 2]
set clk_ns      [lindex $argv 3]
set out_dir     [lindex $argv 4]

set collateral "$gen_src_dir/gen-collateral"
file mkdir $out_dir

# Read every file in the firtool filelist (paths are relative to gen-collateral).
# Vivado elaborates only the $top hierarchy; unused modules are dropped.
set fl [open "$collateral/filelist.f" r]
set sv_files {}
while {[gets $fl line] >= 0} {
  set line [string trim $line]
  if {$line eq ""} { continue }
  lappend sv_files "$collateral/$line"
}
close $fl
read_verilog -sv $sv_files

# Identical clock constraint for all configs, loaded before synthesis so the run is
# timing-driven; Fmax_est = 1/(clk_ns - WNS).
set xdc_path "$out_dir/ooc.xdc"
set xdc [open $xdc_path w]
puts $xdc "create_clock -name core_clock -period $clk_ns \[get_ports clock\]"
close $xdc
read_xdc $xdc_path

synth_design -mode out_of_context -top $top -part $part -flatten_hierarchy rebuilt

# Fail loudly on unresolved black boxes (a missing memory model would silently zero the
# resource numbers).
set bbs [get_cells -hierarchical -quiet -filter {IS_BLACKBOX}]
if {[llength $bbs] > 0} {
  puts "ERROR: unresolved black boxes after synthesis: $bbs"
  exit 1
}

report_utilization -file "$out_dir/utilization.rpt"
report_utilization -hierarchical -hierarchical_depth 3 -file "$out_dir/utilization_hier.rpt"
report_timing_summary -file "$out_dir/timing_summary.rpt"
write_checkpoint -force "$out_dir/post_synth.dcp"

puts "OOC_SYNTH_DONE top=$top part=$part clk_ns=$clk_ns out=$out_dir"
exit 0
