package com.cocauto.utils

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import kotlin.coroutines.resume

/**
 * THUẬT TOÁN CLICK - ÁNH XẠ TỌA ĐỘ
 *
 * Game Resolution: 2400x1080 (từ ảnh chụp)
 * Screen Resolution: 2340x1080 (màn hình vật lý)
 *
 * Tọa độ đã lưu: (1200, 540) - TRÊN HỆ QUY CHIẾU GAME
 *
 * Khi click:
 * clickX = 1200 / 2400 * 2340 = 1170
 * clickY = 540 / 1080 * 1080 = 540
 */
class GestureDispatcher(private val service: AccessibilityService) {

    private val context: Context = service

    // Lấy độ phân giải màn hình vật lý
    private fun getRealScreenSize(): android.graphics.Point {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        return android.graphics.Point(metrics.widthPixels, metrics.heightPixels)
    }

    /**
     * Tap tại tọa độ game (đã lưu trong CoordinateManager)
     */
    @RequiresApi(Build.VERSION_CODES.N)
    suspend fun tap(gameX: Float, gameY: Float): Boolean {
        // Chuyển đổi từ tọa độ game -> tọa độ màn hình vật lý
        val screenPoint = gameToScreen(gameX, gameY)
        return performGesture(screenPoint.x, screenPoint.y, screenPoint.x, screenPoint.y, 50L)
    }

    /**
     * Swipe từ điểm game (x1, y1) đến (x2, y2)
     */
    @RequiresApi(Build.VERSION_CODES.N)
    suspend fun swipe(gameX1: Float, gameY1: Float, gameX2: Float, gameY2: Float, duration: Long): Boolean {
        val start = gameToScreen(gameX1, gameY1)
        val end = gameToScreen(gameX2, gameY2)
        val safeDuration = if (duration < 10) 10L else duration
        return performGesture(start.x, start.y, end.x, end.y, safeDuration)
    }

    /**
     * Chuyển đổi tọa độ: Game -> Màn hình vật lý
     */
    private fun gameToScreen(gameX: Float, gameY: Float): android.graphics.PointF {
        val gameRes = CoordinateManager.getGameResolution(context)
        val screenSize = getRealScreenSize()

        // Nếu chưa có game resolution, trả về tọa độ gốc
        if (gameRes.x == 0 || gameRes.y == 0) {
            return android.graphics.PointF(gameX, gameY)
        }

        val screenX = gameX / gameRes.x * screenSize.x
        val screenY = gameY / gameRes.y * screenSize.y

        return android.graphics.PointF(screenX, screenY)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private suspend fun performGesture(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        duration: Long
    ): Boolean = suspendCancellableCoroutine { continuation ->

        val path = Path()
        path.moveTo(startX, startY)

        // FIX: Luôn di chuyển 1 pixel nếu là tap để tránh lỗi
        if (startX == endX && startY == endY) {
            path.lineTo(startX + 1, startY + 1)
        } else {
            path.lineTo(endX, endY)
        }

        val builder = GestureDescription.Builder()

        try {
            val safeDuration = if (duration <= 0) 10L else duration
            val stroke = GestureDescription.StrokeDescription(path, 0, safeDuration)

            builder.addStroke(stroke)
            val gesture = builder.build()

            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    Timber.w("Gesture cancelled")
                    if (continuation.isActive) continuation.resume(false)
                }
            }

            val dispatched = service.dispatchGesture(gesture, callback, null)
            if (!dispatched && continuation.isActive) {
                Timber.e("Failed to dispatch gesture")
                continuation.resume(false)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error creating gesture")
            if (continuation.isActive) continuation.resume(false)
        }
    }

    /**
     * Tap nhanh (không chờ) - Dùng cho recording pass-through
     */
    fun tapAsync(x: Float, y: Float) {
        // Chuyển đổi game -> screen
        val screenPoint = gameToScreen(x, y)

        val path = Path()
        path.moveTo(screenPoint.x, screenPoint.y)
        path.lineTo(screenPoint.x + 1, screenPoint.y + 1)

        val builder = GestureDescription.Builder()
        val stroke = GestureDescription.StrokeDescription(path, 0, 10)

        try {
            builder.addStroke(stroke)
            service.dispatchGesture(builder.build(), null, null)
        } catch (e: Exception) {
            Timber.e(e, "Lỗi tapAsync")
        }
    }
}