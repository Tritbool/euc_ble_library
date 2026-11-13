package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.core.EucClock
import io.github.tritbool.euc.ble.core.SystemEucClock
import io.github.tritbool.euc.ble.session.ProtocolSessionDriver

/** Coroutine-compatible facade for the synchronous Ninebot state machine. */
class NinebotProtocol private constructor(
    private val engine: NinebotSyncProtocol,
    private val adapter: ProtocolSessionDriver
) : EUCProtocol by adapter {
    constructor(clock: EucClock = SystemEucClock) : this(NinebotSyncProtocol(clock))
    private constructor(engine: NinebotSyncProtocol) : this(engine, ProtocolSessionDriver(engine))

    data class ZSettingsSnapshot(
        val bleVersion: String? = null,
        val authKeyHex: String? = null,
        val authKeyAscii: String? = null,
        val lockState: Int? = null,
        val limitedModeEnabled: Boolean? = null,
        val speedLimitKmh: Double? = null,
        val alarmsArmedMask: Int? = null,
        val alarm1SpeedKmh: Int? = null,
        val alarm2SpeedKmh: Int? = null,
        val alarm3SpeedKmh: Int? = null,
        val ledMode: Int? = null,
        val driveFlags: Int? = null,
        val speakerVolumeStep: Int? = null
    ) {
        val drlEnabled: Boolean? get() = driveFlags?.let { (it and 0x01) != 0 }
        val headlightEnabled: Boolean? get() = driveFlags?.let { (it and 0x04) != 0 }
    }

    data class ZBmsSnapshot(
        val bmsIndex: Int,
        val rawPayloadHex: String,
        val voltage: Double? = null,
        val current: Double? = null,
        val cellVoltages: List<Double>? = null
    )

    fun getZSettingsSnapshot(): ZSettingsSnapshot =
        adapter.withProtocol { engine.getZSettingsSnapshot().toLegacy() }
    fun getZBmsSnapshots(): List<ZBmsSnapshot> =
        adapter.withProtocol { engine.getZBmsSnapshots().map { it.toLegacy() } }

    override fun close() {
        adapter.resetSession()
        adapter.close()
    }
}

internal fun NinebotSettingsSnapshot.toLegacy() = NinebotProtocol.ZSettingsSnapshot(
    bleVersion, authKeyHex, authKeyAscii, lockState, limitedModeEnabled, speedLimitKmh,
    alarmsArmedMask, alarm1SpeedKmh, alarm2SpeedKmh, alarm3SpeedKmh, ledMode, driveFlags,
    speakerVolumeStep
)

internal fun NinebotBmsSnapshot.toLegacy() = NinebotProtocol.ZBmsSnapshot(
    bmsIndex, rawPayloadHex, voltage, current, cellVoltages
)
