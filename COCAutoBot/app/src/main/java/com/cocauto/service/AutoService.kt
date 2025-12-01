package com.cocauto.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityEvent
import com.cocauto.logic.GameLogic
import com.cocauto.utils.GestureDispatcher
import kotlinx.coroutines.*
import timber.log.Timber

/**
 * Accessibility Service - Core của bot
 * Đã cập nhật: Thêm hàm performPassThroughTap cho chế độ Ghi âm
 */
class AutoService : AccessibilityService() {

    private lateinit var gestureDispatcher: GestureDispatcher
    private var gameLogic: GameLogic? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var botJob: Job? = null
    private var currentLogCallback: ((String) -> Unit)? = null

    // Biến lưu cấu hình
    private var savedGoldThreshold = 100_000
    private var savedElixirThreshold = 100_000
    private var savedAttackDuration = 60
    private var savedUpgradeGoldTrigger = 5_000_000
    private var savedUpgradeElixirTrigger = 5_000_000
    private var savedMatchesBeforeUpgrade = 3
    private var savedEnableWallUpgrade = true
    private var savedEnableResourceFilter = true
    private var savedAttackScripts: List<String> = emptyList()

    companion object {
        @Volatile
        private var instance: AutoService? = null
        fun getInstance(): AutoService? = instance
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            instance = this
            gestureDispatcher = GestureDispatcher(this)
            Timber.d("AutoService connected")
        } catch (e: Exception) { Timber.e(e) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { stopBot { } }

    // --- HÀM CALIBRATE (Đồng bộ) ---
    suspend fun calibrateResolutionSync(getScreenshot: suspend () -> Bitmap?): Boolean {
        ensureGameLogicCreated()
        return gameLogic?.initializeResolution(getScreenshot) ?: false
    }

    // --- HÀM CLICK XUYÊN THẤU (Đã tối ưu tốc độ) ---
    fun performPassThroughTap(x: Float, y: Float) {
        // Kiểm tra khởi tạo để tránh crash
        if (::gestureDispatcher.isInitialized) {
            // Gọi trực tiếp hàm async, không cần coroutine launch để giảm độ trễ
            gestureDispatcher.tapAsync(x, y)
        }
    }

    /**
     * Start bot
     */
    fun startBot(onLog: (String) -> Unit, getScreenshot: suspend () -> Bitmap?) {
        if (botJob?.isActive == true) {
            botJob?.cancel()
        }

        currentLogCallback = onLog
        ensureGameLogicCreated()
        applySavedSettingsToLogic()

        gameLogic?.isRunning = true

        botJob = serviceScope.launch {
            try {
                gameLogic?.mainLoop(getScreenshot)
            } catch (e: CancellationException) {
                currentLogCallback?.invoke("⏹️ Đã dừng.")
            } catch (e: Exception) {
                Timber.e(e, "Bot error")
                currentLogCallback?.invoke("❌ Lỗi: ${e.message}")
            }
        }
        onLog("▶️ Bot đã khởi động")
    }

    /**
     * Stop bot
     */
    fun stopBot(onLog: (String) -> Unit) {
        gameLogic?.isRunning = false
        if (botJob?.isActive == true) {
            botJob?.cancel()
        }
        botJob = null
    }

    fun updateSettings(
        goldThreshold: Int, elixirThreshold: Int, attackDuration: Int,
        upgradeGoldTrigger: Int, upgradeElixirTrigger: Int, matchesBeforeUpgrade: Int,
        enableWallUpgrade: Boolean, enableResourceFilter: Boolean
    ) {
        this.savedGoldThreshold = goldThreshold
        this.savedElixirThreshold = elixirThreshold
        this.savedAttackDuration = attackDuration
        this.savedUpgradeGoldTrigger = upgradeGoldTrigger
        this.savedUpgradeElixirTrigger = upgradeElixirTrigger
        this.savedMatchesBeforeUpgrade = matchesBeforeUpgrade
        this.savedEnableWallUpgrade = enableWallUpgrade
        this.savedEnableResourceFilter = enableResourceFilter

        if (gameLogic != null) applySavedSettingsToLogic()
    }

    fun updateAttackScripts(scriptPaths: List<String>) {
        this.savedAttackScripts = scriptPaths
        gameLogic?.setAttackScripts(scriptPaths)
    }

    private fun ensureGameLogicCreated() {
        if (gameLogic == null) {
            gameLogic = GameLogic(this, gestureDispatcher) { msg ->
                currentLogCallback?.invoke(msg) ?: Timber.i("BotLog: $msg")
            }
        }
    }

    private fun applySavedSettingsToLogic() {
        gameLogic?.apply {
            goldThreshold = savedGoldThreshold
            elixirThreshold = savedElixirThreshold
            attackDuration = savedAttackDuration
            upgradeGoldTrigger = savedUpgradeGoldTrigger
            upgradeElixirTrigger = savedUpgradeElixirTrigger
            matchesBeforeUpgrade = savedMatchesBeforeUpgrade
            enableWallUpgrade = savedEnableWallUpgrade
            enableResourceFilter = savedEnableResourceFilter
            setAttackScripts(savedAttackScripts)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        gameLogic?.isRunning = false
        botJob?.cancel()
        serviceScope.cancel()
        gameLogic?.release()
        instance = null
        Timber.d("AutoService destroyed")
    }
}