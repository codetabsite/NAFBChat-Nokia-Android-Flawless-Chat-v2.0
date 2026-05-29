package com.nafbchat

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AndroidBluetoothManager(
    private val context: Context,
    private val myName: String,
    private val onMessage: (String) -> Unit
) {
    companion object {
        val NAFB_UUID: UUID = UUID.fromString("4E414642-4368-6174-0000-000000ABCD00")
        const val SERVICE_NAME = "NAFBChat"
    }

    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = btManager.adapter
    private val connections = ConcurrentHashMap<String, OutputStream>()
    private val seenMessages = mutableListOf<String>()
    private val seenLock = Any()
    private var serverSocket: BluetoothServerSocket? = null
    private var running = false
    private val activity get() = context as? ChatActivity

    fun start() {
        running = true
        startServer()
        Thread {
            Thread.sleep(1000)
            connectLoop()
        }.start()
    }

    // ─── Sunucu ───────────────────────────────────────────────────────────────

    private fun startServer() {
        Thread {
            try {
                if (!hasBtPermission()) return@Thread
                serverSocket = adapter?.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, NAFB_UUID)
                onMessage("[Sistem] Bağlantı bekleniyor...")
                while (running) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        handleSocket(socket)
                    } catch (e: IOException) {
                        if (running) onMessage("[HATA] Sunucu: ${e.message}")
                        break
                    }
                }
            } catch (e: Exception) {
                onMessage("[HATA] Sunucu: ${e.message}")
            }
        }.start()
    }

    private fun handleSocket(socket: BluetoothSocket) {
        Thread {
            val addr = socket.remoteDevice.address
            if (connections.containsKey(addr)) { socket.close(); return@Thread }
            try {
                val inp: InputStream = socket.inputStream
                val out: OutputStream = socket.outputStream

                // Kendini tanıt
                out.write(myName.toByteArray(Charsets.UTF_8))
                out.flush()

                // Karşı tarafın adını al
                val buf = ByteArray(512)
                val len = inp.read(buf)
                if (len <= 0) { socket.close(); return@Thread }
                val remoteName = String(buf, 0, len, Charsets.UTF_8)

                connections[addr] = out
                onMessage("[+] $remoteName bağlandı")
                activity?.setStatus("${connections.size} cihaz")

                while (running) {
                    val n = inp.read(buf)
                    if (n <= 0) break
                    receiveMessage(String(buf, 0, n, Charsets.UTF_8), addr)
                }
            } catch (e: IOException) {
                // Bağlantı koptu
            } finally {
                connections.remove(addr)
                onMessage("[-] Bağlantı kesildi")
                activity?.setStatus("${connections.size} cihaz")
                try { socket.close() } catch (e: IOException) {}
            }
        }.start()
    }

    // ─── İstemci: otomatik bağlanma döngüsü ──────────────────────────────────

    private fun connectLoop() {
        while (running) {
            try {
                if (!hasBtPermission()) { Thread.sleep(5000); continue }
                val bonded: Set<BluetoothDevice> = adapter?.bondedDevices ?: emptySet()
                for (device in bonded) {
                    if (!running) break
                    if (!connections.containsKey(device.address)) {
                        connectToDevice(device)
                        Thread.sleep(500)
                    }
                }
                Thread.sleep(15_000)
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Thread.sleep(10_000)
            }
        }
    }

    // ─── Manuel bağlantı (cihaz listesinden seçildi) ─────────────────────────

    fun connectToAddress(address: String) {
        if (!hasBtPermission()) return
        val device = adapter?.getRemoteDevice(address) ?: return
        connectToDevice(device)
    }

    private fun connectToDevice(device: BluetoothDevice) {
        Thread {
            val addr = device.address
            if (connections.containsKey(addr)) return@Thread
            try {
                if (!hasBtPermission()) return@Thread
                adapter?.cancelDiscovery()
                val socket: BluetoothSocket =
                    device.createInsecureRfcommSocketToServiceRecord(NAFB_UUID)
                socket.connect()
                handleSocket(socket)
            } catch (e: IOException) {
                // Bağlanamadı
            }
        }.start()
    }

    // ─── Mesh yayın ───────────────────────────────────────────────────────────

    private fun receiveMessage(msg: String, fromAddr: String) {
        val id = getMsgId(msg)
        synchronized(seenLock) {
            if (seenMessages.contains(id)) return
            seenMessages.add(id)
            if (seenMessages.size > 100) seenMessages.removeAt(0)
        }
        onMessage(msg)
        broadcast(msg, fromAddr)
    }

    fun broadcast(msg: String, exceptAddr: String?) {
        val id = getMsgId(msg)
        synchronized(seenLock) {
            if (!seenMessages.contains(id)) {
                seenMessages.add(id)
                if (seenMessages.size > 100) seenMessages.removeAt(0)
            }
        }
        val data = msg.toByteArray(Charsets.UTF_8)
        val toRemove = mutableListOf<String>()
        for ((addr, out) in connections) {
            if (addr == exceptAddr) continue
            try { out.write(data); out.flush() }
            catch (e: IOException) { toRemove.add(addr) }
        }
        toRemove.forEach { connections.remove(it) }
    }

    private fun getMsgId(msg: String) = msg.take(32)

    private fun hasBtPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (e: IOException) {}
    }
}
