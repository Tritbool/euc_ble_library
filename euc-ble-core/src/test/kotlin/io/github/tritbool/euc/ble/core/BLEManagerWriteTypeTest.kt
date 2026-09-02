package io.github.tritbool.euc.ble.core

import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import io.github.tritbool.euc.ble.models.EUCData
import io.github.tritbool.euc.ble.protocols.CommandType
import io.github.tritbool.euc.ble.protocols.EUCProtocol
import io.github.tritbool.euc.ble.protocols.GotwayProtocol
import io.github.tritbool.euc.ble.protocols.ProtocolWriteType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.UUID

class BLEManagerWriteTypeTest {
    private lateinit var manager: BLEManager

    @BeforeEach
    fun setUp() {
        manager = BLEManager(mock<Context>(), NoOpLogger())
    }

    @Test
    fun autoUsesNoResponseWhenCharacteristicOnlySupportsWriteWithoutResponse() {
        manager.currentProtocol = TestProtocol(ProtocolWriteType.AUTO)

        assertEquals(
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            manager.resolveWriteType(characteristic(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE))
        )
    }

    @Test
    fun autoUsesDefaultWhenCharacteristicSupportsWriteWithResponse() {
        manager.currentProtocol = TestProtocol(ProtocolWriteType.AUTO)

        assertEquals(
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            manager.resolveWriteType(
                characteristic(
                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                )
            )
        )
    }

    @Test
    fun noResponsePreferenceIsHonouredWhenCharacteristicSupportsBoth() {
        manager.currentProtocol = TestProtocol(ProtocolWriteType.NO_RESPONSE)

        assertEquals(
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            manager.resolveWriteType(
                characteristic(
                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                )
            )
        )
    }

    @Test
    fun noResponsePreferenceFallsBackToDefaultWhenOnlyWriteWithResponseIsSupported() {
        manager.currentProtocol = TestProtocol(ProtocolWriteType.NO_RESPONSE)

        assertEquals(
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            manager.resolveWriteType(characteristic(BluetoothGattCharacteristic.PROPERTY_WRITE))
        )
    }

    @Test
    fun withResponsePreferenceFallsBackToNoResponseWhenUnsupported() {
        manager.currentProtocol = TestProtocol(ProtocolWriteType.WITH_RESPONSE)

        assertEquals(
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            manager.resolveWriteType(characteristic(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE))
        )
    }

    @Test
    fun gotwayCommandsAlwaysUseWriteWithoutResponse() {
        val gotway = GotwayProtocol()
        try {
            manager.currentProtocol = gotway
            assertEquals(ProtocolWriteType.NO_RESPONSE, gotway.preferredWriteType)
            assertEquals(
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                manager.resolveWriteType(characteristic(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE))
            )
            assertEquals(
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                manager.resolveWriteType(
                    characteristic(
                        BluetoothGattCharacteristic.PROPERTY_WRITE or
                                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                    )
                )
            )
        } finally {
            gotway.close()
        }
    }

    private fun characteristic(properties: Int): BluetoothGattCharacteristic =
        mock<BluetoothGattCharacteristic>().also {
            whenever(it.properties).thenReturn(properties)
        }

    private class TestProtocol(
        override val preferredWriteType: ProtocolWriteType
    ) : EUCProtocol {
        override val manufacturer: String = "Test"
        override val dataFlow: Flow<EUCData> = emptyFlow()
        override fun decode(data: ByteArray): EUCData? = null
        override fun getDataCharacteristicUUID(): UUID =
            UUID.fromString("00000000-0000-0000-0000-0000000000E1")

        override fun getServiceUUID(): UUID =
            UUID.fromString("00000000-0000-0000-0000-0000000000F0")

        override fun createCommand(commandType: CommandType, value: Any): ByteArray = byteArrayOf(0x01)
        override fun isDeviceReady(data: EUCData): Boolean = true
        override fun close() = Unit
    }
}
