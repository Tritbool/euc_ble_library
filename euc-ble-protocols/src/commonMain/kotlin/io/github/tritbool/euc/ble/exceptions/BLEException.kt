package io.github.tritbool.euc.ble.exceptions

/**
 * Custom exception for BLE operations.
 *
 * Provides detailed error information including error types for specific
 * BLE failure scenarios encountered during EUC communication.
 */
class BLEException : Exception {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
    constructor(cause: Throwable) : super(cause)
    
    /**
     * Error types for categorizing BLE exceptions.
     */
    enum class ErrorType {
        BLUETOOTH_DISABLED,
        BLUETOOTH_NOT_SUPPORTED,
        SCAN_FAILED,
        CONNECTION_FAILED,
        CONNECTION_TIMEOUT,
        SERVICE_DISCOVERY_FAILED,
        CHARACTERISTIC_NOT_FOUND,
        DATA_DECODING_FAILED,
        COMMAND_FAILED,
        MTU_NEGOTIATION_FAILED,
        DEVICE_NOT_SUPPORTED,
        UNKNOWN
    }

    private var errorType: ErrorType=ErrorType.UNKNOWN
    
    constructor(message: String, errorType: ErrorType) : super(message) {
        this.errorType = errorType
    }
    
    constructor(message: String, cause: Throwable, errorType: ErrorType) : super(message, cause) {
        this.errorType = errorType
    }
}