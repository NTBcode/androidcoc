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
    // 1. Của người chơi (Góc Phải)
    private var playerGoldRegion: Rect? = null
    private var playerElixirRegion: Rect? = null

    // 2. Của đối thủ (Góc Trái - Mới thêm)
    private var enemyGoldRegion: Rect? = null
    private var enemyElixirRegion: Rect? = null

    private var selectedAttackScripts: List<String> = emptyList()
    private var currentScriptIndex = 0

    // --- HÀM TÍNH TOÁN VÙNG QUÉT THÔNG MINH ---
    private fun calculateSmartRegions(width: Int, height: Int) {
        // A. NGƯỜI CHƠI (Góc Trên Phải - Như cũ)
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

        // B. ĐỐI THỦ (Góc Trên Trái - Mới cập nhật theo ảnh)
        // Vị trí: X ~ 2.5%, Y ~ 13.5% (Vàng), Y ~ 18.5% (Dầu)
        val eGx = (width * 0.025).toInt()
        val eGy = (height * 0.135).toInt()
        val eGw = (width * 0.15).toInt() // Rộng khoảng 15% màn hình
        val eGh = (height * 0.045).toInt() // Cao khoảng 4.5% màn hình
        enemyGoldRegion = Rect(eGx, eGy, eGw, eGh)

        val eEx = eGx
        val eEy = (height * 0.185).toInt()
        val eEw = eGw
        val eEh = eGh
        enemyElixirRegion = Rect(eEx, eEy, eEw, eEh)

        Timber.d("Smart Regions Updated for ${width}x${height}")
    }

    // --- CẬP NHẬT ĐỘ PHÂN GIẢI ---
    private fun updateGameResolution(width: Int, height: Int) {
        if (gameWidth != width || gameHeight != height) {
            gameWidth = width
            gameHeight = height
            // Tính toán lại ngay khi có kích thước mới
            calculateSmartRegions(width, height)
            onLog("Đã xác định màn hình: ${gameWidth}x${gameHeight}")
        }
    }

    // --- HÀM KHỞI TẠO (Called by AutoService) ---
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

    // --- MAIN LOOP ---
    suspend fun mainLoop(getScreenshot: suspend () -> Bitmap?) {
        onLog("=== BOT BẮT ĐẦU ===")
        delay(1000)

        // 1. Lấy độ phân giải đã lưu để tính vùng quét trước
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
                // Luôn lấy ảnh mới nhất
                val screenshot = getScreenshot()
                if (screenshot == null) {
                    onLog("Lỗi chụp ảnh. Thử lại...")
                    delay(2000); continue
                }

                // Check lại nếu màn hình xoay
                updateGameResolution(screenshot.width, screenshot.height)

                if (enableWallUpgrade) {
                    // Check tài nguyên người chơi
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
                    // onLog("Nghỉ 3s...")
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

    // --- LOGIC ĐI FARM ---
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
            // onLog("Tìm nhà... ($searchCount)")

            val screenshot = getScreenshot() ?: continue
            val screenMat = imageProcessor.bitmapToMat(screenshot)

            // QUAN TRỌNG: Dùng hàm đọc tài nguyên ĐỐI THỦ mới
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

    // --- CÁC HÀM OCR THÔNG MINH (AUTO SCALE) ---

    private fun getPlayerResourcesSmart(screen: org.opencv.core.Mat): ResourceData {
        // Lấy vùng đã tính toán
        val rGold = playerGoldRegion ?: return ResourceData(0, 0)
        val rElixir = playerElixirRegion ?: return ResourceData(0, 0)

        val gold = ocrEngine.readPlayerResource(screen, rGold, false)
        val elixir = ocrEngine.readPlayerResource(screen, rElixir, false)
        return ResourceData(gold, elixir)
    }

    private fun getEnemyResourcesSmart(screen: org.opencv.core.Mat): ResourceData {
        // Lấy vùng đã tính toán
        val rGold = enemyGoldRegion ?: return ResourceData(0, 0)
        val rElixir = enemyElixirRegion ?: return ResourceData(0, 0)

        // Đối thủ thường số màu trắng/vàng nhạt trên nền tối
        val gold = ocrEngine.readPlayerResource(screen, rGold, false)

        // Dầu đối thủ đôi khi có nền tím đậm, thử bật isPurple=true
        val elixir = ocrEngine.readPlayerResource(screen, rElixir, true)

        return ResourceData(gold, elixir)
    }

    // --- CÁC HÀM KHÁC GIỮ NGUYÊN ---

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

    private suspend fun clickButton(key: String): Boolean {
        if (!isRunning) return false
        val point = CoordinateManager.getCoordinate(context, key)
        if (point.x <= 0) {
            onLog("⚠️ Chưa cài nút: $key")
            return false
        }
        // Ánh xạ ngược từ tọa độ Game -> Màn hình thực
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val metrics = android.util.DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)

        val scaleX = metrics.widthPixels.toFloat() / gameWidth
        val scaleY = metrics.heightPixels.toFloat() / gameHeight

        val clickX = point.x * scaleX
        val clickY = point.y * scaleY

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
