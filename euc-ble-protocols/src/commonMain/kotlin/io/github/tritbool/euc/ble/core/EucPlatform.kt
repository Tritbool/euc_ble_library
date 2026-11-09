package io.github.tritbool.euc.ble.core

/**
 * Local wall-clock date and time, as needed by wheel commands that embed the current time
 * (for example the LeaperKim lock frame).
 */
data class EucDateTime(
    val year: Int,
    /** 1..12 */
    val month: Int,
    /** 1..31 */
    val dayOfMonth: Int,
    /** 0..23 */
    val hour: Int,
    /** 0..59 */
    val minute: Int,
    /** 0..59 */
    val second: Int,
)

/**
 * Time source injected into protocols, so that decoding stays deterministic in tests and portable
 * across Kotlin targets without depending on a particular platform clock.
 */
interface EucClock {
    /** Wall-clock time in milliseconds since the Unix epoch. */
    fun currentTimeMillis(): Long

    /** Local wall-clock date and time. */
    fun localDateTime(): EucDateTime
}

/** Randomness source injected into protocols that need it (for example KingSong unlock tokens). */
fun interface EucRandom {
    /** Returns a uniformly distributed value in `0 until bound`. */
    fun nextInt(bound: Int): Int
}
