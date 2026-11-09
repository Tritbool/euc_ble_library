package io.github.tritbool.euc.ble.core

/**
 * Simple logging interface to avoid external dependencies
 */
interface Logger {
    fun verbose(tag: String, message: String)
    fun debug(tag: String, message: String)
    fun info(tag: String, message: String)
    fun warn(tag: String, message: String)
    fun error(tag: String, message: String)
    fun error(tag: String, message: String, throwable: Throwable)
}

/**
 * No-op logger for testing or environments without logging
 */
class NoOpLogger : Logger {
    override fun verbose(tag: String, message: String) {/*empty*/}/*empty*/
    override fun debug(tag: String, message: String) {/*empty*/}
    override fun info(tag: String, message: String) {/*empty*/}
    override fun warn(tag: String, message: String) {/*empty*/}
    override fun error(tag: String, message: String) {/*empty*/}
    override fun error(tag: String, message: String, throwable: Throwable) {/*empty*/}
}