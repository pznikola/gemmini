package gemmini

import chisel3._
import chisel3.util._
import freechips.rocketchip.rocket.MStatus
import freechips.rocketchip.tile.CoreBundle
import org.chipsalliance.cde.config.Parameters

class MXScaleMemReadRequest(entries: Int)(implicit p: Parameters) extends CoreBundle {
  val vaddr = UInt(coreMaxAddrBits.W)
  val laddr = UInt(log2Up(entries).W)
  val rows = UInt(16.W)
  val cols = UInt(16.W)
  val stride = UInt(coreMaxAddrBits.W)
  val is_b = Bool()
  val cmd_id = UInt(8.W)
  val status = new MStatus
}

class MXScaleMemReadResponse extends Bundle {
  val bytesRead = UInt(16.W)
  val cmd_id = UInt(8.W)
}

class MXScaleReadMemIO(entries: Int)(implicit p: Parameters) extends CoreBundle {
  val req = Decoupled(new MXScaleMemReadRequest(entries))
  val resp = Flipped(Valid(new MXScaleMemReadResponse))
}

class MXScaleSRAMWriteReq(entries: Int, lanes: Int, scaleBits: Int) extends Bundle {
  val is_b = Bool()
  val addr = UInt(log2Up(entries).W)
  val bytes = Vec(lanes, UInt(scaleBits.W))
  val mask = Vec(lanes, Bool())
}

class MXScaleSRAMReadReq(entries: Int) extends Bundle {
  val addr = UInt(log2Up(entries).W)
}

class MXScaleSRAMReadResp(lanes: Int, expBits: Int) extends Bundle {
  val exp = Vec(lanes, SInt(expBits.W))
  val invalid = Vec(lanes, Bool())
}

class MXScaleSRAMIO(entries: Int, lanes: Int, scaleBits: Int, expBits: Int) extends Bundle {
  val write = Flipped(Decoupled(new MXScaleSRAMWriteReq(entries, lanes, scaleBits)))
  val read_a = Flipped(Decoupled(new MXScaleSRAMReadReq(entries)))
  val read_b = Flipped(Decoupled(new MXScaleSRAMReadReq(entries)))
  val resp_a = Valid(new MXScaleSRAMReadResp(lanes, expBits))
  val resp_b = Valid(new MXScaleSRAMReadResp(lanes, expBits))
  val busy = Output(Bool())
}

class MXScaleSRAM[T <: Data : Arithmetic, U <: Data, V <: Data](config: GemminiArrayConfig[T, U, V]) extends Module {
  private val entries = config.mx_scale_sp_entries
  private val lanes = config.DIM
  private val scaleBits = config.mx_scale_bits
  private val expBits = config.mx_scale_exp_bits

  val io = IO(new MXScaleSRAMIO(entries, lanes, scaleBits, expBits))

  // Only the predecoded E8M0 exponents are stored. The raw scale bytes are never
  // read back by any consumer, and the per-lane invalid flag is fully derivable
  // from the exponent (`exp === 128` iff the byte was the rejected 0xff NaN), so
  // neither needs its own SyncReadMem. This keeps the sidecar metadata SRAM to two
  // memories instead of six.
  private val expA = SyncReadMem(entries, Vec(lanes, SInt(expBits.W)))
  private val expB = SyncReadMem(entries, Vec(lanes, SInt(expBits.W)))

  private def decodeE8M0(byte: UInt): SInt = {
    (Cat(0.U(1.W), byte).asSInt - 127.S(expBits.W)).asSInt
  }

  val decoded = VecInit(io.write.bits.bytes.map(decodeE8M0))
  val invalidScaleByte = ((BigInt(1) << scaleBits) - 1).U(scaleBits.W)
  val invalid = VecInit(io.write.bits.bytes.map(_ === invalidScaleByte))
  val maskedInvalid = (io.write.bits.mask zip invalid).map { case (m, inv) => m && inv }.reduce(_ || _)

  io.write.ready := true.B
  when (io.write.fire) {
    assert(!maskedInvalid, "MXINT8 v1 rejects E8M0 NaN scale byte 0xff")
    when (io.write.bits.is_b) {
      expB.write(io.write.bits.addr, decoded, io.write.bits.mask)
    } .otherwise {
      expA.write(io.write.bits.addr, decoded, io.write.bits.mask)
    }
  }

  io.read_a.ready := true.B
  io.read_b.ready := true.B

  val readAFire = io.read_a.fire
  val readBFire = io.read_b.fire

  // E8M0 0xff decodes to 255 - 127 = 128; no valid byte (0..254) reaches it, so the
  // invalid flag is exactly `exp === 128` recomputed from the exponent read.
  private val invalidExp = 128.S(expBits.W)

  val expReadA = expA.read(io.read_a.bits.addr, readAFire)
  io.resp_a.valid := RegNext(readAFire, false.B)
  io.resp_a.bits.exp := expReadA
  io.resp_a.bits.invalid := VecInit(expReadA.map(_ === invalidExp))

  val expReadB = expB.read(io.read_b.bits.addr, readBFire)
  io.resp_b.valid := RegNext(readBFire, false.B)
  io.resp_b.bits.exp := expReadB
  io.resp_b.bits.invalid := VecInit(expReadB.map(_ === invalidExp))

  io.busy := false.B
}
