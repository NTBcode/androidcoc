package com.cocauto.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
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
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.cocauto.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.nio.ByteBuffer

/**
 * Service chụp màn hình qua MediaProjection (ĐÃ FIX LỖI ANDROID 14)
 */
class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    companion object {
        private const val NOTIFICATION_ID = 1002 // ID khác với FloatingService
        private const val CHANNEL_ID = "screen_capture_channel"

        @SuppressLint("StaticFieldLeak")
        private var instance: ScreenCaptureService? = null

        fun getInstance(): ScreenCaptureService? = instance
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        createNotificationChannel()

        // FIX LỖI CRASH ANDROID 14: Thêm type MEDIA_PROJECTION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>("data")

        if (resultCode == Activity.RESULT_OK && data != null) {
            startProjection(resultCode, data)
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Khởi động MediaProjection
     */
    private fun startProjection(resultCode: Int, data: Intent) {
        // FIX: Lấy kích thước màn hình chuẩn xác qua WindowManager
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)

        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        // Callback xử lý khi bị dừng đột ngột
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                stopSelf()
            }
        }, null)

        @SuppressLint("WrongConstant")
        // Dùng maxImages = 2 để tránh lag bộ nhớ
        imageReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            2
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "COCAutoCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )

        Timber.d("Screen capture started: ${screenWidth}x${screenHeight}")
    }

    /**
     * Chụp màn hình (Chạy trên IO Thread)
     */
    suspend fun captureScreen(): Bitmap? = withContext(Dispatchers.IO) {
        var image: Image? = null
        try {
            // Lấy ảnh mới nhất
            image = imageReader?.acquireLatestImage()

            if (image != null) {
                val planes = image.planes
                val buffer: ByteBuffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * screenWidth

                // Tạo bitmap
                val bitmap = Bitmap.createBitmap(
                    screenWidth + rowPadding / pixelStride,
                    screenHeight,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)

                // Quan trọng: Đóng image ngay sau khi copy để giải phóng buffer
                image.close()

                // Crop nếu có padding thừa (do phần cứng)
                return@withContext if (rowPadding == 0) {
                    bitmap
                } else {
                    val cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
                    if (cropped != bitmap) bitmap.recycle() // Giải phóng ảnh gốc nếu đã crop
                    cropped
                }
            }
            return@withContext null
        } catch (e: Exception) {
            Timber.e(e, "Failed to capture screen")
            // Đảm bảo đóng image nếu có lỗi xảy ra
            try { image?.close() } catch (ex: Exception) {}
            return@withContext null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Capture Service",
                NotificationManager.IMPORTANCE_LOW // Low để không phát ra tiếng
            ).apply {
                description = "Đang quay màn hình để xử lý hình ảnh"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("COC Bot - Vision")
            .setContentText("Đang theo dõi màn hình...")
            .setSmallIcon(R.mipmap.ic_launcher) // FIX: Dùng icon có sẵn
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        instance = null
        Timber.d("ScreenCaptureService destroyed")
    }
}