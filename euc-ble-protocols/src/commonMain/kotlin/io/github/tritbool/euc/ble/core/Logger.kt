/*
 *
 *  * Copyright (C) ${YEAR} Gauthier Le Bartz Lyan (Tritbool)
 *  * euc_ble_library — version 1.1.0 — licence GPL-3.0
 *  * https://github.com/Tritbool/euc_ble_library
 *
 */

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