package com.cocauto.processor

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import org.opencv.core.Mat
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

/**
 * OCR Engine sử dụng Tesseract
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

            // Copy eng.traineddata từ assets nếu chưa có
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
     * Đọc tài nguyên người chơi (Gold/Elixir) với nhiều pipeline
     */
    fun readPlayerResource(
        screen: Mat,
        region: org.opencv.core.Rect,
        isPurpleBackground: Boolean = false
    ): Int {
        try {
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

            // Trả về giá trị xuất hiện nhiều nhất
            return if (candidates.isNotEmpty()) {
                candidates.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 0
            } else {
                0
            }

        } catch (e: Exception) {
            Timber.e(e, "Failed to read player resource")
            return 0
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

            // Lọc giá hợp lệ
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