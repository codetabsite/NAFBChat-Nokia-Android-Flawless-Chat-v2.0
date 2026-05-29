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
import java.lang.reflect.Method
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

    // ─── Sunucu: önce OKUR, sonra yazar ──────────────────────────────────────

    private fun startServer() {
        Thread {
            try {
                if (!hasBtPermission()) { onMessage("[HATA] BT izni yok"); return@Thread }
                serverSocket = adapter?.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, NAFB_UUID)
                onMessage("[Sistem] Bağlantı bekleniyor...")
                while (running) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        handleAsServer(socket)
                    } catch (e: IOException) {
                        if (running) onMessage("[HATA] Sunucu: ${e.message}")
                        break
                    }
                }
            } catch (e: Exception) {
                onMessage("[HATA] Sunucu başlatma: ${e.message}")
            }
        }.start()
    }

    // Sunucu rolü: önce karşı tarafın adını OKU, sonra kendi adını YAZ
    private fun handleAsServer(socket: BluetoothSocket) {
        Thread {
            val addr = socket.remoteDevice.address
            if (connections.containsKey(addr)) { socket.close(); return@Thread }
            try {
                val inp = socket.inputStream
                val out = socket.outputStream

                // 1. Önce oku (istemci önce yazar)
                val buf = ByteArray(512)
                val len = inp.read(buf)
                if (len <= 0) { socket.close(); return@Thread }
                val remoteName = String(buf, 0, len, Charsets.UTF_8)

                // 2. Sonra yaz
                out.write(myName.toByteArray(Charsets.UTF_8))
                out.flush()

                connections[addr] = out
                onMessage("[+] $remoteName bağlandı")
                activity?.setStatus("${connections.size} cihaz")

                listenForMessages(inp, out, addr, socket)
            } catch (e: IOException) {
                onMessage("[HATA] Sunucu socket: ${e.message}")
                try { socket.close() } catch (e2: IOException) {}
            }
        }.start()
    }

    // ─── İstemci: önce YAZAR, sonra okur ────────────────────────────────────

    private fun handleAsClient(socket: BluetoothSocket) {
        Thread {
            val addr = socket.remoteDevice.address
            if (connections.containsKey(addr)) { socket.close(); return@Thread }
            try {
                val inp = socket.inputStream
                val out = socket.outputStream

                // 1. Önce yaz (sunucu önce okur)
                out.write(myName.toByteArray(Charsets.UTF_8))
                out.flush()

                // 2. Sonra oku
                val buf = ByteArray(512)
                val len = inp.read(buf)
                if (len <= 0) { socket.close(); return@Thread }
                val remoteName = String(buf, 0, len, Charsets.UTF_8)

                connections[addr] = out
                onMessage("[+] $remoteName bağlandı")
                activity?.setStatus("${connections.size} cihaz")

                listenForMessages(inp, out, addr, socket)
            } catch (e: IOException) {
                onMessage("[HATA] İstemci socket: ${e.message}")
                try { socket.close() } catch (e2: IOException) {}
            }
        }.start()
    }

    // Mesaj dinleme döngüsü
    private fun listenForMessages(inp: InputStream, out: OutputStream, addr: String, socket: BluetoothSocket) {
        try {
            val buf = ByteArray(512)
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
    }

    // ─── Otomatik bağlantı döngüsü ───────────────────────────────────────────

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
            } catch (e: InterruptedException) { break }
            catch (e: Exception) { Thread.sleep(10_000) }
        }
    }

    // ─── Manuel bağlantı ─────────────────────────────────────────────────────

    fun connectToAddress(address: String) {
        onMessage("[Bağlantı] $address deneniyor...")
        if (!hasBtPermission()) { onMessage("[HATA] BT izni yok"); return }
        try {
            val device = adapter?.getRemoteDevice(address) ?: run {
                onMessage("[HATA] Cihaz bulunamadı: $address"); return
            }
            connectToDevice(device)
        } catch (e: Exception) {
            onMessage("[HATA] connectToAddress: ${e.message}")
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        Thread {
            val addr = device.address
            if (connections.containsKey(addr)) return@Thread
            val name = try { device.name ?: addr } catch (e: Exception) { addr }
            onMessage("[Bağlantı] $name deneniyor...")
            try {
                adapter?.cancelDiscovery()
                var socket: BluetoothSocket? = null
                try {
                    socket = device.createInsecureRfcommSocketToServiceRecord(NAFB_UUID)
                    socket.connect()
                } catch (e: IOException) {
                    onMessage("[Bağlantı] Fallback deneniyor: ${e.message}")
                    try { socket?.close() } catch (e2: Exception) {}
                    try {
                        val m: Method = device.javaClass.getMethod(
                            "createInsecureRfcommSocket", Int::class.java)
                        socket = m.invoke(device, 1) as BluetoothSocket
                        socket.connect()
                    } catch (e2: Exception) {
                        onMessage("[HATA] $name: ${e2.message}")
                        return@Thread
                    }
                }
                socket?.let { handleAsClient(it) }
            } catch (e: Exception) {
                onMessage("[HATA] $name: ${e.message}")
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
