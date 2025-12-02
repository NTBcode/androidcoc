package com.cocauto.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.util.DisplayMetrics
import android.view.*
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.cocauto.R
import com.cocauto.utils.CoordinateManager

/**
 * THUẬT TOÁN "2 TỜ GIẤY CHỒNG LÊN NHAU"
 *
 * Tờ 1 (Game): Ảnh chụp màn hình game (ví dụ: 2400x1080)
 * Tờ 2 (Overlay): Lớp phủ trong suốt ĐÚNG kích thước game (2400x1080)
 *
 * Khi chạm vào overlay -> Lưu tọa độ trực tiếp (x, y)
 * Khi click trong game -> Dùng lại tọa độ (x, y) đó
 *
 * KHÔNG cần scale, KHÔNG cần chuyển đổi!
 */
class TargetOverlayController(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayContainer: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var currentKey: String = ""

    // Biến lưu tọa độ chạm trên overlay (chính là tọa độ game)
    private var savedX = 0
    private var savedY = 0

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    fun showTarget(key: String, label: String, onSaved: () -> Unit) {
        removeTarget()
        currentKey = key

        // Lấy độ phân giải game đã lưu
        val gameRes = CoordinateManager.getGameResolution(context)
        if (gameRes.x == 0 || gameRes.y == 0) {
            Toast.makeText(context, "❌ Chưa có độ phân giải game! Hãy bật bot trước.", Toast.LENGTH_LONG).show()
            return
        }

        // Tạo container overlay CÓ ĐÚNG kích thước game
        overlayContainer = FrameLayout(context)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // QUAN TRỌNG: Overlay có ĐÚNG kích thước game
        params = WindowManager.LayoutParams(
            gameRes.x, // Width = Game Width
            gameRes.y, // Height = Game Height
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        // Nền trong suốt có màu nhẹ để người dùng biết overlay đang hoạt động
        overlayContainer?.setBackgroundColor(0x11FF0000) // Đỏ mờ rất nhẹ

        // Tạo crosshair ở vị trí cũ (nếu có)
        val savedPoint = CoordinateManager.getCoordinate(context, key)
        val crosshair = createCrosshair(label, savedPoint.x, savedPoint.y)
        overlayContainer?.addView(crosshair)

        // Xử lý chạm vào overlay
        overlayContainer?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    // Lấy tọa độ TRỰC TIẾP từ overlay (chính là tọa độ game)
                    savedX = event.x.toInt()
                    savedY = event.y.toInt()

                    // Di chuyển crosshair đến vị trí mới
                    updateCrosshairPosition(crosshair, savedX, savedY)
                    true
                }
                else -> false
            }
        }

        // Nút LƯU
        val btnSave = Button(context).apply {
            text = "✓ LƯU TỌA ĐỘ"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF4CAF50.toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = 50
            }
            setOnClickListener {
                if (savedX == 0 && savedY == 0) {
                    // Chưa chạm, dùng vị trí cũ
                    savedX = savedPoint.x
                    savedY = savedPoint.y
                }

                if (savedX == 0 && savedY == 0) {
                    Toast.makeText(context, "⚠️ Hãy chạm vào màn hình để chọn vị trí!", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                // Lưu tọa độ TRỰC TIẾP (không cần chuyển đổi)
                CoordinateManager.saveCoordinate(context, currentKey, savedX.toFloat(), savedY.toFloat())
                Toast.makeText(context, "✓ Đã lưu: ($savedX, $savedY) @ ${gameRes.x}x${gameRes.y}", Toast.LENGTH_SHORT).show()
                removeTarget()
                onSaved()
            }
        }
        overlayContainer?.addView(btnSave)

        // Nút HỦY
        val btnCancel = Button(context).apply {
            text = "✕ HỦY"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFFF44336.toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = 20
                rightMargin = 20
            }
            setOnClickListener {
                removeTarget()
                onSaved()
            }
        }
        overlayContainer?.addView(btnCancel)

        // Hiển thị overlay
        try {
            windowManager.addView(overlayContainer, params)
            Toast.makeText(context, "📍 Chạm vào vị trí nút $label", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "❌ Lỗi: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createCrosshair(label: String, x: Int, y: Int): View {
        return LayoutInflater.from(context).inflate(R.layout.layout_target_overlay, null).apply {
            findViewById<TextView>(R.id.tvTargetName)?.text = label
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            if (x > 0 && y > 0) {
                this.x = (x - 16).toFloat() // Center crosshair
                this.y = (y - 16).toFloat()
            } else {
                this.x = 100f
                this.y = 100f
            }
            // Ẩn nút LƯU trong crosshair (vì đã có nút LƯU chính)
            findViewById<Button>(R.id.btnSavePosition)?.visibility = View.GONE
        }
    }

    private fun updateCrosshairPosition(crosshair: View, x: Int, y: Int) {
        crosshair.x = (x - 16).toFloat()
        crosshair.y = (y - 16).toFloat()
    }

    fun removeTarget() {
        if (overlayContainer != null) {
            try {
                windowManager.removeView(overlayContainer)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            overlayContainer = null
            savedX = 0
            savedY = 0
        }
    }
}