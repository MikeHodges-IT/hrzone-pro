package com.example.ble00001

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

@Suppress("MissingPermission") // Permissions are checked via hasRequiredBluetoothPermissions()
class MainActivity : AppCompatActivity() {

    // UI components
    private lateinit var scanButton: Button
    private lateinit var statusText: TextView
    private lateinit var scanResultsRecyclerView: RecyclerView

    // BLE components
    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private val bleScanner: BluetoothLeScanner by lazy {
        bluetoothAdapter.bluetoothLeScanner
    }

    private val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()

    // Connection Manager
    private val connectionManager: BluetoothConnectionManager by lazy {
        BluetoothConnectionManager.getInstance(this).apply {
            onConnectionStateChange = { connected, error ->
                runOnUiThread {
                    if (connected) {
                        statusText.text = "Connected! Discovering services..."
                    } else {
                        statusText.text = error ?: "Disconnected"
                    }
                }
            }

            onServicesDiscovered = { services ->
                runOnUiThread {
                    statusText.text = "Connected! Found ${services.size} services"
                    Log.i(TAG, "Available services:")
                    services.forEach { service ->
                        Log.i(TAG, "Service: ${service.uuid} (${service.characteristics.size} characteristics)")
                    }

                    // Launch control activity after successful service discovery
                    launchControlActivity()
                }
            }

            onCharacteristicChanged = { characteristic, value ->
                runOnUiThread {
                    Log.i(TAG, "Notification from ${characteristic.uuid}: ${value.toHexString()}")
                }
            }
        }
    }

    private var connectedDeviceName: String? = null
    private var connectedDeviceAddress: String? = null

    // Scan state management
    private var isScanning = false
        set(value) {
            field = value
            runOnUiThread {
                scanButton.text = if (value) "Stop Scan" else "Start Scan"
                statusText.text = if (value) "Scanning for BLE devices..." else "Ready to scan"
            }
        }

    // Scan results
    private val scanResults = mutableListOf<ScanResult>()
    private val scanResultAdapter: ScanResultAdapter by lazy {
        ScanResultAdapter(scanResults) { result ->
            // Handle device selection (connect)
            if (isScanning) {
                stopBleScan()
            }
            connectToDevice(result)
        }
    }

    // Permission handling
    private val bluetoothEnablingResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Bluetooth is enabled, good to go
            statusText.text = "Bluetooth enabled - ready to scan"
        } else {
            // User dismissed or denied Bluetooth prompt
            promptEnableBluetooth()
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1
        private const val TAG = "BLE00001"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize views
        scanButton = findViewById(R.id.scan_button)
        statusText = findViewById(R.id.status_text)
        scanResultsRecyclerView = findViewById(R.id.scan_results_recycler_view)

        setupRecyclerView()
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        if (!bluetoothAdapter.isEnabled) {
            promptEnableBluetooth()
        }
    }

    private fun setupRecyclerView() {
        scanResultsRecyclerView.apply {
            adapter = scanResultAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
        }
    }

    private fun setupClickListeners() {
        scanButton.setOnClickListener {
            if (isScanning) {
                stopBleScan()
            } else {
                startBleScan()
            }
        }
    }

    // BLE Scanning Functions
    private fun startBleScan() {
        if (!hasRequiredBluetoothPermissions()) {
            requestRelevantRuntimePermissions()
            return
        }

        scanResults.clear()
        scanResultAdapter.notifyDataSetChanged()
        bleScanner.startScan(null, scanSettings, scanCallback)
        isScanning = true
    }

    private fun stopBleScan() {
        if (!hasRequiredBluetoothPermissions()) {
            return
        }
        bleScanner.stopScan(scanCallback)
        isScanning = false
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!hasRequiredBluetoothPermissions()) {
                return
            }

            val indexQuery = scanResults.indexOfFirst { it.device.address == result.device.address }
            if (indexQuery != -1) {
                // Update existing scan result
                scanResults[indexQuery] = result
                scanResultAdapter.notifyItemChanged(indexQuery)
            } else {
                // Add new scan result
                val deviceName = if (hasRequiredBluetoothPermissions()) {
                    result.device.name ?: "Unnamed"
                } else {
                    "Unknown"
                }
                Log.i(TAG, "Found BLE device! Name: $deviceName, address: ${result.device.address}")
                scanResults.add(result)
                scanResultAdapter.notifyItemInserted(scanResults.size - 1)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed with error code: $errorCode")
            isScanning = false
            statusText.text = "Scan failed with error: $errorCode"
        }
    }

    // Permission Management
    private fun requestRelevantRuntimePermissions() {
        if (hasRequiredBluetoothPermissions()) {
            return
        }
        when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> {
                requestLocationPermission()
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                requestBluetoothPermissions()
            }
        }
    }

    private fun requestLocationPermission() = runOnUiThread {
        AlertDialog.Builder(this)
            .setTitle("Location permission required")
            .setMessage(
                "Starting from Android M (6.0), the system requires apps to be granted " +
                        "location access in order to scan for BLE devices."
            )
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    PERMISSION_REQUEST_CODE
                )
            }
            .show()
    }

    private fun requestBluetoothPermissions() = runOnUiThread {
        AlertDialog.Builder(this)
            .setTitle("Bluetooth permission required")
            .setMessage(
                "Starting from Android 12, the system requires apps to be granted " +
                        "Bluetooth access in order to scan for and connect to BLE devices."
            )
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ),
                    PERMISSION_REQUEST_CODE
                )
            }
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST_CODE) return

        val containsPermanentDenial = permissions.zip(grantResults.toTypedArray()).any {
            it.second == PackageManager.PERMISSION_DENIED &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(this, it.first)
        }
        val containsDenial = grantResults.any { it == PackageManager.PERMISSION_DENIED }
        val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }

        when {
            containsPermanentDenial -> {
                statusText.text = "Permissions permanently denied. Please enable in Settings."
                AlertDialog.Builder(this)
                    .setTitle("Permissions Required")
                    .setMessage("BLE functionality requires permissions. Please enable them in App Settings.")
                    .setPositiveButton("OK") { _, _ -> }
                    .show()
            }
            containsDenial -> {
                requestRelevantRuntimePermissions()
            }
            allGranted && hasRequiredBluetoothPermissions() -> {
                statusText.text = "Permissions granted - ready to scan"
                startBleScan()
            }
            else -> {
                // Unexpected scenario
                recreate()
            }
        }
    }

    // Bluetooth Enable Management
    private fun promptEnableBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        ) {
            // Insufficient permission to prompt for Bluetooth enabling
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).apply {
                bluetoothEnablingResult.launch(this)
            }
        }
    }

    // Device Connection
    private fun connectToDevice(scanResult: ScanResult) {
        if (!hasRequiredBluetoothPermissions()) {
            statusText.text = "Missing Bluetooth permissions"
            return
        }

        val device = scanResult.device
        val deviceName = if (hasRequiredBluetoothPermissions()) {
            device.name ?: "Unnamed"
        } else {
            "Unknown"
        }

        // Store device info for control activity
        connectedDeviceName = deviceName
        connectedDeviceAddress = device.address

        Log.i(TAG, "Attempting to connect to $deviceName (${device.address})")
        statusText.text = "Connecting to ${deviceName}..."

        // Connect using the connection manager
        connectionManager.connect(device)
    }

    private fun launchControlActivity() {
        val intent = Intent(this, BleControlActivity::class.java).apply {
            putExtra(BleControlActivity.EXTRA_DEVICE_NAME, connectedDeviceName)
            putExtra(BleControlActivity.EXTRA_DEVICE_ADDRESS, connectedDeviceAddress)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        connectionManager.close()
    }
}