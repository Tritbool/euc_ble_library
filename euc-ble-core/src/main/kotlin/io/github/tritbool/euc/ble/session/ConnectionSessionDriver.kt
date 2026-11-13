package io.github.tritbool.euc.ble.session

import io.github.tritbool.euc.ble.core.BLEConstants.ConnectionState
import io.github.tritbool.euc.ble.core.EucRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Only coroutine scheduling and transport effects; lifecycle policy lives in [ConnectionSession]. */
class ConnectionSessionDriver(
    private val transport: BleTransport,
    private val scope: CoroutineScope,
    random: EucRandom,
    private val now: () -> Long,
    private val stateChanged: (ConnectionState) -> Unit,
    private val error: (String, Boolean) -> Unit
) {
    private val session = ConnectionSession(random)
    private val lock = Any()
    private var timer: Job? = null
    private var timerGeneration = 0L
    private var dispatching = false

    fun configure(timeout: Long, autoReconnect: Boolean, maxRetries: Int) = synchronized(lock) {
        session.connectionTimeoutMs = timeout
        session.autoReconnect = autoReconnect
        session.maxRetries = maxRetries
    }

    fun connect(address: String) = input { session.connect(address, now()) }
    fun disconnect() = input { session.disconnect() }
    fun onConnected() = input { session.onConnected() }
    fun onDisconnected() = input { session.onDisconnected(now()) }
    fun onConnecting() = input { session.onConnecting() }
    fun onDisconnecting() = input { session.onDisconnecting() }

    private fun input(action: () -> Unit) = synchronized(lock) {
        action()
        if (dispatching) return@synchronized
        dispatching = true
        try {
            while (true) {
                val event = session.pollEvent() ?: break
                if (event.generation != session.generation) continue
                when (val output = event.output) {
                    is ConnectionSession.Output.Connect -> {
                        if (session.state != ConnectionState.CONNECTING || session.address != output.address) continue
                        try {
                            val accepted = transport.connect(output.address)
                            if (event.generation == session.generation && !accepted) {
                                session.onConnectRejected("BLE stack rejected connection", now())
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (event.generation == session.generation) {
                                session.onConnectRejected("Failed to connect to device: ${e.message}", now())
                            }
                        }
                    }
                    ConnectionSession.Output.Close -> transport.disconnect()
                    ConnectionSession.Output.DiscoverServices -> {
                        if (session.state == ConnectionState.CONNECTED) {
                            val accepted = transport.discoverServices()
                            if (event.generation == session.generation && !accepted) {
                                session.onDiscoveryRejected("BLE stack rejected service discovery")
                            }
                        }
                    }
                    is ConnectionSession.Output.StateChanged -> stateChanged(output.state)
                    is ConnectionSession.Output.Error -> error(output.message, output.terminal)
                }
            }
        } finally {
            dispatching = false
            schedule()
        }
    }

    private fun schedule() {
        timer?.cancel()
        timer = null
        val generation = ++timerGeneration
        val deadline = session.nextDeadline ?: return
        timer = scope.launch(start = CoroutineStart.LAZY) {
            delay((deadline - now()).coerceAtLeast(0))
            input {
                if (generation == timerGeneration) session.advance(maxOf(now(), deadline))
            }
        }
        timer?.start()
    }
}
