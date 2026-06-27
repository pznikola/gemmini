package gemmini

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tile._
import freechips.rocketchip.tilelink.{TLIdentityNode}
import GemminiISA._
import Util._

// Counter Address list
object CounterEvent {
  val DISABLE = 0

  val MAIN_LD_CYCLES = 1
  val MAIN_ST_CYCLES = 2
  val MAIN_EX_CYCLES = 3
  val MAIN_LD_ST_CYCLES = 4
  val MAIN_LD_EX_CYCLES = 5
  val MAIN_ST_EX_CYCLES = 6
  val MAIN_LD_ST_EX_CYCLES = 7

  val LOAD_DMA_WAIT_CYCLE = 8
  val LOAD_ACTIVE_CYCLE = 9
  val LOAD_SCRATCHPAD_WAIT_CYCLE = 10

  val STORE_DMA_WAIT_CYCLE = 11
  val STORE_ACTIVE_CYCLE = 12
  val STORE_POOLING_CYCLE = 13
  val STORE_SCRATCHPAD_WAIT_CYCLE = 14

  val DMA_TLB_MISS_CYCLE = 15
  val DMA_TLB_HIT_REQ = 16
  val DMA_TLB_TOTAL_REQ = 17

  val RDMA_ACTIVE_CYCLE = 18
  val RDMA_TLB_WAIT_CYCLES = 19
  val RDMA_TL_WAIT_CYCLES = 20

  val WDMA_ACTIVE_CYCLE = 21
  val WDMA_TLB_WAIT_CYCLES = 22
  val WDMA_TL_WAIT_CYCLES = 23

  val EXE_ACTIVE_CYCLE = 24
  val EXE_FLUSH_CYCLE = 25
  val EXE_CONTROL_Q_BLOCK_CYCLE = 26
  val EXE_PRELOAD_HAZ_CYCLE = 27
  val EXE_OVERLAP_HAZ_CYCLE = 28

  val SCRATCHPAD_A_WAIT_CYCLE = 29
  val SCRATCHPAD_B_WAIT_CYCLE = 30
  val SCRATCHPAD_D_WAIT_CYCLE = 31

  val ACC_A_WAIT_CYCLE = 32
  val ACC_B_WAIT_CYCLE = 33
  val ACC_D_WAIT_CYCLE = 34

  val A_GARBAGE_CYCLES = 35
  val B_GARBAGE_CYCLES = 36
  val D_GARBAGE_CYCLES = 37

  val IM2COL_MEM_CYCLES = 38
  val IM2COL_ACTIVE_CYCLES = 39
  val IM2COL_TRANSPOSER_WAIT_CYCLE = 40

  val RESERVATION_STATION_FULL_CYCLES = 41
  val RESERVATION_STATION_ACTIVE_CYCLES = 42

  val LOOP_MATMUL_ACTIVE_CYCLES = 43
  val TRANSPOSE_PRELOAD_UNROLLER_ACTIVE_CYCLES = 44

  // MX scale-load diagnostics (mx_enabled only; inert otherwise).
  // MX_SCALE_DMA_ACTIVE: scale-load controller busy (DMA in flight or command pending).
  // MX_SCALE_DMA_SOLO:   scale-load busy WHILE the execute pipeline is idle => the scale
  //                      load is on the critical path (not overlapped with compute).
  val MX_SCALE_DMA_ACTIVE_CYCLE = 45
  val MX_SCALE_DMA_SOLO_CYCLE = 46

  // MX serialization diagnostics (observation-only; partition the ~86% mesh-feed stall).
  // WAIT_CMD: execute controller in waiting_for_cmd (starved for an EX command from the RS).
  val MX_DBG_WAIT_CMD_CYCLE = 47

  // Phase-0b DAE-inversion partition (repurposed codes 48/49/50/54; observation-only).
  // The 6-bit counter-config field caps event codes at 63 and all 0..63 are taken, so these
  // reuse codes whose original ENQ_NOT_READY/REQ_STALL/DRAINING/HOLD_NOT_DRAIN signals were
  // already measured (~0 / ideal) and are no longer needed. They now attribute the
  // EX-issue stall to in-flight MX scale-mvins (the decoupled-access-execute inversion: a
  // matmul's dep on a scale-mvin clears only on the scale DMA's COMPLETION, RS:400/499).
  // EX_BLOCKED_ON_SCALE:  an unissued EX entry is dep-blocked with >=1 dep on a live is_mx_scale load.
  // EX_BLOCKED_SCALE_ONLY: same, and the scale dep is its ONLY blocker (relaxing it frees the entry).
  // EX_BLOCKED_NONSCALE:  an unissued EX entry is dep-blocked by some non-scale dep (other gate).
  val MX_EX_BLOCKED_ON_SCALE_CYCLE = 48
  val MX_EX_BLOCKED_SCALE_ONLY_CYCLE = 49
  val MX_EX_BLOCKED_NONSCALE_CYCLE = 50

  // Phase-0 gate partition (split the 86% waiting_for_cmd; observation-only).
  // NO_CMD: in waiting_for_cmd with NO command presented (RS not issuing => upstream gate).
  // CMD_BLOCKED: in waiting_for_cmd WITH a command that can't act (config/hazard barrier).
  // MATMUL_IN_PROGRESS: mesh holds >=1 matmul tag.
  val MX_DBG_NO_CMD_CYCLE = 51
  val MX_DBG_CMD_BLOCKED_CYCLE = 52
  val MX_DBG_MATMUL_IN_PROGRESS_CYCLE = 53
  // Code 54 repurposed (was HOLD_NOT_DRAIN): EX pool empty => unroller is not delivering
  // matmuls (idle or stalled on ld_ahead/upstream), a distinct cause from a dep-blocked pool.
  val MX_EX_POOL_EMPTY_CYCLE = 54

  // RS-internal partition (why has the RS no issuable EX command?). Observation-only.
  // EX_READY: an EX entry is ready (deps clear) but not yet issued (issue-handshake gate).
  // EX_BLOCKED: an EX entry is unissued and dependency-blocked (dependency gate).
  // EX_INFLIGHT: an EX entry is issued but not yet completed (completion-latency gate).
  // EX_POOL_FULL: the EX entry pool is full. LD_INFLIGHT: a load is issued-not-complete.
  val MX_DBG_EX_READY_CYCLE = 55
  val MX_DBG_EX_BLOCKED_CYCLE = 56
  val MX_DBG_EX_INFLIGHT_CYCLE = 57
  val MX_DBG_EX_POOL_FULL_CYCLE = 58
  val MX_DBG_LD_INFLIGHT_CYCLE = 59

  // RS LD/ST pool composition: which pool actually fills the RS and throttles the unroller.
  val MX_DBG_LD_POOL_FULL_CYCLE = 60
  val MX_DBG_LD_BLOCKED_CYCLE = 61
  val MX_DBG_ST_POOL_FULL_CYCLE = 62
  val MX_DBG_ST_INFLIGHT_CYCLE = 63

  val n = 64
}

object CounterExternal {
  val DISABLE = 0

  val RESERVATION_STATION_LD_COUNT = 1
  val RESERVATION_STATION_ST_COUNT = 2
  val RESERVATION_STATION_EX_COUNT = 3

  val RDMA_BYTES_REC = 4
  val WDMA_BYTES_SENT = 5

  val RDMA_TOTAL_LATENCY = 6
  val WDMA_TOTAL_LATENCY = 7

  val n = 8

  val EXTERNAL_WIDTH = 32
}

class CounterEventIO extends Bundle {
  val event_signal = Output(Vec(CounterEvent.n, Bool()))
  val external_values = Output(Vec(CounterExternal.n, UInt(CounterExternal.EXTERNAL_WIDTH.W)))
  val external_reset = Input(Bool())

  // Connect Event Signal
  private var connected = Array.fill(CounterEvent.n)(false)
  def connectEventSignal(addr: Int, sig: UInt) = {
    event_signal(addr) := sig
    connected(addr) = true
  }

  // Connect Event Signal
  private var connected_external = Array.fill(CounterEvent.n)(false)
  def connectExternalCounter(addr: Int, ext_value: UInt) = {
    external_values(addr) := ext_value
    connected_external(addr) = true
  }

  // Collect IO from submodule
  def collect(io: CounterEventIO) = {
    io.external_reset := external_reset
    for (i <- 0 until CounterEvent.n)
      if (io.connected(i)) {
        if (connected(i))
          throw new IllegalStateException("Port " + i + " is already connected in another IO")
        else {
          connected(i) = true
          event_signal(i) := io.event_signal(i)
        }
      }
    for (i <- 0 until CounterExternal.n)
      if (io.connected_external(i)) {
        if (connected_external(i))
          throw new IllegalStateException("External counter " + i + " is already connected in another IO")
        else {
          connected_external(i) = true
          external_values(i) := io.external_values(i)
        }
      }
  }
}

object CounterEventIO {
  def init(io: CounterEventIO) = {
    io.event_signal := 0.U.asTypeOf(io.event_signal.cloneType)
    io.external_values := 0.U.asTypeOf(io.external_values.cloneType)
  }
}

class CounterIO(nPerfCounter: Int, counterWidth: Int) extends Bundle {
  val counter_reset = Input(Bool())
  val snapshot = Input(Bool())
  val snapshot_reset = Input(Bool())
  val addr = Input(UInt(log2Ceil(nPerfCounter).W))
  val data = Output(UInt(counterWidth.W))
  val config_address = Flipped(Valid(UInt(log2Ceil(CounterEvent.n).W)))
  val external = Input(Bool())

  val event_io = Flipped(new CounterEventIO)
}

// A simple counter file. Every counter is incremented when the corresponding event signal is high on rising edge.
// There are two type of counters: Built-in counters and external counters. External counters have their value
// stored in other modules and can incremented by arbitary values.
class CounterFile(nPerfCounter: Int, counterWidth: Int) extends Module
{
  val io = IO(new CounterIO(nPerfCounter, counterWidth))

  val config_width = log2Ceil(scala.math.max(CounterEvent.n, CounterExternal.n)) + 1
  val counter_config = RegInit(VecInit.tabulate(nPerfCounter)(_ => 0.U(config_width.W)))
  val counter_is_external = Reg(Vec(nPerfCounter, Bool()))

  io.event_io.external_reset := io.counter_reset
  withReset(reset.asBool || io.counter_reset) {
    val counter_snapshot = RegInit(VecInit.tabulate(nPerfCounter)(_ => 0.U(counterWidth.W)))
    val counters = RegInit(VecInit.tabulate(nPerfCounter)(_ => 0.U(counterWidth.W)))
    val snapshot_enable = RegInit(false.B)

    // Function to take correct counter value.
    // If the highest bit of the config register is 1, it's an external counter; otherwise, take it from
    // local counter
    val take_value = (config: UInt, counter: UInt) => {
      // Set the width
      val external = io.event_io.external_values(config)
      val is_external = counter_is_external(io.addr)

      Mux(is_external, external, counter)
    }
    // Snapshot: In case a sequence of access instructions get interrupted (i.e. preempted by OS), it is possible
    // to take a snapshot when reading counter value by setting a bit in the instruction. All subsequent readings
    // return the values from the snapshot until it is cleared by a instruction with "clear" bit marked.
    // When the snapshot bit is set, the normal counters are still being incremented.
    when (io.snapshot_reset) {
      snapshot_enable := false.B
    } .elsewhen (io.snapshot) {
      snapshot_enable := true.B
      // Move counter values to snapshot register
      (counter_snapshot zip (counters zip counter_config)) map { case (snapshot, (counter, config)) => {
        snapshot := take_value(config, counter)
      }}
    }

    // Connect read port
    io.data := Mux(snapshot_enable, counter_snapshot(io.addr), take_value(counter_config(io.addr), counters(io.addr)))

    // Write configuration reg
    when (io.config_address.valid) {
      counter_config(io.addr) := io.config_address.bits
      counter_is_external(io.addr) := io.external
      counters(io.addr) := 0.U
    }

    // Update signal
    ((counters zip counter_config).zipWithIndex) map { case ((counter, config), idx) => {
      when (io.event_io.event_signal(config)) {
        when (!(io.config_address.valid && io.addr === idx.U)) {
          counter := counter + 1.U
        }
      }}
    }
  }
}

class CounterController(nPerfCounter: Int, counterWidth: Int)(implicit p: Parameters) extends Module {
  val io = IO(new Bundle() {
    val in = Flipped(Decoupled(new RoCCCommand))
    val out = Decoupled(new RoCCResponse)
    val event_io = Flipped(new CounterEventIO)
  })

  if (nPerfCounter > 0) {
    val nCounterIndexBit = log2Ceil(nPerfCounter)

    val counterfile = Module(new CounterFile(nPerfCounter: Int, counterWidth: Int))
    counterfile.io.event_io <> io.event_io

    val out_reg = Reg(io.out.bits.cloneType)
    val out_valid_reg = RegInit(false.B)

    // Decode access option (assume 8 counters)
    // rs1[0] = Global counter value reset
    // rs1[1] = Snapshot reset
    // rs1[2] = Take snapshot
    // rs1[3] = Change config
    // rs1[6:4] = Counter index
    // rs1[17:12] = new counter address for counter with index specified in rs1[6:4]
    // We can change the number of physical counters up to 256 (which is really large)
    // rs1[31] = External counter flag

    io.in.ready := !out_valid_reg
    counterfile.io.addr := io.in.bits.rs1(nCounterIndexBit + 3, 4)
    counterfile.io.counter_reset := io.in.bits.rs1(0) & io.in.fire
    counterfile.io.snapshot_reset := io.in.bits.rs1(1) & io.in.fire
    counterfile.io.snapshot := io.in.bits.rs1(2) & io.in.fire
    counterfile.io.config_address.valid := io.in.bits.rs1(3) & io.in.fire
    counterfile.io.config_address.bits := io.in.bits.rs1(17, 12)
    counterfile.io.external := io.in.bits.rs1(31)

    when (io.out.fire) {
      out_valid_reg := false.B
    } .elsewhen (io.in.fire) {
      out_valid_reg := true.B
      out_reg.rd := io.in.bits.inst.rd
      out_reg.data := 0.U
      out_reg.data := counterfile.io.data
    }

    io.out.valid := out_valid_reg
    io.out.bits := out_reg
  } else {
    io <> DontCare
  }
}
