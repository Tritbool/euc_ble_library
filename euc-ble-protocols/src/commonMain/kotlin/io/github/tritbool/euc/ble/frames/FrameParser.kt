/*
 *
 *  Copyright (C) 2026 Gauthier Le Bartz Lyan (Tritbool)
 *  euc_ble_library — version 1.2.0 — licence GPL-3.0
 *  https://github.com/Tritbool/euc_ble_library
 * /
 */

package io.github.tritbool.euc.ble.frames

interface FrameParser {
    /**
     * Append incoming bytes and return a list of complete frames extracted.
     */
    fun appendAndExtract(data: ByteArray): List<ByteArray>

    /** Clear internal state. */
    fun reset()
}