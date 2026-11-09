package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.models.EUCData
import io.github.tritbool.euc.ble.models.WheelSecurityEvent
import io.github.tritbool.euc.ble.models.WheelSecurityState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Coroutine-compatible protocol facade. New protocol logic belongs in [SyncEucProtocol];
 * [io.github.tritbool.euc.ble.session.FlowProtocolAdapter] owns serialization and timers.
 */
interface EUCProtocol : EucProtocolSpec {
    /** Legacy extension point. Built-in facades check and convert values at the JVM boundary. */
    fun createCommand(commandType: CommandType, value: Any): ByteArray

    /** Keeps legacy custom implementations working when polling supplies typed values. */
    override fun createCommand(commandType: CommandType, value: CommandValue): ByteArray =
        createCommand(commandType, value.toLegacyValue())

    fun close()
    val dataFlow: Flow<EUCData>

    /** Defensive copies of incoming notification chunks. */
    val rawFrameFlow: Flow<ByteArray> get() = emptyFlow()
    val writeFlow: Flow<ByteArray> get() = emptyFlow()
    val securityStateFlow: Flow<WheelSecurityState> get() = emptyFlow()
    val securityEventFlow: Flow<WheelSecurityEvent> get() = emptyFlow()

    fun decode(data: ByteArray): EUCData?
}
