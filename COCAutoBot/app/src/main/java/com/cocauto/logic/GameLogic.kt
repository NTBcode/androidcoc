package com.cocauto.logic

import android.content.Context
import android.graphics.Bitmap
import com.cocauto.processor.ImageProcessor
import com.cocauto.processor.OCREngine
import com.cocauto.utils.CoordinateManager
import com.cocauto.utils.GestureDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.opencv.core.Rect
import timber.log.Timber
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

                if (gameWidth != screenshot.width) {
                    gameWidth = screenshot.width
                    gameHeight = screenshot.height
                }

                if (enableWallUpgrade) {
                    val screenMat = imageProcessor.bitmapToMat(screenshot)
                    // NEW: Sử dụng hàm smart detection
                    val playerRes = ocrEngine.readPlayerResourcesSmart(screenMat)
                    screenMat.release()

                    onLog("Kho nhà: 🟡${formatK(playerRes.gold)}  🟣${formatK(playerRes.elixir)}")

                    if (playerRes.gold >= upgradeGoldTrigger || playerRes.elixir >= upgradeElixirTrigger) {
                        val useGold = playerRes.gold >= upgradeGoldTrigger
                        onLog("=> Dư tiền. Đi tìm tường...")
                        if (upgradeWallSmart(getScreenshot, useGold)) {
                            onLog("✅ Đã nâng tường thành công!")
                        } else {
                            onLog("⚠️ Không tìm thấy tường để nâng.")
                        }
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

            // NEW: Sử dụng hàm smart detection
            val enemyRes = ocrEngine.readEnemyResourcesSmart(screenMat)
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
     */
    private suspend fun clickButton(key: String): Boolean {
        if (!isRunning) return false

        val point = CoordinateManager.getCoordinate(context, key)
        if (point.x <= 0 || point.y <= 0) {
            onLog("⚠️ Chưa cài nút: $key")
            return false
        }

        gestureDispatcher.tap(point.x.toFloat(), point.y.toFloat())
        return true
    }

    /**
     * NEW: Nâng tường thông minh
     * Tìm chữ "Wall" trong menu upgrade bằng cách vuốt lên/xuống
     */
    private suspend fun upgradeWallSmart(getScreenshot: suspend () -> Bitmap?, useGold: Boolean): Boolean {
        try {
            // Bước 1: Mở menu upgrade
            onLog("📋 Mở menu upgrade...")
            if (!clickButton(CoordinateManager.KEY_BTN_UPGRADE_MENU)) {
                onLog("❌ Chưa cài nút Upgrade Menu")
                return false
            }
            delay(1500)

            // Bước 2: Tìm chữ "Wall" bằng cách vuốt
            onLog("🔍 Tìm chữ Wall...")
            val wallFound = findWallInMenu(getScreenshot)
            if (!wallFound) {
                onLog("❌ Không tìm thấy Wall sau khi tìm kiếm")
                // Đóng menu và return
                clickButton(CoordinateManager.KEY_BTN_RETURN_HOME)
                delay(1000)
                return false
            }

            // Bước 3: Click vào Wall (đã được click trong findWallInMenu)
            onLog("✅ Đã click vào Wall")
            delay(1500)

            // Bước 4: Click nút upgrade (Gold hoặc Elixir)
            val upgradeKey = if (useGold) {
                onLog("💰 Chọn nâng bằng Vàng...")
                CoordinateManager.KEY_BTN_UPGRADE_WALL_GOLD
            } else {
                onLog("🟣 Chọn nâng bằng Dầu...")
                CoordinateManager.KEY_BTN_UPGRADE_WALL_ELIXIR
            }

            if (!clickButton(upgradeKey)) {
                onLog("❌ Chưa cài nút upgrade wall (${if (useGold) "Gold" else "Elixir"})")
                return false
            }
            delay(1000)

            // Bước 5: Xác nhận upgrade
            onLog("✔️ Xác nhận nâng tường...")
            if (!clickButton(CoordinateManager.KEY_BTN_CONFIRM_WALL_UPGRADE)) {
                onLog("❌ Chưa cài nút xác nhận")
                return false
            }
            delay(1500)

            onLog("🎉 Hoàn thành nâng tường!")
            return true

        } catch (e: Exception) {
            Timber.e(e, "Error in upgradeWallSmart")
            onLog("❌ Lỗi nâng tường: ${e.message}")
            return false
        }
    }

    /**
     * NEW: Tìm chữ "Wall" trong menu bằng cách vuốt lên/xuống
     * Trả về true nếu tìm thấy và đã click vào Wall
     */
    private suspend fun findWallInMenu(getScreenshot: suspend () -> Bitmap?): Boolean {
        val maxSwipes = 7
        var direction = "down" // Bắt đầu vuốt xuống
        var swipeCount = 0
        var totalAttempts = 0
        val maxTotalAttempts = 20 // Tổng số lần thử tối đa

        // Vùng tìm kiếm: Menu upgrade thường ở giữa màn hình
        val searchRegion = Rect(
            (gameWidth * 0.2).toInt(),
            (gameHeight * 0.25).toInt(),
            (gameWidth * 0.6).toInt(),
            (gameHeight * 0.5).toInt()
        )

        onLog("🔎 Bắt đầu quét menu...")

        while (totalAttempts < maxTotalAttempts && isRunning) {
            totalAttempts++

            // Chụp màn hình và tìm text "Wall"
            val screenshot = getScreenshot()
            if (screenshot != null) {
                val screenMat = imageProcessor.bitmapToMat(screenshot)
                val wallPosition = ocrEngine.findTextInRegion(screenMat, "Wall", searchRegion)
                screenMat.release()

                if (wallPosition != null) {
                    onLog("✅ Tìm thấy Wall tại (${wallPosition.x}, ${wallPosition.y})!")
                    // Click vào vị trí tìm được
                    gestureDispatcher.tap(wallPosition.x.toFloat(), wallPosition.y.toFloat())
                    delay(500)
                    return true
                }
            }

            // Nếu chưa tìm thấy, vuốt menu
            swipeCount++

            if (swipeCount > maxSwipes) {
                // Đổi hướng vuốt
                direction = if (direction == "down") "up" else "down"
                swipeCount = 0
                onLog("🔄 Đổi hướng: ${if (direction == "down") "⬇️ Xuống" else "⬆️ Lên"}")
            }

            // Thực hiện vuốt
            performMenuSwipe(direction)
            delay(800) // Chờ animation
        }

        onLog("⏱️ Hết thời gian tìm Wall (${totalAttempts} lần thử)")
        return false
    }

    /**
     * NEW: Vuốt menu upgrade lên hoặc xuống
     */
    private suspend fun performMenuSwipe(direction: String) {
        val centerX = gameWidth / 2f
        val startY: Float
        val endY: Float

        if (direction == "down") {
            // Vuốt từ trên xuống (scroll down - xem nội dung bên dưới)
            startY = (gameHeight * 0.6).toFloat()
            endY = (gameHeight * 0.3).toFloat()
        } else {
            // Vuốt từ dưới lên (scroll up - xem nội dung bên trên)
            startY = (gameHeight * 0.3).toFloat()
            endY = (gameHeight * 0.6).toFloat()
        }

        gestureDispatcher.swipe(centerX, startY, centerX, endY, 300L)
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

    /**
     * NEW: Test phát lại attack script
     */
    suspend fun testAttackScript(scriptPath: String, onLog: (String) -> Unit) {
        onLog("🎬 Test script: ${scriptPath.substringAfterLast("/")}")

        val recordingData = if (scriptPath.startsWith("assets/")) {
            attackRecorder.loadRecordingFromAssets(scriptPath.substringAfter("assets/"))
        } else {
            attackRecorder.loadRecording(scriptPath)
        }

        if (recordingData == null) {
            onLog("❌ Không load được script!")
            return
        }

        val gestures = attackRecorder.buildGestureSummary(recordingData.actions)
        onLog("📊 Script có ${gestures.size} gestures, thời lượng: ${recordingData.metadata.durationSeconds}s")

        var currentTimeMs = 0L
        for ((index, gesture) in gestures.withIndex()) {
            val targetTime = gesture.startTimeMs
            val wait = targetTime - currentTimeMs
            if (wait > 10) delay(wait)

            val start = scaleScriptPoint(gesture.startPoint)
            val end = scaleScriptPoint(gesture.endPoint)

            when (gesture.type) {
                "tap" -> {
                    gestureDispatcher.tap(start.first, start.second)
                    if (index % 10 == 0) { // Log mỗi 10 gesture để không spam
                        onLog("👆 Tap #${index + 1}/${gestures.size}")
                    }
                }
                "hold", "swipe" -> {
                    gestureDispatcher.swipe(
                        start.first, start.second,
                        end.first, end.second,
                        gesture.durationMs
                    )
                    if (index % 10 == 0) {
                        onLog("👉 Swipe #${index + 1}/${gestures.size}")
                    }
                }
            }

            currentTimeMs = targetTime
            delay(50) // Delay nhỏ giữa các gesture
        }

        onLog("✅ Hoàn thành test! Đã thực hiện ${gestures.size} gestures")
    }

    private suspend fun runOneScriptCycle() {
        if (selectedAttackScripts.isEmpty()) {
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

    /**
     * Scale tọa độ script (960x540) -> Game Resolution
     */
    private fun scaleScriptPoint(point: Pair<Int, Int>): Pair<Float, Float> {
        val scriptBaseW = 960f
        val scriptBaseH = 540f
        val scaleX = gameWidth / scriptBaseW
        val scaleY = gameHeight / scriptBaseH

        return Pair(point.first * scaleX, point.second * scaleY)
    }

    private fun formatK(value: Int): String {
        return if (value >= 1000) "${value / 1000}k" else "$value"
    }

    fun setAttackScripts(scriptPaths: List<String>) {
        selectedAttackScripts = scriptPaths
    }

    fun release() {
        ocrEngine.release()
    }
}