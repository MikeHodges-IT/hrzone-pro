package com.example.ble00001

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.bluetooth.BluetoothGattCharacteristic

// Add these to your existing Extensions.kt file:

// BluetoothGattCharacteristic extensions
fun BluetoothGattCharacteristic.isReadable(): Boolean =
    containsProperty(BluetoothGattCharacteristic.PROPERTY_READ)

fun BluetoothGattCharacteristic.isWritable(): Boolean =
    containsProperty(BluetoothGattCharacteristic.PROPERTY_WRITE)

fun BluetoothGattCharacteristic.isWritableWithoutResponse(): Boolean =
    containsProperty(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)

fun BluetoothGattCharacteristic.isIndicatable(): Boolean =
    containsProperty(BluetoothGattCharacteristic.PROPERTY_INDICATE)

fun BluetoothGattCharacteristic.isNotifiable(): Boolean =
    containsProperty(BluetoothGattCharacteristic.PROPERTY_NOTIFY)

fun BluetoothGattCharacteristic.containsProperty(property: Int): Boolean {
    return properties and property != 0
}


/**
 * Determine whether the current [Context] has been granted the relevant [Manifest.permission].
 */
fun Context.hasPermission(permissionType: String): Boolean {
    return ContextCompat.checkSelfPermission(this, permissionType) ==
            PackageManager.PERMISSION_GRANTED
}

/**
 * Determine whether the current [Context] has been granted the relevant permissions to perform
 * Bluetooth operations depending on the mobile device's Android version.
 */
fun Context.hasRequiredBluetoothPermissions(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        hasPermission(Manifest.permission.BLUETOOTH_SCAN) &&
                hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

/**
 * Convert ByteArray to hex string for debugging
 */
fun ByteArray.toHexString(): String =
    joinToString(separator = " ", prefix = "0x") { String.format("%02X", it) }