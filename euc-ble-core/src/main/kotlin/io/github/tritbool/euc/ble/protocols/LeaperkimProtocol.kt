package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.core.EucClock
import io.github.tritbool.euc.ble.core.EucDateTime
import io.github.tritbool.euc.ble.core.SystemEucClock
import io.github.tritbool.euc.ble.models.BMSData
import io.github.tritbool.euc.ble.models.EUCData
import io.github.tritbool.euc.ble.session.FlowProtocolAdapter
import kotlinx.coroutines.CoroutineScope
import java.util.Calendar

private class LegacyLeaperkimEngine(clock: EucClock) : LeaperkimSyncProtocol(clock) {
    lateinit var owner: LeaperkimProtocol
    override val manufacturer: String get() = owner.manufacturer
    override fun bmsCellCount(majorVersion: Int?) = owner.callBmsCellCount(majorVersion)
    override fun decodeSpeed(speedRaw: Short) = owner.callDecodeSpeed(speedRaw)
    override fun modelByMajorVersion(version: Int) = owner.callModel(version)
    override fun estimateBatteryPercent(voltageRaw: Int, majorVersion: Int) =
        owner.callBattery(voltageRaw, majorVersion)
    override fun extractMajorVersion(versionRaw: Int) = owner.callMajor(versionRaw)
    override fun formatVersion(versionRaw: Int) = owner.callFormat(versionRaw)

    fun defaultBmsCellCount(version: Int?) = super.bmsCellCount(version)
    fun defaultDecodeSpeed(speed: Short) = super.decodeSpeed(speed)
    fun defaultModel(version: Int) = super.modelByMajorVersion(version)
    fun defaultBattery(voltage: Int, version: Int) = super.estimateBatteryPercent(voltage, version)
    fun defaultMajor(version: Int) = super.extractMajorVersion(version)
    fun defaultFormat(version: Int) = super.formatVersion(version)
}

/** Flow-compatible wrapper for the synchronous Leaperkim state machine. */
open class LeaperkimProtocol protected constructor(
    private val engine: LeaperkimSyncProtocol,
    private val adapter: FlowProtocolAdapter
) : EUCProtocol by adapter {
    constructor(scope: CoroutineScope? = null, clock: EucClock = SystemEucClock) :
        this(LegacyLeaperkimEngine(clock), scope)

    protected constructor(engine: LeaperkimSyncProtocol, scope: CoroutineScope?) :
        this(engine, FlowProtocolAdapter(engine, scope))

    init {
        if (engine is LegacyLeaperkimEngine) engine.owner = this
    }

    override val manufacturer: String get() = "Leaperkim"
    protected open fun bmsCellCount(majorVersion: Int?): Int =
        (engine as LegacyLeaperkimEngine).defaultBmsCellCount(majorVersion)
    protected open fun decodeSpeed(speedRaw: Short): Double =
        (engine as LegacyLeaperkimEngine).defaultDecodeSpeed(speedRaw)
    protected open fun modelByMajorVersion(version: Int): String =
        (engine as LegacyLeaperkimEngine).defaultModel(version)
    protected open fun estimateBatteryPercent(voltageRaw: Int, majorVersion: Int): Int =
        (engine as LegacyLeaperkimEngine).defaultBattery(voltageRaw, majorVersion)
    protected open fun extractMajorVersion(versionRaw: Int): Int =
        (engine as LegacyLeaperkimEngine).defaultMajor(versionRaw)
    protected open fun formatVersion(versionRaw: Int): String =
        (engine as LegacyLeaperkimEngine).defaultFormat(versionRaw)

    internal fun callBmsCellCount(version: Int?) = bmsCellCount(version)
    internal fun callDecodeSpeed(speed: Short) = decodeSpeed(speed)
    internal fun callModel(version: Int) = modelByMajorVersion(version)
    internal fun callBattery(voltage: Int, version: Int) = estimateBatteryPercent(voltage, version)
    internal fun callMajor(version: Int) = extractMajorVersion(version)
    internal fun callFormat(version: Int) = formatVersion(version)

    val debugFramesObserved: Int get() = adapter.withProtocol { engine.debugFramesObserved }
    val debugFramesParsed: Int get() = adapter.withProtocol { engine.debugFramesParsed }
    val debugFramesSent: Int get() = adapter.withProtocol { engine.debugFramesSent }
    val debugSendFailures: Int get() = adapter.withProtocol { engine.debugSendFailures }
    override fun getBMSData(): List<BMSData> = adapter.withProtocol { engine.getBMSData() }

    override fun decode(data: ByteArray): EUCData? {
        if (data.isNotEmpty()) adapter.decode(data)
        return null
    }

    internal fun buildLockFrame(locked: Boolean, now: Calendar): ByteArray =
        adapter.withProtocol {
            engine.buildLockFrame(
                locked,
                EucDateTime(
                    now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1,
                    now.get(Calendar.DAY_OF_MONTH), now.get(Calendar.HOUR_OF_DAY),
                    now.get(Calendar.MINUTE), now.get(Calendar.SECOND)
                )
            )
        }

    override fun close() {
        adapter.resetSession()
        adapter.close()
    }
}
