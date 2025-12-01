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

    private var gameWidth = 0
    private var gameHeight = 0

    // --- CÁC VÙNG QUÉT OCR (Tự động tính toán) ---
    private var playerGoldRegion: Rect? = null
    private var playerElixirRegion: Rect? = null
    private var enemyGoldRegion: Rect? = null
    private var enemyElixirRegion: Rect? = null

    private var selectedAttackScripts: List<String> = emptyList()
    private var currentScriptIndex = 0

    // === MỚI: Cache kích thước màn hình thực để tránh query nhiều lần ===
    private var realScreenWidth = 0
    private var realScreenHeight = 0

    private fun calculateSmartRegions(width: Int, height: Int) {
        val pGx = (width * 0.78).toInt()
        val pGy = (height * 0.005).toInt()
        val pGw = (width * 0.18).toInt()
        val pGh = (height * 0.09).toInt()
        playerGoldRegion = Rect(pGx, pGy, pGw, pGh)

        val pEx = (width * 0.78).toInt()
        val pEy = (height * 0.11).toInt()
        val pEw = (width * 0.18).toInt()
        val pEh = (height * 0.09).toInt()
        playerElixirRegion = Rect(pEx, pEy, pEw, pEh)

        val eGx = (width * 0.025).toInt()
        val eGy = (height * 0.135).toInt()
        val eGw = (width * 0.15).toInt()
        val eGh = (height * 0.045).toInt()
        enemyGoldRegion = Rect(eGx, eGy, eGw, eGh)

        val eEx = eGx
        val eEy = (height * 0.185).toInt()
        val eEw = eGw
        val eEh = eGh
        enemyElixirRegion = Rect(eEx, eEy, eEw, eEh)

        Timber.d("Smart Regions Updated for ${width}x${height}")
    }

    private fun updateGameResolution(width: Int, height: Int) {
        if (gameWidth != width || gameHeight != height) {
            gameWidth = width
            gameHeight = height
            calculateSmartRegions(width, height)

            // === QUAN TRỌNG: Cập nhật kích thước màn hình thực ===
            updateRealScreenSize()

            onLog("Đã xác định màn hình: ${gameWidth}x${gameHeight}")
        }
    }

    // === HÀM MỚI: Lấy kích thước màn hình thực (Physical screen) ===
    private fun updateRealScreenSize() {
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val metrics = android.util.DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(metrics)

            realScreenWidth = metrics.widthPixels
            realScreenHeight = metrics.heightPixels

            Timber.d("Real screen size: ${realScreenWidth}x${realScreenHeight}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to get real screen size")
        }
    }

    suspend fun initializeResolution(getScreenshot: suspend () -> Bitmap?): Boolean {
        var attempts = 0
        while (attempts < 5) {
            val screenshot = getScreenshot()
            if (screenshot != null && screenshot.width > 0 && screenshot.height > 0) {
                updateGameResolution(screenshot.width, screenshot.height)
                CoordinateManager.saveGameResolution(context, gameWidth, gameHeight)
                return true
            }
            attempts++
            delay(500)
        }
        return false
    }

    suspend fun mainLoop(getScreenshot: suspend () -> Bitmap?) {
        onLog("=== BOT BẮT ĐẦU ===")
        delay(1000)

        val savedRes = CoordinateManager.getGameResolution(context)
        if (savedRes.x > 0) {
            updateGameResolution(savedRes.x, savedRes.y)
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
                if (screenshot == null) {
                    onLog("Lỗi chụp ảnh. Thử lại...")
                    delay(2000); continue
                }

                updateGameResolution(screenshot.width, screenshot.height)

                if (enableWallUpgrade) {
                    val screenMat = imageProcessor.bitmapToMat(screenshot)
                    val playerRes = getPlayerResourcesSmart(screenMat)
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

                if (isRunning) {
                    delay(3000)
                }

            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                onLog("Lỗi: ${e.message}")
                delay(3000)
            }
        }
        onLog("Bot đã dừng.")
    }

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

            val enemyRes = getEnemyResourcesSmart(screenMat)
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

    private fun getPlayerResourcesSmart(screen: org.opencv.core.Mat): ResourceData {
        val rGold = playerGoldRegion ?: return ResourceData(0, 0)
        val rElixir = playerElixirRegion ?: return ResourceData(0, 0)

        val gold = ocrEngine.readPlayerResource(screen, rGold, false)
        val elixir = ocrEngine.readPlayerResource(screen, rElixir, false)
        return ResourceData(gold, elixir)
    }

    private fun getEnemyResourcesSmart(screen: org.opencv.core.Mat): ResourceData {
        val rGold = enemyGoldRegion ?: return ResourceData(0, 0)
        val rElixir = enemyElixirRegion ?: return ResourceData(0, 0)

        val gold = ocrEngine.readPlayerResource(screen, rGold, false)
        val elixir = ocrEngine.readPlayerResource(screen, rElixir, true)

        return ResourceData(gold, elixir)
    }

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
            val p = CoordinateManager.getCoordinate(context, CoordinateManager.KEY_BTN_DEPLOY_ATTACK)
            if (p.x > 0) gestureDispatcher.tap(p.x.toFloat(), p.y.toFloat())
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

                if (gesture.type == "tap") gestureDispatcher.tap(start.first, start.second)
                else gestureDispatcher.swipe(start.first, start.second, end.first, end.second, gesture.durationMs)

                currentTimeMs = targetTime
            }
        }
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

    // === HÀM ĐÃ SỬA: CLICK BUTTON VỚI LOGIC CHUYỂN ĐỔI TỌA ĐỘ ĐÚNG ===
    private suspend fun clickButton(key: String): Boolean {
        if (!isRunning) return false

        // 1. Lấy tọa độ đã lưu (trong hệ quy chiếu Game)
        val gamePoint = CoordinateManager.getCoordinate(context, key)

        if (gamePoint.x <= 0 || gamePoint.y <= 0) {
            onLog("⚠️ Chưa cài nút: $key")
            return false
        }

        // 2. Lấy kích thước màn hình thực (nếu chưa có)
        if (realScreenWidth == 0 || realScreenHeight == 0) {
            updateRealScreenSize()
        }

        // 3. Kiểm tra Game Resolution đã được khởi tạo chưa
        if (gameWidth == 0 || gameHeight == 0) {
            onLog("⚠️ Lỗi: Chưa có Game Resolution!")
            return false
        }

        // 4. === CHUYỂN ĐỔI: Game Coordinate -> Screen Coordinate ===
        // Công thức: screenPos = gamePos * (realScreenSize / gameSize)
        val scaleX = realScreenWidth.toFloat() / gameWidth
        val scaleY = realScreenHeight.toFloat() / gameHeight

        val clickX = gamePoint.x * scaleX
        val clickY = gamePoint.y * scaleY

        // 5. Thực hiện click
        Timber.d("Click $key: Game($gamePoint.x, $gamePoint.y) -> Screen($clickX, $clickY)")
        gestureDispatcher.tap(clickX, clickY)

        return true
    }

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

    fun setAttackScripts(scriptPaths: List<String>) { selectedAttackScripts = scriptPaths }
    fun release() { ocrEngine.release() }
}

data class ResourceData(val gold: Int, val elixir: Int)