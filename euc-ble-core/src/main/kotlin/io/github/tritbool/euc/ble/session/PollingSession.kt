package io.github.tritbool.euc.ble.session

import io.github.tritbool.euc.ble.core.QueryTraceEvent
import io.github.tritbool.euc.ble.protocols.CommandSupport
import io.github.tritbool.euc.ble.protocols.EUCProtocol
import io.github.tritbool.euc.ble.protocols.toLegacyValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Coroutine driver; transport is supplied by the connector and the caller's scope is never owned. */
class PollingSession(
    private val protocol: EUCProtocol,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val trace: (QueryTraceEvent) -> Unit,
    private val enqueue: (ByteArray, () -> Boolean, () -> Unit, () -> Unit) -> Unit,
    private val onError: (Exception) -> Unit
) {
    private val lock = Any()
    private val engine = PollingEngine(protocol.manufacturer)
    private var job: Job? = null
    @Volatile private var active = false

    fun start() = guarded {
        check(!active) { "Polling session already started" }
        active = true
        engine.start(protocol.getPollingPlan(), now())
        job = scope.launch {
            while (true) {
                guarded {
                    if (active) {
                        engine.tick(protocol.getPollingPlan(), now())
                        dispatch()
                    }
                }
                if (!active) return@launch
                delay(25)
            }
        }
    }

    fun stop() = synchronized(lock) {
        active = false
        job?.cancel()
        job = null
        engine.reset()
    }

    fun onNotification(data: ByteArray) = guarded {
        if (active) {
            engine.onResponse(now()) { protocol.matchesQueryResponse(it, data) }
            dispatch()
        }
    }

    private fun guarded(action: () -> Unit) = synchronized(lock) {
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            stop()
            onError(e)
        }
    }

    private fun dispatch() {
        while (true) {
            when (val output = engine.pollOutput() ?: return) {
                is PollingEngine.Output.Trace -> trace(output.event)
                is PollingEngine.Output.Query -> {
                    val payload = if (protocol.getCommandSupport(output.spec.commandType) == CommandSupport.SUPPORTED)
                        protocol.createCommand(output.spec.commandType, output.spec.value.toLegacyValue()) else byteArrayOf()
                    if (payload.isEmpty()) {
                        engine.onFailed(output.token, now(), unsupported = true)
                    } else {
                        enqueue(payload,
                            { synchronized(lock) { active && engine.isQueued(output.token) } },
                            { guarded {
                                if (active) { engine.onAccepted(output.token, now()); dispatch() }
                            } },
                            { guarded {
                                if (active) { engine.onFailed(output.token, now()); dispatch() }
                            } })
                    }
                }
            }
        }
    }
}
