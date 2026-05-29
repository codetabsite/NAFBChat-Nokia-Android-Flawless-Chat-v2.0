package com.nafbchat

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.nafbchat.databinding.ActivityNameBinding

class NameActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNameBinding
    private val PERM_REQUEST = 101
    private val BT_ENABLE_REQUEST = 102
    private val PREFS = "nafbchat"
    private val KEY_NAME = "username"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNameBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Kayıtlı isim varsa direkt giriş yap
        val savedName = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_NAME, null)

        if (savedName != null) {
            // İsim var - direkt chat'e geç
            binding.etName.setText(savedName)
            binding.btnClear.visibility = View.VISIBLE
            checkPermissionsAndStart(savedName)
        }

        binding.btnOk.setOnClickListener {
            val name = binding.etName.text.toString().trim()
            if (name.isEmpty()) {
                binding.etName.error = "İsim gir"
                return@setOnClickListener
            }
            // İsmi kaydet
            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_NAME, name).apply()
            checkPermissionsAndStart(name)
        }

        // İsmi sil butonu
        binding.btnClear.setOnClickListener {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_NAME).apply()
            binding.etName.setText("")
            binding.btnClear.visibility = View.GONE
            Toast.makeText(this, "İsim silindi", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkPermissionsAndStart(name: String) {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.BLUETOOTH_SCAN)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE)
                != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (perms.isNotEmpty()) {
            binding.btnOk.tag = name
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), PERM_REQUEST)
        } else {
            ensureBluetoothEnabled(name)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQUEST) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                val name = binding.btnOk.tag as? String ?: return
                ensureBluetoothEnabled(name)
            } else {
                Toast.makeText(this, "Bluetooth izni gerekli", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun ensureBluetoothEnabled(name: String) {
        val btManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = btManager.adapter
        if (adapter == null) {
            Toast.makeText(this, "Bluetooth desteklenmiyor", Toast.LENGTH_LONG).show()
            return
        }
        if (!adapter.isEnabled) {
            binding.btnOk.tag = name
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), BT_ENABLE_REQUEST)
        } else {
            goToChat(name)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == BT_ENABLE_REQUEST) {
            if (resultCode == RESULT_OK) {
                val name = binding.btnOk.tag as? String ?: return
                goToChat(name)
            } else {
                Toast.makeText(this, "Bluetooth gerekli", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun goToChat(name: String) {
        startActivity(Intent(this, ChatActivity::class.java).putExtra("name", name))
    }
}
