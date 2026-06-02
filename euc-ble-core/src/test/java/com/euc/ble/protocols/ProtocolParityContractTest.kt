package com.euc.ble.protocols

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProtocolParityContractTest {

    @Test
    fun `kingsong supports speed limit and alarm commands`() {
        val protocol = KingsongProtocol()

        assertEquals(CommandSupport.SUPPORTED, protocol.getCommandSupport(CommandType.SET_SPEED_LIMIT))
        assertEquals(CommandSupport.SUPPORTED, protocol.getCommandSupport(CommandType.SET_ALARM_SPEED))
        assertEquals(CommandSupport.SUPPORTED, protocol.getCommandSupport(CommandType.SET_PEDALS_MODE))
        assertEquals(CommandSupport.SUPPORTED, protocol.getCommandSupport(CommandType.CALIBRATE))
        assertEquals(CommandSupport.SUPPORTED, protocol.getCommandSupport(CommandType.REQUEST_SERIAL))
        assertEquals(CommandSupport.SUPPORTED, protocol.getCommandSupport(CommandType.REQUEST_FIRMWARE))
    }

    @Test
    fun `ninebot supports explicit query commands for orchestration`() {
        val protocol = NinebotProtocol()

        assertEquals(CommandSupport.SUPPORTED, protocol.getCommandSupport(CommandType.REQUEST_SERIAL))
        assertEquals(CommandSupport.SUPPORTED, protocol.getCommandSupport(CommandType.REQUEST_FIRMWARE))
        assertEquals(CommandSupport.SUPPORTED, protocol.getCommandSupport(CommandType.REQUEST_BATTERY_INFO))
    }

    @Test
    fun `inmotion exposes startup and periodic polling plan`() {
        val protocol = InMotionProtocol()
        val plan = protocol.getPollingPlan()

        assertTrue(plan.enabled)
        assertTrue(plan.startupQueries.isNotEmpty())
        assertTrue(plan.periodicQueries.any { it.intervalMs > 0L })
    }

    @Test
    fun `ninebot z split exposes handshake and bms polling queries`() {
        val protocol = NinebotZProtocol()
        val plan = protocol.getPollingPlan()

        val startupIds = plan.startupQueries.map { it.id }.toSet()
        assertTrue(plan.enabled)
        assertTrue("ninebot-z.auth-key" in startupIds)
        assertTrue("ninebot-z.bms1" in startupIds)
        assertTrue(plan.periodicQueries.any { it.id == "ninebot-z.realtime" })
    }
}
