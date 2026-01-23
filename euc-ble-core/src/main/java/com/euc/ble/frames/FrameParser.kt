package com.euc.ble.frames

interface FrameParser {
    /**
     * Append incoming bytes and return a list of complete frames extracted.
     */
    fun appendAndExtract(data: ByteArray): List<ByteArray>

    /** Clear internal state. */
    fun reset()
}