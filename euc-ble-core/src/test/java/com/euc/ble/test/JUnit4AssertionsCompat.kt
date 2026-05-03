package com.euc.ble.test

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Assertions

object JUnit4AssertionsCompat {
    fun assertTrue(condition: Boolean) = Assertions.assertTrue(condition)
    fun assertTrue(message: String, condition: Boolean) = Assertions.assertTrue(condition, message)

    fun assertFalse(condition: Boolean) = Assertions.assertFalse(condition)
    fun assertFalse(message: String, condition: Boolean) = Assertions.assertFalse(condition, message)

    fun assertNull(actual: Any?) = Assertions.assertNull(actual)
    fun assertNull(message: String, actual: Any?) = Assertions.assertNull(actual, message)

    fun assertNotNull(actual: Any?) = Assertions.assertNotNull(actual)
    fun assertNotNull(message: String, actual: Any?) = Assertions.assertNotNull(actual, message)

    fun assertEquals(expected: Any?, actual: Any?) = Assertions.assertEquals(expected, actual)
    fun assertEquals(message: String, expected: Any?, actual: Any?) = Assertions.assertEquals(expected, actual, message)
    fun assertEquals(expected: Double, actual: Double, delta: Double) = Assertions.assertEquals(expected, actual, delta)
    fun assertEquals(message: String, expected: Double, actual: Double, delta: Double) =
        Assertions.assertEquals(expected, actual, delta, message)
    fun assertEquals(expected: Float, actual: Float, delta: Float) = Assertions.assertEquals(expected, actual, delta)
    fun assertEquals(message: String, expected: Float, actual: Float, delta: Float) =
        Assertions.assertEquals(expected, actual, delta, message)

    fun assertArrayEquals(expected: ByteArray, actual: ByteArray) = Assertions.assertArrayEquals(expected, actual)
    fun assertArrayEquals(message: String, expected: ByteArray, actual: ByteArray) =
        Assertions.assertArrayEquals(expected, actual, message)

    fun fail(message: String): Nothing = Assertions.fail(message)

    fun assumeTrue(assumption: Boolean) = Assumptions.assumeTrue(assumption)
    fun assumeTrue(message: String, assumption: Boolean) = Assumptions.assumeTrue(assumption, message)
}
