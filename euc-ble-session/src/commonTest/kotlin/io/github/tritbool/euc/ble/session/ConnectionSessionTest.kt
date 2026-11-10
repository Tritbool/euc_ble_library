package io.github.tritbool.euc.ble.session

import io.github.tritbool.euc.ble.core.BLEConstants.ConnectionState
import io.github.tritbool.euc.ble.core.EucRandom
import kotlin.test.*

class ConnectionSessionTest {
    private fun session() = ConnectionSession(EucRandom { 0 }, ReconnectPolicy(100, 500, 0))
        .apply { connectionTimeoutMs = 50 }
    private fun ConnectionSession.drain(): List<ConnectionSession.Output> = buildList {
        while (true) add(pollOutput() ?: break)
    }

    @Test
    fun languageNeutralLifecycleVectors() {
        val sessions = mutableMapOf<String, ConnectionSession>()
        connectionFixtures.forEach { row ->
            val (scenario, input, argument, timestamp, expected) = row.split('\t')
            val session = sessions.getOrPut(scenario) { session().apply { maxRetries = 1 } }
            val now = timestamp.toLong()
            when (input) {
                "connect" -> session.connect(argument, now)
                "rejected" -> session.onConnectRejected(argument, now)
                "connected" -> session.onConnected()
                "disconnected" -> session.onDisconnected(now)
                "disconnect" -> session.disconnect()
                "advance" -> session.advance(now)
                else -> error("Unknown fixture input: $input")
            }
            val actual = session.drain().joinToString(";") {
                when (it) {
                    is ConnectionSession.Output.Connect -> "Connect:${it.address}"
                    is ConnectionSession.Output.StateChanged -> "StateChanged:${it.state}"
                    is ConnectionSession.Output.Error -> "Error:${it.terminal}:${it.message}"
                    ConnectionSession.Output.Close -> "Close"
                    ConnectionSession.Output.DiscoverServices -> "DiscoverServices"
                }
            }
            assertEquals(expected, actual, row)
        }
    }

    @Test
    fun connectFailureAndTimeoutAlwaysLeaveConnecting() {
        for (timeout in listOf(false, true)) {
            val session = session()
            session.connect("wheel", 0)
            assertEquals(ConnectionState.CONNECTING, session.state)
            assertIs<ConnectionSession.Output.Connect>(session.drain().last())
            session.advance(49)
            assertNull(session.pollOutput())
            if (timeout) session.advance(50) else session.onConnectRejected("rejected", 1)
            assertEquals(ConnectionState.DISCONNECTED, session.state)
            assertNull(session.nextDeadline)
            assertTrue(session.drain().filterIsInstance<ConnectionSession.Output.Error>().single().terminal)
        }
    }

    @Test
    fun reconnectBackoffAndTimeoutsExhaustBoundedAttempts() {
        val session = session().apply { maxRetries = 2 }
        session.connect("wheel", 0)
        session.drain()
        session.onConnected()
        assertEquals(ConnectionState.CONNECTED, session.state)
        assertTrue(session.drain().contains(ConnectionSession.Output.DiscoverServices))
        session.onDisconnected(10)
        session.drain()
        assertEquals(110, session.nextDeadline)
        session.advance(109)
        assertNull(session.pollOutput())
        session.advance(110)
        assertIs<ConnectionSession.Output.Connect>(session.drain().last())
        session.advance(160)
        session.drain()
        assertEquals(360, session.nextDeadline)
        session.advance(360)
        assertIs<ConnectionSession.Output.Connect>(session.drain().last())
        session.onConnectRejected("busy", 361)
        val errors = session.drain().filterIsInstance<ConnectionSession.Output.Error>()
        assertEquals(2, errors.size)
        assertTrue(errors.last().terminal)
        assertNull(session.nextDeadline)
        assertEquals(ConnectionState.DISCONNECTED, session.state)
    }

    @Test
    fun manualDisconnectCancelsReconnectAndLateConnectedCallback() {
        val session = session()
        session.connect("wheel", 0)
        session.drain()
        session.onConnected()
        session.drain()
        session.onDisconnected(1)
        session.drain()
        session.disconnect()
        session.drain()
        session.advance(10_000)
        session.onConnected()
        assertEquals(ConnectionState.DISCONNECTED, session.state)
        assertNull(session.pollOutput())
        assertNull(session.nextDeadline)
    }
}
