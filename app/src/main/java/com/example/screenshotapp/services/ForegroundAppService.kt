package com.example.screenshotapp.services

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class ForegroundAppService : AccessibilityService() {

    companion object {
        const val ACTION_START_TRACKING = "com.example.screenshotapp.START_TRACKING"
        const val ACTION_STOP_TRACKING = "com.example.screenshotapp.STOP_TRACKING"
        const val ACTION_FOREGROUND_APP_CHANGED = "com.example.screenshotapp.FOREGROUND_APP_CHANGED"
        const val EXTRA_FOREGROUND_APP = "com.example.screenshotapp.FOREGROUND_APP"

        var isTracking = false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isTracking) return

        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (!pkg.isNullOrBlank()) {
                Log.d("ForegroundAppService", "Foreground package: $pkg")

                val intent = Intent(ACTION_FOREGROUND_APP_CHANGED).apply {
                    putExtra(EXTRA_FOREGROUND_APP, pkg)
                }
                sendBroadcast(intent)
            }
        }
    }

    override fun onInterrupt() {
        Log.d("ForegroundAppService", "Accessibility service interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("ForegroundAppService", "Service connected")

        val filter = IntentFilter().apply {
            addAction(ACTION_START_TRACKING)
            addAction(ACTION_STOP_TRACKING)
        }
        registerReceiver(broadcastReceiver, filter, RECEIVER_EXPORTED)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("ForegroundAppService", "Service destroyed")
        unregisterReceiver(broadcastReceiver)
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_START_TRACKING -> {
                    isTracking = true
                    Log.d("ForegroundAppService", "Tracking started")
                }
                ACTION_STOP_TRACKING -> {
                    isTracking = false
                    Log.d("ForegroundAppService", "Tracking stopped")
                }
            }
        }
    }
}

