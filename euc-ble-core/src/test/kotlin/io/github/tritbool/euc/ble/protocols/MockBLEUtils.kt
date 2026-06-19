package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.models.EUCDevice
import io.github.tritbool.euc.ble.models.EUCData
import java.util.UUID

/**
 * Mock BLE utilities for testing protocols without requiring actual BLE hardware
 */
object MockBLEUtils {

    /**
     * Create a mock EUC device for testing
     */
    fun createMockDevice(
        name: String = "TestWheel",
        address: String = "00:11:22:33:44:55",
        manufacturerId: Int = 0x0049,
        rssi: Int = -50,
    ): EUCDevice {
        return EUCDevice(
            name = name,
            address = address,
            manufacturerId = manufacturerId,
            rssi = rssi
        )
    }

    /**
     * Create mock EUC data for testing
     */
    fun createMockEUCData(
        speed: Double = 10.0,
        voltage: Double = 67.2,
        current: Double = 2.5,
        temperature: Double = 25.0,
        batteryLevel: Int = 80,
        distance: Double = 10.5,
        power: Double = 168.0,
        manufacturer: String = "TestManufacturer",
        model: String = "TestModel",
        isCharging: Boolean = false,
        cellVoltages: List<Double>? = null,
        motorTemperature: Double? = null
    ): EUCData {
        return EUCData(
            speed = speed,
            voltage = voltage,
            current = current,
            temperature = temperature,
            batteryLevel = batteryLevel,
            distance = distance,
            power = power,
            timestamp = System.currentTimeMillis(),
            rawData = byteArrayOf(),
            manufacturer = manufacturer,
            model = model,
            serialNumber = null,
            firmwareVersion = null,
            isCharging = isCharging,
            rideTime = 0,
            cellVoltages = cellVoltages,
            motorTemperature = motorTemperature,
        )
    }

    /**
     * Create a mock UUID for testing
     */
    fun createMockUUID(): UUID {
        return UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    /**
     * Create mock byte array data for protocol testing
     */
    fun createMockByteData(size: Int = 20): ByteArray {
        return ByteArray(size) { (it % 256).toByte() }
    }

    /**
     * Create a mock protocol test environment
     */
    fun createMockProtocolTestEnvironment() {
        // This can be expanded to mock BLE connections, characteristics, etc.
        println("Mock BLE environment initialized for testing")
    }

    /**
     * Generate realistic test data for a specific protocol
     */
    fun generateProtocolSpecificTestData(protocolName: String): ByteArray {
        return when (protocolName.lowercase()) {
            "kingsong" -> byteArrayOf(
                0xAA.toByte(), 0x55.toByte(), // Kingsong header
                0x01.toByte(), // Message type
                0x64.toByte(), 0x01.toByte(), // Voltage (36.0V)
                0x2C.toByte(), 0x01.toByte(), // Speed (30.0 km/h)
                0x40.toByte(), 0x42.toByte(), 0x0F.toByte(), 0x00.toByte(), // Distance
                0xE8.toByte(), 0x03.toByte(), // Current (100.0A)
                0x14.toByte(), 0x00.toByte(), // Temperature (2.0°C)
                0x64.toByte(), // Battery (100%)
                0x01.toByte()  // Status
            )
            "gotway" -> byteArrayOf(
                0x55.toByte(), 0xAA.toByte(), // Gotway header
                0x01.toByte(), // Message type
                0x64.toByte(), 0x01.toByte(), // Voltage (36.0V)
                0x2C.toByte(), 0x01.toByte(), // Speed (30.0 km/h)
                0x40.toByte(), 0x42.toByte(), 0x0F.toByte(), 0x00.toByte(), // Distance
                0xE8.toByte(), 0x03.toByte(), // Current (100.0A)
                0x14.toByte(), 0x00.toByte(), // Temperature (2.0°C)
                0x64.toByte(), // Battery (100%)
                0x01.toByte()  // Status
            )
            "inmotion" -> byteArrayOf(
                0xAA.toByte(), 0x55.toByte(), // InMotion header
                0x01.toByte(), // Message type
                0x10.toByte(), // Data length
                0x64.toByte(), 0x01.toByte(), // Voltage (36.0V)
                0x2C.toByte(), 0x01.toByte(), // Speed (30.0 km/h)
                0x40.toByte(), 0x42.toByte(), 0x0F.toByte(), 0x00.toByte(), // Distance
                0xE8.toByte(), 0x03.toByte(), // Current (100.0A)
                0x14.toByte(), 0x00.toByte(), // Temperature (2.0°C)
                0x64.toByte(), // Battery (100%)
                0x01.toByte()  // Status
            )
            "ninebot" -> byteArrayOf(
                0x55.toByte(), // Ninebot header
                0x18.toByte(), // Frame length
                0x01.toByte(), // Message type
                0x01.toByte(), // Sequence number
                0x64.toByte(), 0x01.toByte(), // Voltage (36.0V)
                0x2C.toByte(), 0x01.toByte(), // Speed (30.0 km/h)
                0x40.toByte(), 0x42.toByte(), 0x0F.toByte(), 0x00.toByte(), // Distance
                0xE8.toByte(), 0x03.toByte(), // Current (100.0A)
                0x14.toByte(), 0x00.toByte(), // Temperature (2.0°C)
                0x64.toByte(), // Battery (100%)
                0x01.toByte(), // Status
                0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                0x00.toByte()  // Checksum
            )
            "veteran" -> byteArrayOf(
                0x55.toByte(), 0xAA.toByte(), // Veteran header
                0x01.toByte(), // Frame type
                0x32.toByte(), // Data length
                0x64.toByte(), 0x01.toByte(), // Voltage (36.0V)
                0x2C.toByte(), 0x01.toByte(), // Speed (30.0 km/h)
                0x40.toByte(), 0x42.toByte(), 0x0F.toByte(), 0x00.toByte(), // Distance
                0x80.toByte(), 0x84.toByte(), 0x1E.toByte(), 0x00.toByte(), // Total distance
                0xE8.toByte(), 0x03.toByte(), // Current (100.0A)
                0x14.toByte(), 0x00.toByte(), // Temperature (2.0°C)
                0x00.toByte(), 0x00.toByte(), // Auto-off time
                0x00.toByte(), 0x00.toByte(), // Charge mode
                0x14.toByte(), 0x00.toByte(), // Speed alert
                0x1E.toByte(), 0x00.toByte(), // Speed tiltback
                0x01.toByte(), 0x00.toByte(), // Version info
                0x00.toByte(), 0x00.toByte(), // Pedals mode
                0x00.toByte(), 0x00.toByte(), // Pitch angle
                0x00.toByte(), 0x00.toByte(), // PWM value
                0x64.toByte(), // Battery (100%)
                0x01.toByte(), // Status
                0x01.toByte(), // BMS packet type
                0x64.toByte(), 0x01.toByte(), // Cell 1 voltage
                0x64.toByte(), 0x01.toByte(), // Cell 2 voltage
                0x64.toByte(), 0x01.toByte(), // Cell 3 voltage
                0x64.toByte(), 0x01.toByte(), // Cell 4 voltage
                0x64.toByte(), 0x01.toByte(), // Cell 5 voltage
                0x64.toByte(), 0x01.toByte(), // Cell 6 voltage
                0x64.toByte(), 0x01.toByte(), // Cell 7 voltage
                0x64.toByte(), 0x01.toByte(), // Cell 8 voltage
                0x64.toByte(), 0x01.toByte(), // Cell 9 voltage
                0x64.toByte(), 0x01.toByte(), // Cell 10 voltage
                0x64.toByte(), 0x01.toByte(), // Cell 11 voltage
                0x64.toByte(), 0x01.toByte(), // Cell 12 voltage
                0x64.toByte(), 0x01.toByte(), // Cell 13 voltage
                0x64.toByte(), 0x01.toByte(), // Cell 14 voltage
                0x64.toByte(), 0x01.toByte(), // Cell 15 voltage
                0x00.toByte()  // Checksum
            )
            else -> createMockByteData(20)
        }
    }

    /**
     * Create a mock BLE characteristic for testing
     */
    fun createMockCharacteristic(uuid: UUID = createMockUUID()): Any {
        // In a real implementation, this would return a mock BluetoothGattCharacteristic
        // For now, return a simple object that can be used in tests
        return object {
            val uuid = uuid
            override fun toString(): String = "MockCharacteristic(uuid=$uuid)"
        }
    }

    /**
     * Create a mock BLE service for testing
     */
    fun createMockService(uuid: UUID = createMockUUID()): Any {
        // In a real implementation, this would return a mock BluetoothGattService
        // For now, return a simple object that can be used in tests
        return object {
            val uuid = uuid
            override fun toString(): String = "MockService(uuid=$uuid)"
        }
    }
}