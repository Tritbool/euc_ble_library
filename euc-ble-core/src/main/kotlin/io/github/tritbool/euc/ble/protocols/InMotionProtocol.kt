package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.core.EucClock
import io.github.tritbool.euc.ble.core.Logger
import io.github.tritbool.euc.ble.core.NoOpLogger
import io.github.tritbool.euc.ble.core.SystemEucClock
import io.github.tritbool.euc.ble.models.EUCData
import io.github.tritbool.euc.ble.models.WheelSecurityState
import io.github.tritbool.euc.ble.session.ProtocolSessionDriver
import kotlinx.coroutines.flow.StateFlow

/** Coroutine-compatible facade for the synchronous InMotion state machine. */
class InMotionProtocol private constructor(
    private val engine: InMotionSyncProtocol,
    private val adapter: ProtocolSessionDriver
) : EUCProtocol by adapter {
    constructor(logger: Logger = NoOpLogger(), clock: EucClock = SystemEucClock) :
        this(InMotionSyncProtocol(clock), logger)

    private constructor(engine: InMotionSyncProtocol, logger: Logger) :
        this(engine, ProtocolSessionDriver(engine, logger = logger))

    override val securityStateFlow: StateFlow<WheelSecurityState> = adapter.nonNullSecurityState
    override fun getSecurityState(): WheelSecurityState = adapter.withProtocol { engine.getSecurityState() }
    override fun decode(data: ByteArray): EUCData? =
        if (data.isEmpty()) null else adapter.decode(data)
    fun setDialect(version: Int) = adapter.withProtocol { engine.setDialect(version) }
    fun lastDecodedModel(): String = adapter.withProtocol { engine.lastDecodedModel() }
    fun lastDecodedFirmware(): String? = adapter.withProtocol { engine.lastDecodedFirmware() }

    override fun close() {
        adapter.resetSession()
        adapter.close()
    }
}
