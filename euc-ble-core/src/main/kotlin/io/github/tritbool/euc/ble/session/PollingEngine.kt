package io.github.tritbool.euc.ble.session

import io.github.tritbool.euc.ble.core.QueryTraceEvent
import io.github.tritbool.euc.ble.core.QueryTracePhase
import io.github.tritbool.euc.ble.protocols.ProtocolPollingPlan
import io.github.tritbool.euc.ble.protocols.ProtocolQuerySpec

/** Synchronous polling state. The driver supplies monotonic milliseconds and performs writes. */
class PollingEngine(private val manufacturer: String) {
    sealed interface Output {
        data class Query(val token: Long, val spec: ProtocolQuerySpec, val attempt: Int) : Output
        data class Trace(val event: QueryTraceEvent) : Output
    }

    private enum class Phase { WAITING, QUEUED, ACCEPTED, FINISHED }
    private class Entry(val spec: ProtocolQuerySpec, val startup: Boolean, var due: Long) {
        var phase = Phase.WAITING
        var attempt = 1
        var token = 0L
        var acceptedAt = 0L
        var scheduled = true
    }

    private val entries = mutableListOf<Entry>()
    private val outputs = ArrayDeque<Output>()
    private var nextToken = 1L

    fun start(plan: ProtocolPollingPlan, now: Long) {
        reset()
        if (plan.enabled) {
            entries += plan.startupQueries.mapIndexed { index, spec ->
                Entry(spec, true, now + spec.initialDelayMs.coerceAtLeast(0)).also {
                    it.scheduled = index == 0
                }
            }
        }
    }

    fun reset() {
        entries.clear()
        outputs.clear()
    }

    fun pollOutput(): Output? = outputs.removeFirstOrNull()

    /** Reconciles model-dependent periodic queries, then advances retry/response deadlines. */
    fun tick(plan: ProtocolPollingPlan, now: Long) {
        if (!plan.enabled) {
            reset()
            return
        }
        val startupComplete = entries.none { it.startup && it.phase != Phase.FINISHED }
        val periodic = if (plan.enabled && startupComplete) plan.periodicQueries else emptyList()
        entries.removeAll { !it.startup && periodic.none { spec -> spec.id == it.spec.id } }
        periodic.forEach { spec ->
            if (entries.none { !it.startup && it.spec.id == spec.id }) {
                entries += Entry(spec, false, now + spec.initialDelayMs.coerceAtLeast(0))
            }
        }
        for (entry in entries) {
            if (entry.phase == Phase.ACCEPTED &&
                now - entry.acceptedAt >= entry.spec.responseTimeoutMs.coerceAtLeast(200L)
            ) {
                trace(entry, QueryTracePhase.TIMEOUT, now, "No matching response")
                if (entry.attempt <= entry.spec.maxRetries.coerceAtLeast(0)) {
                    entry.attempt++
                    entry.phase = Phase.WAITING
                    entry.due = now + entry.spec.retryBackoffMs.coerceAtLeast(0)
                    trace(entry, QueryTracePhase.RETRY_SCHEDULED, now, "Retry scheduled")
                } else {
                    trace(entry, QueryTracePhase.FAILED, now, "Query exhausted retries")
                    finish(entry, now)
                }
            }
        }
        val startup = entries.firstOrNull { it.startup && it.phase != Phase.FINISHED }
        if (startup != null && !startup.scheduled) {
            startup.scheduled = true
            startup.due = now + startup.spec.initialDelayMs.coerceAtLeast(0)
        }
        entries.filter { !it.startup || it === startup }.forEach { entry ->
            if (entry.phase == Phase.WAITING && now >= entry.due &&
                entries.none { it !== entry && it.spec.id == entry.spec.id &&
                    it.phase in setOf(Phase.QUEUED, Phase.ACCEPTED) }
            ) {
                entry.phase = Phase.QUEUED
                entry.token = nextToken++
                outputs += Output.Query(entry.token, entry.spec, entry.attempt)
            }
        }
    }

    fun isQueued(token: Long): Boolean = entries.any { it.token == token && it.phase == Phase.QUEUED }

    /** Called at transport acceptance, never at encoding or queue insertion. */
    fun onAccepted(token: Long, now: Long) {
        val entry = entries.singleOrNull { it.token == token && it.phase == Phase.QUEUED } ?: return
        entry.phase = Phase.ACCEPTED
        entry.acceptedAt = now
        trace(entry, QueryTracePhase.SENT, now)
    }

    fun onFailed(token: Long, now: Long, unsupported: Boolean = false) {
        val entry = entries.singleOrNull { it.token == token && it.phase == Phase.QUEUED } ?: return
        trace(entry, if (unsupported) QueryTracePhase.UNSUPPORTED else QueryTracePhase.FAILED, now,
            if (unsupported) "Command unsupported or empty" else "Transport rejected query")
        // Unsupported periodic requests stay dormant until the plan removes/reintroduces them.
        entry.phase = Phase.FINISHED
    }

    fun onResponse(now: Long, matches: (ProtocolQuerySpec) -> Boolean) {
        entries.filter { it.phase == Phase.ACCEPTED }.forEach { entry ->
            if (matches(entry.spec)) {
                trace(entry, QueryTracePhase.RESPONSE_MATCHED, now, latency = (now - entry.acceptedAt).coerceAtLeast(0))
                finish(entry, now)
            }
        }
    }

    private fun finish(entry: Entry, now: Long) {
        if (!entry.startup && entry.spec.intervalMs > 0) {
            entry.phase = Phase.WAITING
            entry.attempt = 1
            entry.due = now + entry.spec.intervalMs
        } else entry.phase = Phase.FINISHED
    }

    private fun trace(entry: Entry, phase: QueryTracePhase, now: Long, message: String? = null, latency: Long? = null) {
        outputs += Output.Trace(QueryTraceEvent(now, manufacturer, entry.spec.id, entry.spec.commandType,
            phase, entry.attempt, latency, message))
    }
}
