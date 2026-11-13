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
import io.github.tritbool.euc.ble.protocols.CommandValue
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
import io.github.tritbool.euc.ble.protocols.toCommandValue

/**
 * Coroutine/Flow driver for a pure [ProtocolSession], preserving the public [EUCProtocol] API.
 *
 * - Every call into [protocol] is serialized, so the protocol itself needs no synchronization.
 * - After each call, pending [ProtocolOutput]s are drained: telemetry goes to [dataFlow], writes
 *   to [writeFlow], security updates to [securityStateFlow]/[securityEventFlow], and timer
 *   requests are scheduled on the coroutine scope.
 * - [decode] returns the last telemetry produced by that notification, synchronously.
 *
 * When no scope is given the driver owns one and cancels it in [close]; a caller-provided scope
 * is never cancelled (only this driver's timer jobs are).
 */
class ProtocolSessionDriver(
    val protocol: SyncEucProtocol,
    scope: CoroutineScope? = null,
    private val logger: Logger = NoOpLogger(),
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) : EUCProtocol {
    private val ownsScope = scope == null
    private val scope: CoroutineScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val session = ProtocolSession(protocol)
    private var sessionTime = now()
    private var timerJob: Job? = null
    private var scheduledDeadline: Long? = null
    private var timerGeneration = 0L
    private var lastTelemetry: EUCData? = null

    private val dataChannel = Channel<EUCData>(
        capacity = BLEConstants.DEFAULT_FLOW_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val dataFlow: Flow<EUCData> = dataChannel.receiveAsFlow()

    private val writeChannel = Channel<ByteArray>(capacity = BLEConstants.DEFAULT_FLOW_BUFFER_CAPACITY)
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
    val armedTimerCount: Int get() = synchronized(lock) { session.armedTimerCount }

    override fun decode(data: ByteArray): EUCData? {
        _rawFrameFlow.tryEmit(data.copyOf())
        return synchronized(lock) {
            lastTelemetry = null
            sessionTime = maxOf(sessionTime, now())
            session.onNotification(data, sessionTime)
            dispatchOutputs()
            lastTelemetry.also { lastTelemetry = null }
        }
    }

    override fun resetSession() {
        synchronized(lock) {
            cancelAllTimers()
            while (writeChannel.tryReceive().isSuccess) Unit
            while (dataChannel.tryReceive().isSuccess) Unit
            sessionTime = maxOf(sessionTime, now())
            session.reset(sessionTime)
            dispatchOutputs()
            val state = protocol.getSecurityState()
            _securityState.value = state
            _nonNullSecurityState.value = state ?: WheelSecurityState()
        }
    }

    override fun close() {
        synchronized(lock) {
            cancelAllTimers()
            session.close()
        }
        if (ownsScope) scope.cancel()
    }

    override val manufacturer: String get() = protocol.manufacturer
    override val protocolId: String get() = protocol.protocolId
    override val parentProtocolId: String? get() = protocol.parentProtocolId
    override fun getSecurityState(): WheelSecurityState? = call { protocol.getSecurityState() }
    override fun canSendCommand(payload: ByteArray): Boolean = call { protocol.canSendCommand(payload) }
    override fun onCommandSubmitted(payload: ByteArray) = call { protocol.onCommandSubmitted(payload) }
    override fun canRetryCommand(payload: ByteArray): Boolean = call { protocol.canRetryCommand(payload) }
    override fun getCandidateDataCharacteristicUUIDs(): List<EucUuid> =
        call { protocol.getCandidateDataCharacteristicUUIDs() }
    override fun getDataCharacteristicUUID(): EucUuid = call { protocol.getDataCharacteristicUUID() }
    override fun getServiceUUID(): EucUuid = call { protocol.getServiceUUID() }
    override fun getWriteCharacteristicUUID(): EucUuid = call { protocol.getWriteCharacteristicUUID() }
    override fun createCommand(commandType: CommandType, value: Any): ByteArray {
        val typed = try {
            value.toCommandValue()
        } catch (e: IllegalArgumentException) {
            logger.warn(TAG, "Invalid command value for '$commandType': ${e.message}")
            return byteArrayOf()
        }
        return createCommand(commandType, typed)
    }
    override fun createCommand(commandType: CommandType, value: CommandValue): ByteArray =
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
        sessionTime = maxOf(sessionTime, now())
        session.afterInput(sessionTime)
        dispatchOutputs()
        result
    }

    internal fun <T> withProtocol(block: (SyncEucProtocol) -> T): T = call { block(protocol) }

    /** Must be called while holding [lock]. */
    private fun dispatchOutputs() {
        while (true) {
            when (val output = session.pollOutput() ?: break) {
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
                is ProtocolOutput.Write -> if (output.payload.isNotEmpty()) {
                    check(writeChannel.trySend(output.payload).isSuccess) {
                        "Legacy write flow queue is full; control write was rejected"
                    }
                }
                is ProtocolOutput.StartTimer, is ProtocolOutput.CancelTimer ->
                    error("Timer bookkeeping belongs to ProtocolSession")
            }
        }
        scheduleTimer()
    }

    /** Must be called while holding [lock]. */
    private fun scheduleTimer() {
        val deadline = session.nextDeadline
        if (deadline == scheduledDeadline) return
        timerJob?.cancel()
        scheduledDeadline = deadline
        val generation = ++timerGeneration
        if (deadline == null) return
        val delayMs = (deadline - sessionTime).coerceAtLeast(0)
        timerJob = scope.launch(start = CoroutineStart.LAZY) {
            delay(delayMs)
            synchronized(lock) {
                if (generation != timerGeneration) return@launch
                scheduledDeadline = null
                try {
                    sessionTime = maxOf(deadline, now())
                    session.advance(sessionTime)
                } catch (e: Exception) {
                    logger.error(TAG, "Timer failed in ${protocol.manufacturer} protocol", e)
                }
                dispatchOutputs()
            }
        }
        timerJob?.start()
    }

    /** Must be called while holding [lock]. */
    private fun cancelAllTimers() {
        timerGeneration++
        timerJob?.cancel()
        timerJob = null
        scheduledDeadline = null
    }

    private companion object {
        const val TAG = "ProtocolSessionDriver"
        const val SECURITY_EVENT_BUFFER = 16
    }
}
