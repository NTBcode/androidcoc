package com.cocauto

import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.cocauto.service.AutoService
import com.cocauto.service.FloatingControlService
import com.cocauto.service.ScreenCaptureService
import timber.log.Timber

class MainActivity : AppCompatActivity() {

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Khi quay lại từ cài đặt Overlay, cập nhật lại nút bấm
        updateButtonState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // Setup các nút tĩnh (Stop, Open Accessibility)
        setupStaticUI()
    }

    override fun onResume() {
        super.onResume()
        // QUAN TRỌNG: Cập nhật trạng thái nút Start mỗi khi quay lại App
        updateButtonState()
    }

    private fun setupStaticUI() {
        findViewById<Button>(R.id.btnStopService).setOnClickListener {
            stopFloatingService()
        }

        findViewById<Button>(R.id.btnOpenAccessibility).setOnClickListener {
            openAccessibilitySettings()
        }
    }

    /**
     * Logic thông minh: Thay đổi nút bấm dựa trên quyền hạn
     */
    private fun updateButtonState() {
        val btnStart = findViewById<Button>(R.id.btnStartService)

        if (checkAllPermissionsGranted()) {
            // TRƯỜNG HỢP 1: Đã đủ quyền -> Hiện nút Mở Game
            btnStart.text = "Mở Game & Bật Bot"
            btnStart.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4CAF50")) // Màu xanh lá
            btnStart.setOnClickListener {
                startFloatingService()
                launchGame()
            }
        } else {
            // TRƯỜNG HỢP 2: Chưa đủ quyền -> Hiện nút Cấp quyền
            btnStart.text = "Cấp quyền & Khởi động"
            btnStart.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#6200EE")) // Màu tím mặc định
            btnStart.setOnClickListener {
                checkPermissions()
            }
        }
    }

    private fun launchGame() {
        val packageName = "com.supercell.clashofclans"
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                startActivity(intent)
                Toast.makeText(this, "Đang mở Clash of Clans...", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Không tìm thấy game Clash of Clans trên máy!", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Lỗi mở game: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // --- CÁC HÀM KIỂM TRA QUYỀN (GIỮ NGUYÊN) ---

    private fun checkPermissions() {
        if (!checkOverlayPermission()) {
            showOverlayPermissionDialog()
            return
        }
        if (!isAccessibilityServiceEnabled()) {
            showAccessibilityDialog()
        }
    }

    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = ComponentName(this, AutoService::class.java)
        val enabledServicesSetting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)

        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledComponent = ComponentName.unflattenFromString(componentNameString)
            if (enabledComponent != null && enabledComponent == expectedComponentName) {
                return true
            }
        }
        return false
    }

    private fun checkAllPermissionsGranted(): Boolean {
        return checkOverlayPermission() && isAccessibilityServiceEnabled()
    }

    // --- DIALOGS ---

    private fun showOverlayPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Yêu cầu quyền hiển thị")
            .setMessage("Tool cần hiển thị nút điều khiển trên màn hình game. Vui lòng cấp quyền 'Hiển thị trên ứng dụng khác'.")
            .setPositiveButton("Cấp quyền") { _, _ -> requestOverlayPermission() }
            .setNegativeButton("Thoát", null)
            .show()
    }

    private fun showAccessibilityDialog() {
        AlertDialog.Builder(this)
            .setTitle("Yêu cầu quyền điều khiển")
            .setMessage("Tool cần quyền Trợ năng để tự động click. Vui lòng tìm 'COC Auto Bot' và BẬT nó lên.")
            .setPositiveButton("Đi tới cài đặt") { _, _ -> openAccessibilitySettings() }
            .setNegativeButton("Để sau", null)
            .show()
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
        Toast.makeText(this, "Tìm 'COC Auto Bot' và BẬT lên nhé!", Toast.LENGTH_LONG).show()
    }

    // --- SERVICE CONTROL ---

    private fun startFloatingService() {
        val intent = Intent(this, FloatingControlService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        // Không cần moveTaskToBack(true) ở đây nữa vì launchGame() sẽ tự đè lên App
    }

    private fun stopFloatingService() {
        stopService(Intent(this, FloatingControlService::class.java))
        stopService(Intent(this, ScreenCaptureService::class.java))
        Toast.makeText(this, "Đã dừng Service", Toast.LENGTH_SHORT).show()

        // Reset lại nút bấm
        updateButtonState()
    }
}