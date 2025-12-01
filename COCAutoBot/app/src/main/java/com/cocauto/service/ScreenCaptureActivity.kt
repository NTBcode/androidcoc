package com.cocauto.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Window
import android.widget.Toast

// --- ACTIVITY XIN QUYỀN QUAY MÀN HÌNH ---
class ScreenCaptureActivity : Activity() {
    private val REQUEST_CODE = 100
    private var projectionManager: MediaProjectionManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Yêu cầu không có tiêu đề (đã xử lý trong Theme nhưng thêm cho chắc)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // QUAN TRỌNG: Delay 500ms để Activity kịp "hiện hình" trước khi xin quyền
        // Điều này giúp tránh lỗi Android tự động hủy hộp thoại bảo mật
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                startActivityForResult(projectionManager?.createScreenCaptureIntent(), REQUEST_CODE)
            } catch (e: Exception) {
                e.printStackTrace()
                finish()
            }
        }, 500)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                // Khởi động Service quay màn hình với quyền vừa cấp
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    action = "START_CAPTURE" // Thêm action rõ ràng
                    putExtra("resultCode", resultCode)
                    putExtra("data", data)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }

                // Gửi tín hiệu OK về cho FloatingService để bắt đầu chạy Bot
                val broadcast = Intent(FloatingControlService.ACTION_PERMISSION_GRANTED)
                broadcast.setPackage(packageName)
                sendBroadcast(broadcast)
            } else {
                Toast.makeText(this, "Bạn đã từ chối cấp quyền quay màn hình!", Toast.LENGTH_SHORT).show()
            }
        }
        // Đóng Activity ngay sau khi xử lý xong
        finish()
    }
}