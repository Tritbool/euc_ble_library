package com.euc.ble.frames

import app.cash.turbine.test
import com.euc.ble.protocols.GotwayProtocol.Companion.FOOTER
import com.euc.ble.protocols.GotwayProtocol.Companion.FRAME_SIZE
import com.euc.ble.protocols.GotwayProtocol.Companion.HEADER
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class GotwayFrameReassemblerTest {

    private val testDispatcher = StandardTestDispatcher()
    private val frameParser = FixedSizeFrameParser(FRAME_SIZE, HEADER, FOOTER)
    private lateinit var frameReassembler: FrameReassembler

    @OptIn(ExperimentalCoroutinesApi::class)
    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        frameReassembler = FrameReassembler(frameParser)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        frameReassembler.reset()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun should_emit_a_complete_frame() = runTest {
        val frame = byteArrayOf(
            0x55.toByte(),
            0xAA.toByte(),
            0x17.toByte(),
            0x2F.toByte(),
            0x00.toByte(),
            0x00.toByte(),
            0x00.toByte(),
            0x89.toByte(),
            0x30.toByte(),
            0x16.toByte(),
            0xFA.toByte(),
            0xCE.toByte(),
            0xE8.toByte(),
            0x42.toByte(),
            0x00.toByte(),
            0x88.toByte(),
            0x00.toByte(),
            0x06.toByte(),
            0x00.toByte(),
            0x18.toByte(),
            0x5A.toByte(),
            0x5A.toByte(),
            0x5A.toByte(),
            0x5A.toByte()
        )

        frameReassembler.observeFrames().test(timeout = 5.seconds) {
            frameReassembler.processIncomingBytes(frame)

            Assertions.assertArrayEquals(frame, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun should_handle_multiple_consecutive_frames() = runTest {
        val frame1 = byteArrayOf(
            0x55.toByte(),
            0xAA.toByte(),
            0x17.toByte(),
            0x2F.toByte(),
            0x00.toByte(),
            0x00.toByte(),
            0x00.toByte(),
            0x89.toByte(),
            0x30.toByte(),
            0x16.toByte(),
            0xFA.toByte(),
            0xCE.toByte(),
            0xE8.toByte(),
            0x42.toByte(),
            0x00.toByte(),
            0x88.toByte(),
            0x00.toByte(),
            0x06.toByte(),
            0x00.toByte(),
            0x18.toByte(),
            0x5A.toByte(),
            0x5A.toByte(),
            0x5A.toByte(),
            0x5A.toByte()
        )
        val frame2 = byteArrayOf(
            0x55.toByte(),
            0xAA.toByte(),
            0x17.toByte(),
            0x2F.toByte(),
            0x00.toByte(),
            0x00.toByte(),
            0x00.toByte(),
            0x89.toByte(),
            0x30.toByte(),
            0x16.toByte(),
            0xFA.toByte(),
            0xCE.toByte(),
            0xE8.toByte(),
            0x42.toByte(),
            0x00.toByte(),
            0x88.toByte(),
            0x00.toByte(),
            0x06.toByte(),
            0x00.toByte(),
            0x18.toByte(),
            0x5A.toByte(),
            0x5A.toByte(),
            0x5A.toByte(),
            0x5A.toByte()
        )
        val data = frame1 + frame2

        frameReassembler.observeFrames().test(timeout = 5.seconds) {
            frameReassembler.processIncomingBytes(data)

            Assertions.assertArrayEquals(frame1, awaitItem())
            Assertions.assertArrayEquals(frame2, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun should_handle_garbage_between_frames() = runTest {
        val frame1 = byteArrayOf(
            0x55.toByte(),
            0xAA.toByte(),
            0x17.toByte(),
            0x2F.toByte(),
            0x00.toByte(),
            0x00.toByte(),
            0x00.toByte(),
            0x89.toByte(),
            0x30.toByte(),
            0x16.toByte(),
            0xFA.toByte(),
            0xCE.toByte(),
            0xE8.toByte(),
            0x42.toByte(),
            0x00.toByte(),
            0x88.toByte(),
            0x00.toByte(),
            0x06.toByte(),
            0x00.toByte(),
            0x18.toByte(),
            0x5A.toByte(),
            0x5A.toByte(),
            0x5A.toByte(),
            0x5A.toByte()
        )
        val garbage = byteArrayOf(0x12.toByte(), 0x34.toByte(), 0x56.toByte())
        val frame2 = byteArrayOf(
            0x55.toByte(),
            0xAA.toByte(),
            0x17.toByte(),
            0x2F.toByte(),
            0x00.toByte(),
            0x00.toByte(),
            0x00.toByte(),
            0x89.toByte(),
            0x30.toByte(),
            0x16.toByte(),
            0xFA.toByte(),
            0xCE.toByte(),
            0xE8.toByte(),
            0x42.toByte(),
            0x00.toByte(),
            0x88.toByte(),
            0x00.toByte(),
            0x06.toByte(),
            0x00.toByte(),
            0x18.toByte(),
            0x5A.toByte(),
            0x5A.toByte(),
            0x5A.toByte(),
            0x5A.toByte()
        )
        val data = frame1 + garbage + frame2

        frameReassembler.observeFrames().test(timeout = 5.seconds) {
            frameReassembler.processIncomingBytes(data)

            Assertions.assertArrayEquals(frame1, awaitItem())
            Assertions.assertArrayEquals(frame2, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun should_handle_fragmented_frame() = runTest {
        val frame = byteArrayOf(
            0x55.toByte(),
            0xAA.toByte(),
            0x17.toByte(),
            0x2F.toByte(),
            0x00.toByte(),
            0x00.toByte(),
            0x00.toByte(),
            0x89.toByte(),
            0x30.toByte(),
            0x16.toByte(),
            0xFA.toByte(),
            0xCE.toByte(),
            0xE8.toByte(),
            0x42.toByte(),
            0x00.toByte(),
            0x88.toByte(),
            0x00.toByte(),
            0x06.toByte(),
            0x00.toByte(),
            0x18.toByte(),
            0x5A.toByte(),
            0x5A.toByte(),
            0x5A.toByte(),
            0x5A.toByte()
        )

        val chunk1 = frame.copyOfRange(0, 10)
        val chunk2 = frame.copyOfRange(10, 24)

        frameReassembler.observeFrames().test(timeout = 5.seconds) {
            frameReassembler.processIncomingBytes(chunk1)
            frameReassembler.processIncomingBytes(chunk2)

            Assertions.assertArrayEquals(frame, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }
}