package com.example.heartratezone.presentation

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.*

class MainActivity : ComponentActivity() {

    // Service binding
    private var heartRateService: HeartRateService? = null
    private var serviceBound = false

    // State
    private var isConnected = mutableStateOf(false)
    private var currentHeartRate = mutableStateOf(0)
    private var connectionStatus = mutableStateOf("Not Connected")

    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Log.d(TAG, "All permissions granted")
            connectionStatus.value = "Permissions granted"
            startHeartRateService()
        } else {
            Log.e(TAG, "Some permissions denied")
            connectionStatus.value = "Permissions denied"
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as HeartRateService.LocalBinder
            heartRateService = binder.getService()
            serviceBound = true

            // Set up callbacks
            heartRateService?.onHeartRateChanged = { bpm ->
                currentHeartRate.value = bpm
            }

            heartRateService?.onConnectionStateChanged = { connected, status ->
                isConnected.value = connected
                connectionStatus.value = status
            }

            Log.d(TAG, "Service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            heartRateService = null
            Log.d(TAG, "Service disconnected")
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check and request permissions
        if (!hasAllPermissions()) {
            requestPermissions()
        } else {
            startHeartRateService()
        }

        setContent {
            WearApp(
                heartRate = currentHeartRate.value,
                isConnected = isConnected.value,
                status = connectionStatus.value,
                onScanClick = {
                    heartRateService?.startBleScan()
                },
                onDisconnectClick = {
                    heartRateService?.disconnect()
                }
            )
        }
    }

    private fun startHeartRateService() {
        val serviceIntent = Intent(this, HeartRateService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun hasAllPermissions(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BODY_SENSORS,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.BODY_SENSORS,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BODY_SENSORS,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.BODY_SENSORS,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        permissionLauncher.launch(permissions)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }
}

@Composable
fun WearApp(
    heartRate: Int,
    isConnected: Boolean,
    status: String,
    onScanClick: () -> Unit,
    onDisconnectClick: () -> Unit
) {
    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Heart Icon
                Icon(
                    imageVector = if (isConnected) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Heart",
                    tint = if (heartRate > 0) Color.Red else Color.Gray,
                    modifier = Modifier.size(40.dp)
                )

                // Heart Rate Display
                Text(
                    text = if (heartRate > 0) "$heartRate BPM" else "-- BPM",
                    style = MaterialTheme.typography.display1,
                    textAlign = TextAlign.Center
                )

                // Connection Status
                Text(
                    text = status,
                    style = MaterialTheme.typography.body2,
                    color = if (isConnected) Color.Green else Color.White
                )

                // Action Button
                CompactChip(
                    onClick = if (isConnected) onDisconnectClick else onScanClick,
                    label = {
                        Text(if (isConnected) "Disconnect" else "Connect ESP32")
                    }
                )
            }
        }
    }
}