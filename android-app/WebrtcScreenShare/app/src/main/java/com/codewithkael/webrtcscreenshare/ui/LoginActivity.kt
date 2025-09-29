package com.codewithkael.webrtcscreenshare.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.codewithkael.webrtcscreenshare.databinding.ActivityLoginBinding
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class LoginActivity : AppCompatActivity() {
    lateinit var views: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        views = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(views.root)
        try { supportActionBar?.hide() } catch (_: Throwable) {}

        // Set default values
        views.wsUrlEt.setText("ws://192.168.1.101:3001/ws")
        val deviceName = "Android Device ${System.currentTimeMillis() % 10000}"
        views.usernameEt.setText(deviceName)
        
        views.enterBtn.setOnClickListener {
            val wsUrl = views.wsUrlEt.text.toString().trim()
            val finalDeviceName = if (views.usernameEt.text.isNullOrEmpty()) {
                "Android Device ${System.currentTimeMillis() % 10000}"
            } else {
                views.usernameEt.text.toString()
            }

            if (wsUrl.isEmpty()) {
                Toast.makeText(this, "Please enter WebSocket URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!wsUrl.startsWith("ws://") && !wsUrl.startsWith("wss://")) {
                Toast.makeText(this, "WebSocket URL must start with ws:// or wss://", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            startActivity(
                Intent(this,MainActivity::class.java).apply {
                    putExtra("username", finalDeviceName)
                    putExtra("wsUrl", wsUrl)
                }
            )
        }

    }
}