package com.euc.ble.frames

import java.io.ByteArrayOutputStream

class InmotionV2Unpacker {

    private enum class UnpackerState {
        unknown,
        flagsearch,
        lensearch,
        collecting,
        done
    }

    private var buffer = ByteArrayOutputStream()
    private var oldc: Byte = 0
    private var len: Int = 0
    private var flags: Int = 0
    private var state = UnpackerState.unknown

    fun reset() {
        buffer = ByteArrayOutputStream()
        oldc = 0
        len = 0
        flags = 0
        state = UnpackerState.unknown
    }

    /**
     * Feed un octet et retourne 0..N trames complètes détectées.
     * Comportement calqué sur InmotionUnpackerV2.addChar(int c).
     */
    fun feed(b: Byte): List<ByteArray> {
        val out = mutableListOf<ByteArray>()

        // équivalent à: if (c != (byte)0xA5 || oldc == (byte)0xA5)
        if (b != 0xA5.toByte() || oldc == 0xA5.toByte()) {
            when (state) {
                UnpackerState.collecting -> {
                    buffer.write(b.toInt() and 0xFF)
                    // émission lorsque buffer.size() == len + 5
                    if (buffer.size() == len + 5) {
                        state = UnpackerState.done
                        oldc = 0
                        out.add(buffer.toByteArray())
                        reset()
                    }
                }
                UnpackerState.lensearch -> {
                    buffer.write(b.toInt() and 0xFF)
                    len = b.toInt() and 0xFF
                    state = UnpackerState.collecting
                    oldc = b
                }
                UnpackerState.flagsearch -> {
                    buffer.write(b.toInt() and 0xFF)
                    flags = b.toInt() and 0xFF
                    state = UnpackerState.lensearch
                    oldc = b
                }
                else -> {
                    // recherche du header 0xAA 0xAA
                    if (b == 0xAA.toByte() && oldc == 0xAA.toByte()) {
                        buffer = ByteArrayOutputStream()
                        buffer.write(0xAA)
                        buffer.write(0xAA)
                        state = UnpackerState.flagsearch
                    }
                    oldc = b
                }
            }
        } else {
            // cas b == 0xA5 && oldc != 0xA5 : on ne fait que mettre oldc
            oldc = b
        }

        return out
    }

    fun getBuffer(): ByteArray = buffer.toByteArray()
}
