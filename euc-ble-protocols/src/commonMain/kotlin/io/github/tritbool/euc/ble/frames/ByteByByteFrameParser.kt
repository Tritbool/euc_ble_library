/*
 *
 *  Copyright (C) 2026 Gauthier Le Bartz Lyan (Tritbool)
 *  euc_ble_library — version 1.2.0 — licence GPL-3.0
 *  https://github.com/Tritbool/euc_ble_library
 * /
 */

package io.github.tritbool.euc.ble.frames

/**
 * Parser that calls a functional unpacker with each byte.
 * The unpacker must maintain its internal state and return 0..N complete frames
 * on each feed(byte) call.
 */
class ByteByByteFrameParser(
    private val unpacker: (b: Byte) -> List<ByteArray>,
    private val resetUnpacker: (() -> Unit)? = null
) : FrameParser {
    override fun appendAndExtract(data: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        for (b in data) {
            val frames = unpacker(b)
            if (frames.isNotEmpty()) out.addAll(frames)
        }
        return out
    }

    override fun reset() {
        resetUnpacker?.invoke()
    }
}
