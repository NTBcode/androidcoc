package com.cocauto.utils

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowInsets
import android.view.WindowManager
import timber.log.Timber

/**
 * CoordinateManager - Fixed Version
 * Xử lý chính xác tọa độ trên mọi loại màn hình
 */
object CoordinateManager {
    private const val PREF_NAME = "coc_coordinates"

    // Key cho các nút
    const val KEY_BTN_ATTACK = "btn_attack"
    const val KEY_BTN_FIND_MATCH = "btn_find_match"
    const val KEY_BTN_DEPLOY_ATTACK = "btn_deploy"
    const val KEY_BTN_NEXT = "btn_next"
    const val KEY_BTN_END_BATTLE = "btn_end_battle"
    const val KEY_BTN_OK_RESULT = "btn_ok_result"
    const val KEY_BTN_RETURN_HOME = "btn_return_home"
    const val KEY_BTN_UPGRADE_MENU = "btn_upgrade_menu"

    // Key lưu độ phân giải
    private const val KEY_GAME_WIDTH = "game_width"
    private const val KEY_GAME_HEIGHT = "game_height"

    // Key lưu offset của system bars (QUAN TRỌNG)
    private const val KEY_STATUS_BAR_HEIGHT = "status_bar_height"
    private const val KEY_NAV_BAR_HEIGHT = "nav_bar_height"
    private const val KEY_CUTOUT_TOP = "cutout_top"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Lấy kích thước màn hình thực tế (bao gồm system bars)
     */
    fun getRealScreenSize(context: Context): Point {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            return Point(bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            return Point(metrics.widthPixels, metrics.heightPixels)
        }
    }

    /**
     * Lấy kích thước vùng hiển thị (trừ system bars)
     */
    fun getUsableScreenSize(context: Context): Rect {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )

            return Rect(
                insets.left,
                insets.top,
                metrics.bounds.width() - insets.right,
                metrics.bounds.height() - insets.bottom
            )
        } else {
            val size = getRealScreenSize(context)
            val statusBarHeight = getStatusBarHeight(context)
            val navBarHeight = getNavigationBarHeight(context)

            return Rect(
                0,
                statusBarHeight,
                size.x,
                size.y - navBarHeight
            )
        }
    }

    /**
     * Lấy chiều cao thanh trạng thái
     */
    fun getStatusBarHeight(context: Context): Int {
        var result = 0
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = context.resources.getDimensionPixelSize(resourceId)
        }
        return result
    }

    /**
     * Lấy chiều cao thanh điều hướng
     */
    fun getNavigationBarHeight(context: Context): Int {
        var result = 0
        val resourceId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = context.resources.getDimensionPixelSize(resourceId)
        }
        return result
    }

    /**
     * Lấy offset của display cutout (tai thỏ)
     */
    fun getCutoutOffset(context: Context): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val cutout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                wm.currentWindowMetrics.windowInsets.displayCutout
            } else {
                @Suppress("DEPRECATION")
                wm.defaultDisplay.cutout
            }
            return cutout?.safeInsetTop ?: 0
        }
        return 0
    }

    /**
     * Lưu độ phân giải Game (từ ảnh chụp màn hình)
     * Ảnh từ MediaProjection = Màn hình thật (bao gồm system bars)
     */
    fun saveGameResolution(context: Context, width: Int, height: Int) {
        // Lưu độ phân giải
        val prefs = getPrefs(context)
        prefs.edit()
            .putInt(KEY_GAME_WIDTH, width)
            .putInt(KEY_GAME_HEIGHT, height)
            .apply()

        // Lưu system bars offset để mapping chính xác
        val statusBarHeight = getStatusBarHeight(context)
        val navBarHeight = getNavigationBarHeight(context)
        val cutoutTop = getCutoutOffset(context)

        prefs.edit()
            .putInt(KEY_STATUS_BAR_HEIGHT, statusBarHeight)
            .putInt(KEY_NAV_BAR_HEIGHT, navBarHeight)
            .putInt(KEY_CUTOUT_TOP, cutoutTop)
            .apply()

        Timber.d("Saved game resolution: ${width}x${height}, StatusBar: $statusBarHeight, NavBar: $navBarHeight, Cutout: $cutoutTop")
    }

    /**
     * Lấy độ phân giải Game đã lưu
     */
    fun getGameResolution(context: Context): Point {
        val prefs = getPrefs(context)
        val w = prefs.getInt(KEY_GAME_WIDTH, 0)
        val h = prefs.getInt(KEY_GAME_HEIGHT, 0)
        return Point(w, h)
    }

    /**
     * Lấy system bars offset đã lưu
     */
    private fun getSavedSystemBarsOffset(context: Context): Triple<Int, Int, Int> {
        val prefs = getPrefs(context)
        val statusBar = prefs.getInt(KEY_STATUS_BAR_HEIGHT, 0)
        val navBar = prefs.getInt(KEY_NAV_BAR_HEIGHT, 0)
        val cutout = prefs.getInt(KEY_CUTOUT_TOP, 0)
        return Triple(statusBar, navBar, cutout)
    }

    /**
     * LƯU TỌA ĐỘ (Từ Overlay -> Game Space)
     * Input: Tọa độ ngón tay trên màn hình thật (Raw X/Y)
     * Output: Tọa độ chuẩn hóa trong Game Space (ảnh chụp)
     */
    fun saveCoordinate(context: Context, key: String, rawX: Float, rawY: Float) {
        val realScreen = getRealScreenSize(context)
        val gameRes = getGameResolution(context)

        // Nếu chưa có game resolution, dùng tạm màn hình thật
        val gameW = if (gameRes.x > 0) gameRes.x else realScreen.x
        val gameH = if (gameRes.y > 0) gameRes.y else realScreen.y

        // Map tọa độ: Màn hình thật -> Game Space
        // Game Space = Ảnh chụp màn hình (bao gồm system bars)
        val gameX = (rawX / realScreen.x * gameW).toInt()
        val gameY = (rawY / realScreen.y * gameH).toInt()

        getPrefs(context).edit()
            .putInt("${key}_x", gameX)
            .putInt("${key}_y", gameY)
            .apply()

        Timber.d("Saved coordinate '$key': Raw($rawX, $rawY) -> Game($gameX, $gameY) @ ${gameW}x${gameH}")
    }

    /**
     * LẤY TỌA ĐỘ (Từ Game Space -> Gesture Space)
     * Input: Key đã lưu
     * Output: Tọa độ để dispatch gesture (tọa độ màn hình thật)
     */
    fun getCoordinateForGesture(context: Context, key: String): Point {
        val prefs = getPrefs(context)
        val gameX = prefs.getInt("${key}_x", 0)
        val gameY = prefs.getInt("${key}_y", 0)

        if (gameX == 0 && gameY == 0) {
            return Point(0, 0)
        }

        val realScreen = getRealScreenSize(context)
        val gameRes = getGameResolution(context)

        // Nếu chưa có game resolution, trả về thẳng
        if (gameRes.x == 0 || gameRes.y == 0) {
            return Point(gameX, gameY)
        }

        // Map ngược: Game Space -> Màn hình thật
        val gestureX = (gameX.toFloat() / gameRes.x * realScreen.x).toInt()
        val gestureY = (gameY.toFloat() / gameRes.y * realScreen.y).toInt()

        return Point(gestureX, gestureY)
    }

    /**
     * LẤY TỌA ĐỘ (Từ Game Space -> Overlay Display)
     * Dùng để hiển thị lại vị trí đã lưu trong TargetOverlay
     */
    fun getCoordinateForOverlay(context: Context, key: String): Point {
        // Overlay sử dụng cùng hệ tọa độ với Gesture
        return getCoordinateForGesture(context, key)
    }

    /**
     * LẤY TỌA ĐỘ THÔI (Raw - trong Game Space)
     * Dùng cho OCR và xử lý ảnh
     */
    fun getCoordinate(context: Context, key: String): Point {
        val prefs = getPrefs(context)
        val x = prefs.getInt("${key}_x", 0)
        val y = prefs.getInt("${key}_y", 0)
        return Point(x, y)
    }

    /**
     * Scale tọa độ từ script (960x540) -> Game Space
     */
    fun scaleScriptCoordinate(context: Context, scriptX: Int, scriptY: Int): Point {
        val gameRes = getGameResolution(context)

        if (gameRes.x == 0 || gameRes.y == 0) {
            // Fallback
            val realScreen = getRealScreenSize(context)
            val scaleX = realScreen.x / 960f
            val scaleY = realScreen.y / 540f
            return Point((scriptX * scaleX).toInt(), (scriptY * scaleY).toInt())
        }

        val scaleX = gameRes.x / 960f
        val scaleY = gameRes.y / 540f
        return Point((scriptX * scaleX).toInt(), (scriptY * scaleY).toInt())
    }

    /**
     * Kiểm tra xem tọa độ đã được cấu hình chưa
     */
    fun isCoordinateConfigured(context: Context, key: String): Boolean {
        val point = getCoordinate(context, key)
        return point.x > 0 && point.y > 0
    }

    /**
     * Xóa tất cả tọa độ đã lưu
     */
    fun clearAllCoordinates(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
}