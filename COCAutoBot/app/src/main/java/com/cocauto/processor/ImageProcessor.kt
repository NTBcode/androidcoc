package com.cocauto.processor

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import timber.log.Timber

/**
 * Xử lý ảnh OpenCV - Chuyển đổi trực tiếp từ logic Python
 */
class ImageProcessor {

    companion object {

    }

    /**
     * Chuyển đổi từ hàm _prep_white_text của Python
     * Lọc text trắng trên nền màu (cho Gold/Elixir người chơi)
     */
    fun prepWhiteText(roi: Mat, scaleFactor: Double = 10.0): Mat {
        // Scale up
        val roiLarge = Mat()
        Imgproc.resize(roi, roiLarge, Size(), scaleFactor, scaleFactor, Imgproc.INTER_CUBIC)

        // Bilateral filter
        val filtered = Mat()
        Imgproc.bilateralFilter(roiLarge, filtered, 7, 75.0, 75.0)

        // Convert to HSV
        val hsv = Mat()
        Imgproc.cvtColor(filtered, hsv, Imgproc.COLOR_BGR2HSV)

        // Define white color range
        val lowerWhite = Scalar(0.0, 0.0, 180.0)
        val upperWhite = Scalar(180.0, 60.0, 255.0)

        // Create mask
        val mask = Mat()
        Core.inRange(hsv, lowerWhite, upperWhite, mask)

        // Gaussian blur
        Imgproc.GaussianBlur(mask, mask, Size(3.0, 3.0), 0.0)

        // Binary threshold (Otsu)
        val binary = Mat()
        Imgproc.threshold(mask, binary, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)

        // Morphology operations
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel)

        val kernel2 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0, 2.0))
        Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_OPEN, kernel2)

        // Release temporary mats
        roiLarge.release()
        filtered.release()
        hsv.release()
        mask.release()
        kernel.release()
        kernel2.release()

        return binary
    }

    /**
     * Chuyển đổi từ hàm _prep_purple_bg của Python
     * Lọc text trắng trên nền tím (cho Elixir đối thủ)
     */
    fun prepPurpleBackground(roi: Mat, scaleFactor: Double = 12.0): Mat {
        // Scale up
        val roiLarge = Mat()
        Imgproc.resize(roi, roiLarge, Size(), scaleFactor, scaleFactor, Imgproc.INTER_CUBIC)

        // Bilateral filter
        val filtered = Mat()
        Imgproc.bilateralFilter(roiLarge, filtered, 7, 75.0, 75.0)

        // HSV
        val hsv = Mat()
        Imgproc.cvtColor(filtered, hsv, Imgproc.COLOR_BGR2HSV)

        // LAB
        val lab = Mat()
        Imgproc.cvtColor(filtered, lab, Imgproc.COLOR_BGR2Lab)

        // HSV white mask
        val lowerWhite = Scalar(0.0, 0.0, 200.0)
        val upperWhite = Scalar(180.0, 45.0, 255.0)
        val maskWhite = Mat()
        Core.inRange(hsv, lowerWhite, upperWhite, maskWhite)

        // LAB mask
        val channels = ArrayList<Mat>()
        Core.split(lab, channels)
        val lChannel = channels[0]
        val maskLab = Mat()
        Imgproc.threshold(lChannel, maskLab, 175.0, 255.0, Imgproc.THRESH_BINARY)

        // Combine masks
        val mask = Mat()
        Core.bitwise_or(maskWhite, maskLab, mask)

        // Filters
        Imgproc.medianBlur(mask, mask, 3)
        Imgproc.GaussianBlur(mask, mask, Size(3.0, 3.0), 0.0)

        // Binary threshold
        val binary = Mat()
        Imgproc.threshold(mask, binary, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)

        // Morphology
        val kernel1 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0, 2.0))
        Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_OPEN, kernel1)

        val kernel2 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(4.0, 4.0))
        Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel2)

        // Release
        roiLarge.release()
        filtered.release()
        hsv.release()
        lab.release()
        maskWhite.release()
        maskLab.release()
        mask.release()
        kernel1.release()
        kernel2.release()
        for (ch in channels) ch.release()

        return binary
    }

    /**
     * Chuyển đổi từ hàm _prep_gold_price của Python
     * Lọc text vàng (giá nâng tường)
     */
    fun prepGoldPrice(roi: Mat, scaleFactor: Double = 8.0): Mat {
        // Scale up
        val roiLarge = Mat()
        Imgproc.resize(roi, roiLarge, Size(), scaleFactor, scaleFactor, Imgproc.INTER_CUBIC)

        // Bilateral filter
        val filtered = Mat()
        Imgproc.bilateralFilter(roiLarge, filtered, 5, 60.0, 60.0)

        // HSV
        val hsv = Mat()
        Imgproc.cvtColor(filtered, hsv, Imgproc.COLOR_BGR2HSV)

        // Gold color range
        val lowerGold = Scalar(15.0, 80.0, 150.0)
        val upperGold = Scalar(45.0, 255.0, 255.0)

        // Mask
        val mask = Mat()
        Core.inRange(hsv, lowerGold, upperGold, mask)

        // Filters
        Imgproc.medianBlur(mask, mask, 3)
        Imgproc.GaussianBlur(mask, mask, Size(3.0, 3.0), 0.0)

        // Morphology
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)

        // Binary threshold
        val binary = Mat()
        Imgproc.threshold(mask, binary, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)

        // Release
        roiLarge.release()
        filtered.release()
        hsv.release()
        mask.release()
        kernel.release()

        return binary
    }

    /**
     * Scale rect từ tọa độ 960x540 sang resolution thực tế
     */
    fun scaleRect(
        screenWidth: Int,
        screenHeight: Int,
        rect960: Rect
    ): Rect {
        val sx = screenWidth / 960.0
        val sy = screenHeight / 540.0

        return Rect(
            (rect960.x * sx).toInt(),
            (rect960.y * sy).toInt(),
            (rect960.width * sx).toInt(),
            (rect960.height * sy).toInt()
        )
    }

    /**
     * Crop ROI từ Mat
     */
    fun cropROI(source: Mat, rect: Rect): Mat {
        return Mat(source, rect)
    }

    /**
     * Convert Bitmap to OpenCV Mat
     */
    fun bitmapToMat(bitmap: Bitmap): Mat {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        // Convert RGBA to BGR (OpenCV format)
        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2BGR)
        return mat
    }

    /**
     * Convert OpenCV Mat to Bitmap (for debugging)
     */
    fun matToBitmap(mat: Mat): Bitmap {
        val bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, bitmap)
        return bitmap
    }

    /**
     * Connect digit gaps - để ghép các số bị tách rời
     */
    fun connectDigitGaps(binary: Mat): Mat {
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 1.0))
        val result = Mat()
        Imgproc.morphologyEx(binary, result, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 1)
        kernel.release()
        return result
    }
}