package com.cocauto.utils

import android.content.Context
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

object FileUtils {
    fun copyAssets(context: Context) {
        // Copy file eng.traineddata cho Tesseract
        val tessDir = File(context.filesDir, "tessdata")
        if (!tessDir.exists()) {
            tessDir.mkdirs()
        }

        val dataFile = File(tessDir, "eng.traineddata")
        if (!dataFile.exists()) {
            try {
                context.assets.open("tessdata/eng.traineddata").use { inputStream ->
                    FileOutputStream(dataFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                Timber.d("Copied tessdata success")
            } catch (e: Exception) {
                Timber.e(e, "Error copying tessdata")
            }
        }
    }
}