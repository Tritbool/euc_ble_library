package io.github.tritbool.euc.ble.session

import io.github.tritbool.euc.ble.core.BLEConstants.ConnectionState
import io.github.tritbool.euc.ble.core.EucRandom

/** Transport-free connection/reconnect policy. All deadlines use caller-supplied monotonic time. */
class ConnectionSession(
    private val random: EucRandom,
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy()
) {
    sealed interface Output {
        data class Connect(val address: String) : Output
        data object Close : Output
        data object DiscoverServices : Output
        data class StateChanged(val state: ConnectionState) : Output
        data class Error(val message: String, val terminal: Boolean) : Output
    }
    data class Event(val generation: Long, val output: Output)

    var generation = 0L
        private set
    var state = ConnectionState.DISCONNECTED
        private set
    var address: String? = null
        private set
    var autoReconnect = true
    var maxRetries = 3
    var connectionTimeoutMs = 5_000L
    var nextDeadline: Long? = null
        private set
    private var retries = 0
    private var reconnecting = false
    private var manuallyDisconnected = true
    private val outputs = ArrayDeque<Event>()

    fun connect(address: String, now: Long) {
        check(state == ConnectionState.DISCONNECTED) { "Already connecting or connected" }
        generation++
        this.address = address
        manuallyDisconnected = false
        retries = 0
        reconnecting = false
        beginConnect(now)
    }

    fun onConnected() {
        if (state != ConnectionState.CONNECTING || manuallyDisconnected) return
        nextDeadline = null
        retries = 0
        reconnecting = false
        changeState(ConnectionState.CONNECTED)
        emit(Output.DiscoverServices)
    }

    fun onConnecting() {
        if (!manuallyDisconnected) changeState(ConnectionState.CONNECTING)
    }

    fun onDisconnecting() = changeState(ConnectionState.DISCONNECTING)

    fun onDisconnected(now: Long) {
        nextDeadline = null
        emit(Output.Close)
        if (state == ConnectionState.DISCONNECTED) emit(Output.StateChanged(state))
        else changeState(ConnectionState.DISCONNECTED)
        if (!manuallyDisconnected && autoReconnect && address != null) scheduleReconnect(now)
    }

    fun onDiscoveryRejected(message: String) {
        if (state != ConnectionState.CONNECTED) return
        disconnect()
        emit(Output.Error(message, terminal = false))
    }

    fun onConnectRejected(message: String, now: Long) {
        if (state != ConnectionState.CONNECTING) return
        nextDeadline = null
        emit(Output.Close)
        changeState(ConnectionState.DISCONNECTED)
        if (reconnecting && !manuallyDisconnected && autoReconnect) {
            emit(Output.Error(message, terminal = false))
            scheduleReconnect(now)
        } else {
            manuallyDisconnected = true
            address = null
            emit(Output.Error(message, terminal = true))
        }
    }

    fun disconnect() {
        generation++
        manuallyDisconnected = true
        nextDeadline = null
        retries = 0
        address = null
        emit(Output.Close)
        if (state == ConnectionState.DISCONNECTED) emit(Output.StateChanged(state))
        else changeState(ConnectionState.DISCONNECTED)
    }

    fun advance(now: Long) {
        val deadline = nextDeadline ?: return
        if (now < deadline) return
        nextDeadline = null
        if (state == ConnectionState.CONNECTING) {
            onConnectRejected("Connection timeout", now)
        } else if (state == ConnectionState.DISCONNECTED && !manuallyDisconnected && autoReconnect) {
            retries++
            reconnecting = true
            beginConnect(now)
        }
    }

    fun pollEvent(): Event? = outputs.removeFirstOrNull()
    fun pollOutput(): Output? = pollEvent()?.output

    private fun beginConnect(now: Long) {
        nextDeadline = now + connectionTimeoutMs.coerceAtLeast(0)
        changeState(ConnectionState.CONNECTING)
        emit(Output.Connect(checkNotNull(address)))
    }

    private fun scheduleReconnect(now: Long) {
        val delay = reconnectPolicy.delayForRetry(retries, maxRetries, random)
        if (delay == null) {
            manuallyDisconnected = true
            address = null
            emit(Output.Error("Reconnection failed after $retries attempts", terminal = true))
        } else nextDeadline = now + delay
    }

    private fun changeState(value: ConnectionState) {
        if (state == value) return
        state = value
        emit(Output.StateChanged(value))
    }

    private fun emit(output: Output) {
        check(outputs.size < 32) { "Connection output queue overflow (capacity=32)" }
        outputs.addLast(Event(generation, output))
    }
}
