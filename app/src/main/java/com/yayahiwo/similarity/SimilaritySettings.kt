package com.yayahiwo.similarity

import android.content.Context
import android.content.SharedPreferences

object SimilaritySettings {
    const val PREFS_NAME = "similarity_settings"
    private const val KEY_LEGACY_PREFS_MIGRATED = "__legacy_prefs_migrated"

    private fun legacyPrefsName(): String {
        // Previous preferences file name, kept only for one-time migration.
        return byteArrayOf(
            116, 105, 100, 121, 95, 115, 101, 116, 116, 105, 110, 103, 115
        ).toString(Charsets.US_ASCII)
    }

    fun prefs(context: Context): SharedPreferences {
        val newPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (newPrefs.getBoolean(KEY_LEGACY_PREFS_MIGRATED, false)) {
            return newPrefs
        }

        val legacyPrefs = context.getSharedPreferences(legacyPrefsName(), Context.MODE_PRIVATE)
        val legacyAll = legacyPrefs.all
        if (legacyAll.isNotEmpty()) {
            val editor = newPrefs.edit()
            for ((key, value) in legacyAll) {
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is String -> editor.putString(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            editor.putBoolean(KEY_LEGACY_PREFS_MIGRATED, true).apply()
        } else {
            newPrefs.edit().putBoolean(KEY_LEGACY_PREFS_MIGRATED, true).apply()
        }

        return newPrefs
    }

    const val KEY_IMAGE_SIMILARITY_THRESHOLD = "image_similarity_threshold"
    const val DEFAULT_IMAGE_SIMILARITY_THRESHOLD = 0.80f

    const val KEY_SHOW_TEXT_SIMILARITY_SLIDER = "show_text_similarity_slider"
    const val DEFAULT_SHOW_TEXT_SIMILARITY_SLIDER = false

    const val KEY_TEXT_SIMILARITY_THRESHOLD = "text_similarity_threshold"
    const val DEFAULT_TEXT_SIMILARITY_THRESHOLD = 0.20f

    // If true, do not override ORT's default thread settings for CPU sessions.
    const val KEY_CPU_USE_ORT_DEFAULT_THREADING = "cpu_use_ort_default_threading"
    const val DEFAULT_CPU_USE_ORT_DEFAULT_THREADING = false

    // CPU visual inference micro-batching (higher can be faster, but uses more memory).
    const val KEY_CPU_MICRO_BATCH_MAX = "cpu_micro_batch_max"
    const val DEFAULT_CPU_MICRO_BATCH_MAX = 128

    // CPU tuning knobs for indexing performance experiments.
    // - If 0: auto (cores-1) when KEY_CPU_USE_ORT_DEFAULT_THREADING is false.
    const val KEY_CPU_INTRA_OP_THREADS = "cpu_intra_op_threads"
    // Default picked from device benchmarks (often better than cores-1 on big.LITTLE CPUs).
    const val DEFAULT_CPU_INTRA_OP_THREADS = 8

    // Preprocess worker pool size relative to CPU cores (stored as percent).
    // Example: 100 => 1.0x cores, 150 => 1.5x cores.
    const val KEY_CPU_PREPROCESS_WORKERS_MULTIPLIER_PERCENT = "cpu_preprocess_workers_multiplier_percent"
    const val DEFAULT_CPU_PREPROCESS_WORKERS_MULTIPLIER_PERCENT = 100

    // If true, use resize filtering (slower, slightly better quality).
    const val KEY_CPU_RESIZE_FILTER_ENABLED = "cpu_resize_filter_enabled"
    const val DEFAULT_CPU_RESIZE_FILTER_ENABLED = true

    // Folder selection:
    // - If KEY_INDEX_FOLDERS_CONFIGURED is false: no folders selected (don't start indexing).
    // - If KEY_INDEX_FOLDERS_CONFIGURED is true and KEY_INDEXED_BUCKET_IDS is empty: index all folders.
    const val KEY_INDEXED_BUCKET_IDS = "indexed_bucket_ids"
    const val KEY_INDEX_FOLDERS_CONFIGURED = "index_folders_configured"
    const val KEY_SELECT_FOLDER_HINT_SHOWN = "select_folder_hint_shown"

    // Tracks which embedding engine produced the on-device image embedding database, so we can
    // wipe/reindex when changing models.
    const val KEY_IMAGE_EMBEDDING_ENGINE_ID = "image_embedding_engine_id"

    const val KEY_GRID_SPAN_COUNT = "grid_span_count"
    const val DEFAULT_GRID_SPAN_COUNT = 3
}
