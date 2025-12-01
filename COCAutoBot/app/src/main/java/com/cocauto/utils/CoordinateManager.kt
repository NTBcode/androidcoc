package com.cocauto.utils

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Point

object CoordinateManager {
    private const val PREF_NAME = "coc_coordinates"

    // Định nghĩa Key cho các nút
    const val KEY_BTN_ATTACK = "btn_attack"
    const val KEY_BTN_FIND_MATCH = "btn_find_match"
    const val KEY_BTN_DEPLOY_ATTACK = "btn_deploy"
    const val KEY_BTN_NEXT = "btn_next"
    const val KEY_BTN_END_BATTLE = "btn_end_battle"
    const val KEY_BTN_OK_RESULT = "btn_ok_result"
    const val KEY_BTN_RETURN_HOME = "btn_return_home"
    const val KEY_BTN_UPGRADE_MENU = "btn_upgrade_menu"

    // Định nghĩa Key lưu độ phân giải chuẩn của Game (Lấy từ ảnh chụp màn hình)
    private const val KEY_GAME_WIDTH = "game_width"
    private const val KEY_GAME_HEIGHT = "game_height"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /**
     * MỚI: Lưu độ phân giải chuẩn của Game
     * (Được gọi từ GameLogic khi chụp ảnh màn hình lần đầu)
     */
    fun saveGameResolution(context: Context, width: Int, height: Int) {
        getPrefs(context).edit()
            .putInt(KEY_GAME_WIDTH, width)
            .putInt(KEY_GAME_HEIGHT, height)
            .apply()
    }

    /**
     * MỚI: Lấy độ phân giải chuẩn của Game đã lưu
     */
    fun getGameResolution(context: Context): Point {
        val prefs = getPrefs(context)
        val w = prefs.getInt(KEY_GAME_WIDTH, 0)
        val h = prefs.getInt(KEY_GAME_HEIGHT, 0)
        return Point(w, h)
    }

    /**
     * Lưu tọa độ nút
     * (Lưu ý: Tọa độ x, y truyền vào đây ĐÃ ĐƯỢC ánh xạ sang hệ quy chiếu Game Resolution ở bước trước rồi)
     */
    fun saveCoordinate(context: Context, key: String, x: Int, y: Int) {
        getPrefs(context).edit()
            .putInt("${key}_x", x)
            .putInt("${key}_y", y)
            .apply()
    }

    /**
     * Lấy tọa độ nút đã lưu
     */
    fun getCoordinate(context: Context, key: String): Point {
        val prefs = getPrefs(context)

        // Mặc định trả về 0,0 nếu chưa lưu
        val x = prefs.getInt("${key}_x", 0)
        val y = prefs.getInt("${key}_y", 0)

        return Point(x, y)
    }
}