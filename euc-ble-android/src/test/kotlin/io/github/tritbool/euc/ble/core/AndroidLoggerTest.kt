/*
 *
 *  Copyright (C) 2026 Gauthier Le Bartz Lyan (Tritbool)
 *  euc_ble_library — version 1.2.0 — licence GPL-3.0
 *  https://github.com/Tritbool/euc_ble_library
 * /
 */

package io.github.tritbool.euc.ble.core

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test

class AndroidLoggerTest {
    @Test
    fun androidLoggerSupportsEachLogLevel() {
        val logger = AndroidLogger()
        val throwable = IllegalStateException("failure")

        assertDoesNotThrow {
            logger.verbose("test", "verbose")
            logger.debug("test", "debug")
            logger.info("test", "info")
            logger.warn("test", "warning")
            logger.error("test", "error")
            logger.error("test", "error with cause", throwable)
        }
    }

}
