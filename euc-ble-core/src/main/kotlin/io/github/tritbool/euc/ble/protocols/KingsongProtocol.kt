package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.core.EucClock
import io.github.tritbool.euc.ble.core.EucRandom
import io.github.tritbool.euc.ble.core.SecureEucRandom
import io.github.tritbool.euc.ble.core.SystemEucClock
import io.github.tritbool.euc.ble.models.BMSData
import io.github.tritbool.euc.ble.models.EUCData
import io.github.tritbool.euc.ble.models.WheelSecurityState
import io.github.tritbool.euc.ble.session.FlowProtocolAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** Flow-compatible wrapper for the synchronous KingSong state machine. */
class KingsongProtocol private constructor(
    private val engine: KingsongSyncProtocol,
    private val adapter: FlowProtocolAdapter
) : EUCProtocol by adapter {
    constructor(
        scope: CoroutineScope? = null,
        clock: EucClock = SystemEucClock,
        random: EucRandom = SecureEucRandom()
    ) : this(KingsongSyncProtocol(clock, random), scope)

    private constructor(engine: KingsongSyncProtocol, scope: CoroutineScope?) :
        this(engine, FlowProtocolAdapter(engine, scope))

    companion object {
        internal const val PASSWORD_OPERATION_TIMEOUT_MS = KingsongSyncProtocol.PASSWORD_OPERATION_TIMEOUT_MS
        internal fun seriesCellsForModel(model: String?) = KingsongSyncProtocol.seriesCellsForModel(model)
        internal fun seriesCellsForVoltage(voltage: Double) = KingsongSyncProtocol.seriesCellsForVoltage(voltage)
        internal fun batteryPercentFromVoltage(voltage: Double, cells: Int) =
            KingsongSyncProtocol.batteryPercentFromVoltage(voltage, cells)
    }

    override val securityStateFlow: StateFlow<WheelSecurityState> = adapter.nonNullSecurityState
    override fun getSecurityState(): WheelSecurityState = adapter.withProtocol { engine.getSecurityState() }
    override fun getBMSData(): List<BMSData> = adapter.withProtocol { engine.getBMSData() }
    override fun decode(data: ByteArray): EUCData? {
        if (data.isNotEmpty()) adapter.decode(data)
        return null
    }
    override fun close() {
        adapter.resetSession()
        adapter.close()
    }
}
