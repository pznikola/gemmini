package gemmini

import chisel3._
import chisel3.util._
import GemminiISA._
import org.chipsalliance.cde.config.Parameters

class MXScaleLoadController[T <: Data, U <: Data, V <: Data](config: GemminiArrayConfig[T, U, V],
                                                             coreMaxAddrBits: Int)
                                                            (implicit p: Parameters) extends Module {
  import config._

  val io = IO(new Bundle {
    val cmd = Flipped(Decoupled(new GemminiCmd(reservation_station_entries)))
    val dma = new MXScaleReadMemIO(mx_scale_sp_entries)
    val stride_a = Input(UInt(coreMaxAddrBits.W))
    val stride_b = Input(UInt(coreMaxAddrBits.W))
    val completed = Decoupled(UInt(log2Up(reservation_station_entries).W))
    val busy = Output(Bool())
  })

  val waiting_for_command :: waiting_for_dma_req_ready :: sending_rows :: Nil = Enum(3)
  val control_state = RegInit(waiting_for_command)
  val row_counter = RegInit(0.U(16.W))

  val cmd = Queue(io.cmd, ld_queue_length)
  cmd.ready := false.B

  class MXScaleLoadRs2 extends Bundle {
    val num_rows = UInt(16.W)
    val num_cols = UInt(16.W)
    val _spacer0 = UInt((32 - log2Up(mx_scale_sp_entries)).W)
    val local_addr = UInt(log2Up(mx_scale_sp_entries).W)
  }

  val mvin_rs2 = cmd.bits.cmd.rs2.asTypeOf(new MXScaleLoadRs2)
  val is_b = cmd.bits.cmd.inst.funct === LOAD_MX_SCALE_B_CMD
  val rows = mvin_rs2.num_rows
  val cols = mvin_rs2.num_cols
  val localaddr = mvin_rs2.local_addr
  val stride = Mux(is_b, io.stride_b, io.stride_a)
  val vaddr = cmd.bits.cmd.rs1

  val DoLoad = cmd.bits.cmd.inst.funct === LOAD_MX_SCALE_A_CMD ||
    cmd.bits.cmd.inst.funct === LOAD_MX_SCALE_B_CMD

  val nCmds = (max_in_flight_mem_reqs / DIM) + 1
  val deps_t = new Bundle {
    val rob_id = UInt(log2Up(reservation_station_entries).W)
  }
  // A single scale mvin loads up to `rows` rows of at most DIM scale bytes each. With
  // multi-tile (policy Appendix A) the B-scale image is `k_blocks*Jp` rows and the A
  // image is `I*DIM` rows — both up to the half-region bound mx_scale_sp_entries/2, well
  // above DIM. Size the command tracker's byte counter for the whole region (rows up to
  // mx_scale_sp_entries, DIM bytes/row) so `bytes_to_read = rows*cols` never overflows
  // (the DIM^2 bound here truncated large multi-tile B mvins to 0 at DIM < 16).
  val maxBytesInMatRequest = mx_scale_sp_entries * DIM
  val cmd_tracker = Module(new DMACommandTracker(nCmds, maxBytesInMatRequest, deps_t))

  val actual_stride = Mux(stride === 0.U, cols, stride)

  io.dma.req.valid := (control_state === waiting_for_command && cmd.valid && DoLoad && cmd_tracker.io.alloc.ready) ||
    control_state === waiting_for_dma_req_ready ||
    (control_state === sending_rows && row_counter =/= 0.U)
  io.dma.req.bits.vaddr := vaddr + row_counter * actual_stride
  io.dma.req.bits.laddr := localaddr + row_counter
  io.dma.req.bits.rows := rows
  io.dma.req.bits.cols := cols
  io.dma.req.bits.stride := actual_stride
  io.dma.req.bits.is_b := is_b
  io.dma.req.bits.status := cmd.bits.cmd.status

  cmd_tracker.io.alloc.valid := control_state === waiting_for_command && cmd.valid && DoLoad
  cmd_tracker.io.alloc.bits.bytes_to_read := rows * cols
  cmd_tracker.io.alloc.bits.tag.rob_id := cmd.bits.rob_id.bits
  cmd_tracker.io.request_returned.valid := io.dma.resp.fire
  cmd_tracker.io.request_returned.bits.cmd_id := io.dma.resp.bits.cmd_id
  cmd_tracker.io.request_returned.bits.bytes_read := io.dma.resp.bits.bytesRead
  cmd_tracker.io.cmd_completed.ready := io.completed.ready

  // `cmd_id` only reflects the allocated slot one cycle after `alloc.fire`, but the
  // first row request of a command is issued in the *same* cycle as the allocation
  // (both fire in `waiting_for_command`). Using the stale register for that first
  // request misattributes the DMA response to the wrong command-tracker slot. Drive
  // the first request with the freshly-allocated id combinationally; later rows
  // (in `waiting_for_dma_req_ready`/`sending_rows`) use the registered value, which
  // by then equals the same id. With `nCmds == 1` (e.g. DIM=32) the id is always 0
  // so this is inert; it only matters once `nCmds > 1` (DIM=16), where the stale id
  // attributed B's scale-load bytes to A's slot and tripped the `bytes_left` assert.
  val cmd_id = RegEnable(cmd_tracker.io.alloc.bits.cmd_id, cmd_tracker.io.alloc.fire())
  io.dma.req.bits.cmd_id := Mux(control_state === waiting_for_command,
    cmd_tracker.io.alloc.bits.cmd_id, cmd_id)

  io.completed.valid := cmd_tracker.io.cmd_completed.valid
  io.completed.bits := cmd_tracker.io.cmd_completed.bits.tag.rob_id
  io.busy := cmd.valid || cmd_tracker.io.busy

  when (io.dma.req.fire) {
    row_counter := Mux(row_counter === rows - 1.U, 0.U, row_counter + 1.U)
    assert(cols <= DIM.U, "MX scale mvin rows may contain at most DIM scale bytes in v1")
    assert(localaddr + row_counter < mx_scale_sp_entries.U, "MX scale mvin writes past MXScaleSRAM")
  }

  switch (control_state) {
    is (waiting_for_command) {
      when (cmd.valid) {
        when (DoLoad && cmd_tracker.io.alloc.fire()) {
          control_state := Mux(io.dma.req.fire, sending_rows, waiting_for_dma_req_ready)
        }
      }
    }

    is (waiting_for_dma_req_ready) {
      when (io.dma.req.fire) {
        control_state := sending_rows
      }
    }

    is (sending_rows) {
      val last_row = row_counter === 0.U || (row_counter === rows - 1.U && io.dma.req.fire)
      when (last_row) {
        control_state := waiting_for_command
        cmd.ready := true.B
      }
    }
  }

  assert(!(cmd_tracker.io.alloc.fire() && cmd_tracker.io.alloc.bits.bytes_to_read === 0.U),
    "A single MX scale mvin instruction must load more than 0 bytes")
}
