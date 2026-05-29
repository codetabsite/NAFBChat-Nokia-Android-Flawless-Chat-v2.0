package com.nafbchat

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nafbchat.databinding.ActivityDeviceListBinding

class DeviceListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceListBinding
    private lateinit var adapter: BluetoothAdapter
    private val devices = mutableListOf<BluetoothDevice>()
    private val seenAddrs = mutableSetOf<String>()
    private lateinit var deviceAdapter: DeviceAdapter

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        else
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

                    device?.let {
                        if (!seenAddrs.contains(it.address)) {
                            seenAddrs.add(it.address)
                            devices.add(it)
                            deviceAdapter.notifyItemInserted(devices.size - 1)
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    binding.btnScan.text = "TEKRAR TARA"
                    binding.btnScan.isEnabled = true
                    binding.progressBar.visibility = View.GONE
                    if (devices.isEmpty()) {
                        binding.tvEmpty.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val btManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        adapter = btManager.adapter ?: run {
            Toast.makeText(this, "Bluetooth yok", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        // Eşleşmiş cihazları başa ekle
        val bonded = try { adapter.bondedDevices } catch (e: SecurityException) { emptySet() }
        bonded?.forEach {
            if (!seenAddrs.contains(it.address)) {
                seenAddrs.add(it.address)
                devices.add(it)
            }
        }

        deviceAdapter = DeviceAdapter(devices) { device ->
            // Seçilen cihazı ChatActivity'e gönder
            val result = Intent()
            result.putExtra("device_address", device.address)
            result.putExtra("device_name", getDeviceName(device))
            setResult(RESULT_OK, result)
            finish()
        }

        binding.rvDevices.layoutManager = LinearLayoutManager(this)
        binding.rvDevices.adapter = deviceAdapter

        // Broadcast receiver kaydet
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(receiver, filter)

        binding.btnScan.setOnClickListener { startScan() }
        binding.btnBack.setOnClickListener { finish() }

        startScan()
        deviceAdapter.notifyDataSetChanged()
    }

    private fun startScan() {
        if (!hasScanPermission()) {
            Toast.makeText(this, "Bluetooth tarama izni gerekli", Toast.LENGTH_SHORT).show()
            return
        }
        binding.tvEmpty.visibility = View.GONE
        binding.progressBar.visibility = View.VISIBLE
        binding.btnScan.text = "TARANIYOR..."
        binding.btnScan.isEnabled = false

        if (adapter.isDiscovering) adapter.cancelDiscovery()
        adapter.startDiscovery()
    }

    private fun getDeviceName(device: BluetoothDevice): String {
        return try {
            device.name ?: device.address
        } catch (e: SecurityException) {
            device.address
        }
    }

    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(receiver)
            adapter.cancelDiscovery()
        } catch (e: Exception) {}
    }
}

class DeviceAdapter(
    private val devices: List<BluetoothDevice>,
    private val onClick: (BluetoothDevice) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvDeviceName)
        val tvAddr: TextView = view.findViewById(R.id.tvDeviceAddr)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val device = devices[position]
        val name = try { device.name ?: "Bilinmeyen" } catch (e: SecurityException) { "Bilinmeyen" }
        holder.tvName.text = name
        holder.tvAddr.text = device.address
        holder.itemView.setOnClickListener { onClick(device) }
    }

    override fun getItemCount() = devices.size
}
