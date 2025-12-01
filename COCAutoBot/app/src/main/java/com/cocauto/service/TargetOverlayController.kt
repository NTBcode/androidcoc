package com.cocauto.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.util.DisplayMetrics
import android.view.*
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.cocauto.R
import com.cocauto.utils.CoordinateManager
import timber.log.Timber

class TargetOverlayController(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var targetView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var currentKey: String = ""

    // Biến lưu tọa độ ngón tay (Raw Touch)
    private var touchX = 0f
    private var touchY = 0f

    // Lấy kích thước màn hình vật lý (Màn hình thiết bị thực tế)
    private fun getRealScreenSize(): Point {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return Point(metrics.widthPixels, metrics.heightPixels)
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    fun showTarget(key: String, label: String, onSaved: () -> Unit) {
        removeTarget()
        currentKey = key

        targetView = LayoutInflater.from(context).inflate(R.layout.layout_target_overlay, null)

        // === QUAN TRỌNG: Lấy độ phân giải Game từ ảnh chụp màn hình ===
        val gameRes = CoordinateManager.getGameResolution(context)
        val screenSize = getRealScreenSize()

        // Kiểm tra xem đã có Game Resolution chưa
        if (gameRes.x == 0 || gameRes.y == 0) {
            Toast.makeText(
                context,
                "⚠️ Chưa xác định được độ phân giải Game!\nVui lòng chạy Bot 1 lần để calibrate.",
                Toast.LENGTH_LONG
            ).show()
            Timber.w("Game resolution not initialized. Cannot show target overlay.")
            return
        }

        // Lấy tọa độ đã lưu (trong hệ quy chiếu Game)
        val savedPoint = CoordinateManager.getCoordinate(context, key)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
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

            // === LOGIC HIỂN THỊ LẠI VỊ TRÍ CŨ (ĐÃ SỬA) ===
            if (savedPoint.x != 0 && savedPoint.y != 0) {
                // Chuyển đổi từ Game Coordinate -> Screen Coordinate
                val scaleX = screenSize.x.toFloat() / gameRes.x
                val scaleY = screenSize.y.toFloat() / gameRes.y

                // Map tọa độ Game sang màn hình thực
                x = (savedPoint.x * scaleX).toInt() - 16 // Trừ offset icon (nửa kích thước icon)
                y = (savedPoint.y * scaleY).toInt() - 16

                Timber.d("Restored position: Game($savedPoint.x, $savedPoint.y) -> Screen($x, $y)")
            } else {
                // Chưa lưu -> Hiển thị giữa màn hình
                x = screenSize.x / 2 - 16
                y = screenSize.y / 2 - 16
            }
        }

        val tvName = targetView?.findViewById<TextView>(R.id.tvTargetName)
        val btnSave = targetView?.findViewById<Button>(R.id.btnSavePosition)
        val imgTarget = targetView?.findViewById<ImageView>(R.id.imgTarget)

        tvName?.text = label

        // Xử lý kéo thả (Lấy tọa độ RAW của ngón tay)
        imgTarget?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params!!.x
                        initialY = params!!.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params!!.x = initialX + (event.rawX - initialTouchX).toInt()
                        params!!.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(targetView, params)

                        // Cập nhật vị trí ngón tay hiện tại (màn hình thực)
                        touchX = event.rawX
                        touchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        touchX = event.rawX
                        touchY = event.rawY
                        return true
                    }
                }
                return false
            }
        })

        // === XỬ LÝ NÚT LƯU (ĐÃ SỬA HOÀN TOÀN) ===
        btnSave?.setOnClickListener {
            // 1. Xác định tọa độ tâm icon trên màn hình thực
            var centerScreenX = touchX
            var centerScreenY = touchY

            if (centerScreenX == 0f && centerScreenY == 0f) {
                centerScreenX = (params!!.x + imgTarget!!.width / 2f)
                centerScreenY = (params!!.y + imgTarget!!.height / 2f)
            }

            // 2. Lấy kích thước
            val currentScreen = getRealScreenSize()
            val gameResolution = CoordinateManager.getGameResolution(context)

            if (gameResolution.x == 0 || gameResolution.y == 0) {
                Toast.makeText(
                    context,
                    "❌ Lỗi: Chưa có Game Resolution!\nChạy Bot 1 lần trước.",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            // 3. Tính toán scale
            val scaleX = gameResolution.x.toFloat() / currentScreen.x
            val scaleY = gameResolution.y.toFloat() / currentScreen.y

            // 4. Chuyển đổi
            val gameX = (centerScreenX * scaleX).toInt()
            val gameY = (centerScreenY * scaleY).toInt()

            // 5. === DEBUG LOG CHI TIẾT ===
            val debugInfo = """
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        📍 LƯU TỌA ĐỘ: $currentKey
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        🖥️ Screen Size: ${currentScreen.x} x ${currentScreen.y}
        🎮 Game Size:   ${gameResolution.x} x ${gameResolution.y}
        📐 Scale:       X=%.3f, Y=%.3f
        
        👆 Touch (Screen): (%.0f, %.0f)
        🎯 Saved (Game):   ($gameX, $gameY)
        
        🔄 Test ngược:
           Game → Screen = (%.0f, %.0f)
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    """.trimIndent().format(
                scaleX, scaleY,
                centerScreenX, centerScreenY,
                gameX / scaleX, gameY / scaleY
            )

            Timber.d(debugInfo)

            // 6. Lưu
            CoordinateManager.saveCoordinate(context, currentKey, gameX, gameY)

            Toast.makeText(
                context,
                "✅ Đã lưu: ($gameX, $gameY)\n@ ${gameResolution.x}x${gameResolution.y}",
                Toast.LENGTH_LONG
            ).show()

            removeTarget()
            onSaved()
        }

        try {
            windowManager.addView(targetView, params)
        } catch (e: Exception) {
            Timber.e(e, "Failed to add target overlay")
            Toast.makeText(context, "❌ Lỗi hiển thị Overlay: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun removeTarget() {
        if (targetView != null) {
            try {
                windowManager.removeView(targetView)
            } catch (e: Exception) {
                Timber.e(e, "Failed to remove target view")
            }
            targetView = null
            touchX = 0f
            touchY = 0f
        }
    }
}