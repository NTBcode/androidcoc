package com.cocauto.utils

/**
 * Class đại diện cho một hành động chạm/vuốt
 * Được dùng chung cho toàn bộ App
 */
data class TouchAction(
    val type: String,      // "down", "move", "up", "tap", "hold"
    val x: Int,
    val y: Int,
    val timestampMs: Long, // Thời điểm xảy ra (dùng Long để tránh lỗi)
    val holdMs: Long = 0   // Thời gian giữ (cho thao tác hold)
)