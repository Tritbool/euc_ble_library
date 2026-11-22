/*
 *
 *  Copyright (C) 2026 Gauthier Le Bartz Lyan (Tritbool)
 *  euc_ble_library — version 1.2.0 — licence GPL-3.0
 *  https://github.com/Tritbool/euc_ble_library
 * /
 */

package io.github.tritbool.euc.ble.core

import kotlin.random.Random
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Bounded randomness from UUID v4 entropy on supported JVM and Apple targets. */
@OptIn(ExperimentalUuidApi::class)
class SecureEucRandom internal constructor(uuidSource: () -> Uuid) : EucRandom {
    constructor() : this({ Uuid.random() })

    private val random = object : Random() {
        override fun nextBits(bitCount: Int): Int {
            if (bitCount == 0) return 0
            // The first 32 bits do not include the fixed UUID version or variant bits.
            return uuidSource().toLongs { mostSignificantBits, _ ->
                (mostSignificantBits ushr 32).toInt().ushr(32 - bitCount)
            }
        }
    }

    override fun nextInt(bound: Int): Int = random.nextInt(bound)
}
