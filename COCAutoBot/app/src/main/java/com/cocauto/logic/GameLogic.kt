package com.cocauto.logic

import android.content.Context
import android.graphics.Bitmap
import android.util.DisplayMetrics
import android.view.WindowManager
import com.cocauto.processor.ImageProcessor
import com.cocauto.processor.OCREngine
import com.cocauto.utils.CoordinateManager
import com.cocauto.utils.GestureDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.opencv.core.Rect
import timber.log.Timber
import java.io.File
import kotlin.coroutines.coroutineContext

class GameLogic(
    private val context: Context,
    private val gestureDispatcher: GestureDispatcher,
    private val onLog: (String) -> Unit
) {

    private val imageProcessor = ImageProcessor()
    private val ocrEngine = OCREngine(context)
    private val attackRecorder = AttackRecorder(context)

    // --- CẤU HÌNH ---
    var goldThreshold = 100_000
    var elixirThreshold = 100_000
    var attackDuration = 60
    var upgradeGoldTrigger = 5_000_000
    var upgradeElixirTrigger = 5_000_000
    var matchesBeforeUpgrade = 3
    var enableWallUpgrade = true
    var enableResourceFilter = true

    @Volatile
    var isRunning = false

    // Độ phân giải Game (Lấy từ ảnh chụp)
    private var gameWidth = 0
    private var gameHeight = 0

    private var selectedAttackScripts: List<String> = emptyList()
    private var currentScriptIndex = 0

    // --- KHỞI TẠO ĐỘ PHÂN GIẢI ---
    suspend fun initializeResolution(getScreenshot: suspend () -> Bitmap?): Boolean {
        var attempts = 0
        while (attempts < 5) {
            val screenshot = getScreenshot()
            if (screenshot != null && screenshot.width > 0 && screenshot.height > 0) {
                gameWidth = screenshot.width
                gameHeight = screenshot.height
                CoordinateManager.saveGameResolution(context, gameWidth, gameHeight)
                Timber.d("Đã lấy độ phân giải: ${gameWidth}x${gameHeight}")
                return true
            }
            attempts++
            delay(500)
        }
        return false
    }

    // --- MAIN LOOP ---
    suspend fun mainLoop(getScreenshot: suspend () -> Bitmap?) {
        onLog("=== BOT BẮT ĐẦU ===")
        delay(1000)

        // 1. Nạp độ phân giải (Quan trọng để scale tọa độ)
        val savedRes = CoordinateManager.getGameResolution(context)
        if (savedRes.x > 0) {
            gameWidth = savedRes.x
            gameHeight = savedRes.y
        } else {
            onLog("⚠️ Đang đo màn hình...")
            if (!initializeResolution(getScreenshot)) {
                onLog("❌ Lỗi: Không lấy được ảnh. Dừng bot.")
                return
            }
        }

        var matchesSinceCheck = 0

        while (isRunning && coroutineContext.isActive) {
            try {
                val screenshot = getScreenshot()
                if (screenshot == null) { delay(2000); continue }

                // Cập nhật lại nếu xoay màn hình
                if (gameWidth != screenshot.width) {
                    gameWidth = screenshot.width
                    gameHeight = screenshot.height
                }

                if (enableWallUpgrade) {
                    val screenMat = imageProcessor.bitmapToMat(screenshot)
                    // Dùng hàm OCR cũ (scaleRect)
                    val playerRes = getPlayerResources(screenMat)
                    screenMat.release()

                    onLog("Kho nhà: 🟡${formatK(playerRes.gold)}  🟣${formatK(playerRes.elixir)}")

                    if (playerRes.gold >= upgradeGoldTrigger || playerRes.elixir >= upgradeElixirTrigger) {
                        val type = if (playerRes.gold >= upgradeGoldTrigger) "Vàng" else "Dầu"
                        onLog("=> Dư tiền. Đập tường ($type)...")
                        upgradeWallOnce(type)
                        delay(2000)
                    } else {
                        onLog("=> Thiếu tiền. Đi Farm...")
                        if (performFarming(getScreenshot)) matchesSinceCheck++
                    }
                } else {
                    onLog("Chế độ: CHỈ FARM")
                    if (performFarming(getScreenshot)) matchesSinceCheck++
                }

                if (isRunning) delay(3000)

            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                onLog("Lỗi: ${e.message}")
                delay(3000)
            }
        }
        onLog("Bot đã dừng.")
    }

    // --- FARMING ---
    private suspend fun performFarming(getScreenshot: suspend () -> Bitmap?): Boolean {
        if (!clickButton(CoordinateManager.KEY_BTN_ATTACK)) return false
        delay(2000)
        if (!clickButton(CoordinateManager.KEY_BTN_FIND_MATCH)) return false
        delay(1000)
        if (!clickButton(CoordinateManager.KEY_BTN_DEPLOY_ATTACK)) return false
        delay(4000)

        var searchCount = 0
        val maxSearch = 99
        var foundTarget = false

        while (isRunning && searchCount < maxSearch && coroutineContext.isActive) {
            searchCount++
            val screenshot = getScreenshot() ?: continue
            val screenMat = imageProcessor.bitmapToMat(screenshot)

            // Dùng hàm OCR cũ (scaleRect)
            val enemyRes = getEnemyResources(screenMat)
            screenMat.release()

            if (enemyRes.gold >= goldThreshold && enemyRes.elixir >= elixirThreshold) {
                onLog("⚔️ TẤN CÔNG! (🟡${formatK(enemyRes.gold)}  🟣${formatK(enemyRes.elixir)})")
                foundTarget = true
                break
            } else {
                onLog("⏭️ Next ($searchCount): 🟡${formatK(enemyRes.gold)}  🟣${formatK(enemyRes.elixir)}")
                if (!clickButton(CoordinateManager.KEY_BTN_NEXT)) return false
                delay(5000)
            }
        }

        if (foundTarget) {
            executeAttackSequence()
            return true
        } else {
            onLog("🏠 Không tìm thấy. Về nhà.")
            returnHome()
            return false
        }
    }

    /**
     * Click nút đã lưu
     * Tọa độ đã lưu là trên hệ quy chiếu GAME
     * GestureDispatcher sẽ tự động chuyển sang màn hình vật lý
     */
    private suspend fun clickButton(key: String): Boolean {
        if (!isRunning) return false

        val point = CoordinateManager.getCoordinate(context, key)
        if (point.x <= 0 || point.y <= 0) {
            onLog("⚠️ Chưa cài nút: $key")
            return false
        }

        // Dùng TRỰC TIẾP tọa độ đã lưu (không cần scale)
        // GestureDispatcher sẽ tự động chuyển đổi
        gestureDispatcher.tap(point.x.toFloat(), point.y.toFloat())
        return true
    }



    // --- QUAY VỀ HÀM OCR CŨ (Sử dụng scaleRect từ 960x540) ---

    private fun getPlayerResources(screen: org.opencv.core.Mat): ResourceData {
        // Tọa độ cứng theo chuẩn 960x540
        val goldRegion = Rect(749, 5, 157, 51)
        val elixirRegion = Rect(740, 64, 190, 32)

        // Scale lên độ phân giải thực tế của Game (gameWidth x gameHeight)
        val gold = ocrEngine.readPlayerResource(screen, scaleRect(goldRegion), false)
        val elixir = ocrEngine.readPlayerResource(screen, scaleRect(elixirRegion), false)
        return ResourceData(gold, elixir)
    }

    private fun getEnemyResources(screen: org.opencv.core.Mat): ResourceData {
        // Tọa độ cứng theo chuẩn 960x540
        val goldRegion = Rect(22, 73, 124, 26)
        val elixirRegion = Rect(22, 101, 124, 26)

        val gold = ocrEngine.readPlayerResource(screen, scaleRect(goldRegion), false)
        val elixir = ocrEngine.readPlayerResource(screen, scaleRect(elixirRegion), true)
        return ResourceData(gold, elixir)
    }

    // Hàm scale này rất quan trọng để map từ 960x540 -> Game Resolution
    private fun scaleRect(rect: Rect): Rect {
        if (gameWidth == 0) return rect
        return imageProcessor.scaleRect(gameWidth, gameHeight, rect)
    }

    // --- CÁC HÀM PHỤ TRỢ KHÁC ---
    private suspend fun executeAttackSequence() {
        val startTime = System.currentTimeMillis()
        val durationMs = attackDuration * 1000L
        onLog("🔥 Đang đánh ($attackDuration s)...")
        while (System.currentTimeMillis() - startTime < durationMs && isRunning && coroutineContext.isActive) {
            runOneScriptCycle()
            delay(200)
        }
        onLog("🏁 Kết thúc.")
        clickButton(CoordinateManager.KEY_BTN_END_BATTLE)
        delay(1500)
        clickButton(CoordinateManager.KEY_BTN_OK_RESULT)
        delay(4000)
        clickButton(CoordinateManager.KEY_BTN_RETURN_HOME)
        delay(3000)
    }

    private suspend fun runOneScriptCycle() {
        if (selectedAttackScripts.isEmpty()) {
            // Fallback: Click nút Deploy
            clickButton(CoordinateManager.KEY_BTN_DEPLOY_ATTACK)
            return
        }

        val scriptPath = selectedAttackScripts[currentScriptIndex % selectedAttackScripts.size]

        val recordingData = if (scriptPath.startsWith("assets/")) {
            attackRecorder.loadRecordingFromAssets(scriptPath.substringAfter("assets/"))
        } else {
            attackRecorder.loadRecording(scriptPath)
        }

        if (recordingData != null) {
            val gestures = attackRecorder.buildGestureSummary(recordingData.actions)
            var currentTimeMs = 0L

            for (gesture in gestures) {
                if (!isRunning) break

                val targetTime = gesture.startTimeMs
                val wait = targetTime - currentTimeMs
                if (wait > 10) delay(wait)

                // QUAN TRỌNG: Scale tọa độ script đúng cách
                val start = scaleScriptPoint(gesture.startPoint)
                val end = scaleScriptPoint(gesture.endPoint)

                when (gesture.type) {
                    "tap" -> gestureDispatcher.tap(start.first, start.second)
                    "hold", "swipe" -> gestureDispatcher.swipe(
                        start.first, start.second,
                        end.first, end.second,
                        gesture.durationMs
                    )
                }

                currentTimeMs = targetTime
            }
        }

        currentScriptIndex = (currentScriptIndex + 1) % selectedAttackScripts.size
    }
    private suspend fun returnHome() {
        clickButton(CoordinateManager.KEY_BTN_END_BATTLE)
        delay(1000)
        clickButton(CoordinateManager.KEY_BTN_OK_RESULT)
        delay(3000)
        clickButton(CoordinateManager.KEY_BTN_RETURN_HOME)
    }

    private suspend fun upgradeWallOnce(type: String): Boolean {
        clickButton(CoordinateManager.KEY_BTN_UPGRADE_MENU)
        delay(1000)
        return true
    }

    /**
     * Scale tọa độ script (960x540) -> Game Resolution
     */
    private fun scaleScriptPoint(point: Pair<Int, Int>): Pair<Float, Float> {
        val scriptBaseW = 960f
        val scriptBaseH = 540f
        val scaleX = gameWidth / scriptBaseW
        val scaleY = gameHeight / scriptBaseH

        // Trả về tọa độ trên hệ quy chiếu GAME
        return Pair(point.first * scaleX, point.second * scaleY)
    }

    private fun formatK(value: Int): String {
        return if (value >= 1000) "${value / 1000}k" else "$value"
    }

    fun setAttackScripts(scriptPaths: List<String>) { selectedAttackScripts = scriptPaths }
    fun release() { ocrEngine.release() }
}

data class ResourceData(val gold: Int, val elixir: Int)