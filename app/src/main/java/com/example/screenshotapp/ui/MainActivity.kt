package com.example.screenshotapp.ui

import android.app.AppOpsManager
import android.app.Application
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.screenshotapp.services.TAG
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val usageViewModel: UsageViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!hasUsagePermission()) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } else {
            startService(Intent(this, UsageTrackingService::class.java))
        }

        setContent {
            val usageState by usageViewModel.usageState.collectAsState()

            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text("Foreground App: ${usageState.foregroundApp ?: "Unknown"}", fontWeight = FontWeight.Bold)

                Spacer(Modifier.height(16.dp))

                usageState.topApps.forEach { app ->
                    Text("${app.packageName} - ${app.usageTime / 1000}s")
                }
            }

            LaunchedEffect(Unit) {
                while (true) {
                    usageViewModel.refreshUsage()
                    delay(1000L)
                }
            }
        }
    }

    private fun hasUsagePermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            applicationInfo.uid,
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }
}

class UsageTrackingService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val updateInterval = 1000L // 1 second

    private val usageRunnable = object : Runnable {
        override fun run() {
            val (foregroundApp, topApps) = AppUsageHelper.trackUsage(applicationContext)

            Log.i("UsageService", "Foreground app: $foregroundApp")
            topApps.forEach {
                Log.i("UsageService", "${it.packageName} - usage: ${it.usageTime / 1000}s")
            }

            handler.postDelayed(this, updateInterval)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.post(usageRunnable)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(usageRunnable)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}


object AppUsageHelper {

    data class AppUsage(
        val packageName: String,
        val usageTime: Long // in milliseconds
    )

    fun trackUsage(context: Context): Pair<String?, List<AppUsage>> {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        val endTime = System.currentTimeMillis()
        val startTime = endTime - TimeUnit.HOURS.toMillis(1) // 1 hour ago

        val usageEvents = usageStatsManager.queryEvents(startTime, endTime)
        val event = UsageEvents.Event()
        var latestFgTime = 0L
        var currentForegroundApp: String? = null
        val activePackages = mutableSetOf<String>()

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)

            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && event.timeStamp > latestFgTime) {
                latestFgTime = event.timeStamp
                currentForegroundApp = event.packageName
            }

            // Track all packages with activity during this period
            activePackages.add(event.packageName)
        }

        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY, startTime, endTime
        )

        val appUsages = stats
            .filter { it.totalTimeInForeground > 0 && it.packageName in activePackages }
            .map { AppUsage(it.packageName, it.totalTimeInForeground) }
            .sortedByDescending { it.usageTime }
            .take(5)

        return Pair(currentForegroundApp, appUsages)
    }
}

@HiltViewModel
class UsageViewModel @Inject constructor(
    private val app: Application
) : ViewModel() {

    data class UsageState(
        val foregroundApp: String? = null,
        val topApps: List<AppUsageHelper.AppUsage> = emptyList()
    )

    private val _usageState = MutableStateFlow(UsageState())
    val usageState: StateFlow<UsageState> = _usageState.asStateFlow()

    fun refreshUsage() {
        val (fgApp, topApps) = AppUsageHelper.trackUsage(app)
        _usageState.value = UsageState(fgApp, topApps)
    }
}






