package com.nafbchat

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import com.nafbchat.databinding.ActivityChatBinding

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var btManager: AndroidBluetoothManager
    private lateinit var myName: String
    private val chatLines = StringBuilder()

    companion object {
        const val DEVICE_LIST_REQUEST = 200
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        myName = intent.getStringExtra("name") ?: "Android"
        supportActionBar?.title = "NAFBChat"
        supportActionBar?.subtitle = myName

        binding.btnSend.setOnClickListener { sendMessage() }
        binding.btnDevices.setOnClickListener {
            startActivityForResult(
                Intent(this, DeviceListActivity::class.java),
                DEVICE_LIST_REQUEST
            )
        }

        binding.etMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage(); true
            } else false
        }

        btManager = AndroidBluetoothManager(this, myName) { msg ->
            runOnUiThread { appendLog(msg) }
        }
        btManager.start()
    }

    // Cihaz listesinden seçim döndü
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == DEVICE_LIST_REQUEST && resultCode == RESULT_OK) {
            val addr = data?.getStringExtra("device_address") ?: return
            val name = data.getStringExtra("device_name") ?: addr
            appendLog("[Bağlanıyor] $name...")
            btManager.connectToAddress(addr)
        }
    }

    private fun sendMessage() {
        val text = binding.etMessage.text.toString().trim()
        if (text.isEmpty()) return
        val msg = "$myName: $text"
        appendLog(msg)
        btManager.broadcast(msg, null)
        binding.etMessage.text?.clear()
    }

    fun appendLog(line: String) {
        chatLines.appendLine(line)
        binding.tvChat.text = chatLines.toString()
        binding.scrollView.post {
            binding.scrollView.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }

    fun setStatus(status: String) {
        runOnUiThread {
            supportActionBar?.subtitle = "$myName · $status"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        btManager.stop()
    }
}
