package com.example.ble00001

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.*

@SuppressLint("MissingPermission") // App's role to ensure permissions are available
class BluetoothConnectionManager private constructor(private val context: Context) {

    private var bluetoothGatt: BluetoothGatt? = null
    private var connectionState = BluetoothProfile.STATE_DISCONNECTED
    private val operationsQueue = mutableListOf<BleOperation>()
    private var isExecutingOperation = false

    // Callbacks for connection events
    var onConnectionStateChange: ((connected: Boolean, error: String?) -> Unit)? = null
    var onServicesDiscovered: ((services: List<BluetoothGattService>) -> Unit)? = null
    var onCharacteristicRead: ((characteristic: BluetoothGattCharacteristic, value: ByteArray) -> Unit)? = null
    var onCharacteristicWrite: ((characteristic: BluetoothGattCharacteristic, success: Boolean) -> Unit)? = null
    var onCharacteristicChanged: ((characteristic: BluetoothGattCharacteristic, value: ByteArray) -> Unit)? = null

    companion object {
        private const val TAG = "BLE00001_Manager"
        private const val GATT_MAX_MTU_SIZE = 517
        private const val CCC_DESCRIPTOR_UUID = "00002902-0000-1000-8000-00805f9b34fb"

        @Volatile
        private var INSTANCE: BluetoothConnectionManager? = null

        fun getInstance(context: Context): BluetoothConnectionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BluetoothConnectionManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    // BLE Operation Types
    private sealed class BleOperation {
        data class Connect(val device: BluetoothDevice) : BleOperation()
        object Disconnect : BleOperation()
        object DiscoverServices : BleOperation()
        data class ReadCharacteristic(val characteristic: BluetoothGattCharacteristic) : BleOperation()
        data class WriteCharacteristic(val characteristic: BluetoothGattCharacteristic, val value: ByteArray) : BleOperation()
        data class EnableNotifications(val characteristic: BluetoothGattCharacteristic) : BleOperation()
        data class DisableNotifications(val characteristic: BluetoothGattCharacteristic) : BleOperation()
        object RequestMtu : BleOperation()
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceAddress = gatt.device.address
            Log.i(TAG, "Connection state changed for $deviceAddress. Status: $status, NewState: $newState")

            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            Log.i(TAG, "Connected to $deviceAddress")
                            connectionState = BluetoothProfile.STATE_CONNECTED
                            bluetoothGatt = gatt

                            // Automatically discover services after connection
                            Handler(Looper.getMainLooper()).post {
                                discoverServices()
                            }

                            onConnectionStateChange?.invoke(true, null)
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            Log.i(TAG, "Disconnected from $deviceAddress")
                            connectionState = BluetoothProfile.STATE_DISCONNECTED
                            bluetoothGatt = null
                            gatt.close()
                            onConnectionStateChange?.invoke(false, null)
                        }
                    }
                }
                else -> {
                    Log.e(TAG, "Connection failed for $deviceAddress. Status: $status")
                    connectionState = BluetoothProfile.STATE_DISCONNECTED
                    bluetoothGatt = null
                    gatt.close()

                    val errorMsg = when (status) {
                        133 -> "GATT_ERROR (133) - Connection failed"
                        8 -> "GATT_CONNECTION_TIMEOUT (8)"
                        else -> "Connection failed with status: $status"
                    }
                    onConnectionStateChange?.invoke(false, errorMsg)
                }
            }
            completeOperation()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    Log.i(TAG, "Services discovered: ${gatt.services.size} services")

                    // Print GATT table for debugging
                    printGattTable()

                    // Request larger MTU for better throughput
                    requestMtu()

                    onServicesDiscovered?.invoke(gatt.services)
                }
                else -> {
                    Log.e(TAG, "Service discovery failed with status: $status")
                }
            }
            completeOperation()
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU changed to $mtu, success: ${status == BluetoothGatt.GATT_SUCCESS}")
            completeOperation()
        }

        // Handle both old and new callback versions for Android 13+ compatibility
        @Deprecated("Deprecated for Android 13+")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    val value = characteristic.value
                    Log.i(TAG, "Read characteristic ${characteristic.uuid}: ${value.toHexString()}")
                    onCharacteristicRead?.invoke(characteristic, value)
                }
                else -> {
                    Log.e(TAG, "Failed to read characteristic ${characteristic.uuid}. Status: $status")
                }
            }
            completeOperation()
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    Log.i(TAG, "Read characteristic ${characteristic.uuid}: ${value.toHexString()}")
                    onCharacteristicRead?.invoke(characteristic, value)
                }
                else -> {
                    Log.e(TAG, "Failed to read characteristic ${characteristic.uuid}. Status: $status")
                }
            }
            completeOperation()
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    Log.i(TAG, "Successfully wrote to characteristic ${characteristic.uuid}")
                    onCharacteristicWrite?.invoke(characteristic, true)
                }
                BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH -> {
                    Log.e(TAG, "Write failed - data too long for MTU")
                    onCharacteristicWrite?.invoke(characteristic, false)
                }
                else -> {
                    Log.e(TAG, "Failed to write characteristic ${characteristic.uuid}. Status: $status")
                    onCharacteristicWrite?.invoke(characteristic, false)
                }
            }
            completeOperation()
        }

        @Deprecated("Deprecated for Android 13+")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value
            Log.i(TAG, "Characteristic ${characteristic.uuid} changed: ${value.toHexString()}")
            onCharacteristicChanged?.invoke(characteristic, value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            Log.i(TAG, "Characteristic ${characteristic.uuid} changed: ${value.toHexString()}")
            onCharacteristicChanged?.invoke(characteristic, value)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    Log.i(TAG, "Successfully wrote descriptor ${descriptor.uuid}")
                }
                else -> {
                    Log.e(TAG, "Failed to write descriptor ${descriptor.uuid}. Status: $status")
                }
            }
            completeOperation()
        }
    }

    // Public API
    fun connect(device: BluetoothDevice) {
        if (!context.hasRequiredBluetoothPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions")
            onConnectionStateChange?.invoke(false, "Missing Bluetooth permissions")
            return
        }

        Log.i(TAG, "Connecting to ${device.address}")
        enqueueOperation(BleOperation.Connect(device))
    }

    fun disconnect() {
        enqueueOperation(BleOperation.Disconnect)
    }

    fun discoverServices() {
        enqueueOperation(BleOperation.DiscoverServices)
    }

    fun readCharacteristic(characteristic: BluetoothGattCharacteristic) {
        if (characteristic.isReadable()) {
            enqueueOperation(BleOperation.ReadCharacteristic(characteristic))
        } else {
            Log.e(TAG, "Characteristic ${characteristic.uuid} is not readable")
        }
    }

    fun writeCharacteristic(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.isWritable() || characteristic.isWritableWithoutResponse()) {
            enqueueOperation(BleOperation.WriteCharacteristic(characteristic, value))
        } else {
            Log.e(TAG, "Characteristic ${characteristic.uuid} is not writable")
        }
    }

    fun enableNotifications(characteristic: BluetoothGattCharacteristic) {
        if (characteristic.isNotifiable() || characteristic.isIndicatable()) {
            enqueueOperation(BleOperation.EnableNotifications(characteristic))
        } else {
            Log.e(TAG, "Characteristic ${characteristic.uuid} doesn't support notifications/indications")
        }
    }

    fun disableNotifications(characteristic: BluetoothGattCharacteristic) {
        enqueueOperation(BleOperation.DisableNotifications(characteristic))
    }

    fun requestMtu() {
        enqueueOperation(BleOperation.RequestMtu)
    }

    fun isConnected(): Boolean = connectionState == BluetoothProfile.STATE_CONNECTED

    fun getServices(): List<BluetoothGattService> = bluetoothGatt?.services ?: emptyList()

    // Queue Management
    private fun enqueueOperation(operation: BleOperation) {
        operationsQueue.add(operation)
        if (!isExecutingOperation) {
            executeNextOperation()
        }
    }

    private fun executeNextOperation() {
        if (operationsQueue.isEmpty()) {
            isExecutingOperation = false
            return
        }

        isExecutingOperation = true
        val operation = operationsQueue.removeAt(0)

        when (operation) {
            is BleOperation.Connect -> {
                bluetoothGatt = operation.device.connectGatt(context, false, gattCallback)
            }
            is BleOperation.Disconnect -> {
                bluetoothGatt?.disconnect() ?: completeOperation()
            }
            is BleOperation.DiscoverServices -> {
                bluetoothGatt?.discoverServices() ?: completeOperation()
            }
            is BleOperation.ReadCharacteristic -> {
                bluetoothGatt?.readCharacteristic(operation.characteristic) ?: completeOperation()
            }
            is BleOperation.WriteCharacteristic -> {
                writeCharacteristicInternal(operation.characteristic, operation.value)
            }
            is BleOperation.EnableNotifications -> {
                enableNotificationsInternal(operation.characteristic)
            }
            is BleOperation.DisableNotifications -> {
                disableNotificationsInternal(operation.characteristic)
            }
            is BleOperation.RequestMtu -> {
                bluetoothGatt?.requestMtu(GATT_MAX_MTU_SIZE) ?: completeOperation()
            }
        }
    }

    private fun completeOperation() {
        isExecutingOperation = false
        executeNextOperation()
    }

    // Internal implementations
    private fun writeCharacteristicInternal(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        val writeType = when {
            characteristic.isWritable() -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.isWritableWithoutResponse() -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else -> {
                Log.e(TAG, "Characteristic ${characteristic.uuid} cannot be written to")
                completeOperation()
                return
            }
        }

        bluetoothGatt?.let { gatt ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(characteristic, value, writeType)
            } else {
                // Legacy method for Android < 13
                @Suppress("DEPRECATION")
                characteristic.writeType = writeType
                @Suppress("DEPRECATION")
                characteristic.value = value
                gatt.writeCharacteristic(characteristic)
            }
        } ?: completeOperation()
    }

    private fun enableNotificationsInternal(characteristic: BluetoothGattCharacteristic) {
        val cccdUuid = UUID.fromString(CCC_DESCRIPTOR_UUID)
        val descriptor = characteristic.getDescriptor(cccdUuid)

        if (descriptor == null) {
            Log.e(TAG, "CCCD not found for ${characteristic.uuid}")
            completeOperation()
            return
        }

        val payload = when {
            characteristic.isIndicatable() -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            characteristic.isNotifiable() -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else -> {
                Log.e(TAG, "Characteristic ${characteristic.uuid} doesn't support notifications/indications")
                completeOperation()
                return
            }
        }

        if (bluetoothGatt?.setCharacteristicNotification(characteristic, true) != true) {
            Log.e(TAG, "setCharacteristicNotification failed for ${characteristic.uuid}")
            completeOperation()
            return
        }

        writeDescriptor(descriptor, payload)
    }

    private fun disableNotificationsInternal(characteristic: BluetoothGattCharacteristic) {
        val cccdUuid = UUID.fromString(CCC_DESCRIPTOR_UUID)
        val descriptor = characteristic.getDescriptor(cccdUuid)

        if (descriptor == null) {
            Log.e(TAG, "CCCD not found for ${characteristic.uuid}")
            completeOperation()
            return
        }

        if (bluetoothGatt?.setCharacteristicNotification(characteristic, false) != true) {
            Log.e(TAG, "setCharacteristicNotification failed for ${characteristic.uuid}")
            completeOperation()
            return
        }

        writeDescriptor(descriptor, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
    }

    private fun writeDescriptor(descriptor: BluetoothGattDescriptor, value: ByteArray) {
        bluetoothGatt?.let { gatt ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, value)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = value
                gatt.writeDescriptor(descriptor)
            }
        } ?: completeOperation()
    }

    private fun printGattTable() {
        bluetoothGatt?.services?.forEach { service ->
            Log.i(TAG, "Service: ${service.uuid}")
            service.characteristics.forEach { characteristic ->
                Log.i(TAG, "  Characteristic: ${characteristic.uuid}")
                Log.i(TAG, "    Properties: ${characteristic.properties}")
                characteristic.descriptors.forEach { descriptor ->
                    Log.i(TAG, "    Descriptor: ${descriptor.uuid}")
                }
            }
        }
    }

    // Clean up resources
    fun close() {
        operationsQueue.clear()
        bluetoothGatt?.close()
        bluetoothGatt = null
        connectionState = BluetoothProfile.STATE_DISCONNECTED
    }
}