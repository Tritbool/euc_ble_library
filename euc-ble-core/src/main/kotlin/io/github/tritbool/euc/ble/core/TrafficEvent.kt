package io.github.tritbool.euc.ble.core

import java.util.UUID

enum class TrafficDirection {
    RX, TX
}

/**
 * One BLE notification chunk (RX) or write accepted by Android (TX), without reassembly.
 * TX does not confirm delivery to, or execution by, the wheel.
 *
 * [sequence] starts at 1 and increases across both directions for the lifetime of a BLEManager,
 * including reconnects and periods without collectors. Gaps between collected events indicate
 * missed traffic. [timestampMs] is capture time in Unix epoch milliseconds; use [sequence] for
 * library observation order, since the wall clock can change. This is not an over-the-air trace.
 *
 * [bytes] is a defensive snapshot shared with all traffic and raw-frame collectors.
 * Treat it as read-only; copy it before modifying it.
 */
data class TrafficEvent(
    val sequence: Long,
    val timestampMs: Long,
    val direction: TrafficDirection,
    val characteristicUuid: UUID,
    val bytes: ByteArray
)
