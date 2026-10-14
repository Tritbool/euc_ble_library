package io.github.tritbool.euc.ble.core

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test

class LoggerTest {
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

    @Test
    fun noOpLoggerAcceptsEachLogLevelWithoutSideEffectsOrFailure() {
        val logger = NoOpLogger()
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
