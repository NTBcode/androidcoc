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

class TargetOverlayController(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var targetView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var currentKey: String = ""

    // Biến lưu tọa độ ngón tay (Raw Touch)
    private var touchX = 0f
    private var touchY = 0f

    // Lấy kích thước màn hình vật lý (Tờ giấy trên)
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

        // Lấy thông tin đã lưu
        val savedPoint = CoordinateManager.getCoordinate(context, key)
        val gameRes = CoordinateManager.getGameResolution(context)
        val screenSize = getRealScreenSize()

        // Nếu chưa có Game Resolution, dùng tạm màn hình hiện tại
        val baseW = if (gameRes.x > 0) gameRes.x else screenSize.x
        val baseH = if (gameRes.y > 0) gameRes.y else screenSize.y

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
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or // Tràn viền
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            gravity = Gravity.TOP or Gravity.START

            // Logic hiển thị lại vị trí cũ (Map ngược từ Game -> Screen để hiển thị)
            if (savedPoint.x != 0 && savedPoint.y != 0) {
                val ratioX = savedPoint.x.toFloat() / baseW
                val ratioY = savedPoint.y.toFloat() / baseH
                x = (ratioX * screenSize.x).toInt() - 40 // Trừ bán kính icon
                y = (ratioY * screenSize.y).toInt() - 40
            } else {
                x = screenSize.x / 2
                y = screenSize.y / 2
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

                        // Cập nhật vị trí ngón tay hiện tại (Raw)
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

        // Xử lý nút LƯU (QUAN TRỌNG: Ánh xạ Screen -> Game)
        btnSave?.setOnClickListener {
            // 1. Xác định tâm
            var centerX = touchX
            var centerY = touchY

            // Nếu chưa kéo (vừa mở lên bấm lưu), tính theo params
            if (centerX == 0f && centerY == 0f) {
                centerX = (params!!.x + imgTarget!!.width / 2).toFloat()
                centerY = (params!!.y + imgTarget!!.height / 2).toFloat()
            }

            // 2. Lấy kích thước
            val currentScreen = getRealScreenSize()
            var targetW = gameRes.x
            var targetH = gameRes.y

            // Fallback nếu chưa có độ phân giải Game
            if (targetW == 0 || targetH == 0) {
                targetW = currentScreen.x
                targetH = currentScreen.y
                CoordinateManager.saveGameResolution(context, targetW, targetH)
            }

            // 3. ÁNH XẠ: Screen (Màn hình thật) -> Game (Ảnh chụp)
            // Công thức: Tọa độ Lưu = (Tọa độ Màn hình / Kích thước Màn hình) * Kích thước Game
            val finalX = (centerX / currentScreen.x * targetW).toInt()
            val finalY = (centerY / currentScreen.y * targetH).toInt()

            CoordinateManager.saveCoordinate(context, currentKey, finalX, finalY)

            Toast.makeText(context, "Đã lưu: ($finalX, $finalY) @ ${targetW}x${targetH}", Toast.LENGTH_SHORT).show()
            removeTarget()
            onSaved()
        }

        try {
            windowManager.addView(targetView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun removeTarget() {
        if (targetView != null) {
            try { windowManager.removeView(targetView) } catch (e: Exception) {}
            targetView = null
            touchX = 0f; touchY = 0f
        }
    }
}