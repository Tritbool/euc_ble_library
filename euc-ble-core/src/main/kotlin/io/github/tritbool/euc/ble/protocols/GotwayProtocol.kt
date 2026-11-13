package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.core.EucClock
import io.github.tritbool.euc.ble.core.Logger
import io.github.tritbool.euc.ble.core.NoOpLogger
import io.github.tritbool.euc.ble.core.SystemEucClock
import io.github.tritbool.euc.ble.models.BMSData
import io.github.tritbool.euc.ble.models.EUCData
import io.github.tritbool.euc.ble.session.ProtocolSessionDriver
import kotlinx.coroutines.CoroutineScope

/** Flow-compatible wrapper for the synchronous Gotway state machine. */
open class GotwayProtocol protected constructor(
    private val engine: GotwaySyncProtocol,
    private val adapter: ProtocolSessionDriver,
    val logger: Logger
) : EUCProtocol by adapter {
    constructor(scope: CoroutineScope? = null, logger: Logger = NoOpLogger(), clock: EucClock = SystemEucClock) :
        this(GotwaySyncProtocol(clock), scope, logger)

    protected constructor(engine: GotwaySyncProtocol, scope: CoroutineScope?, logger: Logger) :
        this(engine, ProtocolSessionDriver(engine, scope, logger), logger)

    data class BatteryVoltageRange(val minVoltage: Double, val maxVoltage: Double) {
        val seriesCells: Int get() = (maxVoltage / 4.2).toInt()
    }

    companion object {
        const val FRAME_SIZE = GotwaySyncProtocol.FRAME_SIZE
        val HEADER: ByteArray = GotwaySyncProtocol.HEADER
        val FOOTER: ByteArray = GotwaySyncProtocol.FOOTER
    }

    override fun decode(data: ByteArray): EUCData? {
        adapter.decode(data)
        return null
    }

    override fun getBMSData(): List<BMSData> = adapter.withProtocol { engine.getBMSData() }

    override fun close() {
        adapter.resetSession()
        adapter.close()
    }
}
