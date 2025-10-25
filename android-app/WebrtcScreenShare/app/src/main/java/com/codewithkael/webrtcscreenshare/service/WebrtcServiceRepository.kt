package com.codewithkael.webrtcscreenshare.service

import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

class WebrtcServiceRepository @Inject constructor(
    private val context:Context
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun startIntent(username:String, wsUrl: String = "ws://192.168.1.101:3001/ws"){
        scope.launch {
            val startIntent = Intent(context, WebrtcService::class.java)
            startIntent.action = "StartIntent"
            startIntent.putExtra("username",username)
            startIntent.putExtra("wsUrl", wsUrl)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
                context.startForegroundService(startIntent)
            } else {
                context.startService(startIntent)
            }
        }
    }

    fun requestConnection(target: String){
        scope.launch {
            val startIntent = Intent(context, WebrtcService::class.java)
            startIntent.action = "RequestConnectionIntent"
            startIntent.putExtra("target",target)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
                context.startForegroundService(startIntent)
            } else {
                context.startService(startIntent)
            }
        }
    }

    fun acceptCAll(target:String){
        scope.launch {
            val startIntent = Intent(context, WebrtcService::class.java)
            startIntent.action = "AcceptCallIntent"
            startIntent.putExtra("target",target)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(startIntent)
            } else {
                context.startService(startIntent)
            }
        }
    }

    fun endCallIntent() {
        scope.launch {
            val startIntent = Intent(context, WebrtcService::class.java)
            startIntent.action = "EndCallIntent"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(startIntent)
            } else {
                context.startService(startIntent)
            }
        }
    }

    fun startStreamingToWeb() {
        scope.launch {
            val startIntent = Intent(context, WebrtcService::class.java)
            startIntent.action = "PrepareStreamingIntent"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(startIntent)
            } else {
                context.startService(startIntent)
            }
        }
    }

    fun stopIntent() {
        scope.launch {
            val startIntent = Intent(context, WebrtcService::class.java)
            startIntent.action = "StopIntent"
            // Stop should not require foreground start; use startService or stopService directly
            try {
                context.startService(startIntent)
            } catch (_: Exception) {
                try { context.stopService(startIntent) } catch (_: Exception) {}
            }
        }
    }

}
