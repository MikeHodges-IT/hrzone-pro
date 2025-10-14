package com.example.ble00001

import android.annotation.SuppressLint
import android.bluetooth.le.ScanResult
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

@SuppressLint("MissingPermission") // Permissions are checked before adapter is populated
class ScanResultAdapter(
    private val items: List<ScanResult>,
    private val onClickListener: ((device: ScanResult) -> Unit)
) : RecyclerView.Adapter<ScanResultAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_scan_result, parent, false)
        return ViewHolder(view, onClickListener)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item)
    }

    override fun getItemCount() = items.size

    class ViewHolder(
        itemView: View,
        private val onClickListener: ((device: ScanResult) -> Unit)
    ) : RecyclerView.ViewHolder(itemView) {

        private val deviceName: TextView = itemView.findViewById(R.id.device_name)
        private val macAddress: TextView = itemView.findViewById(R.id.mac_address)
        private val signalStrength: TextView = itemView.findViewById(R.id.signal_strength)

        fun bind(result: ScanResult) {
            // Check permissions before accessing device name
            val deviceDisplayName = if (itemView.context.hasRequiredBluetoothPermissions()) {
                result.device.name ?: "Unnamed Device"
            } else {
                "Unknown Device"
            }

            deviceName.text = deviceDisplayName
            macAddress.text = result.device.address
            signalStrength.text = "RSSI: ${result.rssi} dBm"

            // Set signal strength color based on RSSI value
            val signalColor = when {
                result.rssi > -50 -> android.R.color.holo_green_dark
                result.rssi > -70 -> android.R.color.holo_orange_dark
                else -> android.R.color.holo_red_dark
            }
            signalStrength.setTextColor(itemView.context.getColor(signalColor))

            itemView.setOnClickListener { onClickListener.invoke(result) }
        }
    }
}