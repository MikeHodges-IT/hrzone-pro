package com.example.ble00001

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.*

@SuppressLint("MissingPermission")
class BleControlActivity : AppCompatActivity() {

    private lateinit var deviceNameText: TextView
    private lateinit var connectionStatusText: TextView
    private lateinit var ledOnButton: Button
    private lateinit var ledOffButton: Button
    private lateinit var redButton: Button
    private lateinit var greenButton: Button
    private lateinit var blueButton: Button
    private lateinit var rainbowButton: Button
    private lateinit var blinkButton: Button
    private lateinit var customColorButton: Button
    private lateinit var redSeekBar: SeekBar
    private lateinit var greenSeekBar: SeekBar
    private lateinit var blueSeekBar: SeekBar
    private lateinit var colorPreview: View
    private lateinit var notificationsText: TextView
    private lateinit var scrollView: ScrollView

    private var connectionManager: BluetoothConnectionManager? = null
    private var ledCharacteristic: BluetoothGattCharacteristic? = null
    private var sensorCharacteristic: BluetoothGattCharacteristic? = null
    private var counterCharacteristic: BluetoothGattCharacteristic? = null

    companion object {
        private const val TAG = "BLE00001_Control"
        const val EXTRA_DEVICE_NAME = "device_name"
        const val EXTRA_DEVICE_ADDRESS = "device_address"

        // Service and characteristic UUIDs (match ESP32 firmware)
        private const val SERVICE_UUID = "12345678-1234-1234-1234-123456789abc"
        private const val LED_CHAR_UUID = "12345678-1234-1234-1234-123456789001"
        private const val SENSOR_CHAR_UUID = "12345678-1234-1234-1234-123456789002"
        private const val COUNTER_CHAR_UUID = "12345678-1234-1234-1234-123456789004"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ble_control)

        initializeViews()
        setupConnectionManager()
        setupClickListeners()

        val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "Unknown Device"
        val deviceAddress = intent.getStringExtra(EXTRA_DEVICE_ADDRESS) ?: "Unknown Address"

        deviceNameText.text = deviceName
        connectionStatusText.text = "Connected to $deviceAddress"

        // Find characteristics after connection is established
        findCharacteristics()
    }

    private fun initializeViews() {
        deviceNameText = findViewById(R.id.device_name_text)
        connectionStatusText = findViewById(R.id.connection_status_text)
        ledOnButton = findViewById(R.id.led_on_button)
        ledOffButton = findViewById(R.id.led_off_button)
        redButton = findViewById(R.id.red_button)
        greenButton = findViewById(R.id.green_button)
        blueButton = findViewById(R.id.blue_button)
        rainbowButton = findViewById(R.id.rainbow_button)
        blinkButton = findViewById(R.id.blink_button)
        customColorButton = findViewById(R.id.custom_color_button)
        redSeekBar = findViewById(R.id.red_seekbar)
        greenSeekBar = findViewById(R.id.green_seekbar)
        blueSeekBar = findViewById(R.id.blue_seekbar)
        colorPreview = findViewById(R.id.color_preview)
        notificationsText = findViewById(R.id.notifications_text)
        scrollView = findViewById(R.id.scroll_view)
    }

    private fun setupConnectionManager() {
        // Get the existing connected instance - don't create a new one
        connectionManager = BluetoothConnectionManager.getInstance(this)

        // Set up the notification callback to display data
        connectionManager?.onCharacteristicChanged = { characteristic, value ->
            runOnUiThread {
                val dataString = String(value)
                val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    .format(Date())

                val notification = "$timestamp: $dataString\n"
                notificationsText.append(notification)

                // Auto-scroll to bottom
                scrollView.post {
                    scrollView.fullScroll(ScrollView.FOCUS_DOWN)
                }
            }
        }
    }
    private fun setupClickListeners() {
        ledOnButton.setOnClickListener { sendLedCommand("ON") }
        ledOffButton.setOnClickListener { sendLedCommand("OFF") }
        redButton.setOnClickListener { sendLedCommand("RED") }
        greenButton.setOnClickListener { sendLedCommand("GREEN") }
        blueButton.setOnClickListener { sendLedCommand("BLUE") }
        rainbowButton.setOnClickListener { sendLedCommand("RAINBOW") }
        blinkButton.setOnClickListener { sendLedCommand("BLINK") }

        customColorButton.setOnClickListener {
            val r = redSeekBar.progress
            val g = greenSeekBar.progress
            val b = blueSeekBar.progress
            sendLedCommand("RGB:$r,$g,$b")
        }

        // Update color preview as sliders change
        val colorUpdateListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateColorPreview()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }

        redSeekBar.setOnSeekBarChangeListener(colorUpdateListener)
        greenSeekBar.setOnSeekBarChangeListener(colorUpdateListener)
        blueSeekBar.setOnSeekBarChangeListener(colorUpdateListener)
    }

    private fun findCharacteristics() {
        // Use getInstance to get the connected manager
        val manager = BluetoothConnectionManager.getInstance(this)
        val services = manager.getServices()

        Log.i(TAG, "Looking for services. Found ${services.size} services")

        val customService = services.find {
            it.uuid.toString().equals(SERVICE_UUID, ignoreCase = true)
        }

        if (customService != null) {
            Log.i(TAG, "Found custom service!")

            ledCharacteristic = customService.getCharacteristic(
                UUID.fromString(LED_CHAR_UUID)
            )
            sensorCharacteristic = customService.getCharacteristic(
                UUID.fromString(SENSOR_CHAR_UUID)
            )
            counterCharacteristic = customService.getCharacteristic(
                UUID.fromString(COUNTER_CHAR_UUID)
            )

            // Enable notifications for sensor and counter
            sensorCharacteristic?.let {
                manager.enableNotifications(it)
                Log.i(TAG, "Enabled notifications for sensor")
            }
            counterCharacteristic?.let {
                manager.enableNotifications(it)
                Log.i(TAG, "Enabled notifications for counter")
            }

            Log.i(TAG, "LED characteristic found: ${ledCharacteristic != null}")
            Log.i(TAG, "Sensor characteristic found: ${sensorCharacteristic != null}")
            Log.i(TAG, "Counter characteristic found: ${counterCharacteristic != null}")
        } else {
            Log.e(TAG, "Custom service not found! Available services:")
            services.forEach { service ->
                Log.e(TAG, "  Service UUID: ${service.uuid}")
            }
        }
    }

    private fun sendLedCommand(command: String) {
        ledCharacteristic?.let { characteristic ->
            val manager = BluetoothConnectionManager.getInstance(this)
            manager.writeCharacteristic(characteristic, command.toByteArray())
            Log.i(TAG, "Sent LED command: $command")
        } ?: run {
            Log.e(TAG, "LED characteristic not available")
            Toast.makeText(this, "LED control not available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateColorPreview() {
        val r = redSeekBar.progress
        val g = greenSeekBar.progress
        val b = blueSeekBar.progress

        val color = Color.rgb(r, g, b)
        colorPreview.setBackgroundColor(color)
    }

    override fun onDestroy() {
        super.onDestroy()
        connectionManager?.close()
    }
}