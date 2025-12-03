package com.cocauto.processor

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import org.opencv.core.Mat
import org.opencv.core.Rect
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

/**
 * OCR Engine với khả năng tự động detect vùng nhận diện thông minh
 */
class OCREngine(private val context: Context) {

    private var tessBaseAPI: TessBaseAPI? = null
    private val imageProcessor = ImageProcessor()

    init {
        initTesseract()
    }

    private fun initTesseract() {
        try {
            val tessDataPath = context.filesDir.absolutePath
            val tessDir = File(tessDataPath, "tessdata")

            if (!tessDir.exists()) {
                tessDir.mkdirs()
            }

            val trainedDataFile = File(tessDir, "eng.traineddata")
            if (!trainedDataFile.exists()) {
                context.assets.open("tessdata/eng.traineddata").use { input ->
                    FileOutputStream(trainedDataFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            tessBaseAPI = TessBaseAPI().apply {
                init(tessDataPath, "eng")
                setVariable(TessBaseAPI.VAR_CHAR_WHITELIST, "0123456789")
                pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
            }

            Timber.d("Tesseract initialized successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize Tesseract")
        }
    }

    /**
     * Đọc số từ ROI đã được pre-process
     */
    fun recognizeNumber(processedMat: Mat): Int {
        try {
            val bitmap = imageProcessor.matToBitmap(processedMat)
            tessBaseAPI?.setImage(bitmap) ?: return 0

            val text = tessBaseAPI?.utF8Text ?: ""
            val cleanText = text.replace(Regex("[^0-9]"), "")

            return if (cleanText.isNotEmpty()) {
                cleanText.toIntOrNull() ?: 0
            } else {
                0
            }
        } catch (e: Exception) {
            Timber.e(e, "OCR recognition failed")
            return 0
        }
    }

    /**
     * NEW: Đọc tài nguyên của BẢN THÂN (góc trên bên phải)
     * Tự động phát hiện vùng dựa vào tỷ lệ màn hình
     */
    fun readPlayerResourcesSmart(screen: Mat): ResourceData {
        val screenWidth = screen.cols()
        val screenHeight = screen.rows()

        // Vùng vàng: Góc trên bên phải
        val goldX = (screenWidth * 0.70).toInt()
        val goldY = (screenHeight * 0.005).toInt()
        val goldW = (screenWidth * 0.25).toInt()
        val goldH = (screenHeight * 0.065).toInt()
        val goldRegion = Rect(goldX, goldY, goldW, goldH)

        // Vùng dầu: Ngay dưới vàng
        val elixirX = (screenWidth * 0.68).toInt()
        val elixirY = (screenHeight * 0.07).toInt()
        val elixirW = (screenWidth * 0.28).toInt()
        val elixirH = (screenHeight * 0.045).toInt()
        val elixirRegion = Rect(elixirX, elixirY, elixirW, elixirH)

        val gold = readResourceFromRegion(screen, goldRegion, false)
        val elixir = readResourceFromRegion(screen, elixirRegion, false)

        Timber.d("Player resources - Gold: $gold, Elixir: $elixir (Screen: ${screenWidth}x${screenHeight})")

        return ResourceData(gold, elixir)
    }

    /**
     * NEW: Đọc tài nguyên ĐỐI THỦ (Available Loot - góc trên bên trái)
     * Chỉ đọc 2 dòng đầu (Gold và Elixir), bỏ qua Dark Elixir
     */
    fun readEnemyResourcesSmart(screen: Mat): ResourceData {
        val screenWidth = screen.cols()
        val screenHeight = screen.rows()

        // Vùng vàng (dòng 1)
        val goldX = (screenWidth * 0.01).toInt()
        val goldY = (screenHeight * 0.10).toInt()
        val goldW = (screenWidth * 0.18).toInt()
        val goldH = (screenHeight * 0.035).toInt()
        val goldRegion = Rect(goldX, goldY, goldW, goldH)

        // Vùng dầu (dòng 2)
        val elixirX = (screenWidth * 0.01).toInt()
        val elixirY = (screenHeight * 0.135).toInt()
        val elixirW = (screenWidth * 0.18).toInt()
        val elixirH = (screenHeight * 0.035).toInt()
        val elixirRegion = Rect(elixirX, elixirY, elixirW, elixirH)

        val gold = readResourceFromRegion(screen, goldRegion, false)
        val elixir = readResourceFromRegion(screen, elixirRegion, true)

        Timber.d("Enemy resources - Gold: $gold, Elixir: $elixir (Screen: ${screenWidth}x${screenHeight})")

        return ResourceData(gold, elixir)
    }

    /**
     * Helper: Đọc tài nguyên từ một vùng cụ thể
     */
    private fun readResourceFromRegion(
        screen: Mat,
        region: Rect,
        isPurpleBackground: Boolean
    ): Int {
        try {
            // Validate region bounds
            if (region.x < 0 || region.y < 0 ||
                region.x + region.width > screen.cols() ||
                region.y + region.height > screen.rows()) {
                Timber.w("Invalid region bounds: $region for screen ${screen.cols()}x${screen.rows()}")
                return 0
            }

            val roi = imageProcessor.cropROI(screen, region)

            // Pipeline 1: Original filter
            val processed1 = if (isPurpleBackground) {
                imageProcessor.prepPurpleBackground(roi)
            } else {
                imageProcessor.prepWhiteText(roi)
            }

            // Pipeline 2: White text với scale lớn hơn
            val processed2 = imageProcessor.prepWhiteText(roi, 12.0)

            // Pipeline 3: Threshold đơn giản
            val gray = Mat()
            org.opencv.imgproc.Imgproc.cvtColor(roi, gray, org.opencv.imgproc.Imgproc.COLOR_BGR2GRAY)
            val processed3 = Mat()
            org.opencv.imgproc.Imgproc.threshold(gray, processed3, 160.0, 255.0, org.opencv.imgproc.Imgproc.THRESH_BINARY)

            // Pipeline 4: Invert
            val processed4 = Mat()
            org.opencv.core.Core.bitwise_not(processed3, processed4)

            // Thử OCR trên từng pipeline
            val candidates = mutableListOf<Int>()

            listOf(processed1, processed2, processed3, processed4).forEach { mat ->
                val value = recognizeNumber(mat)
                if (value > 0) {
                    candidates.add(value)
                }
                mat.release()
            }

            roi.release()
            gray.release()

            // Trả về giá trị xuất hiện nhiều nhất hoặc giá trị lớn nhất
            return if (candidates.isNotEmpty()) {
                val grouped = candidates.groupingBy { it }.eachCount()
                val mostFrequent = grouped.maxByOrNull { it.value }?.key
                val largest = candidates.maxOrNull()

                mostFrequent ?: largest ?: 0
            } else {
                0
            }

        } catch (e: Exception) {
            Timber.e(e, "Failed to read resource from region")
            return 0
        }
    }

    /**
     * LEGACY: Giữ lại để tương thích với code cũ
     */
    fun readPlayerResource(
        screen: Mat,
        region: Rect,
        isPurpleBackground: Boolean = false
    ): Int {
        return readResourceFromRegion(screen, region, isPurpleBackground)
    }

    /**
     * NEW: Tìm text "Wall" trong menu upgrade
     * Trả về tọa độ của text nếu tìm thấy
     */
    fun findTextInRegion(screen: Mat, searchText: String, searchRegion: Rect? = null): android.graphics.Point? {
        try {
            // Nếu không có region cụ thể, tìm trong toàn bộ màn hình
            val roi = if (searchRegion != null) {
                imageProcessor.cropROI(screen, searchRegion)
            } else {
                screen.clone()
            }

            // Chuẩn bị ảnh cho OCR text
            val gray = Mat()
            org.opencv.imgproc.Imgproc.cvtColor(roi, gray, org.opencv.imgproc.Imgproc.COLOR_BGR2GRAY)

            val processed = Mat()
            org.opencv.imgproc.Imgproc.threshold(gray, processed, 0.0, 255.0,
                org.opencv.imgproc.Imgproc.THRESH_BINARY + org.opencv.imgproc.Imgproc.THRESH_OTSU)

            // Cho phép nhận diện chữ
            tessBaseAPI?.setVariable(TessBaseAPI.VAR_CHAR_WHITELIST, "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz")
            tessBaseAPI?.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO

            val bitmap = imageProcessor.matToBitmap(processed)
            tessBaseAPI?.setImage(bitmap)

            val detectedText = tessBaseAPI?.utF8Text ?: ""

            // Restore settings về nhận diện số
            tessBaseAPI?.setVariable(TessBaseAPI.VAR_CHAR_WHITELIST, "0123456789")
            tessBaseAPI?.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_LINE

            roi.release()
            gray.release()
            processed.release()

            // Kiểm tra xem có chứa text cần tìm không (case-insensitive)
            if (detectedText.contains(searchText, ignoreCase = true)) {
                // Trả về tọa độ trung tâm của region
                val centerX = if (searchRegion != null) {
                    searchRegion.x + searchRegion.width / 2
                } else {
                    screen.cols() / 2
                }
                val centerY = if (searchRegion != null) {
                    searchRegion.y + searchRegion.height / 2
                } else {
                    screen.rows() / 2
                }

                Timber.d("Found text '$searchText' at ($centerX, $centerY)")
                return android.graphics.Point(centerX, centerY)
            }

            return null
        } catch (e: Exception) {
            Timber.e(e, "Failed to find text in region")
            return null
        }
    }

    /**
     * Đọc giá tường (Wall price)
     */
    fun readWallPrice(roi: Mat): Pair<Int, String> {
        try {
            val allowedPrices = setOf(
                1_000, 5_000, 10_000, 20_000, 30_000, 50_000, 75_000,
                100_000, 200_000, 500_000, 1_000_000, 1_500_000,
                2_000_000, 3_000_000, 4_000_000, 5_000_000,
                7_000_000, 10_000_000
            )

            val candidates = mutableListOf<Int>()

            // Pipeline 1: Gold price filter
            val processed1 = imageProcessor.prepGoldPrice(roi)
            val value1 = recognizeNumber(processed1)
            if (value1 > 0) candidates.add(value1)
            processed1.release()

            // Pipeline 2: White text
            val processed2 = imageProcessor.prepWhiteText(roi, 9.0)
            val value2 = recognizeNumber(processed2)
            if (value2 > 0) candidates.add(value2)
            processed2.release()

            val validPrices = candidates.filter { it in allowedPrices }

            return if (validPrices.isNotEmpty()) {
                val bestPrice = validPrices.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 0
                Pair(bestPrice, bestPrice.toString())
            } else {
                Pair(0, "")
            }

        } catch (e: Exception) {
            Timber.e(e, "Failed to read wall price")
            return Pair(0, "")
        }
    }

    fun release() {
        tessBaseAPI?.end()
        tessBaseAPI = null
    }
}

/**
 * Data class cho tài nguyên
 */
data class ResourceData(val gold: Int, val elixir: Int)