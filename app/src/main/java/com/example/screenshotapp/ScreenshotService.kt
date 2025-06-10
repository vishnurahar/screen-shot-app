package com.example.screenshotapp

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import androidx.core.graphics.createBitmap

const val TAG = "SCREEN_SHOT_APP"

class ScreenshotService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var screenshotCount = 0
    private val maxScreenshots = 30
    private val screenshotIntervalMillis = 2000L
    private var screenshotHandler: Handler? = null
    private var screenshotRunnable: Runnable? = null

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_START") {
            startForegroundService()
            val resultCode = intent.getIntExtra("result_code", Activity.RESULT_CANCELED)
            val data = intent.getParcelableExtra<Intent>("result_intent")
            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data!!)

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    stopSelf()
                }
            }, Handler(Looper.getMainLooper()))

            startTakingScreenshots()
        }

        return START_NOT_STICKY
    }

    private fun startTakingScreenshots() {
        val metrics = Resources.getSystem().displayMetrics
        imageReader = ImageReader.newInstance(
            metrics.widthPixels,
            metrics.heightPixels,
            PixelFormat.RGBA_8888,
            2
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenshotService",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        screenshotCount = 0
        screenshotHandler = Handler(Looper.getMainLooper())
        screenshotRunnable = object : Runnable {
            override fun run() {
                takeAndBroadcastScreenshot()
                screenshotCount++
                if (screenshotCount < maxScreenshots) {
                    screenshotHandler?.postDelayed(this, screenshotIntervalMillis)
                } else {
                    stopSelf()
                }
            }
        }

        screenshotHandler?.postDelayed(screenshotRunnable!!, 1000)
    }

    private fun takeAndBroadcastScreenshot() {
        val image = imageReader?.acquireLatestImage() ?: return
        val width = image.width
        val height = image.height
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * width

        val bitmap = createBitmap(width + rowPadding / pixelStride, height)
        bitmap.copyPixelsFromBuffer(buffer)
        image.close()

        val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
        val file = File(externalCacheDir, "screenshot_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out ->
            cropped.compress(Bitmap.CompressFormat.PNG, 100, out)
        }

        val packageName = getForegroundApp()
        val broadcast = Intent("screenshot_broadcast").apply {
            putExtra("screenshot_path", file.absolutePath)
            putExtra("captured_app", packageName)
        }
        Log.i(TAG, "takeAndBroadcastScreenshot --> package name --> $packageName")
        sendBroadcast(broadcast)
    }

    private fun getForegroundApp(): String? {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val time = System.currentTimeMillis()
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            time - 1000 * 10,
            time
        )

        return stats
            ?.maxByOrNull { it.lastTimeUsed }
            ?.packageName
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startForegroundService() {
        val notificationChannelId = "screenshot_channel"
        val channel = NotificationChannel(
            notificationChannelId,
            "Screenshot Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, notificationChannelId)
            .setContentTitle("Screenshot in progress")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()

        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }
}





