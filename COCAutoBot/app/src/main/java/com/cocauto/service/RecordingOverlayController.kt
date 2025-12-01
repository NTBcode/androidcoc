package com.cocauto.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.*
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import com.cocauto.logic.AttackRecorder
import com.cocauto.utils.TouchAction
import timber.log.Timber

class RecordingOverlayController(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: ViewGroup? = null
    private var touchInterceptView: View? = null
    private var isRecording = false

    // Danh sách hành động đã ghi
    private val recordedActions = mutableListOf<TouchAction>()
    private var startTime = 0L

    // === BẢN FIX HOÀN TOÀN: Dùng 2 layer overlay ===
    @SuppressLint("ClickableViewAccessibility")
    fun startRecording(onStop: (String) -> Unit) {
        if (isRecording) return
        isRecording = true
        recordedActions.clear()
        startTime = System.currentTimeMillis()

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // === LAYER 1: Touch Intercept (PHỦ TOÀN MÀN HÌNH, TRONG SUỐT) ===
        // Layer này sẽ CHẶN touch event để ghi lại, nhưng KHÔNG HIỂN THỊ gì
        val interceptView = View(context)

        val interceptParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            // === QUAN TRỌNG: KHÔNG dùng FLAG_NOT_FOCUSABLE ===
            // Để overlay này có thể nhận touch event
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or // Cho phép touch pass through
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, // Nhận touch bên ngoài
            PixelFormat.TRANSLUCENT
        )

        // Xử lý Touch Event: GHI LẠI + CHUYỂN TIẾP xuống game
        interceptView.setOnTouchListener { _, event ->
            val x = event.rawX.toInt()
            val y = event.rawY.toInt()
            val time = System.currentTimeMillis() - startTime

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    recordAction("down", x, y, time)
                    passThroughClick(event.rawX, event.rawY)
                }
                MotionEvent.ACTION_MOVE -> {
                    recordAction("move", x, y, time)
                    // Không pass move để tránh lag
                }
                MotionEvent.ACTION_UP -> {
                    recordAction("up", x, y, time)
                }
            }

            // === QUAN TRỌNG: Trả về FALSE để touch event được pass xuống game ===
            false
        }

        touchInterceptView = interceptView

        try {
            windowManager.addView(touchInterceptView, interceptParams)
        } catch (e: Exception) {
            Timber.e(e, "Failed to add touch intercept layer")
            Toast.makeText(context, "❌ Lỗi khởi tạo ghi: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }

        // === LAYER 2: Control Button (CHỈ HIỂN THỊ NÚT DỪNG) ===
        val controlLayout = FrameLayout(context)

        val btnStop = Button(context).apply {
            text = "⬛ DỪNG GHI & LƯU"
            setTextColor(0xFFFFFFFF.toInt())
            background?.setTint(0xFFFF0000.toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = 50
            }
            setOnClickListener {
                stopRecording(onStop)
            }
        }
        controlLayout.addView(btnStop)

        val controlParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            // Nút này CẦN focusable để có thể click được
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
        }

        overlayView = controlLayout

        try {
            windowManager.addView(overlayView, controlParams)
            Toast.makeText(context, "🔴 Đang ghi! Hãy thực hiện tấn công.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Timber.e(e, "Failed to add control overlay")
            Toast.makeText(context, "❌ Lỗi hiển thị nút điều khiển: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording(onStop: (String) -> Unit) {
        if (!isRecording) return
        isRecording = false

        // Xóa cả 2 layer
        try {
            if (touchInterceptView != null) {
                windowManager.removeView(touchInterceptView)
                touchInterceptView = null
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to remove intercept view")
        }

        try {
            if (overlayView != null) {
                windowManager.removeView(overlayView)
                overlayView = null
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to remove overlay view")
        }

        // Lưu file
        if (recordedActions.isNotEmpty()) {
            val recorder = AttackRecorder(context)
            val name = "Attack"
            val path = recorder.saveRecording(name, recordedActions)

            if (path != null) {
                Toast.makeText(context, "✅ Đã lưu ${recordedActions.size} hành động!", Toast.LENGTH_SHORT).show()
                Timber.d("Saved recording: $path (${recordedActions.size} actions)")
                onStop(path)
            } else {
                Toast.makeText(context, "❌ Lỗi lưu file!", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "⚠️ Chưa ghi được hành động nào!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun recordAction(type: String, x: Int, y: Int, time: Long) {
        recordedActions.add(TouchAction(type, x, y, time))

        // Log mỗi 50 action để debug
        if (recordedActions.size % 50 == 0) {
            Timber.d("Recorded ${recordedActions.size} actions")
        }
    }

    // Gửi lệnh click giả lập để game phản hồi
    private fun passThroughClick(x: Float, y: Float) {
        val autoService = AutoService.getInstance()
        autoService?.performPassThroughTap(x, y)
    }
}