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

    // Kendi MAC adresi
    private val myAddress: String get() = try {
        adapter?.address ?: "00:00:00:00:00:00"
    } catch (e: Exception) { "00:00:00:00:00:00" }

    fun start() {
        running = true
        startServer()
        Thread {
            Thread.sleep(2000)
            connectLoop()
        }.start()
    }

    // ─── Sunucu ───────────────────────────────────────────────────────────────

    private fun startServer() {
        Thread {
            try {
                if (!hasBtPermission()) { onMessage("[HATA] BT izni yok"); return@Thread }
                serverSocket = adapter?.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, NAFB_UUID)
                onMessage("[Sistem] Sunucu hazır, bağlantı bekleniyor...")
                while (running) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        val addr = socket.remoteDevice.address
                        onMessage("[Sistem] Gelen bağlantı: $addr")
                        // Sunucu rolünde: önce oku sonra yaz
                        handleConnection(socket, isServer = true)
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

    // ─── Bağlantı döngüsü ────────────────────────────────────────────────────

    private fun connectLoop() {
        while (running) {
            try {
                if (!hasBtPermission()) { Thread.sleep(5000); continue }
                val bonded: Set<BluetoothDevice> = adapter?.bondedDevices ?: emptySet()
                onMessage("[Tarama] ${bonded.size} eşleşmiş cihaz")

                for (device in bonded) {
                    if (!running) break
                    val addr = device.address
                    if (connections.containsKey(addr)) continue

                    // MAC karşılaştırması: büyük MAC bağlanır, küçük bekler
                    // Bu çakışmayı önler
                    val myMac = myAddress.replace(":", "")
                    val theirMac = addr.replace(":", "")
                    if (myMac > theirMac) {
                        onMessage("[Bağlantı] ${device.name ?: addr} — ben bağlanıyorum")
                        connectToDevice(device)
                        Thread.sleep(1000)
                    } else {
                        onMessage("[Bağlantı] ${device.name ?: addr} — onların bağlanması bekleniyor")
                    }
                }
                Thread.sleep(15_000)
            } catch (e: InterruptedException) { break }
            catch (e: Exception) {
                onMessage("[HATA] Döngü: ${e.message}")
                Thread.sleep(10_000)
            }
        }
    }

    // ─── Manuel bağlantı ─────────────────────────────────────────────────────

    fun connectToAddress(address: String) {
        onMessage("[Bağlantı] Manuel: $address")
        if (!hasBtPermission()) { onMessage("[HATA] BT izni yok"); return }
        try {
            val device = adapter?.getRemoteDevice(address) ?: run {
                onMessage("[HATA] Cihaz bulunamadı"); return
            }
            connectToDevice(device)
        } catch (e: Exception) {
            onMessage("[HATA] Manuel bağlantı: ${e.message}")
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        Thread {
            val addr = device.address
            if (connections.containsKey(addr)) return@Thread
            val name = try { device.name ?: addr } catch (e: Exception) { addr }
            try {
                adapter?.cancelDiscovery()
                var socket: BluetoothSocket? = null

                // 1. Normal UUID ile dene
                try {
                    socket = device.createInsecureRfcommSocketToServiceRecord(NAFB_UUID)
                    socket.connect()
                    onMessage("[Bağlantı] $name bağlandı (UUID)")
                } catch (e: IOException) {
                    onMessage("[Bağlantı] UUID başarısız, port 1 deneniyor...")
                    try { socket?.close() } catch (ignored: Exception) {}

                    // 2. Reflection port 1
                    try {
                        val m: Method = device.javaClass.getMethod(
                            "createInsecureRfcommSocket", Int::class.java)
                        socket = m.invoke(device, 1) as BluetoothSocket
                        socket.connect()
                        onMessage("[Bağlantı] $name bağlandı (port 1)")
                    } catch (e2: Exception) {
                        onMessage("[HATA] $name bağlanamadı: ${e2.message}")
                        return@Thread
                    }
                }

                socket?.let { handleConnection(it, isServer = false) }
            } catch (e: Exception) {
                onMessage("[HATA] $name: ${e.message}")
            }
        }.start()
    }

    // ─── El sıkışma: isServer=true → önce oku; false → önce yaz ──────────────

    private fun handleConnection(socket: BluetoothSocket, isServer: Boolean) {
        Thread {
            val addr = socket.remoteDevice.address
            if (connections.containsKey(addr)) { socket.close(); return@Thread }
            try {
                val inp = socket.inputStream
                val out = socket.outputStream
                val buf = ByteArray(512)
                val remoteName: String

                if (isServer) {
                    // Sunucu: önce oku, sonra yaz
                    val len = inp.read(buf)
                    if (len <= 0) { socket.close(); return@Thread }
                    remoteName = String(buf, 0, len, Charsets.UTF_8)
                    out.write(myName.toByteArray(Charsets.UTF_8))
                    out.flush()
                } else {
                    // İstemci: önce yaz, sonra oku
                    out.write(myName.toByteArray(Charsets.UTF_8))
                    out.flush()
                    val len = inp.read(buf)
                    if (len <= 0) { socket.close(); return@Thread }
                    remoteName = String(buf, 0, len, Charsets.UTF_8)
                }

                connections[addr] = out
                onMessage("[+] $remoteName bağlandı ✓")
                activity?.setStatus("${connections.size} cihaz")

                // Mesaj döngüsü
                while (running) {
                    val n = inp.read(buf)
                    if (n <= 0) break
                    receiveMessage(String(buf, 0, n, Charsets.UTF_8), addr)
                }
            } catch (e: IOException) {
                onMessage("[HATA] Bağlantı: ${e.message}")
            } finally {
                connections.remove(addr)
                onMessage("[-] Bağlantı kesildi")
                activity?.setStatus("${connections.size} cihaz")
                try { socket.close() } catch (e: IOException) {}
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
