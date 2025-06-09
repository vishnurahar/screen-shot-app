package com.example.screenshotapp

import android.app.Activity
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.screenshotapp.ui.theme.ScreenShotAppTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var mediaProjectionIntent: Intent? = null
    private val requestScreenCapture = 1001
    private val usageAccessSettings = 1002

    private var hasUsageAccessPermission by mutableStateOf(false)
    private var capturedAppPackage by mutableStateOf<String?>(null)
    private var screenshotBitmap by mutableStateOf<Bitmap?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkUsageAccessPermission()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val path = intent?.getStringExtra("screenshot_path")
                val pkg = intent?.getStringExtra("captured_app")

                if (!path.isNullOrEmpty()) {
                    screenshotBitmap = BitmapFactory.decodeFile(path)
                    capturedAppPackage = pkg
                }

                Log.i(TAG, "onReceive --> In Main Activity --> $pkg")
            }
        }
        registerReceiver(receiver, IntentFilter("screenshot_broadcast"), RECEIVER_EXPORTED)

        setContent {
            ScreenshotUI(
                hasUsageAccessPermission,
                capturedAppPackage,
                screenshotBitmap,
                onGrantUsagePermission = {
                    startActivityForResult(
                        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
                        usageAccessSettings
                    )
                },
                onTakeScreenshot = {
                    val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), requestScreenCapture)
                }
            )
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == requestScreenCapture && resultCode == RESULT_OK && data != null) {
            mediaProjectionIntent = data
            startScreenshotService()
        } else if (requestCode == usageAccessSettings) {
            checkUsageAccessPermission()
        }
    }

    private fun startScreenshotService() {
        val intent = Intent(this, ScreenshotService::class.java).apply {
            action = "ACTION_START"
            putExtra("result_code", Activity.RESULT_OK)
            putExtra("result_intent", mediaProjectionIntent)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun checkUsageAccessPermission() {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        val beginTime = endTime - 1000 * 10
        val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, beginTime, endTime)
        hasUsageAccessPermission = stats != null && stats.isNotEmpty()
    }
}



@Composable
fun ScreenshotUI(
    hasUsageAccess: Boolean,
    capturedApp: String?,
    screenshot: Bitmap?,
    onGrantUsagePermission: () -> Unit,
    onTakeScreenshot: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (hasUsageAccess) "Usage Access Granted ✅" else "Usage Access NOT Granted ❌",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(Modifier.height(12.dp))

        Button(onClick = {
            if (!hasUsageAccess) {
                onGrantUsagePermission()
            } else {
                onTakeScreenshot()
            }
        }) {
            Text("Take Screenshot")
        }

        Spacer(Modifier.height(20.dp))

        capturedApp?.let {
            Text("Captured App: $it", color = Color.Blue)
        }

        screenshot?.let {
            Spacer(Modifier.height(12.dp))
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Screenshot",
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .aspectRatio(it.width.toFloat() / it.height.toFloat())
            )
        }
    }
}



