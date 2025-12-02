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
import com.cocauto.utils.CoordinateManager
import com.cocauto.utils.TouchAction
import timber.log.Timber

/**
 * RecordingOverlayController - Fixed Version
 * Ghi lại thao tác người dùng với tọa độ chuẩn hóa
 */
class RecordingOverlayController(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: ViewGroup? = null
    private var touchInterceptView: View? = null
    private var isRecording = false

    private val recordedActions = mutableListOf<TouchAction>()
    private var startTime = 0L

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

        // === LAYER 1: Touch Intercept (Trong suốt, phủ toàn màn hình) ===
        val interceptView = View(context)

        val interceptParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        // Xử lý Touch Event: GHI LẠI + CHUYỂN TIẾP
        interceptView.setOnTouchListener { _, event ->
            // Lấy tọa độ Raw (màn hình thật)
            val rawX = event.rawX
            val rawY = event.rawY

            // Chuyển đổi sang Game Space để lưu
            val gamePoint = screenToGameCoordinate(rawX, rawY)
            val time = System.currentTimeMillis() - startTime

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    recordAction("down", gamePoint.first, gamePoint.second, time)
                    passThroughClick(rawX, rawY)
                }
                MotionEvent.ACTION_MOVE -> {
                    recordAction("move", gamePoint.first, gamePoint.second, time)
                }
                MotionEvent.ACTION_UP -> {
                    recordAction("up", gamePoint.first, gamePoint.second, time)
                }
            }

            // Trả về FALSE để touch event pass xuống game
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

        // === LAYER 2: Control Button ===
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

        // Xóa overlay
        try {
            touchInterceptView?.let { windowManager.removeView(it) }
            touchInterceptView = null
        } catch (e: Exception) {
            Timber.e(e, "Failed to remove intercept view")
        }

        try {
            overlayView?.let { windowManager.removeView(it) }
            overlayView = null
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

    /**
     * Chuyển đổi tọa độ màn hình -> Game Space
     * Để lưu vào file script
     */
    private fun screenToGameCoordinate(rawX: Float, rawY: Float): Pair<Int, Int> {
        val realScreen = CoordinateManager.getRealScreenSize(context)
        val gameRes = CoordinateManager.getGameResolution(context)

        if (gameRes.x == 0 || gameRes.y == 0) {
            // Chưa có game resolution, trả về thẳng
            return Pair(rawX.toInt(), rawY.toInt())
        }

        // Map: Screen -> Game
        val gameX = (rawX / realScreen.x * gameRes.x).toInt()
        val gameY = (rawY / realScreen.y * gameRes.y).toInt()

        return Pair(gameX, gameY)
    }

    private fun recordAction(type: String, x: Int, y: Int, time: Long) {
        recordedActions.add(TouchAction(type, x, y, time))

        // Log mỗi 50 action
        if (recordedActions.size % 50 == 0) {
            Timber.d("Recorded ${recordedActions.size} actions")
        }
    }

    /**
     * Pass-through click để game phản hồi ngay
     */
    private fun passThroughClick(x: Float, y: Float) {
        val autoService = AutoService.getInstance()
        autoService?.performPassThroughTap(x, y)
    }
}