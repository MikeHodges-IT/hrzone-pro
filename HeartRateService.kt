package com.example.heartratezone.presentation

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.util.UUID

class HeartRateService : Service(), SensorEventListener {

    private val binder = LocalBinder()
    private lateinit var sensorManager: SensorManager
    private var heartRateSensor: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // BLE Components
    private var bluetoothGatt: BluetoothGatt? = null
    private var heartRateCharacteristic: BluetoothGattCharacteristic? = null

    // State
    private var isConnected = false
    private var currentHeartRate = 0
    private var lastTransmissionTime = 0L
    private val TRANSMISSION_INTERVAL_MS = 2000L // Send every 2 seconds

    // Callbacks for UI updates
    var onHeartRateChanged: ((Int) -> Unit)? = null
    var onConnectionStateChanged: ((Boolean, String) -> Unit)? = null

    companion object {
        private const val TAG = "HeartRateService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "heart_rate_channel"

        // BLE Constants
        private const val SERVICE_UUID = "12345678-1234-1234-1234-123456789abc"
        private const val HEART_RATE_CHAR_UUID = "12345678-1234-1234-1234-123456789001"
        private const val TARGET_DEVICE_NAME = "HRZone"
    }

    inner class LocalBinder : Binder() {
        fun getService(): HeartRateService = this@HeartRateService
    }

    override fun onCreate() {
        super.onCreate()

        // Initialize sensors
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)

        // Acquire wake lock to keep CPU running
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "HeartRateService::WakeLock"
        )
        wakeLock?.acquire()

        // Create notification channel
        createNotificationChannel()

        // Start as foreground service
        startForeground(NOTIFICATION_ID, createNotification())

        // Start heart rate monitoring
        startHeartRateMonitoring()

        Log.d(TAG, "Service created")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Heart Rate Monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Continuous heart rate monitoring"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Heart Rate Monitor")
            .setContentText("Monitoring: ${if (isConnected) "$currentHeartRate BPM" else "Not connected"}")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    @SuppressLint("NotificationPermission")
    private fun updateNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification())
    }

    private fun startHeartRateMonitoring() {
        heartRateSensor?.also { sensor ->
            sensorManager.registerListener(
                this,
                 sensor,
                SensorManager.SENSOR_DELAY_NORMAL,
                0
                )
            Log.d(TAG, "Heart rate monitoring started")
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_HEART_RATE) {
            val bpm = event.values[0].toInt()
            currentHeartRate = bpm

            // Update UI callback
            onHeartRateChanged?.invoke(bpm)

            // Send to ESP32 with throttling
            val currentTime = System.currentTimeMillis()
            if (isConnected &&
                currentTime - lastTransmissionTime >= TRANSMISSION_INTERVAL_MS) {
                sendHeartRateToEsp32(bpm)
                lastTransmissionTime = currentTime
                updateNotification()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used
    }

    @SuppressLint("MissingPermission")
    fun startBleScan() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter

        if (!bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth is disabled")
            onConnectionStateChanged?.invoke(false, "Bluetooth disabled")
            return
        }

        val bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner

        onConnectionStateChanged?.invoke(false, "Scanning...")
        Log.d(TAG, "Starting scan for: $TARGET_DEVICE_NAME")

        val scanCallback = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                val device = result.device
                val name = device.name

                if (name == TARGET_DEVICE_NAME || name == "HRZone") {
                    Log.d(TAG, "Found target: $name at ${device.address}")
                    bluetoothLeScanner.stopScan(this)
                    connectToDevice(device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: $errorCode")
                onConnectionStateChanged?.invoke(false, "Scan failed: $errorCode")
            }
        }

        bluetoothLeScanner.startScan(scanCallback)

        // Stop after 15 seconds
        CoroutineScope(Dispatchers.Main).launch {
            delay(15000)
            bluetoothLeScanner.stopScan(scanCallback)
            if (!isConnected) {
                onConnectionStateChanged?.invoke(false, "Device not found")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        onConnectionStateChanged?.invoke(false, "Connecting...")

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(TAG, "Connected to ESP32")
                        onConnectionStateChanged?.invoke(false, "Discovering services...")
                        gatt.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d(TAG, "Disconnected from ESP32")
                        isConnected = false
                        onConnectionStateChanged?.invoke(false, "Disconnected")
                        updateNotification()
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val service = gatt.getService(UUID.fromString(SERVICE_UUID))
                    heartRateCharacteristic = service?.getCharacteristic(UUID.fromString(HEART_RATE_CHAR_UUID))

                    if (heartRateCharacteristic != null) {
                        isConnected = true
                        onConnectionStateChanged?.invoke(true, "Connected to ESP32")
                        updateNotification()
                    } else {
                        onConnectionStateChanged?.invoke(false, "Service not found")
                    }
                }
            }
        }

        bluetoothGatt = device.connectGatt(this, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    private fun sendHeartRateToEsp32(bpm: Int) {
        heartRateCharacteristic?.let { char ->
            val data = "HR:$bpm"
            char.value = data.toByteArray()
            bluetoothGatt?.writeCharacteristic(char)
            Log.d(TAG, "Sent heart rate to ESP32: $data")
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        isConnected = false
        onConnectionStateChanged?.invoke(false, "Disconnected")
        updateNotification()
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        disconnect()
        wakeLock?.release()
        Log.d(TAG, "Service destroyed")
    }
}