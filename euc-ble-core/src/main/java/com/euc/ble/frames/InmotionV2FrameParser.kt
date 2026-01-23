package com.euc.ble.frames

class InmotionV2FrameParser {

    private val unpacker = InmotionV2Unpacker()

    private fun feedByte(b: Byte): List<ByteArray> = unpacker.feed(b)

    fun createReassembler(): FrameReassembler {
        val parser = ByteByByteFrameParser(::feedByte) { unpacker.reset() }
        return FrameReassembler(parser)
    }
}