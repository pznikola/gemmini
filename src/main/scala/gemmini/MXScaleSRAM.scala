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

class MXScaleSRAMReadResp(lanes: Int, scaleBits: Int, expBits: Int) extends Bundle {
  val raw = Vec(lanes, UInt(scaleBits.W))
  val exp = Vec(lanes, SInt(expBits.W))
  val invalid = Vec(lanes, Bool())
}

class MXScaleSRAMIO(entries: Int, lanes: Int, scaleBits: Int, expBits: Int) extends Bundle {
  val write = Flipped(Decoupled(new MXScaleSRAMWriteReq(entries, lanes, scaleBits)))
  val read_a = Flipped(Decoupled(new MXScaleSRAMReadReq(entries)))
  val read_b = Flipped(Decoupled(new MXScaleSRAMReadReq(entries)))
  val resp_a = Valid(new MXScaleSRAMReadResp(lanes, scaleBits, expBits))
  val resp_b = Valid(new MXScaleSRAMReadResp(lanes, scaleBits, expBits))
  val busy = Output(Bool())
}

class MXScaleSRAM[T <: Data : Arithmetic, U <: Data, V <: Data](config: GemminiArrayConfig[T, U, V]) extends Module {
  private val entries = config.mx_scale_sp_entries
  private val lanes = config.DIM
  private val scaleBits = config.mx_scale_bits
  private val expBits = config.mx_scale_exp_bits

  val io = IO(new MXScaleSRAMIO(entries, lanes, scaleBits, expBits))

  private val rawA = SyncReadMem(entries, Vec(lanes, UInt(scaleBits.W)))
  private val rawB = SyncReadMem(entries, Vec(lanes, UInt(scaleBits.W)))
  private val expA = SyncReadMem(entries, Vec(lanes, SInt(expBits.W)))
  private val expB = SyncReadMem(entries, Vec(lanes, SInt(expBits.W)))
  private val invalidA = SyncReadMem(entries, Vec(lanes, Bool()))
  private val invalidB = SyncReadMem(entries, Vec(lanes, Bool()))

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
      rawB.write(io.write.bits.addr, io.write.bits.bytes, io.write.bits.mask)
      expB.write(io.write.bits.addr, decoded, io.write.bits.mask)
      invalidB.write(io.write.bits.addr, invalid, io.write.bits.mask)
    } .otherwise {
      rawA.write(io.write.bits.addr, io.write.bits.bytes, io.write.bits.mask)
      expA.write(io.write.bits.addr, decoded, io.write.bits.mask)
      invalidA.write(io.write.bits.addr, invalid, io.write.bits.mask)
    }
  }

  io.read_a.ready := true.B
  io.read_b.ready := true.B

  val readAFire = io.read_a.fire
  val readBFire = io.read_b.fire

  io.resp_a.valid := RegNext(readAFire, false.B)
  io.resp_a.bits.raw := rawA.read(io.read_a.bits.addr, readAFire)
  io.resp_a.bits.exp := expA.read(io.read_a.bits.addr, readAFire)
  io.resp_a.bits.invalid := invalidA.read(io.read_a.bits.addr, readAFire)

  io.resp_b.valid := RegNext(readBFire, false.B)
  io.resp_b.bits.raw := rawB.read(io.read_b.bits.addr, readBFire)
  io.resp_b.bits.exp := expB.read(io.read_b.bits.addr, readBFire)
  io.resp_b.bits.invalid := invalidB.read(io.read_b.bits.addr, readBFire)

  io.busy := false.B
}
