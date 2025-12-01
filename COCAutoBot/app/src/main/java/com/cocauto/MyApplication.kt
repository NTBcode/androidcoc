package com.cocauto

import android.app.Application
import com.cocauto.utils.FileUtils
import org.opencv.android.OpenCVLoader
import timber.log.Timber

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // 1. Khởi tạo Timber để log lỗi
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // 2. Nạp thư viện OpenCV
        if (OpenCVLoader.initDebug()) {
            Timber.d("OpenCV loaded successfully")
        } else {
            Timber.e("Could not load OpenCV!")
        }

        // 3. Copy file dữ liệu OCR (tessdata) từ assets ra bộ nhớ máy
        // (Chúng ta sẽ tạo FileUtils ở bước sau)
        FileUtils.copyAssets(this)
    }
}