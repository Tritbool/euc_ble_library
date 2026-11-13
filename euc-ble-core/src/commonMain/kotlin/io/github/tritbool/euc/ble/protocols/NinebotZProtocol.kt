package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.core.EucClock
import io.github.tritbool.euc.ble.core.SystemEucClock
import io.github.tritbool.euc.ble.session.ProtocolSessionDriver

/** Coroutine-compatible facade for the synchronous Ninebot Z state machine. */
class NinebotZProtocol private constructor(
    private val engine: NinebotZSyncProtocol,
    private val adapter: ProtocolSessionDriver
) : EUCProtocol by adapter {
    constructor(clock: EucClock = SystemEucClock) : this(NinebotZSyncProtocol(clock))
    private constructor(engine: NinebotZSyncProtocol) : this(engine, ProtocolSessionDriver(engine))

    fun getZSettingsSnapshot(): NinebotProtocol.ZSettingsSnapshot =
        adapter.withProtocol { engine.getZSettingsSnapshot().toLegacy() }

    fun getZBmsSnapshots(): List<NinebotProtocol.ZBmsSnapshot> =
        adapter.withProtocol { engine.getZBmsSnapshots().map { it.toLegacy() } }

    override fun close() {
        adapter.resetSession()
        adapter.close()
    }
}
