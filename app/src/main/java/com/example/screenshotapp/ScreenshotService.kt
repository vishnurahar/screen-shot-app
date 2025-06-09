package com.example.screenshotapp

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
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

class ScreenshotService : Service() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var handler: Handler

    private var capturedAppPackage: String? = null

    private val foregroundAppReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ForegroundAppService.ACTION_FOREGROUND_APP_CHANGED) {
                capturedAppPackage = intent.getStringExtra(ForegroundAppService.EXTRA_FOREGROUND_APP)
                Log.d("ScreenshotService", "Detected foreground app: $capturedAppPackage")
            }
        }
    }

    companion object {
        const val ACTION_START = "com.example.screenshotapp.START"
        const val ACTION_STOP = "com.example.screenshotapp.STOP"
        const val EXTRA_RESULT_CODE = "com.example.screenshotapp.RESULT_CODE"
        const val EXTRA_RESULT_INTENT = "com.example.screenshotapp.RESULT_INTENT"
        const val NOTIFICATION_CHANNEL_ID = "screenshot_channel"
        const val NOTIFICATION_ID = 101
        const val BROADCAST_ACTION_SCREENSHOT = "com.example.screenshotapp.SCREENSHOT_TAKEN"
        const val EXTRA_BITMAP_PATH = "com.example.screenshotapp.BITMAP_PATH"
        const val EXTRA_CAPTURED_PACKAGE = "com.example.screenshotapp.CAPTURED_PACKAGE"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("ScreenshotService", "Service created")
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        handler = Handler(Looper.getMainLooper())

        registerReceiver(
            foregroundAppReceiver,
            IntentFilter(ForegroundAppService.ACTION_FOREGROUND_APP_CHANGED),
            RECEIVER_EXPORTED
        )
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                Log.d("ScreenshotService", "Start action received")
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_INTENT)

                resultData?.let {
                    startForegroundService()
                    handler.post {
                        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, it)
                        mediaProjection?.registerCallback(mediaProjectionCallback, handler)

                        Log.d("ScreenshotService", "Requesting foreground app tracking")
                        sendBroadcast(Intent(ForegroundAppService.ACTION_START_TRACKING))

                        setupVirtualDisplay()
                    }
                } ?: Log.e("ScreenshotService", "Result data is null, cannot start projection")
            }

            ACTION_STOP -> {
                Log.d("ScreenshotService", "Stop action received")
                stopCapture()
            }
        }

        return START_NOT_STICKY
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startForegroundService() {
        val channelId = NOTIFICATION_CHANNEL_ID
        val channelName = "Screenshot Service"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(chan)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Screen Capture")
            .setContentText("Capturing screen...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun setupVirtualDisplay() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "Screenshot",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, handler
        )

        Log.d("ScreenshotService", "Virtual display created")

        imageReader?.setOnImageAvailableListener({ reader ->
            var image: Image? = null
            try {
                image = reader.acquireLatestImage()
                image?.let {
                    Log.d("ScreenshotService", "Image available")
                    processImage(it)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e("ScreenshotService", "Error acquiring image: ${e.message}")
            } finally {
                image?.close()
                stopCapture()
            }
        }, handler)
    }

    private fun processImage(image: Image) {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        ).apply {
            copyPixelsFromBuffer(buffer)
        }

        val filePath = saveBitmapToFile(bitmap)

        Log.d("ScreenshotService", "Saved bitmap to path: $filePath")
        Log.d("ScreenshotService", "Broadcasting with package: $capturedAppPackage")

        if (filePath != null) {
            val broadcastIntent = Intent(BROADCAST_ACTION_SCREENSHOT).apply {
                putExtra(EXTRA_BITMAP_PATH, filePath)
                putExtra(EXTRA_CAPTURED_PACKAGE, capturedAppPackage)
            }
            sendBroadcast(broadcastIntent)
        }
    }

    private fun saveBitmapToFile(bitmap: Bitmap): String? {
        val file = File(cacheDir, "screenshot.png")
        return try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file.absolutePath
        } catch (e: IOException) {
            e.printStackTrace()
            Log.e("ScreenshotService", "Failed to save bitmap: ${e.message}")
            null
        }
    }

    private fun stopCapture() {
        handler.post {
            Log.d("ScreenshotService", "Stopping capture")
            mediaProjection?.unregisterCallback(mediaProjectionCallback)
            mediaProjection?.stop()
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection = null

            sendBroadcast(Intent(ForegroundAppService.ACTION_STOP_TRACKING))
        }

        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("ScreenshotService", "Service destroyed")
        unregisterReceiver(foregroundAppReceiver)
        stopCapture()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d("ScreenshotService", "MediaProjection stopped")
            stopCapture()
        }
    }
}


