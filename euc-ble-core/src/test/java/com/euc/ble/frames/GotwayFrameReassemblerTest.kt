package com.euc.ble.frames

import com.euc.ble.protocols.GotwayProtocol
import com.euc.ble.protocols.GotwayProtocol.Companion.FOOTER
import com.euc.ble.protocols.GotwayProtocol.Companion.FRAME_SIZE
import com.euc.ble.protocols.GotwayProtocol.Companion.HEADER
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GotwayFrameReassemblerTest {

    private val testDispatcher = StandardTestDispatcher()
    private val frameParser= FixedSizeFrameParser(FRAME_SIZE, HEADER, FOOTER)
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
        frameReassembler.reset() // Clear buffer and state after each test
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

        val result = async {
            frameReassembler.observeFrames().first()
        }
        runCurrent()
        frameReassembler.processIncomingBytes(frame)
        advanceUntilIdle()
        Assertions.assertArrayEquals(frame, result.await())
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

        val results = mutableListOf<ByteArray>()

        val job = launch {
            frameReassembler.observeFrames().toList(results)
        }
        runCurrent()
        frameReassembler.processIncomingBytes(data)
        advanceUntilIdle()

        Assertions.assertEquals(2, results.size)
        Assertions.assertArrayEquals(frame1, results[0])
        Assertions.assertArrayEquals(frame2, results[1])

        job.cancel()
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

        val results = mutableListOf<ByteArray>()

        val job = launch {
            frameReassembler.observeFrames().toList(results)
        }
        runCurrent()
        frameReassembler.processIncomingBytes(data)
        advanceUntilIdle()

        Assertions.assertEquals(2, results.size)
        Assertions.assertArrayEquals(frame1, results[0])
        Assertions.assertArrayEquals(frame2, results[1])

        job.cancel()
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

        val result = async {
            frameReassembler.observeFrames().first()
        }
        runCurrent()

        frameReassembler.processIncomingBytes(chunk1)
        advanceUntilIdle() // Ensure the first chunk is processed

        frameReassembler.processIncomingBytes(chunk2)
        advanceUntilIdle() // Ensure the second chunk is processed

        Assertions.assertArrayEquals(frame, result.await())
    }
}