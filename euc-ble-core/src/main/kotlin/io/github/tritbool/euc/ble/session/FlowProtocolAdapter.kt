package io.github.tritbool.euc.ble.session

import io.github.tritbool.euc.ble.core.BLEConstants
import io.github.tritbool.euc.ble.core.Logger
import io.github.tritbool.euc.ble.core.NoOpLogger
import io.github.tritbool.euc.ble.models.BMSData
import io.github.tritbool.euc.ble.models.EUCData
import io.github.tritbool.euc.ble.models.WheelSecurityEvent
import io.github.tritbool.euc.ble.models.WheelSecurityState
import io.github.tritbool.euc.ble.protocols.CommandSupport
import io.github.tritbool.euc.ble.protocols.CommandType
import io.github.tritbool.euc.ble.protocols.EUCProtocol
import io.github.tritbool.euc.ble.protocols.ProtocolOutput
import io.github.tritbool.euc.ble.protocols.ProtocolPollingPlan
import io.github.tritbool.euc.ble.protocols.ProtocolQuerySpec
import io.github.tritbool.euc.ble.protocols.ProtocolWriteType
import io.github.tritbool.euc.ble.protocols.SyncEucProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import io.github.tritbool.euc.ble.core.EucUuid

/**
 * Session-layer bridge exposing a synchronous [SyncEucProtocol] through the coroutine-based
 * [EUCProtocol] contract.
 *
 * - Every call into [protocol] is serialized, so the protocol itself needs no synchronization.
 * - After each call, pending [ProtocolOutput]s are drained: telemetry goes to [dataFlow], writes
 *   to [writeFlow], security updates to [securityStateFlow]/[securityEventFlow], and timer
 *   requests are scheduled on the coroutine scope.
 * - [decode] returns the last telemetry produced by that notification, synchronously.
 *
 * When no scope is given the adapter owns one and cancels it in [close]; a caller-provided scope
 * is never cancelled (only this adapter's timer jobs are).
 */
class FlowProtocolAdapter(
    val protocol: SyncEucProtocol,
    scope: CoroutineScope? = null,
    private val logger: Logger = NoOpLogger(),
) : EUCProtocol {
    private val ownsScope = scope == null
    private val scope: CoroutineScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val timers = HashMap<String, Job>()
    private var lastTelemetry: EUCData? = null

    private val dataChannel = Channel<EUCData>(capacity = Channel.UNLIMITED)
    override val dataFlow: Flow<EUCData> = dataChannel.receiveAsFlow()

    private val writeChannel = Channel<ByteArray>(capacity = Channel.UNLIMITED)
    override val writeFlow: Flow<ByteArray> = writeChannel.receiveAsFlow()

    private val _rawFrameFlow = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = BLEConstants.DEFAULT_FLOW_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val rawFrameFlow: Flow<ByteArray> = _rawFrameFlow.asSharedFlow()

    private val _securityState = MutableStateFlow(protocol.getSecurityState())
    /** Latest notification-confirmed security state, or `null` when unsupported/unknown. */
    val securityState: StateFlow<WheelSecurityState?> = _securityState.asStateFlow()
    override val securityStateFlow: Flow<WheelSecurityState> = _securityState.filterNotNull()
    private val _nonNullSecurityState = MutableStateFlow(_securityState.value ?: WheelSecurityState())
    internal val nonNullSecurityState: StateFlow<WheelSecurityState> = _nonNullSecurityState.asStateFlow()

    private val _securityEventFlow = MutableSharedFlow<WheelSecurityEvent>(
        extraBufferCapacity = SECURITY_EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val securityEventFlow: Flow<WheelSecurityEvent> = _securityEventFlow.asSharedFlow()

    /** Number of currently armed timers (for diagnostics and tests). */
    val armedTimerCount: Int get() = synchronized(lock) { timers.size }

    override fun decode(data: ByteArray): EUCData? {
        _rawFrameFlow.tryEmit(data.copyOf())
        return synchronized(lock) {
            lastTelemetry = null
            protocol.onNotification(data)
            dispatchOutputs()
            lastTelemetry.also { lastTelemetry = null }
        }
    }

    override fun resetSession() {
        synchronized(lock) {
            cancelAllTimers()
            while (writeChannel.tryReceive().isSuccess) Unit
            while (dataChannel.tryReceive().isSuccess) Unit
            protocol.resetSession()
            dispatchOutputs()
            val state = protocol.getSecurityState()
            _securityState.value = state
            _nonNullSecurityState.value = state ?: WheelSecurityState()
        }
    }

    override fun close() {
        synchronized(lock) {
            cancelAllTimers()
        }
        if (ownsScope) scope.cancel()
    }

    override val manufacturer: String get() = protocol.manufacturer
    override fun getSecurityState(): WheelSecurityState? = call { protocol.getSecurityState() }
    override fun canSendCommand(payload: ByteArray): Boolean = call { protocol.canSendCommand(payload) }
    override fun onCommandSubmitted(payload: ByteArray) = call { protocol.onCommandSubmitted(payload) }
    override fun canRetryCommand(payload: ByteArray): Boolean = call { protocol.canRetryCommand(payload) }
    override fun getCandidateDataCharacteristicUUIDs(): List<EucUuid> =
        call { protocol.getCandidateDataCharacteristicUUIDs() }
    override fun getDataCharacteristicUUID(): EucUuid = call { protocol.getDataCharacteristicUUID() }
    override fun getServiceUUID(): EucUuid = call { protocol.getServiceUUID() }
    override fun getWriteCharacteristicUUID(): EucUuid = call { protocol.getWriteCharacteristicUUID() }
    override fun createCommand(commandType: CommandType, value: Any): ByteArray =
        call { protocol.createCommand(commandType, value) }
    override val supportedCommandTypes: Set<CommandType> get() = call { protocol.supportedCommandTypes }
    override fun getCommandSupport(commandType: CommandType): CommandSupport =
        call { protocol.getCommandSupport(commandType) }
    override fun matchesDeviceName(deviceName: String): Boolean = call { protocol.matchesDeviceName(deviceName) }
    override fun getPollingPlan(): ProtocolPollingPlan = call { protocol.getPollingPlan() }
    override fun matchesQueryResponse(query: ProtocolQuerySpec, data: ByteArray): Boolean =
        call { protocol.matchesQueryResponse(query, data) }
    override val preferredWriteType: ProtocolWriteType get() = call { protocol.preferredWriteType }
    override fun isDeviceReady(data: EUCData): Boolean = call { protocol.isDeviceReady(data) }
    override fun getBMSData(): List<BMSData>? = call { protocol.getBMSData() }

    private inline fun <T> call(block: () -> T): T = synchronized(lock) {
        val result = block()
        dispatchOutputs()
        result
    }

    internal fun <T> withProtocol(block: (SyncEucProtocol) -> T): T = call { block(protocol) }

    /** Must be called while holding [lock]. */
    private fun dispatchOutputs() {
        while (true) {
            when (val output = protocol.pollOutput() ?: return) {
                is ProtocolOutput.DebugLog -> logger.debug(output.tag, output.message)
                is ProtocolOutput.Telemetry -> {
                    lastTelemetry = output.data
                    dataChannel.trySend(output.data)
                }
                is ProtocolOutput.SecurityStateChanged -> {
                    _securityState.value = output.state
                    _nonNullSecurityState.value = output.state
                }
                is ProtocolOutput.SecurityEvent -> _securityEventFlow.tryEmit(output.event)
                is ProtocolOutput.Write -> if (output.payload.isNotEmpty()) writeChannel.trySend(output.payload)
                is ProtocolOutput.StartTimer -> startTimer(output.id, output.delayMs)
                is ProtocolOutput.CancelTimer -> timers.remove(output.id)?.cancel()
            }
        }
    }

    /** Must be called while holding [lock]. */
    private fun startTimer(id: String, delayMs: Long) {
        timers.remove(id)?.cancel()
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            delay(delayMs)
            synchronized(lock) {
                // A reset, cancel or re-arm since scheduling makes this expiry stale.
                if (timers[id] !== job) return@launch
                timers.remove(id)
                try {
                    protocol.onTimer(id)
                } catch (e: Exception) {
                    logger.error(TAG, "Timer '$id' failed in ${protocol.manufacturer} protocol", e)
                }
                dispatchOutputs()
            }
        }
        timers[id] = job
        job.start()
    }

    /** Must be called while holding [lock]. */
    private fun cancelAllTimers() {
        timers.values.forEach { it.cancel() }
        timers.clear()
    }

    private companion object {
        const val TAG = "FlowProtocolAdapter"
        const val SECURITY_EVENT_BUFFER = 16
    }
}
