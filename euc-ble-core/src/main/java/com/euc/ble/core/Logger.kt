package com.euc.ble.core

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
 * Default logger implementation using Android logging
 */
class AndroidLogger : Logger {
    override fun verbose(tag: String, message: String) {
        android.util.Log.v(tag, message)
    }

    override fun debug(tag: String, message: String) {
        android.util.Log.d(tag, message)
    }

    override fun info(tag: String, message: String) {
        android.util.Log.i(tag, message)
    }

    override fun warn(tag: String, message: String) {
        android.util.Log.w(tag, message)
    }

    override fun error(tag: String, message: String) {
        android.util.Log.e(tag, message)
    }

    override fun error(tag: String, message: String, throwable: Throwable) {
        android.util.Log.e(tag, message, throwable)
    }
}

/**
 * No-op logger for testing or environments without logging
 */
class NoOpLogger : Logger {
    override fun verbose(tag: String, message: String) {}
    override fun debug(tag: String, message: String) {}
    override fun info(tag: String, message: String) {}
    override fun warn(tag: String, message: String) {}
    override fun error(tag: String, message: String) {}
    override fun error(tag: String, message: String, throwable: Throwable) {}
}