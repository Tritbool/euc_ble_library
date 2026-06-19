package io.github.tritbool.euc.ble.frames

/**
 * Parser qui appelle un *unpacker* fonctionnel avec chaque octet.
 * L'unpacker doit conserver son état interne et renvoyer 0..N trames complètes
 * à chaque appel de feed(byte).
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
