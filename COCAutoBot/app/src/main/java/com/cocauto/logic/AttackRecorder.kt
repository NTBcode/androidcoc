package com.cocauto.logic

import android.content.Context
import com.cocauto.utils.TouchAction
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import timber.log.Timber
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import kotlin.math.abs
import kotlin.math.max

/**
 * Ghi và quản lý attack scripts
 */
class AttackRecorder(private val context: Context) {

    private val gson = Gson()
    private val recordingsDir: File = File(context.filesDir, "attack_recordings")

    init {
        if (!recordingsDir.exists()) {
            recordingsDir.mkdirs()
        }
    }

    /**
     * Lưu recording vào file JSON
     */
    fun saveRecording(sessionName: String, actions: List<TouchAction>): String? {
        try {
            val timestamp = System.currentTimeMillis()
            val filename = "${sessionName}_$timestamp.json"
            val file = File(recordingsDir, filename)

            val durationMs = if (actions.isNotEmpty()) {
                actions.maxOf { it.timestampMs }
            } else {
                0L
            }

            val recordingData = RecordingData(
                metadata = RecordingMetadata(
                    name = "${sessionName}_$timestamp",
                    created = timestamp,
                    durationMs = durationMs,
                    durationSeconds = durationMs / 1000.0,
                    totalActions = actions.size,
                    schemaVersion = 2
                ),
                actions = actions
            )

            FileWriter(file).use { writer ->
                gson.toJson(recordingData, writer)
            }

            Timber.d("Saved recording: ${file.absolutePath}")
            return file.absolutePath
        } catch (e: Exception) {
            Timber.e(e, "Failed to save recording")
            return null
        }
    }

    /**
     * Load recording từ file
     */
    fun loadRecording(filepath: String): RecordingData? {
        try {
            val file = File(filepath)
            if (!file.exists()) {
                Timber.w("Recording file not found: $filepath")
                return null
            }

            FileReader(file).use { reader ->
                val type = object : TypeToken<RecordingData>() {}.type
                return gson.fromJson(reader, type)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load recording")
            return null
        }
    }

    /**
     * Load recording từ assets (built-in scripts)
     */
    fun loadRecordingFromAssets(filename: String): RecordingData? {
        try {
            val path = if (filename.contains("/")) filename else "attack_scripts/$filename"

            context.assets.open(path).use { inputStream ->
                val json = inputStream.bufferedReader().use { it.readText() }
                val type = object : TypeToken<RecordingData>() {}.type
                return gson.fromJson(json, type)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load recording from assets: $filename")
            return null
        }
    }

    /**
     * Liệt kê tất cả recordings
     */
    fun listRecordings(): List<RecordingInfo> {
        val recordings = mutableListOf<RecordingInfo>()

        // 1. Load từ internal storage
        recordingsDir.listFiles()?.forEach { file ->
            if (file.extension == "json") {
                try {
                    val data = loadRecording(file.absolutePath)
                    if (data != null) {
                        recordings.add(
                            RecordingInfo(
                                name = data.metadata.name,
                                filepath = file.absolutePath,
                                duration = data.metadata.durationSeconds,
                                actions = data.metadata.totalActions,
                                source = "storage"
                            )
                        )
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error loading recording: ${file.name}")
                }
            }
        }

        // 2. Load từ assets
        try {
            val assetFiles = context.assets.list("attack_scripts") ?: emptyArray()
            assetFiles.forEach { filename ->
                if (filename.endsWith(".json")) {
                    try {
                        val data = loadRecordingFromAssets(filename)
                        if (data != null) {
                            recordings.add(
                                RecordingInfo(
                                    name = data.metadata.name,
                                    filepath = "assets/$filename",
                                    duration = data.metadata.durationSeconds,
                                    actions = data.metadata.totalActions,
                                    source = "assets"
                                )
                            )
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Error loading asset: $filename")
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error listing assets")
        }

        return recordings.sortedByDescending { it.name }
    }

    /**
     * Simplified Gestures
     */
    fun buildGestureSummary(actions: List<TouchAction>): List<SimplifiedGesture> {
        val gestures = mutableListOf<GestureGroup>()
        var currentGesture: GestureGroup? = null

        for (action in actions) {
            when (action.type) {
                "down" -> {
                    currentGesture = GestureGroup(
                        down = action,
                        moves = mutableListOf(),
                        up = null
                    )
                }
                "move" -> {
                    currentGesture?.moves?.add(action)
                }
                "up" -> {
                    currentGesture?.up = action
                    currentGesture?.let { gestures.add(it) }
                    currentGesture = null
                }
                "tap", "hold" -> {
                    gestures.add(
                        GestureGroup(
                            legacy = action,
                            down = null,
                            moves = mutableListOf(),
                            up = null
                        )
                    )
                }
            }
        }

        currentGesture?.let { gestures.add(it) }

        return gestures.mapNotNull { group ->
            if (group.legacy != null) {
                val action = group.legacy
                val duration = if (action.holdMs > 0) action.holdMs else 0L
                val type = if (action.type == "hold" || duration >= 250) "hold" else "tap"

                SimplifiedGesture(
                    type = type,
                    startTimeMs = action.timestampMs,
                    endTimeMs = action.timestampMs + duration,
                    durationMs = duration,
                    startPoint = Pair(action.x, action.y),
                    endPoint = Pair(action.x, action.y)
                )
            } else {
                val down = group.down ?: return@mapNotNull null
                val moves = group.moves
                val up = group.up

                val endEvent = up ?: moves.lastOrNull() ?: down
                val startTime = down.timestampMs
                val endTime = max(endEvent.timestampMs, startTime)
                val duration = endTime - startTime

                var maxDelta = 0
                for (point in moves) {
                    val dx = abs(point.x - down.x)
                    val dy = abs(point.y - down.y)
                    maxDelta = max(maxDelta, max(dx, dy))
                }
                up?.let {
                    val dx = abs(it.x - down.x)
                    val dy = abs(it.y - down.y)
                    maxDelta = max(maxDelta, max(dx, dy))
                }

                if (maxDelta <= 10) {
                    val type = if (duration >= 250) "hold" else "tap"
                    SimplifiedGesture(
                        type = type,
                        startTimeMs = startTime,
                        endTimeMs = startTime + duration,
                        durationMs = duration,
                        startPoint = Pair(down.x, down.y),
                        endPoint = Pair(down.x, down.y)
                    )
                } else {
                    SimplifiedGesture(
                        type = "swipe",
                        startTimeMs = startTime,
                        endTimeMs = endTime,
                        durationMs = max(duration, 250),
                        startPoint = Pair(down.x, down.y),
                        endPoint = Pair(endEvent.x, endEvent.y)
                    )
                }
            }
        }
    }
}

/**
 * Data classes
 */
data class RecordingData(
    val metadata: RecordingMetadata,
    val actions: List<TouchAction>
)

data class RecordingMetadata(
    val name: String,
    val created: Long,
    @SerializedName("duration_ms")
    val durationMs: Long,
    @SerializedName("duration_seconds")
    val durationSeconds: Double,
    @SerializedName("total_actions")
    val totalActions: Int,
    @SerializedName("schema_version")
    val schemaVersion: Int
)

data class RecordingInfo(
    val name: String,
    val filepath: String,
    val duration: Double,
    val actions: Int,
    val source: String
)

data class SimplifiedGesture(
    val type: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val durationMs: Long,
    val startPoint: Pair<Int, Int>,
    val endPoint: Pair<Int, Int>
)

private data class GestureGroup(
    val legacy: TouchAction? = null,
    val down: TouchAction?,
    val moves: MutableList<TouchAction>,
    var up: TouchAction?
)