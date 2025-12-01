package com.cocauto.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Environment
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Utility class cho screen capture operations
 */
object ScreenCapture {

    /**
     * Crop bitmap theo region
     */
    fun cropBitmap(source: Bitmap, rect: Rect): Bitmap? {
        try {
            // Validate bounds
            if (rect.left < 0 || rect.top < 0 ||
                rect.right > source.width || rect.bottom > source.height) {
                Timber.w("Invalid crop bounds")
                return null
            }

            return Bitmap.createBitmap(
                source,
                rect.left,
                rect.top,
                rect.width(),
                rect.height()
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to crop bitmap")
            return null
        }
    }

    /**
     * Scale bitmap
     */
    fun scaleBitmap(source: Bitmap, scaleFactor: Float): Bitmap {
        val newWidth = (source.width * scaleFactor).toInt()
        val newHeight = (source.height * scaleFactor).toInt()
        return Bitmap.createScaledBitmap(source, newWidth, newHeight, true)
    }

    /**
     * Resize bitmap về 960x540 (COC standard resolution)
     */
    fun resizeTo960x540(source: Bitmap): Bitmap {
        return Bitmap.createScaledBitmap(source, 960, 540, true)
    }

    /**
     * Save bitmap to file
     */
    fun saveBitmap(
        context: Context,
        bitmap: Bitmap,
        filename: String? = null,
        quality: Int = 90
    ): String? {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(Date())
            val name = filename ?: "screenshot_$timestamp.png"

            // Save to app's internal storage (no permission needed)
            val dir = File(context.filesDir, "screenshots")
            if (!dir.exists()) {
                dir.mkdirs()
            }

            val file = File(dir, name)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, quality, out)
            }

            Timber.d("Saved screenshot: ${file.absolutePath}")
            return file.absolutePath
        } catch (e: Exception) {
            Timber.e(e, "Failed to save bitmap")
            return null
        }
    }

    /**
     * Save bitmap to external storage (public Pictures folder)
     * Requires WRITE_EXTERNAL_STORAGE permission on API < 29
     */
    fun saveBitmapToPublicStorage(
        context: Context,
        bitmap: Bitmap,
        filename: String? = null
    ): String? {
        try {
            if (!isExternalStorageWritable()) {
                Timber.w("External storage not writable")
                return null
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(Date())
            val name = filename ?: "COC_$timestamp.png"

            val picturesDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES
            )
            val appDir = File(picturesDir, "COCAutoBot")
            if (!appDir.exists()) {
                appDir.mkdirs()
            }

            val file = File(appDir, name)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            Timber.d("Saved to public storage: ${file.absolutePath}")
            return file.absolutePath
        } catch (e: Exception) {
            Timber.e(e, "Failed to save to public storage")
            return null
        }
    }

    /**
     * Load bitmap from file
     */
    fun loadBitmap(filepath: String): Bitmap? {
        try {
            val file = File(filepath)
            if (!file.exists()) {
                Timber.w("File not found: $filepath")
                return null
            }

            return android.graphics.BitmapFactory.decodeFile(filepath)
        } catch (e: Exception) {
            Timber.e(e, "Failed to load bitmap")
            return null
        }
    }

    /**
     * Check if external storage is writable
     */
    private fun isExternalStorageWritable(): Boolean {
        val state = Environment.getExternalStorageState()
        return Environment.MEDIA_MOUNTED == state
    }

    /**
     * Annotate bitmap with rectangles and text (for debugging)
     */
    fun annotateBitmap(
        source: Bitmap,
        annotations: List<Annotation>
    ): Bitmap {
        val mutableBitmap = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)
        val paint = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 3f
        }

        for (annotation in annotations) {
            paint.color = annotation.color

            // Draw rectangle
            canvas.drawRect(annotation.rect, paint)

            // Draw text
            if (annotation.text.isNotEmpty()) {
                paint.style = android.graphics.Paint.Style.FILL
                paint.textSize = 32f
                canvas.drawText(
                    annotation.text,
                    annotation.rect.left.toFloat(),
                    annotation.rect.top - 10f,
                    paint
                )
                paint.style = android.graphics.Paint.Style.STROKE
            }
        }

        return mutableBitmap
    }

    /**
     * Create debug image with ROI highlighted
     */
    fun createDebugImage(
        context: Context,
        source: Bitmap,
        regions: Map<String, Rect>,
        values: Map<String, String>
    ): String? {
        val annotations = regions.map { (label, rect) ->
            val value = values[label] ?: ""
            val text = if (value.isNotEmpty()) "$label: $value" else label

            Annotation(
                rect = rect,
                text = text,
                color = when {
                    label.contains("gold", ignoreCase = true) -> android.graphics.Color.YELLOW
                    label.contains("elixir", ignoreCase = true) -> android.graphics.Color.MAGENTA
                    label.contains("wall", ignoreCase = true) -> android.graphics.Color.GREEN
                    else -> android.graphics.Color.RED
                }
            )
        }

        val annotatedBitmap = annotateBitmap(source, annotations)
        return saveBitmap(context, annotatedBitmap, "debug_${System.currentTimeMillis()}.png")
    }

    /**
     * Calculate scaled rectangle from base 960x540
     */
    fun scaleRect(baseRect: Rect, screenWidth: Int, screenHeight: Int): Rect {
        val sx = screenWidth / 960f
        val sy = screenHeight / 540f

        return Rect(
            (baseRect.left * sx).toInt(),
            (baseRect.top * sy).toInt(),
            (baseRect.right * sx).toInt(),
            (baseRect.bottom * sy).toInt()
        )
    }

    /**
     * Convert region coordinates (x, y, width, height) to Rect
     */
    fun regionToRect(x: Int, y: Int, width: Int, height: Int): Rect {
        return Rect(x, y, x + width, y + height)
    }

    /**
     * Merge multiple bitmaps vertically (for comparing screenshots)
     */
    fun mergeVertical(bitmaps: List<Bitmap>): Bitmap? {
        if (bitmaps.isEmpty()) return null
        if (bitmaps.size == 1) return bitmaps[0]

        try {
            val width = bitmaps.maxOf { it.width }
            val height = bitmaps.sumOf { it.height }

            val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)

            var currentY = 0f
            for (bitmap in bitmaps) {
                canvas.drawBitmap(bitmap, 0f, currentY, null)
                currentY += bitmap.height
            }

            return result
        } catch (e: Exception) {
            Timber.e(e, "Failed to merge bitmaps")
            return null
        }
    }

    /**
     * Compare two bitmaps (calculate similarity percentage)
     */
    fun compareBitmaps(bitmap1: Bitmap, bitmap2: Bitmap): Float {
        if (bitmap1.width != bitmap2.width || bitmap1.height != bitmap2.height) {
            return 0f
        }

        var matchingPixels = 0
        val totalPixels = bitmap1.width * bitmap1.height

        for (y in 0 until bitmap1.height) {
            for (x in 0 until bitmap1.width) {
                if (bitmap1.getPixel(x, y) == bitmap2.getPixel(x, y)) {
                    matchingPixels++
                }
            }
        }

        return (matchingPixels.toFloat() / totalPixels) * 100f
    }

    /**
     * Clear old screenshots (keep only last N files)
     */
    fun cleanupOldScreenshots(context: Context, keepCount: Int = 10) {
        try {
            val dir = File(context.filesDir, "screenshots")
            if (!dir.exists()) return

            val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return

            if (files.size > keepCount) {
                files.drop(keepCount).forEach { file ->
                    file.delete()
                    Timber.d("Deleted old screenshot: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to cleanup screenshots")
        }
    }

    /**
     * Get file size in MB
     */
    fun getFileSizeMB(filepath: String): Float {
        val file = File(filepath)
        if (!file.exists()) return 0f
        return file.length() / (1024f * 1024f)
    }

    /**
     * Convert bitmap to grayscale
     */
    fun toGrayscale(source: Bitmap): Bitmap {
        val grayscale = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(grayscale)
        val paint = android.graphics.Paint().apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(
                android.graphics.ColorMatrix().apply {
                    setSaturation(0f)
                }
            )
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return grayscale
    }
}

/**
 * Annotation data class for debug images
 */
data class Annotation(
    val rect: Rect,
    val text: String = "",
    val color: Int = android.graphics.Color.RED
)

/**
 * Screenshot metadata
 */
data class ScreenshotMetadata(
    val filepath: String,
    val timestamp: Long,
    val width: Int,
    val height: Int,
    val sizeMB: Float,
    val type: String // "debug", "normal", "error"
)