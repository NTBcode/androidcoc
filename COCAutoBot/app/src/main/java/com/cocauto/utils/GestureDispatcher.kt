package com.cocauto.utils

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import kotlin.coroutines.resume

/**
 * Helper class để thực hiện thao tác Tap/Swipe qua Accessibility
 * Đã FIX lỗi: Path bounds must not be negative
 */
class GestureDispatcher(private val service: AccessibilityService) {

    @RequiresApi(Build.VERSION_CODES.N)
    suspend fun tap(x: Float, y: Float): Boolean {
        // Tap nhanh trong 50ms
        return performGesture(x, y, x, y, 50L)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    suspend fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long): Boolean {
        // Đảm bảo thời gian vuốt tối thiểu 10ms để hệ thống kịp nhận diện
        val safeDuration = if (duration < 10) 10L else duration
        return performGesture(startX, startY, endX, endY, safeDuration)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private suspend fun performGesture(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        duration: Long
    ): Boolean = suspendCancellableCoroutine { continuation ->

        val path = Path()
        path.moveTo(startX, startY)

        // --- FIX LỖI Ở ĐÂY ---
        // Nếu điểm đầu trùng điểm cuối (Tap), hệ thống Android có thể hiểu là Path rỗng
        // và gây lỗi "bounds must not be negative".
        // Giải pháp: Luôn di chuyển đi 1 pixel nhỏ xíu nếu là Tap.
        if (startX == endX && startY == endY) {
            path.lineTo(startX + 1, startY + 1)
        } else {
            path.lineTo(endX, endY)
        }

        val builder = GestureDescription.Builder()

        try {
            // StrokeDescription cũng cần duration > 0 với một số máy
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
                    // Gesture bị hủy thường do có một gesture khác chen ngang
                    // hoặc màn hình bị tắt.
                    Timber.w("Gesture cancelled")
                    if (continuation.isActive) continuation.resume(false)
                }
            }

            val dispatched = service.dispatchGesture(gesture, callback, null)
            if (!dispatched && continuation.isActive) {
                Timber.e("Failed to dispatch gesture (Service busy or invalid path)")
                continuation.resume(false)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error creating gesture: ${e.message}")
            if (continuation.isActive) continuation.resume(false)
        }
    }

    /**
     * Click nhanh (Không chờ kết quả) - Dùng cho chế độ Ghi âm (Pass-through)
     */
    fun tapAsync(x: Float, y: Float) {
        val path = Path()
        path.moveTo(x, y)

        // Di chuyển 1px để tránh lỗi path rỗng
        path.lineTo(x + 1, y + 1)

        val builder = GestureDescription.Builder()
        // Thời gian cực ngắn (10ms) để phản hồi nhanh
        val stroke = GestureDescription.StrokeDescription(path, 0, 10)

        try {
            builder.addStroke(stroke)
            // dispatchGesture với callback null (không cần quan tâm kết quả)
            service.dispatchGesture(builder.build(), null, null)
        } catch (e: Exception) {
            Timber.e(e, "Lỗi tapAsync")
        }
    }
}