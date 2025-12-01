package com.cocauto.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import com.cocauto.R
import com.cocauto.utils.CoordinateManager
import com.cocauto.utils.SettingsManager
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.File

class FloatingControlService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var isExpanded = false

    // Trạng thái Bot
    private var isBotRunning = false

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tvLog: TextView? = null
    private val logLines = mutableListOf<String>()

    // Các Controller
    private lateinit var targetController: TargetOverlayController
    private lateinit var recordingController: RecordingOverlayController

    companion object {
        const val ACTION_PERMISSION_GRANTED = "com.cocauto.ACTION_PERMISSION_GRANTED"
        const val NOTIFICATION_ID = 1001
    }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_PERMISSION_GRANTED) {
                addLog("✅ Đã cấp quyền. Đang cấu hình...")
                serviceScope.launch {
                    var success = false
                    var attempts = 0
                    while (attempts < 10) {
                        delay(500)
                        val autoService = AutoService.getInstance()
                        if (autoService != null) {
                            success = autoService.calibrateResolutionSync {
                                ScreenCaptureService.getInstance()?.captureScreen()
                            }
                            if (success) {
                                val res = CoordinateManager.getGameResolution(this@FloatingControlService)
                                addLog("✅ Sẵn sàng! (${res.x}x${res.y})")
                                isExpanded = true
                                floatingView?.findViewById<View>(R.id.expandedLayout)?.visibility = View.VISIBLE
                                break
                            }
                        }
                        attempts++
                    }
                    if (!success) addLog("❌ Lỗi ảnh màn hình!")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, createNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Khởi tạo các Controller phụ trợ
        targetController = TargetOverlayController(this)
        recordingController = RecordingOverlayController(this)

        createFloatingView()

        val filter = IntentFilter(ACTION_PERMISSION_GRANTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(permissionReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(permissionReceiver, filter)
        }
    }

    @SuppressLint("InflateParams")
    private fun createFloatingView() {
        try {
            floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_control, null)
            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
            params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, layoutFlag, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 100 }
            setupUI()
            setupDraggable()
            windowManager?.addView(floatingView, params)
        } catch (e: Exception) { stopSelf() }
    }

    private fun setupUI() {
        val view = floatingView ?: return
        val btnToggle = view.findViewById<View>(R.id.btnToggle)
        val expandedLayout = view.findViewById<View>(R.id.expandedLayout)
        tvLog = view.findViewById(R.id.tvLog)

        // 1. LOGO Click
        btnToggle.setOnClickListener {
            if (ScreenCaptureService.getInstance() == null) {
                addLog("⚠️ Xin quyền màn hình...")
                val intent = Intent(this, ScreenCaptureActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } else {
                isExpanded = !isExpanded
                expandedLayout.visibility = if (isExpanded) View.VISIBLE else View.GONE
            }
        }

        // 2. Nút Thu gọn
        view.findViewById<View>(R.id.btnHide)?.setOnClickListener {
            isExpanded = false
            expandedLayout.visibility = View.GONE
        }

        // 3. Nút Play/Pause
        val btnPlayPause = view.findViewById<Button>(R.id.btnPlayPause)
        btnPlayPause?.setOnClickListener {
            if (isBotRunning) {
                stopBotProcess()
            } else {
                startBotReal()
            }
        }

        // 4. Nút Cấu hình vị trí
        view.findViewById<Button>(R.id.btnConfigPos)?.setOnClickListener {
            showPositionConfigDialog()
        }

        // 5. Nút Cài đặt
        view.findViewById<Button>(R.id.btnSettings)?.setOnClickListener {
            showSettingsDialog()
        }

        // 6. Nút Ghi Kịch bản (MỚI)
        view.findViewById<Button>(R.id.btnRecord)?.setOnClickListener {
            // Ẩn menu đi để ghi cho dễ
            floatingView?.visibility = View.GONE

            recordingController.startRecording { savedPath ->
                // Callback khi dừng ghi: Hiện lại menu
                floatingView?.visibility = View.VISIBLE

                // Tự động chọn kịch bản vừa ghi
                val autoService = AutoService.getInstance()
                autoService?.updateAttackScripts(listOf(savedPath))

                val fileName = File(savedPath).name
                view.findViewById<TextView>(R.id.tvScriptName)?.text = fileName
                addLog("✅ Đã lưu & chọn: $fileName")
            }
        }

        // 7. Nút Chọn Kịch bản (MỚI)
        view.findViewById<Button>(R.id.btnSelectScript)?.setOnClickListener {
            showScriptSelectionDialog()
        }
    }

    // --- LOGIC START/STOP ---
    private fun startBotReal() {
        if (ScreenCaptureService.getInstance() == null) {
            addLog("❌ Mất quyền! Bấm Logo để cấp lại.")
            return
        }
        val res = CoordinateManager.getGameResolution(this)
        if (res.x == 0) {
            addLog("⚠️ Chưa có độ phân giải. Đang thử lại...")
            val intent = Intent(ACTION_PERMISSION_GRANTED)
            permissionReceiver.onReceive(this, intent)
            return
        }

        val autoService = AutoService.getInstance()
        if (autoService != null) {
            val settings = SettingsManager.loadSettings(this)
            autoService.updateSettings(
                goldThreshold = settings.goldThreshold,
                elixirThreshold = settings.elixirThreshold,
                attackDuration = settings.attackDuration,
                upgradeGoldTrigger = settings.upgradeGold,
                upgradeElixirTrigger = settings.upgradeElixir,
                matchesBeforeUpgrade = settings.matchesBeforeUpgrade,
                enableWallUpgrade = settings.enableWall,
                enableResourceFilter = true
            )

            isBotRunning = true
            val btn = floatingView?.findViewById<Button>(R.id.btnPlayPause)
            btn?.text = "⏸"
            btn?.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#F44336"))

            addLog("▶️ Đang chạy...")
            isExpanded = false
            floatingView?.findViewById<View>(R.id.expandedLayout)?.visibility = View.GONE

            autoService.startBot(
                onLog = { msg -> addLog(msg) },
                getScreenshot = { ScreenCaptureService.getInstance()?.captureScreen() }
            )
        }
    }

    private fun stopBotProcess() {
        AutoService.getInstance()?.stopBot { msg -> addLog(msg) }

        isBotRunning = false
        val btn = floatingView?.findViewById<Button>(R.id.btnPlayPause)
        btn?.text = "▶"
        btn?.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF50"))

        addLog("⏹️ Đã dừng.")
    }

    // --- DIALOG CHỌN KỊCH BẢN ---
    private fun showScriptSelectionDialog() {
        floatingView?.visibility = View.GONE
        val dir = File(filesDir, "attack_recordings")
        if (!dir.exists()) dir.mkdirs()

        val files = dir.listFiles { _, name -> name.endsWith(".json") }
        if (files.isNullOrEmpty()) {
            Toast.makeText(this, "Chưa có kịch bản nào!", Toast.LENGTH_SHORT).show()
            floatingView?.visibility = View.VISIBLE
            return
        }

        val fileNames = files.map { it.name }.toTypedArray()
        val filePaths = files.map { it.absolutePath }

        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Chọn Kịch Bản")
            .setItems(fileNames) { _, which ->
                val selectedPath = filePaths[which]
                val selectedName = fileNames[which]

                val autoService = AutoService.getInstance()
                autoService?.updateAttackScripts(listOf(selectedPath))

                floatingView?.findViewById<TextView>(R.id.tvScriptName)?.text = selectedName
                addLog("✅ Đã chọn: $selectedName")
                floatingView?.visibility = View.VISIBLE
            }
            .setOnCancelListener { floatingView?.visibility = View.VISIBLE }
            .create()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        } else {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT)
        }
        dialog.show()
    }

    // --- DIALOG CÀI ĐẶT ---
    @SuppressLint("InflateParams")
    private fun showSettingsDialog() {
        floatingView?.visibility = View.GONE
        val dialogView = LayoutInflater.from(this).inflate(R.layout.layout_settings_dialog, null)
        val current = SettingsManager.loadSettings(this)

        val etGold = dialogView.findViewById<EditText>(R.id.etGoldThreshold)
        val etElixir = dialogView.findViewById<EditText>(R.id.etElixirThreshold)
        val etDuration = dialogView.findViewById<EditText>(R.id.etAttackDuration)
        val etUpGold = dialogView.findViewById<EditText>(R.id.etUpgradeGold)
        val etUpElixir = dialogView.findViewById<EditText>(R.id.etUpgradeElixir)
        val etMatches = dialogView.findViewById<EditText>(R.id.etMatches)
        val cbWall = dialogView.findViewById<CheckBox>(R.id.cbEnableWall)

        etGold.setText(current.goldThreshold.toString())
        etElixir.setText(current.elixirThreshold.toString())
        etDuration.setText(current.attackDuration.toString())
        etUpGold.setText(current.upgradeGold.toString())
        etUpElixir.setText(current.upgradeElixir.toString())
        etMatches.setText(current.matchesBeforeUpgrade.toString())
        cbWall.isChecked = current.enableWall

        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        } else {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT)
        }

        dialogView.findViewById<Button>(R.id.btnSave).setOnClickListener {
            try {
                SettingsManager.saveSettings(
                    this,
                    etGold.text.toString().toIntOrNull() ?: 0,
                    etElixir.text.toString().toIntOrNull() ?: 0,
                    etUpGold.text.toString().toIntOrNull() ?: 0,
                    etUpElixir.text.toString().toIntOrNull() ?: 0,
                    etDuration.text.toString().toIntOrNull() ?: 60,
                    etMatches.text.toString().toIntOrNull() ?: 3,
                    cbWall.isChecked
                )
                addLog("💾 Đã lưu cấu hình!")
                if (isBotRunning) {
                    AutoService.getInstance()?.updateSettings(
                        etGold.text.toString().toInt(), etElixir.text.toString().toInt(),
                        etDuration.text.toString().toInt(), etUpGold.text.toString().toInt(),
                        etUpElixir.text.toString().toInt(), etMatches.text.toString().toInt(),
                        cbWall.isChecked, true
                    )
                }
            } catch (e: Exception) {}
            dialog.dismiss()
            floatingView?.visibility = View.VISIBLE
        }

        dialogView.findViewById<Button>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
            floatingView?.visibility = View.VISIBLE
        }
        dialog.show()
    }

    // ... Helper Functions ...
    private fun addLog(msg: String) {
        serviceScope.launch {
            val time = java.text.SimpleDateFormat("mm:ss").format(java.util.Date())
            logLines.add("[$time] $msg")
            if (logLines.size > 15) logLines.removeAt(0)
            tvLog?.text = logLines.joinToString("\n")
            val sv = floatingView?.findViewById<View>(R.id.tvLog)?.parent as? ScrollView
            sv?.post { sv.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun showPositionConfigDialog() {
        floatingView?.visibility = View.GONE
        val options = arrayOf("1. Nút Tấn công", "2. Nút Tìm trận", "3. Nút Thả quân", "4. Nút Next", "5. Nút End Battle", "6. Nút OK", "7. Nút Về nhà", "8. Nút Upgrade Menu")
        val keys = arrayOf(CoordinateManager.KEY_BTN_ATTACK, CoordinateManager.KEY_BTN_FIND_MATCH, CoordinateManager.KEY_BTN_DEPLOY_ATTACK, CoordinateManager.KEY_BTN_NEXT, CoordinateManager.KEY_BTN_END_BATTLE, CoordinateManager.KEY_BTN_OK_RESULT, CoordinateManager.KEY_BTN_RETURN_HOME, CoordinateManager.KEY_BTN_UPGRADE_MENU)
        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Cấu hình vị trí").setItems(options) { _, which -> targetController.showTarget(keys[which], options[which]) { floatingView?.visibility = View.VISIBLE } }.setOnCancelListener { floatingView?.visibility = View.VISIBLE }.create()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) else dialog.window?.setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT)
        dialog.show()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDraggable() {
        val btn = floatingView?.findViewById<View>(R.id.btnToggle) ?: return
        var iX=0; var iY=0; var iTX=0f; var iTY=0f
        btn.setOnTouchListener { v, e -> when(e.action) { MotionEvent.ACTION_DOWN -> { iX=params!!.x; iY=params!!.y; iTX=e.rawX; iTY=e.rawY; true }; MotionEvent.ACTION_UP -> { if(Math.abs(e.rawX-iTX)<10 && Math.abs(e.rawY-iTY)<10) v.performClick(); true }; MotionEvent.ACTION_MOVE -> { params!!.x=iX+(e.rawX-iTX).toInt(); params!!.y=iY+(e.rawY-iTY).toInt(); windowManager?.updateViewLayout(floatingView, params); true }; else -> false } }
    }

    private fun createNotification(): Notification {
        val channelId = "floating_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(NotificationChannel(channelId, "Bot Control", NotificationManager.IMPORTANCE_LOW))
        }
        return NotificationCompat.Builder(this, channelId).setContentTitle("COC Bot").setContentText("Menu đang hiển thị").setSmallIcon(R.mipmap.ic_launcher).build()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(permissionReceiver)
        if (floatingView != null) windowManager?.removeView(floatingView)
        targetController.removeTarget()
        serviceScope.cancel()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}