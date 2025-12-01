package com.cocauto.utils

import android.content.Context
import android.content.SharedPreferences

object SettingsManager {
    private const val PREF_NAME = "coc_bot_settings"

    // Các key lưu trữ
    const val KEY_GOLD_THRESHOLD = "gold_threshold"
    const val KEY_ELIXIR_THRESHOLD = "elixir_threshold"
    const val KEY_UPGRADE_GOLD = "upgrade_gold"
    const val KEY_UPGRADE_ELIXIR = "upgrade_elixir"
    const val KEY_ATTACK_DURATION = "attack_duration"
    const val KEY_MATCHES_BEFORE_UPGRADE = "matches_before_upgrade"
    const val KEY_ENABLE_WALL = "enable_wall"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun saveSettings(
        context: Context,
        goldThreshold: Int, elixirThreshold: Int,
        upgradeGold: Int, upgradeElixir: Int,
        attackDuration: Int, matches: Int,
        enableWall: Boolean
    ) {
        getPrefs(context).edit().apply {
            putInt(KEY_GOLD_THRESHOLD, goldThreshold)
            putInt(KEY_ELIXIR_THRESHOLD, elixirThreshold)
            putInt(KEY_UPGRADE_GOLD, upgradeGold)
            putInt(KEY_UPGRADE_ELIXIR, upgradeElixir)
            putInt(KEY_ATTACK_DURATION, attackDuration)
            putInt(KEY_MATCHES_BEFORE_UPGRADE, matches)
            putBoolean(KEY_ENABLE_WALL, enableWall)
            apply()
        }
    }

    // Trả về object chứa toàn bộ settings
    fun loadSettings(context: Context): BotSettings {
        val p = getPrefs(context)
        return BotSettings(
            goldThreshold = p.getInt(KEY_GOLD_THRESHOLD, 100000),
            elixirThreshold = p.getInt(KEY_ELIXIR_THRESHOLD, 100000),
            upgradeGold = p.getInt(KEY_UPGRADE_GOLD, 5000000),
            upgradeElixir = p.getInt(KEY_UPGRADE_ELIXIR, 5000000),
            attackDuration = p.getInt(KEY_ATTACK_DURATION, 60),
            matchesBeforeUpgrade = p.getInt(KEY_MATCHES_BEFORE_UPGRADE, 3),
            enableWall = p.getBoolean(KEY_ENABLE_WALL, true)
        )
    }
}

data class BotSettings(
    val goldThreshold: Int,
    val elixirThreshold: Int,
    val upgradeGold: Int,
    val upgradeElixir: Int,
    val attackDuration: Int,
    val matchesBeforeUpgrade: Int,
    val enableWall: Boolean
)