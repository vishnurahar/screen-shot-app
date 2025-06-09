package com.example.screenshotapp

import android.app.Activity
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.screenshotapp.ui.theme.ScreenShotAppTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var screenshotReceiver: BroadcastReceiver? = null
    private var screenshotBitmap by mutableStateOf<Bitmap?>(null)
    private var capturedAppPackage by mutableStateOf<String?>(null)
    private var isAccessibilityEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        isAccessibilityEnabled = isAccessibilityServiceEnabled(this)
        setupBroadcastReceiver()

        setContent {
            ScreenShotAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ScreenshotTakerApp(
                        capturedBitmap = screenshotBitmap,
                        capturedApp = capturedAppPackage,
                        isAccessibilityEnabled = isAccessibilityEnabled,
                        onEnableAccessibilityClick = {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            startActivity(intent)
                        },
                        onResultReceived = { resultIntent ->
                            startScreenshotService(resultIntent)
                        }
                    )
                }
            }
        }
    }

    private fun setupBroadcastReceiver() {
        screenshotReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.d("ScreenshotApp", "Broadcast received")
                val path = intent?.getStringExtra(ScreenshotService.EXTRA_BITMAP_PATH)
                val packageName = intent?.getStringExtra(ScreenshotService.EXTRA_CAPTURED_PACKAGE)

                if (path != null) {
                    screenshotBitmap = BitmapFactory.decodeFile(path)
                }

                if (!packageName.isNullOrEmpty()) {
                    capturedAppPackage = packageName
                    Log.d("ScreenshotApp", "Captured app package: $packageName")
                    context?.let {
                        Toast.makeText(it, "Captured app: $packageName", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Log.d("ScreenshotApp", "Captured app package name is null")
                }
            }
        }

        val filter = IntentFilter(ScreenshotService.BROADCAST_ACTION_SCREENSHOT)
        registerReceiver(screenshotReceiver, filter, RECEIVER_EXPORTED)
    }

    private fun startScreenshotService(resultIntent: Intent) {
        val serviceIntent = Intent(this, ScreenshotService::class.java).apply {
            action = ScreenshotService.ACTION_START
            putExtra(ScreenshotService.EXTRA_RESULT_CODE, Activity.RESULT_OK)
            putExtra(ScreenshotService.EXTRA_RESULT_INTENT, resultIntent)
        }
        startForegroundService(serviceIntent)
    }

    override fun onResume() {
        super.onResume()
        isAccessibilityEnabled = isAccessibilityServiceEnabled(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        screenshotReceiver?.let { unregisterReceiver(it) }
        stopService(Intent(this, ScreenshotService::class.java))
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expectedService = "${context.packageName}/${ForegroundAppService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServices.split(":").any { it.equals(expectedService, ignoreCase = true) }
    }
}

@Composable
fun ScreenshotTakerApp(
    capturedBitmap: Bitmap?,
    capturedApp: String?,
    isAccessibilityEnabled: Boolean,
    onEnableAccessibilityClick: () -> Unit,
    onResultReceived: (Intent) -> Unit
) {
    val context = LocalContext.current
    var statusText by remember { mutableStateOf("Click button to take a screenshot") }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.let {
                    statusText = "Permission granted. Capturing..."
                    onResultReceived(it)
                }
            } else {
                statusText = "Permission denied by user."
            }
        }
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (isAccessibilityEnabled) "Accessibility Service is ENABLED ✅"
            else "Accessibility Service is NOT enabled ❌",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Button(onClick = onEnableAccessibilityClick) {
            Text("Enable Accessibility Service")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                if (!isAccessibilityEnabled) {
                    Toast.makeText(context, "Please enable Accessibility Service first", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                val mediaProjectionManager =
                    context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
            }
        ) {
            Text("Take Screenshot")
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (capturedApp != null) {
            Text(
                "Captured App: $capturedApp",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (capturedBitmap != null) {
            Text(
                "Captured Image:",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Image(
                bitmap = capturedBitmap.asImageBitmap(),
                contentDescription = "Screenshot",
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(capturedBitmap.width.toFloat() / capturedBitmap.height.toFloat())
                    .padding(4.dp),
                contentScale = ContentScale.Fit
            )
        }
    }
}


