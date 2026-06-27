
package gemmini

import chisel3._
import chisel3.util._
import GemminiISA._
import Util._
import org.chipsalliance.cde.config.Parameters
import midas.targetutils.PerfCounter

// TODO do we still need to flush when the dataflow is weight stationary? Won't the result just keep travelling through on its own?
class ExecuteController[T <: Data, U <: Data, V <: Data](xLen: Int, tagWidth: Int, config: GemminiArrayConfig[T, U, V])
                                  (implicit p: Parameters, ev: Arithmetic[T]) extends Module {
  import config._
  import ev._

  val io = IO(new Bundle {
    val cmd = Flipped(Decoupled(new GemminiCmd(reservation_station_entries)))

    val im2col = new Bundle {
      val req = Decoupled(new Im2ColReadReq(config))
      val resp = Flipped(Decoupled(new Im2ColReadResp(config)))
    }

    val srams = new Bundle {
      val read = Vec(sp_banks, new ScratchpadReadIO(sp_bank_entries, sp_width))
      val write = Vec(sp_banks, new ScratchpadWriteIO(sp_bank_entries, sp_width, (sp_width / (aligned_to * 8)) max 1))
    }

    val acc = new Bundle {
      val read_req = Vec(acc_banks, Decoupled(new AccumulatorReadReq(
          acc_bank_entries, accType, acc_scale_t
      )))

      val read_resp = Flipped(Vec(acc_banks, Decoupled(new AccumulatorScaleResp(
        Vec(meshColumns, Vec(tileColumns, inputType)),
        Vec(meshColumns, Vec(tileColumns, accType))
      ))))

      // val write = Vec(acc_banks, new AccumulatorWriteIO(acc_bank_entries, Vec(meshColumns, Vec(tileColumns, accType))))
      val write = Vec(acc_banks, Decoupled(new AccumulatorWriteReq(acc_bank_entries, Vec(meshColumns, Vec(tileColumns, accType)))))
    }

    val mx = if (mx_enabled) {
      Some(new Bundle {
        val enable = Input(Bool())
        val reset = Input(Bool())
        // Loop geometry from CONFIG_MXINT8 rs2 (policy Appendix A): output-tile counts and
        // the padded power-of-two J pitch. 1/1/0 reproduces the single-tile v1 behavior.
        val i_tiles = Input(UInt(16.W))
        val j_tiles = Input(UInt(16.W))
        val log2_jp = Input(UInt(4.W))
        // K-block count of the loop (policy Appendix A / P3): lets the drain-order walk
        // wrap mx_out_kb to 0 at the loop boundary and self-cycle per loop with no reset
        // signal, so consecutive fenceless chunks pipeline. 0 = legacy (no wrap; the RESET_K
        // pulse still re-zeroes the walk), preserving v1 behavior bit-for-bit.
        val k_blocks = Input(UInt(16.W))
        val read_a = Decoupled(new MXScaleSRAMReadReq(mx_scale_sp_entries))
        val read_b = Decoupled(new MXScaleSRAMReadReq(mx_scale_sp_entries))
        val resp_a = Flipped(Valid(new MXScaleSRAMReadResp(DIM, mx_scale_exp_bits)))
        val resp_b = Flipped(Valid(new MXScaleSRAMReadResp(DIM, mx_scale_exp_bits)))
      })
    } else {
      None
    }

    val completed = Valid(UInt(log2Up(reservation_station_entries).W))
    val busy = Output(Bool())

    val counter = new CounterEventIO()
  })

  val block_size = meshRows*tileRows

  val mesh_tag = new Bundle with TagQueueTag {
    val rob_id = UDValid(UInt(log2Up(reservation_station_entries).W))
    val addr = local_addr_t.cloneType
    val rows = UInt(log2Up(block_size + 1).W)
    val cols = UInt(log2Up(block_size + 1).W)
    val mx_enabled = Bool()
    val mx_block = UInt(mx_scale_addr_bits.W)
    val mx_second_half = Bool()

    override def make_this_garbage(dummy: Int = 0): Unit = {
      rob_id.valid := false.B
      addr.make_this_garbage()
      mx_enabled := false.B
      mx_block := 0.U
      mx_second_half := false.B
    }
  }

  val unrolled_cmd = TransposePreloadUnroller(io.cmd, config, io.counter)

  val cmd_q_heads = 3
  assert(ex_queue_length >= cmd_q_heads)
  // val (cmd, _) = MultiHeadedQueue(io.cmd, ex_queue_length, cmd_q_heads)
  val (cmd, _) = MultiHeadedQueue(unrolled_cmd, ex_queue_length, cmd_q_heads)
  cmd.pop := 0.U

  // STATE defines
  val waiting_for_cmd :: compute :: flush :: flushing :: Nil = Enum(4)
  val control_state = RegInit(waiting_for_cmd)

  // Instruction-related variables
  val current_dataflow = if (dataflow == Dataflow.BOTH) Reg(UInt(1.W)) else dataflow.id.U

  val functs = cmd.bits.map(_.cmd.inst.funct)
  val rs1s = VecInit(cmd.bits.map(_.cmd.rs1))
  val rs2s = VecInit(cmd.bits.map(_.cmd.rs2))

  val DoConfig = functs(0) === CONFIG_CMD
  val DoComputes = functs.map(f => f === COMPUTE_AND_FLIP_CMD || f === COMPUTE_AND_STAY_CMD)
  val DoPreloads = functs.map(_ === PRELOAD_CMD)

  val preload_cmd_place = Mux(DoPreloads(0), 0.U, 1.U)
  // val a_address_place = Mux(current_dataflow === Dataflow.WS.id.U, 0.U, Mux(preload_cmd_place === 0.U, 1.U, 2.U))

  val in_prop = functs(0) === COMPUTE_AND_FLIP_CMD

  val in_prop_flush = Reg(Bool())
  when (current_dataflow === Dataflow.WS.id.U) {
    in_prop_flush := false.B
  }

  val ocol = RegInit(0.U(8.W))
  val orow = RegInit(0.U(8.W))
  val krow = RegInit(0.U(4.W))
  val weight_stride = RegInit(0.U(3.W))
  val channel = RegInit(0.U(9.W))
  val row_turn = RegInit(0.U(11.W))
  val row_left = RegInit(0.U(4.W))
  val kdim2 = RegInit(0.U(8.W))
  val weight_double_bank = RegInit(false.B)
  val weight_triple_bank = RegInit(false.B)

  val icol = WireInit(0.U(9.W))
  val irow = WireInit(0.U(9.W))

  icol := ((ocol - 1.U) * weight_stride + krow)//.asSInt
  irow := ((orow - 1.U) * weight_stride + krow)//.asSInt

  val im2col_turn = WireInit(0.U(9.W))

  val in_shift = Reg(UInt(log2Up(accType.getWidth).W))
  val acc_scale = Reg(acc_scale_t)
  val activation = if (has_nonlinear_activations) Reg(UInt(Activation.bitwidth.W)) else Activation.NONE // TODO magic number
  val a_transpose = RegInit(false.B)
  val bd_transpose = RegInit(false.B)
  val config_initialized = RegInit(false.B)

  val mx_runtime_enabled = if (mx_enabled) io.mx.get.enable else false.B
  val mx_reset = if (mx_enabled) io.mx.get.reset else false.B
  val mx_k_lane_counter = if (mx_enabled) RegInit(0.U(32.W)) else 0.U(32.W)
  val mx_logical_block = (mx_k_lane_counter >> log2Ceil(mx_block_size)).asUInt
  val mx_second_half = if (mx_enabled && DIM < mx_block_size) mx_k_lane_counter(log2Ceil(mx_block_size) - 1) else false.B
  // Phase index within the logical 32-block: mx_block_size/DIM physical phases per block
  // (the lane counter advances by block_size = DIM per compute). Generalizes the DIM=16
  // two-phase `mx_second_half` to any power-of-two DIM <= mx_block_size.
  val mx_in_phase = if (mx_enabled && DIM < mx_block_size) {
    mx_k_lane_counter(log2Ceil(mx_block_size) - 1, log2Ceil(DIM))
  } else {
    0.U
  }
  val mx_compute_active = mx_enabled.B && mx_runtime_enabled && current_dataflow === Dataflow.WS.id.U

  val a_should_be_fed_into_transposer = Mux(current_dataflow === Dataflow.OS.id.U, !a_transpose, a_transpose)
  val a_address_place = Mux(preload_cmd_place === 0.U, 1.U, Mux(a_should_be_fed_into_transposer, 2.U, 0.U))

  val b_should_be_fed_into_transposer = current_dataflow === Dataflow.OS.id.U && bd_transpose
  val b_address_place = Mux(preload_cmd_place === 0.U, 1.U, Mux(b_should_be_fed_into_transposer, 2.U, 0.U))

  val d_should_be_fed_into_transposer = current_dataflow === Dataflow.WS.id.U && bd_transpose

  assert(!(config_initialized &&
    (a_should_be_fed_into_transposer +& b_should_be_fed_into_transposer +& d_should_be_fed_into_transposer) > 1.U),
    "Too many inputs are being fed into the single transposer we have")

  //fix by input
  val im2col_en = config.hasIm2Col.B && weight_stride =/= 0.U

  // SRAM addresses of matmul operands
  val a_address_rs1 = rs1s(a_address_place).asTypeOf(local_addr_t)
  val b_address_rs2 = rs2s(b_address_place).asTypeOf(local_addr_t)
  val d_address_rs1 = rs1s(preload_cmd_place).asTypeOf(local_addr_t)
  val c_address_rs2 = rs2s(preload_cmd_place).asTypeOf(local_addr_t)

  if (dataflow == Dataflow.OS && hardcode_d_to_garbage_addr) {
    d_address_rs1.make_this_garbage()
  } else if (dataflow == Dataflow.WS && hardcode_d_to_garbage_addr) {
    b_address_rs2.make_this_garbage()
  }

  val multiply_garbage = a_address_rs1.is_garbage()
  val accumulate_zeros = b_address_rs2.is_garbage()
  val preload_zeros = d_address_rs1.is_garbage()

  val a_cols_default = rs1s(a_address_place)(32 + log2Up(block_size + 1) - 1, 32) // TODO magic numbers
  val a_rows_default = rs1s(a_address_place)(48 + log2Up(block_size + 1) - 1, 48) // TODO magic numbers
  val b_cols_default = rs2s(b_address_place)(32 + log2Up(block_size + 1) - 1, 32) // TODO magic numbers
  val b_rows_default = rs2s(b_address_place)(48 + log2Up(block_size + 1) - 1, 48) // TODO magic numbers
  val d_cols_default = rs1s(preload_cmd_place)(32 + log2Up(block_size + 1) - 1, 32) // TODO magic numbers
  val d_rows_default = rs1s(preload_cmd_place)(48 + log2Up(block_size + 1) - 1, 48) // TODO magic numbers

  val a_cols = Mux(a_transpose, a_rows_default, a_cols_default)
  val a_rows = Mux(a_transpose, a_cols_default, a_rows_default)
  val b_cols = Mux(current_dataflow === Dataflow.OS.id.U && bd_transpose, b_rows_default, b_cols_default)
  val b_rows = Mux(current_dataflow === Dataflow.OS.id.U && bd_transpose, b_cols_default, b_rows_default)
  val d_cols = Mux(current_dataflow === Dataflow.WS.id.U && bd_transpose, d_rows_default, d_cols_default)
  val d_rows = Mux(current_dataflow === Dataflow.WS.id.U && bd_transpose, d_cols_default, d_rows_default)
  val c_cols = rs2s(preload_cmd_place)(32 + log2Up(block_size + 1) - 1, 32) // TODO magic numbers
  val c_rows = rs2s(preload_cmd_place)(48 + log2Up(block_size + 1) - 1, 48) // TODO magic numbers

  // Dependency stuff
  io.completed.valid := false.B
  io.completed.bits := DontCare

  // val pending_completed_rob_id = Reg(UDValid(UInt(log2Up(rob_entries).W)))
  val pending_completed_rob_ids = Reg(Vec(2, UDValid(UInt(log2Up(reservation_station_entries).W))))

  // Instantiate a queue which queues up signals which must be fed into the mesh
  val mesh_cntl_signals_q = Module(new Queue(new ComputeCntlSignals, spad_read_delay+1,
    pipe=true))

  val cntl_ready = mesh_cntl_signals_q.io.enq.ready
  val cntl_valid = mesh_cntl_signals_q.io.deq.valid
  val cntl = mesh_cntl_signals_q.io.deq.bits

  // Instantiate the actual mesh
  val mesh = Module(new MeshWithDelays(spatialArrayInputType, spatialArrayWeightType, spatialArrayOutputType, accType, mesh_tag, dataflow, tree_reduction, tile_latency, mesh_output_delay,
    tileRows, tileColumns, meshRows, meshColumns, shifter_banks, shifter_banks))

  if (mx_enabled) {
    val mx = io.mx.get
    mx.read_a.valid := false.B
    mx.read_a.bits := DontCare
    mx.read_b.valid := false.B
    mx.read_b.bits := DontCare
  }

  mesh.io.a.valid := false.B
  mesh.io.b.valid := false.B
  mesh.io.d.valid := false.B
  mesh.io.req.valid := control_state === flush

  mesh.io.a.bits := DontCare
  mesh.io.b.bits := DontCare
  mesh.io.d.bits := DontCare
  mesh.io.req.bits.tag := DontCare
  mesh.io.req.bits.tag.cols := cntl.c_cols
  mesh.io.req.bits.tag.rows := cntl.c_rows
  mesh.io.req.bits.tag.mx_enabled := false.B
  mesh.io.req.bits.tag.mx_block := 0.U
  mesh.io.req.bits.tag.mx_second_half := false.B
  mesh.io.req.bits.total_rows := block_size.U
  mesh.io.req.bits.pe_control.propagate := Mux(control_state === flush, in_prop_flush, cntl.prop)
  mesh.io.req.bits.pe_control.dataflow := cntl.dataflow
  mesh.io.req.bits.pe_control.shift := cntl.shift
  mesh.io.req.bits.a_transpose := cntl.a_transpose
  mesh.io.req.bits.bd_transpose := cntl.bd_transpose
  mesh.io.req.bits.tag.rob_id := cntl.rob_id
  mesh.io.req.bits.flush := Mux(control_state === flush && !cntl_valid, 1.U, 0.U) // We want to make sure that the mesh has absorbed all inputs before flushing

  // Hazards
  val raw_hazards_are_impossible = !ex_read_from_acc && !ex_write_to_spad // Special case where RAW hazards are impossible

  val raw_hazard_pre = mesh.io.tags_in_progress.map { t =>
    val is_garbage = t.addr.is_garbage()
    val pre_raw_haz = t.addr.is_same_address(rs1s(0))
    val mul_raw_haz = t.addr.is_same_address(rs1s(1)) || t.addr.is_same_address(rs2s(1))

    !is_garbage && (pre_raw_haz || mul_raw_haz) && !raw_hazards_are_impossible.B
  }.reduce(_ || _)

  val raw_hazard_mulpre = mesh.io.tags_in_progress.map { t =>
    val is_garbage = t.addr.is_garbage()
    val pre_raw_haz = t.addr.is_same_address(rs1s(1))
    val mul_raw_haz = t.addr.is_same_address(rs1s(2)) || t.addr.is_same_address(rs2s(2))

    !is_garbage && (mul_raw_haz || pre_raw_haz) && !raw_hazards_are_impossible.B
  }.reduce(_ || _)

  val third_instruction_needed = a_address_place > 1.U || b_address_place > 1.U || preload_cmd_place > 1.U || !raw_hazards_are_impossible.B

  val matmul_in_progress = mesh.io.tags_in_progress.map(_.rob_id.valid).reduce(_ || _)

  io.busy := cmd.valid(0) || matmul_in_progress

  // SRAM scratchpad
  // Fire counters which resolve same-bank accesses
  val a_fire_counter = Reg(UInt(log2Up(block_size).W))
  val b_fire_counter = Reg(UInt(log2Up(block_size).W))
  val d_fire_counter = Reg(UInt(log2Up(block_size).W))

  val a_fire_started = RegInit(false.B)
  val d_fire_started = RegInit(false.B)
  val b_fire_started = RegInit(false.B)

  // "A" stride variables
  val a_addr_offset = Reg(UInt((16 + log2Up(block_size)).W))
  val a_addr_stride = Reg(UInt(16.W)) // TODO magic numbers

  // "C" stride variables
  val c_addr_stride = Reg(UInt(16.W)) // TODO magic numbers

  val a_address = a_address_rs1 + a_addr_offset
  val b_address = b_address_rs2 + b_fire_counter
  val d_address = d_address_rs1 + (block_size.U - 1.U - d_fire_counter)

  val dataAbank = a_address.sp_bank()
  val dataBbank = b_address.sp_bank()
  val dataDbank = d_address.sp_bank()

  val dataABankAcc = a_address.acc_bank()
  val dataBBankAcc = b_address.acc_bank()
  val dataDBankAcc = d_address.acc_bank()

  val a_read_from_acc = ex_read_from_acc.B && a_address_rs1.is_acc_addr
  val b_read_from_acc = ex_read_from_acc.B && b_address_rs2.is_acc_addr
  val d_read_from_acc = ex_read_from_acc.B && d_address_rs1.is_acc_addr

  val start_inputting_a = WireInit(false.B)
  val start_inputting_b = WireInit(false.B)
  val start_inputting_d = WireInit(false.B)
  val start_array_outputting = WireInit(false.B)

  val a_garbage = a_address_rs1.is_garbage() || !start_inputting_a
  val b_garbage = b_address_rs2.is_garbage() || !start_inputting_b
  val d_garbage = d_address_rs1.is_garbage() || !start_inputting_d

  // TODO merge these into one enum
  val perform_single_preload = RegInit(false.B)
  val perform_single_mul = RegInit(false.B)
  val perform_mul_pre = RegInit(false.B)

  val performing_single_preload = WireInit(perform_single_preload && control_state === compute)
  val performing_single_mul = WireInit(perform_single_mul && control_state === compute)
  val performing_mul_pre = WireInit(perform_mul_pre && control_state === compute)

  val total_rows = WireInit(block_size.U) // The total number of rows of A, B, and D to feed into the mesh

  // TODO Also reduce the number of rows when "perform_single_preload === true.B"
  when (current_dataflow === Dataflow.WS.id.U && d_garbage &&
    !a_should_be_fed_into_transposer && !b_should_be_fed_into_transposer && !d_should_be_fed_into_transposer) {
    val rows_a = Mux(a_garbage, 1.U, a_rows)
    val rows_b = Mux(b_garbage, 1.U, b_rows)

    /* We can only retire one ROB instruction per cycle (max), but if total_rows == 1, then we would be trying to retire
       2 ROB instructions per cycle (one for the preload, and one for the compute). Therefore, to prevent ROB
       instructions from being lost, we set a minimum floor for total_rows of 2.

       Furthermore, two writes to the same accumulator address must occur at least 4 cycles apart to allow the write to
       fully propagate through. Therefore, we raise the minimum floor for total_rows to 4.
       TODO: add a WAW check to the ROB so that we can lower the floor back to 2
     */
    total_rows := maxOf(maxOf(rows_a, rows_b), 4.U)
  }

  //added for mul_pre sync
  val mul_pre_counter_sub = RegInit(0.U(3.W))
  val mul_pre_counter_count = RegInit(0.U(3.W))
  val mul_pre_counter_lock = RegInit(false.B)

  // These variables determine whether or not the row that is currently being read should be completely padded with 0
  val a_row_is_not_all_zeros = a_fire_counter < a_rows
  val b_row_is_not_all_zeros = b_fire_counter < b_rows
  val d_row_is_not_all_zeros = block_size.U - 1.U - d_fire_counter < d_rows //Todo: d_fire_counter_mulpre?

  val im2col_wire = io.im2col.req.ready

  def same_bank(addr1: LocalAddr, addr2: LocalAddr, is_garbage1: Bool, is_garbage2: Bool, start_inputting1: Bool, start_inputting2: Bool, can_be_im2colled: Boolean): Bool = {
    val addr1_read_from_acc = addr1.is_acc_addr
    val addr2_read_from_acc = addr2.is_acc_addr

    val is_garbage = is_garbage1 || is_garbage2 ||
      !start_inputting1 || !start_inputting2

    val is_being_im2colled = can_be_im2colled.B && im2col_wire && im2col_en//im2col_wire

    !is_garbage && !is_being_im2colled && ((addr1_read_from_acc && addr2_read_from_acc) ||
      (!addr1_read_from_acc && !addr2_read_from_acc && addr1.sp_bank() === addr2.sp_bank()))
  }

  val a_ready = WireInit(true.B)
  val b_ready = WireInit(true.B)
  val d_ready = WireInit(true.B)

  case class Operand(addr: LocalAddr, is_garbage: Bool, start_inputting: Bool, counter: UInt, started: Bool, can_be_im2colled: Boolean, priority: Int) {
    val done = counter === 0.U && started
  }
  val a_operand = Operand(a_address, a_address_rs1.is_garbage(), start_inputting_a, a_fire_counter, a_fire_started, true, 0)
  val b_operand = Operand(b_address, b_address_rs2.is_garbage(), start_inputting_b, b_fire_counter, b_fire_started, false, 1)
  val d_operand = Operand(d_address, d_address_rs1.is_garbage(), start_inputting_d, d_fire_counter, d_fire_started, false, 2)
  val operands = Seq(a_operand, b_operand, d_operand)

  val Seq(a_valid, b_valid, d_valid) = operands.map { case Operand(addr, is_garbage, start_inputting, counter, started, can_be_im2colled, priority) =>
    val others = operands.filter(_.priority != priority)

    val same_banks = others.map(o => same_bank(addr, o.addr, is_garbage, o.is_garbage, start_inputting, o.start_inputting, can_be_im2colled || o.can_be_im2colled))
    val same_counter = others.map(o => started === o.started && counter === o.counter)

    val one_ahead = others.map(o => started && counter === wrappingAdd(o.counter, 1.U, total_rows))

    val higher_priorities = others.map(o => (o.priority < priority).B)

    val must_wait_for = ((same_banks zip same_counter) zip (one_ahead zip higher_priorities)).map {
      case ((sb, sc), (oa, hp)) =>
        (sb && hp && sc) || oa
    }

    !must_wait_for.reduce(_ || _)
  }

  val a_fire = a_valid && a_ready
  val b_fire = b_valid && b_ready
  val d_fire = d_valid && d_ready

  val firing = start_inputting_a || start_inputting_b || start_inputting_d

  when (!firing) {
    a_fire_counter := 0.U
    a_addr_offset := 0.U
  }.elsewhen (firing && a_fire && cntl_ready) {
    a_fire_counter := wrappingAdd(a_fire_counter, 1.U, total_rows)
    a_addr_offset := Mux(a_fire_counter === (total_rows-1.U), 0.U, a_addr_offset + a_addr_stride)
    a_fire_started := true.B
  }

  when (!firing) {
    b_fire_counter := 0.U
  }.elsewhen (firing && b_fire && cntl_ready) {
    b_fire_counter := wrappingAdd(b_fire_counter, 1.U, total_rows)
    b_fire_started := true.B
  }

  when (!firing) {
    d_fire_counter := 0.U
  }.elsewhen (firing && d_fire && cntl_ready) {
    d_fire_counter := wrappingAdd(d_fire_counter, 1.U, total_rows)
    d_fire_started := true.B
  }

  when(performing_mul_pre && !cntl_ready && !mul_pre_counter_lock){
    mul_pre_counter_count := d_fire_counter //store 2
  }.elsewhen(!performing_mul_pre){
    mul_pre_counter_count := 0.U
    mul_pre_counter_lock := false.B
  }.elsewhen(!cntl_ready){
    mul_pre_counter_lock := true.B
  }

  when(!io.im2col.resp.bits.im2col_delay && performing_mul_pre){
    mul_pre_counter_sub := Mux(mul_pre_counter_sub > 0.U,  mul_pre_counter_sub - 1.U, 0.U)
  }.elsewhen(io.im2col.resp.bits.im2col_delay){
    mul_pre_counter_sub := 2.U
  }.otherwise{mul_pre_counter_sub := 0.U}

  // The last line in this (long) Boolean is just to make sure that we don't think we're done as soon as we begin firing
  // TODO change when square requirement lifted
  val about_to_fire_all_rows = ((a_fire_counter === (total_rows-1.U) && a_fire) || a_fire_counter === 0.U) &&
    ((b_fire_counter === (total_rows-1.U) && b_fire) || b_fire_counter === 0.U) &&
    ((d_fire_counter === (total_rows-1.U) && d_fire) || d_fire_counter === 0.U) &&
    (a_fire_started || b_fire_started || d_fire_started) &&
    cntl_ready

  when (about_to_fire_all_rows) {
    a_fire_started := false.B
    b_fire_started := false.B
    d_fire_started := false.B
  }

  val d_fire_counter_mulpre = WireInit(b_fire_counter)
  when(performing_mul_pre && !io.im2col.resp.bits.im2col_delay&&im2col_en){
    d_fire_counter_mulpre := d_fire_counter - mul_pre_counter_sub
  }.otherwise{d_fire_counter_mulpre := d_fire_counter}

  // Scratchpad reads
  for (i <- 0 until sp_banks) {
    val read_a = a_valid && !a_read_from_acc && dataAbank === i.U && start_inputting_a && !multiply_garbage && a_row_is_not_all_zeros && !(im2col_wire&&im2col_en)
    val read_b = b_valid && !b_read_from_acc && dataBbank === i.U && start_inputting_b && !accumulate_zeros && b_row_is_not_all_zeros //&& !im2col_wire
    val read_d = d_valid && !d_read_from_acc && dataDbank === i.U && start_inputting_d && !preload_zeros && d_row_is_not_all_zeros //&& !im2col_wire

    Seq((read_a, a_ready), (read_b, b_ready), (read_d, d_ready)).foreach { case (rd, r) =>
      when (rd && !io.srams.read(i).req.ready) {
        r := false.B
      }
    }

    if (ex_read_from_spad) {
      io.srams.read(i).req.valid := (read_a || read_b || read_d) && cntl_ready
      io.srams.read(i).req.bits.fromDMA := false.B
      io.srams.read(i).req.bits.addr := MuxCase(a_address_rs1.sp_row() + a_fire_counter,
        Seq(read_b -> (b_address_rs2.sp_row() + b_fire_counter),
          read_d -> (d_address_rs1.sp_row() + block_size.U - 1.U - d_fire_counter_mulpre)))

      // TODO this just overrides the previous line. Should we erase the previous line?
      when(im2col_en === false.B) {
        io.srams.read(i).req.bits.addr := MuxCase(a_address.sp_row(),
          Seq(read_b -> b_address.sp_row(),
            read_d -> d_address.sp_row()))
      }
    } else {
      io.srams.read(i).req.valid := false.B
      io.srams.read(i).req.bits.fromDMA := false.B
      io.srams.read(i).req.bits.addr := DontCare
    }

    io.srams.read(i).resp.ready := false.B
  }

  // Accumulator read
  for (i <- 0 until acc_banks) {
    val read_a_from_acc = a_valid && a_read_from_acc && dataABankAcc === i.U && start_inputting_a && !multiply_garbage && a_row_is_not_all_zeros && !(im2col_wire&&im2col_en)
    val read_b_from_acc = b_valid && b_read_from_acc && dataBBankAcc === i.U && start_inputting_b && !accumulate_zeros && b_row_is_not_all_zeros //&& !im2col_wire
    val read_d_from_acc = d_valid && d_read_from_acc && dataDBankAcc === i.U && start_inputting_d && !preload_zeros && d_row_is_not_all_zeros //&& !im2col_wire

    Seq((read_a_from_acc, a_ready), (read_b_from_acc, b_ready), (read_d_from_acc, d_ready)).foreach { case (rd, r) =>
      when(rd && !io.acc.read_req(i).ready) {
        r := false.B
      }
    }

    if (ex_read_from_acc) {
      io.acc.read_req(i).valid := read_a_from_acc || read_b_from_acc || read_d_from_acc
      io.acc.read_req(i).bits.scale := acc_scale
      io.acc.read_req(i).bits.full := false.B
      io.acc.read_req(i).bits.igelu_qb := DontCare
      io.acc.read_req(i).bits.igelu_qc := DontCare
      io.acc.read_req(i).bits.iexp_qln2 := DontCare
      io.acc.read_req(i).bits.iexp_qln2_inv := DontCare
      io.acc.read_req(i).bits.act := activation
      io.acc.read_req(i).bits.fromDMA := false.B
      io.acc.read_req(i).bits.addr := MuxCase(a_address_rs1.acc_row() + a_fire_counter,
        Seq(read_b_from_acc -> (b_address_rs2.acc_row() + b_fire_counter),
          read_d_from_acc -> (d_address_rs1.acc_row() + block_size.U - 1.U - d_fire_counter)))

      // TODO this just overrides the previous line. Should we erase the previous line?
      when(im2col_en === false.B){
        io.acc.read_req(i).bits.addr := MuxCase(a_address.acc_row(),
          Seq(read_b_from_acc -> b_address.acc_row(),
            read_d_from_acc -> d_address.acc_row()))
      }
    } else {
      io.acc.read_req(i).valid := false.B
      io.acc.read_req(i).bits.scale := DontCare
      io.acc.read_req(i).bits.full := false.B
      io.acc.read_req(i).bits.igelu_qb := DontCare
      io.acc.read_req(i).bits.igelu_qc := DontCare
      io.acc.read_req(i).bits.iexp_qln2 := DontCare
      io.acc.read_req(i).bits.iexp_qln2_inv := DontCare
      io.acc.read_req(i).bits.act := DontCare
      io.acc.read_req(i).bits.fromDMA := false.B
      io.acc.read_req(i).bits.addr := DontCare
    }

    io.acc.read_resp(i).ready := false.B
  }

  // Im2Col reads
  {
    val read_a = a_valid && start_inputting_a && !multiply_garbage && im2col_wire&&im2col_en //or just im2col_wire

    when (read_a && !io.im2col.req.ready) {
      a_ready := false.B
    }

    io.im2col.req.valid := read_a
    io.im2col.req.bits.addr := a_address_rs1
    io.im2col.req.bits.icol := icol
    io.im2col.req.bits.irow := irow
    io.im2col.req.bits.ocol := ocol
    io.im2col.req.bits.stride := weight_stride
    io.im2col.req.bits.krow := krow
    io.im2col.req.bits.kdim2 := kdim2
    io.im2col.req.bits.row_turn := row_turn
    io.im2col.req.bits.row_left := row_left
    io.im2col.req.bits.channel := channel
    io.im2col.req.bits.im2col_cmd := im2col_en
    io.im2col.req.bits.start_inputting := start_inputting_a
    io.im2col.req.bits.weight_double_bank := weight_double_bank
    io.im2col.req.bits.weight_triple_bank := weight_triple_bank

    io.im2col.resp.ready := mesh.io.a.ready
  }

  // FSM logic
  switch (control_state) {
    is(waiting_for_cmd) {
      // Default state
      perform_single_preload := false.B
      perform_mul_pre := false.B
      perform_single_mul := false.B

      when(cmd.valid(0))
      {
        when(DoConfig && !matmul_in_progress && !pending_completed_rob_ids.map(_.valid).reduce(_ || _)) {
          val config_ex_rs1 = rs1s(0).asTypeOf(new ConfigExRs1(acc_scale_t_bits))
          val config_ex_rs2 = rs2s(0).asTypeOf(new ConfigExRs2)

          val config_cmd_type = rs1s(0)(1,0) // TODO magic numbers

          when (config_cmd_type === CONFIG_EX) {
            val set_only_strides = config_ex_rs1.set_only_strides

            when (!set_only_strides) {
              if (has_nonlinear_activations) {
                activation := config_ex_rs1.activation
              }
              in_shift := config_ex_rs2.in_shift
              acc_scale := rs1s(0)(xLen - 1, 32).asTypeOf(acc_scale_t) // TODO magic number
              a_transpose := config_ex_rs1.a_transpose
              bd_transpose := config_ex_rs1.b_transpose

              if (dataflow == Dataflow.BOTH) {
                current_dataflow := config_ex_rs1.dataflow
              }
            }

            a_addr_stride := config_ex_rs1.a_stride // TODO this needs to be kept in sync with ROB.scala
            c_addr_stride := config_ex_rs2.c_stride // TODO this needs to be kept in sync with ROB.scala
            config_initialized := true.B
          }.otherwise { // config_cmd_type === CONFIG_IM2COL
            ocol := cmd.bits(0).cmd.rs2(63, 56)
            kdim2 := cmd.bits(0).cmd.rs2(55, 48) //increased bitwidth
            krow := cmd.bits(0).cmd.rs2(47, 44) //increased bitwidth
            channel := cmd.bits(0).cmd.rs2(31, 23)
            weight_stride := cmd.bits(0).cmd.rs2(22, 20)
            weight_double_bank := cmd.bits(0).cmd.rs1(58) //added
            weight_triple_bank := cmd.bits(0).cmd.rs1(59)
            row_left := cmd.bits(0).cmd.rs1(57, 54)
            row_turn := cmd.bits(0).cmd.rs1(53, 42)
          }

          io.completed := cmd.bits(0).rob_id

          cmd.pop := 1.U
        }

        // Preload
        .elsewhen(DoPreloads(0) && cmd.valid(1) && (raw_hazards_are_impossible.B || !raw_hazard_pre)) {
          perform_single_preload := true.B
          performing_single_preload := true.B

          //start_inputting_a := current_dataflow === Dataflow.OS.id.U
          //start_inputting_d := true.B

          start_inputting_a := a_should_be_fed_into_transposer
          start_inputting_b := b_should_be_fed_into_transposer
          start_inputting_d := true.B

          control_state := compute
        }

        // Overlap compute and preload
        .elsewhen(DoComputes(0) && cmd.valid(1) && DoPreloads(1) && (!third_instruction_needed || (cmd.valid(2) && !raw_hazard_mulpre)))
        {
          perform_mul_pre := true.B
          performing_mul_pre := true.B

          start_inputting_a := true.B
          start_inputting_b := true.B
          start_inputting_d := true.B

          control_state := compute
        }

        // Single mul
        .elsewhen(DoComputes(0)) {
          perform_single_mul := true.B
          performing_single_mul := true.B

          start_inputting_a := !a_should_be_fed_into_transposer
          start_inputting_b := !b_should_be_fed_into_transposer

          control_state := compute
        }

        // Flush
        .elsewhen(matmul_in_progress && (current_dataflow === Dataflow.OS.id.U || DoConfig)) {
          control_state := flush
        }
      }.elsewhen(matmul_in_progress && current_dataflow === Dataflow.OS.id.U) {
        // TODO code duplication
        control_state := flush
      }
    }
    is(compute) {
      // Only preloading
      when(perform_single_preload) {
        start_inputting_a := a_should_be_fed_into_transposer
        start_inputting_b := b_should_be_fed_into_transposer
        start_inputting_d := true.B

        when(about_to_fire_all_rows) {
          cmd.pop := 1.U
          control_state := waiting_for_cmd

          pending_completed_rob_ids(0).valid := cmd.bits(0).rob_id.valid && c_address_rs2.is_garbage()
          pending_completed_rob_ids(0).bits := cmd.bits(0).rob_id.bits

          when(current_dataflow === Dataflow.OS.id.U) {
            in_prop_flush := !rs2s(0).asTypeOf(local_addr_t).is_garbage()
          }
        }
      }
      // Overlapping
      .elsewhen(perform_mul_pre) {
        start_inputting_a := true.B
        start_inputting_b := true.B
        start_inputting_d := true.B

        when(about_to_fire_all_rows) {
          cmd.pop := 2.U
          control_state := waiting_for_cmd

          pending_completed_rob_ids(0) := cmd.bits(0).rob_id
          pending_completed_rob_ids(1).valid := cmd.bits(1).rob_id.valid && c_address_rs2.is_garbage()
          pending_completed_rob_ids(1).bits := cmd.bits(1).rob_id.bits

          when(current_dataflow === Dataflow.OS.id.U) {
            in_prop_flush := !rs2s(1).asTypeOf(local_addr_t).is_garbage()
          }
        }
      }
      // Only compute
      .elsewhen(perform_single_mul) {
        start_inputting_a := !a_should_be_fed_into_transposer
        start_inputting_b := !b_should_be_fed_into_transposer

        when(about_to_fire_all_rows) {
          cmd.pop := 1.U
          control_state := waiting_for_cmd
          pending_completed_rob_ids(0) := cmd.bits(0).rob_id
        }
      }
    }
    is(flush) {
      when(mesh.io.req.fire) {
        control_state := flushing
      }
    }
    is(flushing) {
      when(mesh.io.req.ready) {
        // TODO we waste a cycle here if it was better to continue with the flush
        control_state := waiting_for_cmd
      }
    }
  }

  if (mx_enabled) {
    when (mx_reset) {
      mx_k_lane_counter := 0.U
    } .elsewhen (control_state === compute && about_to_fire_all_rows &&
      (perform_single_mul || perform_mul_pre) && mx_compute_active) {
      mx_k_lane_counter := mx_k_lane_counter + block_size.U
    }

    assert(!(mx_compute_active && (a_transpose || bd_transpose)),
      "MXINT8 execute path v1 supports untransposed WS GEMM only")
  }

  // When the physical array is narrower than the logical MX block, one logical
  // 32-element K block spans `mx_block_size/DIM` physical K phases. The lane counter
  // advances by `block_size` (=DIM) per compute, so the logical block
  // (`mx_k_lane_counter >> log2(mx_block_size)`) advances only every 32 lanes, and the
  // phase index (`mx_in_phase`) cycles 0,1,..,N-1. The BlockScaleUnit accumulates the
  // running raw-partial sum across the non-final phases into a single `DIM x DIM` buffer
  // (`mx_raw_buf`), so the phases must arrive in strict 0..N-1 order — checked here.
  if (mx_enabled && DIM < mx_block_size) {
    val mx_in_phases = mx_block_size / DIM
    val mx_phase_ctr = RegInit(0.U(log2Ceil(mx_in_phases).W))
    when (mx_reset) {
      mx_phase_ctr := 0.U
    } .elsewhen (control_state === compute && about_to_fire_all_rows &&
      (perform_single_mul || perform_mul_pre) && mx_compute_active) {
      assert(mx_in_phase === mx_phase_ctr,
        "MXINT8 DIM<32: physical K phases must arrive in strict 0..N-1 order per 32-lane block")
      mx_phase_ctr := Mux(mx_phase_ctr === (mx_in_phases - 1).U, 0.U, mx_phase_ctr + 1.U)
    }
  }

  // Computing logic
  val computing = performing_mul_pre || performing_single_mul || performing_single_preload

  class ComputeCntlSignals extends Bundle {
    val perform_mul_pre = Bool()
    val perform_single_mul = Bool()
    val perform_single_preload = Bool()

    val a_bank = UInt(log2Up(sp_banks).W)
    val b_bank = UInt(log2Up(sp_banks).W)
    val d_bank = UInt(log2Up(sp_banks).W)

    val a_bank_acc = UInt(log2Up(acc_banks).W)
    val b_bank_acc = UInt(log2Up(acc_banks).W)
    val d_bank_acc = UInt(log2Up(acc_banks).W)

    val a_read_from_acc = Bool()
    val b_read_from_acc = Bool()
    val d_read_from_acc = Bool()

    val a_garbage = Bool()
    val b_garbage = Bool()
    val d_garbage = Bool()

    val accumulate_zeros = Bool()
    val preload_zeros = Bool()

    val a_fire = Bool()
    val b_fire = Bool()
    val d_fire = Bool()

    val a_unpadded_cols = UInt(log2Up(block_size + 1).W)
    val b_unpadded_cols = UInt(log2Up(block_size + 1).W)
    val d_unpadded_cols = UInt(log2Up(block_size + 1).W)

    val c_addr = local_addr_t.cloneType
    val c_rows = UInt(log2Up(block_size + 1).W)
    val c_cols = UInt(log2Up(block_size + 1).W)

    val a_transpose = Bool()
    val bd_transpose = Bool()

    val total_rows = UInt(log2Up(block_size + 1).W)

    val rob_id = UDValid(UInt(log2Up(reservation_station_entries).W))

    val dataflow = UInt(1.W)
    val prop = UInt(1.W)
    val shift = UInt(log2Up(accType.getWidth).W)

    val im2colling = Bool()

    val first = Bool()

    val mx_enabled = Bool()
    val mx_block = UInt(mx_scale_addr_bits.W)
    val mx_second_half = Bool()
  }

  mesh_cntl_signals_q.io.enq.valid := computing

  mesh_cntl_signals_q.io.enq.bits.perform_mul_pre := performing_mul_pre
  mesh_cntl_signals_q.io.enq.bits.perform_single_mul := performing_single_mul
  mesh_cntl_signals_q.io.enq.bits.perform_single_preload := performing_single_preload

  mesh_cntl_signals_q.io.enq.bits.a_bank := dataAbank
  mesh_cntl_signals_q.io.enq.bits.b_bank := dataBbank
  mesh_cntl_signals_q.io.enq.bits.d_bank := dataDbank

  mesh_cntl_signals_q.io.enq.bits.a_bank_acc := dataABankAcc
  mesh_cntl_signals_q.io.enq.bits.b_bank_acc := dataBBankAcc
  mesh_cntl_signals_q.io.enq.bits.d_bank_acc := dataDBankAcc

  mesh_cntl_signals_q.io.enq.bits.a_garbage := a_garbage
  mesh_cntl_signals_q.io.enq.bits.b_garbage := b_garbage
  mesh_cntl_signals_q.io.enq.bits.d_garbage := d_garbage

  mesh_cntl_signals_q.io.enq.bits.a_read_from_acc := a_read_from_acc
  mesh_cntl_signals_q.io.enq.bits.b_read_from_acc := b_read_from_acc
  mesh_cntl_signals_q.io.enq.bits.d_read_from_acc := d_read_from_acc

  mesh_cntl_signals_q.io.enq.bits.accumulate_zeros := accumulate_zeros
  mesh_cntl_signals_q.io.enq.bits.preload_zeros := preload_zeros //&& (in_shift(19) =/= 1.U)) //fixed for negative shift?

  mesh_cntl_signals_q.io.enq.bits.a_unpadded_cols := Mux(a_row_is_not_all_zeros, a_cols, 0.U)
  mesh_cntl_signals_q.io.enq.bits.b_unpadded_cols := Mux(b_row_is_not_all_zeros, b_cols, 0.U)
  mesh_cntl_signals_q.io.enq.bits.d_unpadded_cols := Mux(d_row_is_not_all_zeros, d_cols, 0.U)

  mesh_cntl_signals_q.io.enq.bits.total_rows := total_rows

  mesh_cntl_signals_q.io.enq.bits.a_fire := a_fire
  mesh_cntl_signals_q.io.enq.bits.b_fire := b_fire
  mesh_cntl_signals_q.io.enq.bits.d_fire := d_fire

  mesh_cntl_signals_q.io.enq.bits.c_addr := c_address_rs2
  mesh_cntl_signals_q.io.enq.bits.c_rows := c_rows
  mesh_cntl_signals_q.io.enq.bits.c_cols := c_cols

  mesh_cntl_signals_q.io.enq.bits.a_transpose := a_transpose
  mesh_cntl_signals_q.io.enq.bits.bd_transpose := bd_transpose

  mesh_cntl_signals_q.io.enq.bits.rob_id.valid := !performing_single_mul && !c_address_rs2.is_garbage()
  mesh_cntl_signals_q.io.enq.bits.rob_id.bits := cmd.bits(preload_cmd_place).rob_id.bits

  mesh_cntl_signals_q.io.enq.bits.dataflow := current_dataflow
  mesh_cntl_signals_q.io.enq.bits.prop := Mux(performing_single_preload, in_prop_flush, in_prop)//prop) //available propagate or not?
  mesh_cntl_signals_q.io.enq.bits.shift := in_shift

  mesh_cntl_signals_q.io.enq.bits.im2colling := im2col_wire && im2col_en //im2col_wire

  mesh_cntl_signals_q.io.enq.bits.first := !a_fire_started && !b_fire_started && !d_fire_started

  // In weight-stationary mode the matmul output is tagged with the *preload's*
  // tag (it carries the C address), while the multiply feeds A with a garbage
  // output address. So the MX marker must be set on the preload as well, or the
  // BlockScaleUnit never sees mx_enabled at the output and writes the raw partial.
  mesh_cntl_signals_q.io.enq.bits.mx_enabled := mx_compute_active &&
    (performing_single_mul || performing_mul_pre || performing_single_preload)
  mesh_cntl_signals_q.io.enq.bits.mx_block := mx_logical_block(mx_scale_addr_bits - 1, 0)
  mesh_cntl_signals_q.io.enq.bits.mx_second_half := mx_second_half

  // The B scale vector is read at *output* time (addressed by the output's own
  // `tag.mx_block`), mirroring the A-scale read below, rather than latched once at
  // mesh-feed time. A feed-time latch is unsafe across logical K blocks: with the deep
  // weight-stationary output pipeline (worst at DIM=16, where one logical block spans
  // two physical phases) the next block's feed would overwrite the single latch while
  // the current block's outputs are still draining, so the current block's tail rows
  // would be scaled by the next block's B exponents. See the BlockScaleUnit.

  val readData = VecInit(io.srams.read.map(_.resp.bits.data))
  val accReadData = if (ex_read_from_acc) VecInit(io.acc.read_resp.map(_.bits.data.asUInt)) else readData
  val im2ColData = io.im2col.resp.bits.a_im2col.asUInt

  val readValid = VecInit(io.srams.read.map(bank => ex_read_from_spad.B && bank.resp.valid && !bank.resp.bits.fromDMA))
  val accReadValid = VecInit(io.acc.read_resp.map(bank => ex_read_from_acc.B && bank.valid && !bank.bits.fromDMA))
  val im2ColValid = io.im2col.resp.valid

  mesh_cntl_signals_q.io.deq.ready := (!cntl.a_fire || mesh.io.a.fire || !mesh.io.a.ready) &&
    (!cntl.b_fire || mesh.io.b.fire || !mesh.io.b.ready) &&
    (!cntl.d_fire || mesh.io.d.fire || !mesh.io.d.ready) &&
    (!cntl.first || mesh.io.req.ready)

  val dataA_valid = cntl.a_garbage || cntl.a_unpadded_cols === 0.U || Mux(cntl.im2colling, im2ColValid, Mux(cntl.a_read_from_acc, accReadValid(cntl.a_bank_acc), readValid(cntl.a_bank)))

  val dataB_valid = cntl.b_garbage || cntl.b_unpadded_cols === 0.U || MuxCase(readValid(cntl.b_bank), Seq(
    cntl.accumulate_zeros -> false.B,
    cntl.b_read_from_acc -> accReadValid(cntl.b_bank_acc)
  ))
  val dataD_valid = cntl.d_garbage || cntl.d_unpadded_cols === 0.U || MuxCase(readValid(cntl.d_bank), Seq(
    cntl.preload_zeros -> false.B,
    cntl.d_read_from_acc -> accReadValid(cntl.d_bank_acc)
  ))

  //added for negative bitshift
  val preload_zero_counter = RegInit(0.U(5.W))
  //val neg_shift_sub = block_size.U - cntl.c_rows
  preload_zero_counter := wrappingAdd(preload_zero_counter, 1.U, block_size.U, dataA_valid && dataD_valid && cntl.preload_zeros && (cntl.perform_single_preload || cntl.perform_mul_pre))

  val dataA_unpadded = Mux(cntl.im2colling, im2ColData, Mux(cntl.a_read_from_acc, accReadData(cntl.a_bank_acc), readData(cntl.a_bank)))
  val dataB_unpadded = MuxCase(readData(cntl.b_bank), Seq(cntl.accumulate_zeros -> 0.U, cntl.b_read_from_acc -> accReadData(cntl.b_bank_acc)))
  val dataD_unpadded = MuxCase(readData(cntl.d_bank), Seq(cntl.preload_zeros -> 0.U, cntl.d_read_from_acc -> accReadData(cntl.d_bank_acc)))

  val dataA = VecInit(dataA_unpadded.asTypeOf(Vec(block_size, inputType)).zipWithIndex.map { case (d, i) => Mux(i.U < cntl.a_unpadded_cols, d, inputType.zero)}.map(d => d.asTypeOf(inputType).withWidthOf(spatialArrayInputType)))
  val dataB = VecInit(dataB_unpadded.asTypeOf(Vec(block_size, inputType)).zipWithIndex.map { case (d, i) => Mux(i.U < cntl.b_unpadded_cols, d, inputType.zero)}.map(d => d.asTypeOf(inputType).withWidthOf(spatialArrayWeightType)))
  val dataD = VecInit(dataD_unpadded.asTypeOf(Vec(block_size, inputType)).zipWithIndex.map { case (d, i) => Mux(i.U < cntl.d_unpadded_cols, d, inputType.zero)}.map(d => d.asTypeOf(inputType).withWidthOf(spatialArrayWeightType)))

  // Pop responses off the scratchpad io ports
  when (mesh_cntl_signals_q.io.deq.fire) {
    when (cntl.a_fire && mesh.io.a.fire && !cntl.a_garbage && cntl.a_unpadded_cols > 0.U && !cntl.im2colling) {
      when (cntl.a_read_from_acc) {
        io.acc.read_resp(cntl.a_bank_acc).ready := !io.acc.read_resp(cntl.a_bank_acc).bits.fromDMA
      }.otherwise {
        io.srams.read(cntl.a_bank).resp.ready := !io.srams.read(cntl.a_bank).resp.bits.fromDMA
      }
    }

    when (cntl.b_fire && mesh.io.b.fire && !cntl.b_garbage && !cntl.accumulate_zeros && cntl.b_unpadded_cols > 0.U) {
      when (cntl.b_read_from_acc) {
        io.acc.read_resp(cntl.b_bank_acc).ready := !io.acc.read_resp(cntl.b_bank_acc).bits.fromDMA
      }.otherwise {
        io.srams.read(cntl.b_bank).resp.ready := !io.srams.read(cntl.b_bank).resp.bits.fromDMA
      }
    }

    when (cntl.d_fire && mesh.io.d.fire && !cntl.d_garbage && !cntl.preload_zeros && cntl.d_unpadded_cols > 0.U) {
      when (cntl.d_read_from_acc) {
        io.acc.read_resp(cntl.d_bank_acc).ready := !io.acc.read_resp(cntl.d_bank_acc).bits.fromDMA
      }.otherwise {
        io.srams.read(cntl.d_bank).resp.ready := !io.srams.read(cntl.d_bank).resp.bits.fromDMA
      }
    }
  }

  if (!ex_read_from_acc) {
    for (acc_r <- io.acc.read_resp) {
      acc_r.ready := true.B
    }
  }

  when (cntl_valid) {
    // Default inputs
    mesh.io.a.valid := cntl.a_fire && dataA_valid
    mesh.io.b.valid := cntl.b_fire && dataB_valid
    mesh.io.d.valid := cntl.d_fire && dataD_valid

    mesh.io.a.bits := dataA.asTypeOf(Vec(meshRows, Vec(tileRows, spatialArrayInputType)))
    mesh.io.b.bits := dataB.asTypeOf(Vec(meshColumns, Vec(tileColumns, spatialArrayWeightType)))
    mesh.io.d.bits := dataD.asTypeOf(Vec(meshColumns, Vec(tileColumns, spatialArrayWeightType)))

    mesh.io.req.valid := mesh_cntl_signals_q.io.deq.fire && (cntl.a_fire || cntl.b_fire || cntl.d_fire)

    mesh.io.req.bits.tag.addr := cntl.c_addr
    mesh.io.req.bits.tag.mx_enabled := cntl.mx_enabled
    mesh.io.req.bits.tag.mx_block := cntl.mx_block
    mesh.io.req.bits.tag.mx_second_half := cntl.mx_second_half

    mesh.io.req.bits.total_rows := cntl.total_rows
  }

  when (cntl_valid && cntl.perform_single_preload) {
    mesh.io.a.bits := Mux(a_should_be_fed_into_transposer, dataA.asUInt, 0.U).asTypeOf(Vec(meshRows, Vec(tileRows, inputType)))
    mesh.io.b.bits := Mux(b_should_be_fed_into_transposer, dataB.asUInt, 0.U).asTypeOf(Vec(meshColumns, Vec(tileColumns, inputType)))
  }

  when (cntl_valid && cntl.perform_single_mul) {
    mesh.io.a.bits := Mux(a_should_be_fed_into_transposer, 0.U, dataA.asUInt).asTypeOf(Vec(meshRows, Vec(tileRows, inputType)))
    mesh.io.b.bits := Mux(b_should_be_fed_into_transposer, 0.U, dataB.asUInt).asTypeOf(Vec(meshColumns, Vec(tileColumns, inputType)))
    mesh.io.req.bits.tag.addr.make_this_garbage()
  }

  // Scratchpad writes
  // val output_counter = new Counter(block_size)
  val output_counter = RegInit(0.U(log2Up(block_size).W))

  // MXINT8 Part B: the mesh output is consumed in the *undelayed* domain. The scale exponents
  // are read one cycle ahead from MXScaleSRAM -- one read per cycle, matching the
  // one-row-per-cycle drain -- so the 1-cycle SyncReadMem latency is hidden without delaying
  // the data. This removes the former DIM*DIM-wide one-cycle output-delay shadow register
  // (`mx_mesh_resp_bits`) entirely; the read keeps exact pace with the drain (no batch gather).
  val mesh_resp = Wire(chiselTypeOf(mesh.io.resp.bits))
  val mesh_resp_valid = Wire(Bool())
  mesh_resp := mesh.io.resp.bits
  mesh_resp_valid := mesh.io.resp.valid

  // Drain-order matmul indices (policy Appendix A): a chained wrapping walk
  // (phase kp fastest, then tile_i, then tile_j, then logical block kb) ticks once per
  // drained MX matmul (one physical K phase). This recovers the (tile, block, phase)
  // association from the strict drain order, since the feed-sampled tag fields drift one
  // phase (the D4 effect). At DIM == mx_block_size kp is constant 0 and the walk is the
  // stock (i fastest, j, k slowest) order; at I_tiles == J_tiles == 1 it degenerates to
  // the verified single-tile phase sequence. The walk is cross-checked against the
  // tag-derived (tile_i, tile_j) at every committed output row (assert below).
  val mx_block_shift = log2Ceil(mx_block_size / DIM)
  val mx_phases = mx_block_size / DIM
  val mx_i_tiles = if (mx_enabled) io.mx.get.i_tiles else 1.U
  val mx_j_tiles = if (mx_enabled) io.mx.get.j_tiles else 1.U
  val mx_log2_jp = if (mx_enabled) io.mx.get.log2_jp else 0.U
  val mx_k_blocks = if (mx_enabled) io.mx.get.k_blocks else 0.U

  val mx_out_kp = if (mx_enabled && mx_phases > 1) RegInit(0.U(mx_block_shift.W)) else 0.U(1.W)
  val mx_out_i = if (mx_enabled) RegInit(0.U(16.W)) else 0.U
  val mx_out_j = if (mx_enabled) RegInit(0.U(16.W)) else 0.U
  val mx_out_kb = if (mx_enabled) RegInit(0.U((mx_scale_addr_bits + 1).W)) else 0.U
  // P3: loop parity toggled at each loop boundary (kb wrap). Selects the scale-SRAM
  // ping-pong half so a fenceless next chunk's scale mvin cannot clobber this chunk's
  // still-draining scales. Stays 0 in legacy (k_blocks == 0) mode.
  val mx_out_parity = if (mx_enabled) RegInit(0.U(1.W)) else 0.U(1.W)

  // Combinational next-state of the walk, used by the one-cycle-ahead scale reads.
  val mx_kp_wrap = if (mx_phases > 1) mx_out_kp === (mx_phases - 1).U else true.B
  val mx_i_wrap = mx_kp_wrap && (mx_out_i + 1.U === mx_i_tiles)
  val mx_j_wrap = mx_i_wrap && (mx_out_j + 1.U === mx_j_tiles)
  // P3: when the loop's K-block count is known (k_blocks != 0), wrap kb to 0 at the loop
  // boundary so the walk self-cycles per loop with no reset signal and the read-ahead lands
  // on the next loop's first scales. k_blocks == 0 keeps the legacy non-wrapping behavior.
  val mx_kb_wrap = (mx_k_blocks =/= 0.U) && mx_j_wrap && (mx_out_kb + 1.U === mx_k_blocks)
  val mx_next_kp = Mux(mx_kp_wrap, 0.U, mx_out_kp + 1.U)
  val mx_next_i = Mux(!mx_kp_wrap, mx_out_i, Mux(mx_i_wrap, 0.U, mx_out_i + 1.U))
  val mx_next_j = Mux(!mx_i_wrap, mx_out_j, Mux(mx_j_wrap, 0.U, mx_out_j + 1.U))
  val mx_next_kb = Mux(mx_kb_wrap, 0.U, mx_out_kb + mx_j_wrap)
  val mx_next_parity = mx_out_parity ^ mx_kb_wrap

  if (mx_enabled) {
    when (mx_reset) {
      if (mx_phases > 1) { mx_out_kp := 0.U }
      mx_out_i := 0.U
      mx_out_j := 0.U
      mx_out_kb := 0.U
      mx_out_parity := 0.U
    } .elsewhen (mesh_resp_valid && mesh_resp.last &&
        mesh_resp.tag.rob_id.valid && mesh_resp.tag.mx_enabled) {
      if (mx_phases > 1) { mx_out_kp := mx_next_kp }
      mx_out_i := mx_next_i
      mx_out_j := mx_next_j
      mx_out_kb := mx_next_kb
      mx_out_parity := mx_next_parity
    }
  }
  val mx_drain_block = mx_out_kb

  // Scales for the currently-draining output: A exponent for this row, B exponent vector for
  // the N output columns (invalid is re-derived as `exp === 128`). Driven by the read-ahead
  // below; consumed by the BlockScaleUnit (a separate `if (mx_enabled)` block).
  val mx_set_a_exp = Wire(SInt(mx_scale_exp_bits.W))
  val mx_set_b_exp = Wire(Vec(DIM, SInt(mx_scale_exp_bits.W)))
  val mx_set_valid = Wire(Bool())
  if (!mx_enabled) {
    mx_set_a_exp := DontCare
    mx_set_b_exp := DontCare
    mx_set_valid := false.B
  }

  if (mx_enabled) {
    val mx = io.mx.get
    val b_scale_base = (mx_scale_sp_entries / 2).U(mx_scale_addr_bits.W)
    val draining = mesh_resp_valid && mesh_resp.tag.rob_id.valid

    // P3 scale ping-pong: each loop-parity owns half of its scale region (A: low/high of
    // [0, entries/2); B: low/high of [entries/2, entries)). The read uses the *next* parity
    // at a loop boundary (mesh_resp.last) to line up with the next loop's first output, same
    // as mx_a_tile uses mx_next_i. parity == 0 (legacy / k_blocks==0) adds 0 -> addresses
    // are bit-for-bit unchanged.
    val mx_rd_parity = Mux(draining && mesh_resp.last, mx_next_parity, mx_out_parity)
    val mx_scale_pp_off = (mx_rd_parity << log2Ceil(mx_scale_sp_entries / 4)).asUInt

    // Read one cycle ahead: address the output that will drain *next* -- the next row within
    // this matmul, or row 0 of the next matmul (with the *next* walk indices) at a matmul
    // boundary, holding the address during bubbles. The SyncReadMem response one cycle later
    // therefore lines up with the next drained output, and one read per cycle keeps exact
    // pace with the drain.
    // A-scale row (Appendix A.2): tile_i*DIM + output_row.
    val mx_a_row = Mux(draining,
      wrappingAdd(output_counter, 1.U, mesh_resp.total_rows), output_counter)
    val mx_a_tile = Mux(draining && mesh_resp.last, mx_next_i, mx_out_i)
    mx.read_a.valid := mx_runtime_enabled
    mx.read_a.bits.addr := (mx_a_tile << log2Ceil(DIM)).asUInt + mx_a_row + mx_scale_pp_off

    // B-scale row (Appendix A.2): b_scale_base + kb*Jp + tile_j.
    val mx_b_row = (mx_out_kb << mx_log2_jp).asUInt + mx_out_j
    val mx_b_row_next = (mx_next_kb << mx_log2_jp).asUInt + mx_next_j
    mx.read_b.valid := mx_runtime_enabled
    mx.read_b.bits.addr := b_scale_base + mx_scale_pp_off +
      Mux(draining && mesh_resp.last, mx_b_row_next, mx_b_row)

    assert(!(mx.read_a.valid && !mx.read_a.ready), "MXScaleSRAM A read port must accept scale vector reads")
    assert(!(mx.read_b.valid && !mx.read_b.ready), "MXScaleSRAM B read port must accept scale vector reads")

    // Consume the responses (one cycle behind the read, so aligned with this drained output).
    // A selects the block's lane; B spans the N output columns.
    val lane = mx_drain_block(log2Ceil(DIM) - 1, 0)
    mx_set_a_exp := mx.resp_a.bits.exp(lane)
    mx_set_b_exp := mx.resp_b.bits.exp
    mx_set_valid := mx.resp_a.valid && mx.resp_b.valid
  }

  val w_total_output_rows = mesh_resp.total_rows

  val w_address = Mux(current_dataflow === Dataflow.WS.id.U, mesh_resp.tag.addr + output_counter * c_addr_stride,
    mesh_resp.tag.addr + (w_total_output_rows - 1.U - output_counter * c_addr_stride))
  val write_to_acc = w_address.is_acc_addr

  val w_bank = Mux(write_to_acc, w_address.acc_bank(), w_address.sp_bank())
  val w_row = Mux(write_to_acc, w_address.acc_row(), w_address.sp_row())

  val is_garbage_addr = mesh_resp.tag.addr.is_garbage()

  val w_matrix_rows = mesh_resp.tag.rows
  val w_matrix_cols = mesh_resp.tag.cols

  val write_this_row = Mux(current_dataflow === Dataflow.WS.id.U, output_counter < w_matrix_rows,
    w_total_output_rows - 1.U - output_counter < w_matrix_rows)
  val w_mask = (0 until block_size).map(_.U < w_matrix_cols) // This is an element-wise mask, rather than a byte-wise mask

  val mx_suppress_acc_write = WireInit(false.B)
  val mx_acc_wdata = Wire(Vec(meshColumns, Vec(tileColumns, accType)))
  mx_acc_wdata := VecInit(mesh_resp.data.map(v => VecInit(v.map(e => e.withWidthOf(accType)))))

  if (mx_enabled) {
    val mx = io.mx.get
    // First-half raw-partial buffer for the DIM<block two-phase path only. It holds one
    // output tile of raw mesh partials at the spatial-array output width (SInt(20) here),
    // not 64 bits: a summed 32-lane block is <= 32*127^2 < 2^20. At DIM == mx_block_size one
    // physical phase is a whole logical block, so the buffer is never read/written and is
    // not instantiated at all.
    val mx_raw_buf = if (DIM < mx_block_size) {
      Some(Reg(Vec(block_size, Vec(meshColumns, Vec(tileColumns,
        SInt(spatialArrayOutputType.getWidth.W))))))
    } else {
      None
    }
    val mx_resp_is_scaled = mesh_resp_valid && mesh_resp.tag.mx_enabled
    // Phase position within the logical block, recovered from the drain-order walk
    // (`mx_out_kp`, mx_block_shift bits). For mx_block_size/DIM = N phases: phase 0 seeds
    // the buffer, phases 1..N-2 accumulate into it (write suppressed), the last phase
    // (all-ones) adds the buffer to the current partial, scales once, and writes the acc.
    val mx_phase_first = if (DIM < mx_block_size) (mx_out_kp === 0.U) else true.B
    val mx_phase_last = if (DIM < mx_block_size) mx_out_kp.andR else true.B
    val mx_buffering = (DIM < mx_block_size).B && mx_resp_is_scaled && !mx_phase_last

    def roundRightNearestEven(value: SInt, shift: UInt): SInt = {
      val width = 64
      val cappedShift = Mux(shift >= (width - 1).U, (width - 1).U, shift)
      val negative = value < 0.S
      val magnitude = Mux(negative, (-value).asUInt, value.asUInt)
      val quotient = magnitude >> cappedShift
      val remainderMask = (1.U(width.W) << cappedShift) - 1.U
      val remainder = magnitude & remainderMask
      val halfway = 1.U(width.W) << (cappedShift - 1.U)
      val roundUp = remainder > halfway || (remainder === halfway && quotient(0))
      val rounded = quotient + roundUp
      val result = Mux(negative, -rounded.asSInt, rounded.asSInt)
      // Mirror the software golden (`mxint8_round_right_shift_nearest_even`): a
      // right shift of >= 63 annihilates the value rather than rounding to +/-1.
      Mux(shift >= (width - 1).U, 0.S(64.W), result)
    }

    def scalePowerOfTwo(value: SInt, shift: SInt): SInt = {
      val width = 64
      val maxV = ((BigInt(1) << (width - 1)) - 1).S(width.W) // INT64_MAX
      val minV = (-(BigInt(1) << (width - 1))).S(width.W)    // INT64_MIN
      val sh = shift.asUInt
      // Left (non-negative) shift: saturate to the int64 range exactly like the
      // software golden (`mxint8_scale_raw_block`), so a large block scale clips
      // instead of wrapping when `value << shift` exceeds 64 bits. Bound the shift
      // amount fed to `<<` so the generated shifter stays log2(64) bits wide.
      val shBounded = Mux(sh >= width.U, (width - 1).U, sh)(log2Ceil(width) - 1, 0)
      val overflow = value > (maxV >> shBounded) || value < (minV >> shBounded)
      val left = Mux(overflow, Mux(value < 0.S, minV, maxV),
        (value << shBounded)(width - 1, 0).asSInt)
      val right = roundRightNearestEven(value, (-shift).asUInt)
      Mux(shift >= 0.S, left, right)
    }

    def saturateToAcc(value: SInt): T = {
      val max = (BigInt(1) << (accType.getWidth - 1)) - 1
      val min = -(BigInt(1) << (accType.getWidth - 1))
      val clipped = Mux(value > max.S(64.W), max.S(64.W),
        Mux(value < min.S(64.W), min.S(64.W), value))
      clipped(accType.getWidth - 1, 0).asTypeOf(accType)
    }

    // Scales come from the prefetch FIFO (gathered in issue order), available
    // combinationally for the undelayed output. A is indexed by this output's row; B spans
    // the N output columns. Invalid is re-derived as `exp === 128` (the rejected 0xff NaN).
    val a_exp = mx_set_a_exp
    val a_invalid = mx_set_a_exp === 128.S(mx_scale_exp_bits.W)
    val b_exp = mx_set_b_exp
    val mx_b_scale_invalid = VecInit(mx_set_b_exp.map(_ === 128.S(mx_scale_exp_bits.W)))
    val mx_b_scale_valid = mx_set_valid

    // Accumulate the running raw-partial sum across the non-final phases, on real,
    // committed output rows only (mirroring the accumulator-write guards below). In
    // weight-stationary mode the mesh also emits non-output cycles tagged mx_enabled (the
    // multiply's garbage-addr output and pipeline bubbles); these have
    // `rob_id.valid=false`, so they do not advance `output_counter` or raise
    // `start_array_outputting`. Without this guard they would re-store the buffer with
    // zero between phases. Phase 0 seeds the buffer with its partial; later non-final
    // phases add their partial to it. The running sum of up to 32 lanes is bounded by
    // 32*127^2 < 2^19, so it fits the spatial-array output width (SInt(20)); the
    // non-widening add stays at that width (the all-+/-128 corner is deviation D3).
    if (DIM < mx_block_size) {
      when (mx_buffering && start_array_outputting && write_this_row) {
        mx_raw_buf.get(output_counter) := VecInit(mesh_resp.data.zipWithIndex.map { case (col, colId) =>
          VecInit(col.zipWithIndex.map { case (elem, tileId) =>
            val raw_current = elem.asUInt.asSInt
            Mux(mx_phase_first, raw_current,
              mx_raw_buf.get(output_counter)(colId)(tileId) + raw_current)
          })
        })
      }
    }

    mx_suppress_acc_write := mx_buffering
    mx_acc_wdata := VecInit(mesh_resp.data.zipWithIndex.map { case (col, colId) =>
      VecInit(col.zipWithIndex.map { case (elem, tileId) =>
        val lane = colId * tileColumns + tileId
        val raw_current = elem.asUInt.asSInt
        // The accumulator is written only on the last phase (others are suppressed), where
        // the buffer holds the sum of phases 0..N-2; add the final phase's partial, then
        // apply the single block scale. At DIM == mx_block_size one phase is the whole
        // block, so there is no buffer to add.
        val raw_block = if (DIM < mx_block_size) {
          mx_raw_buf.get(output_counter)(colId)(tileId) +& raw_current
        } else {
          raw_current
        }
        val scale_shift = (a_exp +& b_exp(lane)).asSInt - (2 * mx_int_frac_bits).S(mx_scale_exp_bits.W)
        saturateToAcc(scalePowerOfTwo(raw_block, scale_shift))
      })
    })

    // Validate the scales only on real, committed output rows (not the WS bubble /
    // garbage-address cycles, which carry stale scale lanes).
    when (mx_resp_is_scaled && !mx_buffering && start_array_outputting && write_this_row) {
      assert(mx_set_valid, "MXINT8 output reached BlockScaleUnit before its prefetched scale set was ready")
      assert(!a_invalid, "MXINT8 A scale vector contains invalid E8M0 scale")
      assert(!mx_b_scale_invalid.asUInt.orR, "MXINT8 B scale vector contains invalid E8M0 scale")
    }

    // Appendix A.1 cross-check: the tag-derived output tile (recovered from the C
    // accumulator row by bit slicing under the padded power-of-two pitch, with the loop
    // unroller's double-buffer base masked off) must agree with the drain-order walk on
    // every committed output row. Only meaningful for true multi-tile loops; raw
    // single-tile intrinsics may carry metadata bits in the address that this slicing
    // does not model.
    val mx_acc_tiles_half = (acc_banks * acc_bank_entries) / (2 * DIM)
    val mx_tag_t = (mesh_resp.tag.addr.acc_row() >> log2Ceil(DIM)).asUInt &
      (mx_acc_tiles_half - 1).U
    val mx_tag_tj = mx_tag_t & ((1.U << mx_log2_jp).asUInt - 1.U)
    val mx_tag_ti = (mx_tag_t >> mx_log2_jp).asUInt
    when (mx_resp_is_scaled && start_array_outputting && write_this_row &&
        (mx_i_tiles > 1.U || mx_j_tiles > 1.U)) {
      assert(mx_tag_ti === mx_out_i && mx_tag_tj === mx_out_j,
        "MXINT8 multi-tile: tag-derived output tile disagrees with the drain-order walk")
    }
  }

  // Write to normal scratchpad
  for(i <- 0 until sp_banks) {
    val activated_wdata = VecInit(mesh_resp.data.map(v => VecInit(v.map { e =>
      val e_clipped = e.clippedToWidthOf(inputType)
      val e_act = MuxCase(e_clipped, Seq(
        (activation === Activation.RELU) -> e_clipped.relu))

      e_act
    })))

    if (ex_write_to_spad) {
      io.srams.write(i).valid := start_array_outputting && w_bank === i.U && !write_to_acc && !is_garbage_addr && write_this_row
      io.srams.write(i).addr := w_row
      io.srams.write(i).data := activated_wdata.asUInt
      io.srams.write(i).mask := w_mask.flatMap(b => Seq.fill(inputType.getWidth / (aligned_to * 8))(b))
    } else {
      io.srams.write(i).valid := false.B
      io.srams.write(i).addr := DontCare
      io.srams.write(i).data := DontCare
      io.srams.write(i).mask := DontCare
    }
  }

  // Write to accumulator
  for (i <- 0 until acc_banks) {
    if (ex_write_to_acc) {
      io.acc.write(i).valid := start_array_outputting && w_bank === i.U && write_to_acc &&
        !is_garbage_addr && write_this_row && !mx_suppress_acc_write
      io.acc.write(i).bits.addr := w_row
      io.acc.write(i).bits.data := Mux(mesh_resp.tag.mx_enabled, mx_acc_wdata,
        VecInit(mesh_resp.data.map(v => VecInit(v.map(e => e.withWidthOf(accType))))))
      io.acc.write(i).bits.acc := w_address.accumulate
      io.acc.write(i).bits.mask := w_mask.flatMap(b => Seq.fill(accType.getWidth / (aligned_to * 8))(b))
    } else {
      io.acc.write(i).valid := false.B
      io.acc.write(i).bits.addr := DontCare
      io.acc.write(i).bits.data := DontCare
      io.acc.write(i).bits.acc := DontCare
      io.acc.write(i).bits.mask := DontCare
    }

    assert(!(io.acc.write(i).valid && !io.acc.write(i).ready), "Execute controller write to AccumulatorMem was skipped")
  }

  // Handle dependencies and turn off outputs for garbage addresses
  val mesh_completed_rob_id_fire = WireInit(false.B)
  //val complete_lock = RegInit(false.B)

  //Seah: added for WS accumulator
  when(mesh_resp_valid && mesh_resp.tag.rob_id.valid) {
    output_counter := wrappingAdd(output_counter, 1.U, w_total_output_rows)
    val last = mesh_resp.last

    when(last) {
      mesh_completed_rob_id_fire := true.B
      io.completed.valid := true.B
      io.completed.bits := mesh_resp.tag.rob_id.bits
    }
    start_array_outputting :=  !is_garbage_addr
  }

  when (!mesh_completed_rob_id_fire) {
    when(pending_completed_rob_ids(0).valid) {
      io.completed.valid := true.B
      io.completed.bits := pending_completed_rob_ids(0).pop()
    }.elsewhen(pending_completed_rob_ids(1).valid) {
      io.completed.valid := true.B
      io.completed.bits := pending_completed_rob_ids(1).pop()
    }
  }
  val complete_bits_count = RegInit(0.U(15.W))
  when(io.completed.valid) {
    complete_bits_count := complete_bits_count + 1.U
  }

  when (reset.asBool) {
    // pending_completed_rob_id.valid := false.B
    pending_completed_rob_ids.foreach(_.valid := false.B)
  }

  // Performance counter
  CounterEventIO.init(io.counter)
  io.counter.connectEventSignal(CounterEvent.EXE_ACTIVE_CYCLE, control_state === compute)
  io.counter.connectEventSignal(CounterEvent.EXE_FLUSH_CYCLE,
    control_state === flushing || control_state === flush)
  io.counter.connectEventSignal(CounterEvent.EXE_CONTROL_Q_BLOCK_CYCLE,
    !mesh_cntl_signals_q.io.enq.ready && mesh_cntl_signals_q.io.enq.valid)
  io.counter.connectEventSignal(CounterEvent.EXE_PRELOAD_HAZ_CYCLE,
    cmd.valid(0) && DoPreloads(0) && cmd.valid(1) && raw_hazard_pre)
  io.counter.connectEventSignal(CounterEvent.EXE_OVERLAP_HAZ_CYCLE,
    cmd.valid(0) && DoPreloads(1) && cmd.valid(1) && DoComputes(0) && cmd.valid(2) && raw_hazard_mulpre)
  io.counter.connectEventSignal(CounterEvent.A_GARBAGE_CYCLES, cntl.a_garbage)
  io.counter.connectEventSignal(CounterEvent.B_GARBAGE_CYCLES, cntl.b_garbage)
  io.counter.connectEventSignal(CounterEvent.D_GARBAGE_CYCLES, cntl.d_garbage)
  io.counter.connectEventSignal(CounterEvent.ACC_A_WAIT_CYCLE,
    !(!cntl.a_fire || mesh.io.a.fire || !mesh.io.a.ready) && cntl.a_read_from_acc && !cntl.im2colling)
  io.counter.connectEventSignal(CounterEvent.ACC_B_WAIT_CYCLE,
    !(!cntl.b_fire || mesh.io.b.fire || !mesh.io.b.ready) && cntl.b_read_from_acc)
  io.counter.connectEventSignal(CounterEvent.ACC_D_WAIT_CYCLE,
    !(!cntl.d_fire || mesh.io.d.fire || !mesh.io.d.ready) && cntl.d_read_from_acc)
  io.counter.connectEventSignal(CounterEvent.SCRATCHPAD_A_WAIT_CYCLE,
    !(!cntl.a_fire || mesh.io.a.fire || !mesh.io.a.ready) && !cntl.a_read_from_acc && !cntl.im2colling)
  io.counter.connectEventSignal(CounterEvent.SCRATCHPAD_B_WAIT_CYCLE,
    !(!cntl.b_fire || mesh.io.b.fire || !mesh.io.b.ready) && !cntl.b_read_from_acc)
  io.counter.connectEventSignal(CounterEvent.SCRATCHPAD_D_WAIT_CYCLE,
    !(!cntl.d_fire || mesh.io.d.fire || !mesh.io.d.ready) && !cntl.d_read_from_acc)

  // MX serialization diagnostics (observation-only; partition the ~86% mesh-feed stall).
  io.counter.connectEventSignal(CounterEvent.MX_DBG_WAIT_CMD_CYCLE, control_state === waiting_for_cmd)

  // Phase-0 gate partition: split the waiting_for_cmd time and observe mesh tag occupancy.
  // (Codes 48/49/50/54 repurposed for the RS-side DAE-inversion partition; wired in
  // ReservationStation.scala. EX_POOL_EMPTY=54 lives there too.)
  io.counter.connectEventSignal(CounterEvent.MX_DBG_NO_CMD_CYCLE,
    (control_state === waiting_for_cmd) && !cmd.valid(0))
  io.counter.connectEventSignal(CounterEvent.MX_DBG_CMD_BLOCKED_CYCLE,
    (control_state === waiting_for_cmd) && cmd.valid(0))
  io.counter.connectEventSignal(CounterEvent.MX_DBG_MATMUL_IN_PROGRESS_CYCLE, matmul_in_progress)

  if (use_firesim_simulation_counters) {
    val ex_flush_cycle = control_state === flushing || control_state === flush
    val ex_preload_haz_cycle = cmd.valid(0) && DoPreloads(0) && cmd.valid(1) && raw_hazard_pre
    val ex_mulpre_haz_cycle = cmd.valid(0) && DoPreloads(1) && cmd.valid(1) && DoComputes(0) && cmd.valid(2) && raw_hazard_mulpre

    PerfCounter(ex_flush_cycle, "ex_flush_cycle", "cycles during which the ex controller is flushing the spatial array")
    PerfCounter(ex_preload_haz_cycle, "ex_preload_haz_cycle", "cycles during which the execute controller is stalling preloads due to hazards")
    PerfCounter(ex_mulpre_haz_cycle, "ex_mulpre_haz_cycle", "cycles during which the execute controller is stalling matmuls due to hazards")
  }
}
