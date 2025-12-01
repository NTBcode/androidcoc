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

class RecordingOverlayController(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: ViewGroup? = null
    private var isRecording = false

    // Danh sách hành động đã ghi
    private val recordedActions = mutableListOf<TouchAction>()
    private var startTime = 0L

    @SuppressLint("ClickableViewAccessibility")
    fun startRecording(onStop: (String) -> Unit) {
        if (isRecording) return
        isRecording = true
        recordedActions.clear()
        startTime = System.currentTimeMillis()

        // 1. Tạo layout trùm toàn màn hình
        val layout = FrameLayout(context)

        // Nút Dừng ghi (Góc trên phải)
        val btnStop = Button(context).apply {
            text = "DỪNG GHI & LƯU"
            // Màu đỏ cho dễ nhìn
            background.setTint(0xFFFF0000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = 100
            }
            setOnClickListener {
                stopRecording(onStop)
            }
        }
        layout.addView(btnStop)

        // 2. Cấu hình Window phủ kín màn hình
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            // FLAG_NOT_FOCUSABLE: Để không chiếm phím điều hướng
            // FLAG_LAYOUT_NO_LIMITS: Tràn viền
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        // 3. Xử lý chạm (Touch) - ĐÃ SỬA LOGIC
        layout.setOnTouchListener { _, event ->
            val x = event.rawX.toInt()
            val y = event.rawY.toInt()
            val time = System.currentTimeMillis() - startTime

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Ghi lại hành động
                    recordAction("down", x, y, time)

                    // QUAN TRỌNG: Bắn lệnh click xuống game NGAY LẬP TỨC
                    // Chỉ bắn 1 lần khi vừa chạm vào (Down) để game phản hồi
                    passThroughClick(x, y)
                }
                MotionEvent.ACTION_MOVE -> {
                    // Chỉ ghi lại hành động di chuyển, KHÔNG bắn lệnh click (tránh lag)
                    recordAction("move", x, y, time)
                }
                MotionEvent.ACTION_UP -> {
                    // Ghi lại hành động nhấc tay
                    recordAction("up", x, y, time)
                }
            }
            // Trả về true để tiếp tục nhận các sự kiện Move/Up tiếp theo
            true
        }

        overlayView = layout
        try {
            windowManager.addView(overlayView, params)
            Toast.makeText(context, "Bắt đầu ghi! Hãy thực hiện tấn công.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopRecording(onStop: (String) -> Unit) {
        if (!isRecording) return
        isRecording = false

        try {
            windowManager.removeView(overlayView)
        } catch (e: Exception) {}
        overlayView = null

        // Lưu file
        if (recordedActions.isNotEmpty()) {
            val recorder = AttackRecorder(context)
            // Tên file: Attack_GiờPhút
            val name = "Attack"
            val path = recorder.saveRecording(name, recordedActions)

            if (path != null) {
                Toast.makeText(context, "Đã lưu kịch bản!", Toast.LENGTH_SHORT).show()
                onStop(path)
            } else {
                Toast.makeText(context, "Lỗi lưu file!", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Chưa ghi được hành động nào!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun recordAction(type: String, x: Int, y: Int, time: Long) {
        recordedActions.add(TouchAction(type, x, y, time))
    }

    // Gửi lệnh click giả lập để game phản hồi (Pass-through)
    // ĐÃ SỬA: Gọi trực tiếp performPassThroughTap (đã tối ưu async bên AutoService)
    private fun passThroughClick(x: Int, y: Int) {
        val autoService = AutoService.getInstance()
        if (autoService != null) {
            // Gọi hàm thực thi nhanh, không tạo thêm Coroutine ở đây để giảm độ trễ
            autoService.performPassThroughTap(x.toFloat(), y.toFloat())
        }
    }
}