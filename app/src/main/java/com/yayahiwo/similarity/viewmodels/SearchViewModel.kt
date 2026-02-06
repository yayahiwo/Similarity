package com.yayahiwo.similarity.viewmodels

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import com.yayahiwo.similarity.SimilaritySettings
import com.yayahiwo.similarity.dot

class SearchViewModel(application: Application) : AndroidViewModel(application) {
    var searchResults: List<Long>? = null
    var fromImg2ImgFlag: Boolean = false
    var lastSearchEmbedding: FloatArray? = null
    var lastSearchIsImageSearch: Boolean = false
    var showBackToAllImages: Boolean = false
    var lastResultsAreNearDuplicates: Boolean = false
    var showNearDuplicateDimensions: Boolean = true
    var startupRequested: Boolean = false
    var pendingIndexRefresh: Boolean = false
    var indexPaused: Boolean = false
    var showImageSearchDimensions: Boolean = true
    val selectedImageIds: LinkedHashSet<Long> = linkedSetOf()
    val imageDimensionsById: LinkedHashMap<Long, String> = linkedMapOf()
    var similaritySortActive: Boolean = false
    var similaritySortBaseResults: List<Long>? = null
    val textSearchTokens: MutableList<String> = mutableListOf()
    val textSearchEmbeddingByToken: LinkedHashMap<String, FloatArray> = linkedMapOf()

    // Metadata Tags cache (derived from image XMP dc:subject). This is kept in-memory only to avoid
    // re-scanning all images on every open/click.
    var metadataTagsCacheKey: String? = null
    var metadataTagsCacheScopeLabel: String? = null
    var metadataTagsCacheScannedImages: Int = 0
    var metadataTagsCacheTaggedImages: Int = 0
    val metadataTagCountsByNorm: LinkedHashMap<String, Long> = linkedMapOf()
    val metadataTagDisplayByNorm: LinkedHashMap<String, String> = linkedMapOf()
    val metadataTagImageIdsByNorm: LinkedHashMap<String, LongArray> = linkedMapOf()
    val metadataTagImageLocationById: LinkedHashMap<Long, String> = linkedMapOf()

    // Used to invalidate cached thumbnails after in-place edits (rotate/crop/etc.)
    // without disabling caching for the whole grid.
    private val imageEditNonceById: LinkedHashMap<Long, Long> = linkedMapOf()
    private var imageEditNonceVersion: Long = 0L

    private val prefs = SimilaritySettings.prefs(application)
    private var imageSimilarityThreshold: Float =
        prefs.getFloat(
            SimilaritySettings.KEY_IMAGE_SIMILARITY_THRESHOLD,
            SimilaritySettings.DEFAULT_IMAGE_SIMILARITY_THRESHOLD
        )
    private var showTextSimilaritySlider: Boolean =
        prefs.getBoolean(
            SimilaritySettings.KEY_SHOW_TEXT_SIMILARITY_SLIDER,
            SimilaritySettings.DEFAULT_SHOW_TEXT_SIMILARITY_SLIDER
        )
    private var textSimilarityThreshold: Float =
        prefs.getFloat(
            SimilaritySettings.KEY_TEXT_SIMILARITY_THRESHOLD,
            SimilaritySettings.DEFAULT_TEXT_SIMILARITY_THRESHOLD
        )
    private var cpuUseOrtDefaultThreading: Boolean =
        prefs.getBoolean(
            SimilaritySettings.KEY_CPU_USE_ORT_DEFAULT_THREADING,
            SimilaritySettings.DEFAULT_CPU_USE_ORT_DEFAULT_THREADING
        )
    private var cpuMicroBatchMax: Int =
        prefs.getInt(
            SimilaritySettings.KEY_CPU_MICRO_BATCH_MAX,
            SimilaritySettings.DEFAULT_CPU_MICRO_BATCH_MAX
        )
    private var cpuIntraOpThreads: Int =
        prefs.getInt(
            SimilaritySettings.KEY_CPU_INTRA_OP_THREADS,
            SimilaritySettings.DEFAULT_CPU_INTRA_OP_THREADS
        )
    private var cpuPreprocessWorkersMultiplierPercent: Int =
        prefs.getInt(
            SimilaritySettings.KEY_CPU_PREPROCESS_WORKERS_MULTIPLIER_PERCENT,
            SimilaritySettings.DEFAULT_CPU_PREPROCESS_WORKERS_MULTIPLIER_PERCENT
        )
    private var cpuResizeFilterEnabled: Boolean =
        prefs.getBoolean(
            SimilaritySettings.KEY_CPU_RESIZE_FILTER_ENABLED,
            SimilaritySettings.DEFAULT_CPU_RESIZE_FILTER_ENABLED
        )
    private var indexedBucketIds: Set<String> =
        prefs.getStringSet(SimilaritySettings.KEY_INDEXED_BUCKET_IDS, emptySet())?.toSet() ?: emptySet()
    private var gridSpanCount: Int =
        prefs.getInt(SimilaritySettings.KEY_GRID_SPAN_COUNT, SimilaritySettings.DEFAULT_GRID_SPAN_COUNT)

    init {
        // Persist updated defaults on upgrade (SharedPreferences defaults are not stored automatically).
        if (!prefs.contains(SimilaritySettings.KEY_CPU_USE_ORT_DEFAULT_THREADING)) {
            setCpuUseOrtDefaultThreading(SimilaritySettings.DEFAULT_CPU_USE_ORT_DEFAULT_THREADING)
        }
        if (!prefs.contains(SimilaritySettings.KEY_CPU_INTRA_OP_THREADS)) {
            setCpuIntraOpThreads(SimilaritySettings.DEFAULT_CPU_INTRA_OP_THREADS)
        } else if (prefs.getInt(SimilaritySettings.KEY_CPU_INTRA_OP_THREADS, 0) == 5) {
            // Migrate old tuned default (5) to the newer tuned default (8).
            setCpuIntraOpThreads(SimilaritySettings.DEFAULT_CPU_INTRA_OP_THREADS)
        }
        if (!prefs.contains(SimilaritySettings.KEY_CPU_MICRO_BATCH_MAX)) {
            setCpuMicroBatchMax(SimilaritySettings.DEFAULT_CPU_MICRO_BATCH_MAX)
        }
        if (!prefs.contains(SimilaritySettings.KEY_CPU_PREPROCESS_WORKERS_MULTIPLIER_PERCENT)) {
            setCpuPreprocessWorkersMultiplierPercent(SimilaritySettings.DEFAULT_CPU_PREPROCESS_WORKERS_MULTIPLIER_PERCENT)
        }
        if (!prefs.contains(SimilaritySettings.KEY_CPU_RESIZE_FILTER_ENABLED)) {
            setCpuResizeFilterEnabled(SimilaritySettings.DEFAULT_CPU_RESIZE_FILTER_ENABLED)
        }
    }

    fun getImageSimilarityThreshold(): Float = imageSimilarityThreshold

    fun setImageSimilarityThreshold(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        imageSimilarityThreshold = clamped
        prefs.edit().putFloat(SimilaritySettings.KEY_IMAGE_SIMILARITY_THRESHOLD, clamped).apply()
    }

    fun getShowTextSimilaritySlider(): Boolean = showTextSimilaritySlider

    fun setShowTextSimilaritySlider(value: Boolean) {
        showTextSimilaritySlider = value
        prefs.edit().putBoolean(SimilaritySettings.KEY_SHOW_TEXT_SIMILARITY_SLIDER, value).apply()
    }

    fun getTextSimilarityThreshold(): Float = textSimilarityThreshold

    fun setTextSimilarityThreshold(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        textSimilarityThreshold = clamped
        prefs.edit().putFloat(SimilaritySettings.KEY_TEXT_SIMILARITY_THRESHOLD, clamped).apply()
    }

    fun getCpuUseOrtDefaultThreading(): Boolean = cpuUseOrtDefaultThreading

    fun setCpuUseOrtDefaultThreading(value: Boolean) {
        cpuUseOrtDefaultThreading = value
        prefs.edit().putBoolean(SimilaritySettings.KEY_CPU_USE_ORT_DEFAULT_THREADING, value).apply()
    }

    fun getCpuMicroBatchMax(): Int = cpuMicroBatchMax

    fun setCpuMicroBatchMax(value: Int) {
        val clamped = value.coerceIn(1, 128)
        cpuMicroBatchMax = clamped
        prefs.edit().putInt(SimilaritySettings.KEY_CPU_MICRO_BATCH_MAX, clamped).apply()
    }

    fun getCpuIntraOpThreads(): Int = cpuIntraOpThreads

    fun setCpuIntraOpThreads(value: Int) {
        val clamped = value.coerceIn(0, 64)
        cpuIntraOpThreads = clamped
        prefs.edit().putInt(SimilaritySettings.KEY_CPU_INTRA_OP_THREADS, clamped).apply()
    }

    fun getCpuPreprocessWorkersMultiplierPercent(): Int = cpuPreprocessWorkersMultiplierPercent

    fun setCpuPreprocessWorkersMultiplierPercent(value: Int) {
        val clamped = value.coerceIn(50, 300)
        cpuPreprocessWorkersMultiplierPercent = clamped
        prefs.edit()
            .putInt(SimilaritySettings.KEY_CPU_PREPROCESS_WORKERS_MULTIPLIER_PERCENT, clamped)
            .apply()
    }

    fun getCpuResizeFilterEnabled(): Boolean = cpuResizeFilterEnabled

    fun setCpuResizeFilterEnabled(value: Boolean) {
        cpuResizeFilterEnabled = value
        prefs.edit().putBoolean(SimilaritySettings.KEY_CPU_RESIZE_FILTER_ENABLED, value).apply()
    }

    fun getIndexedBucketIds(): Set<String> = indexedBucketIds

    fun setIndexedBucketIds(value: Set<String>) {
        indexedBucketIds = value.toSet()
        prefs.edit()
            .putStringSet(SimilaritySettings.KEY_INDEXED_BUCKET_IDS, indexedBucketIds)
            .putBoolean(SimilaritySettings.KEY_INDEX_FOLDERS_CONFIGURED, true)
            .apply()

        // Folder scope changed => cached tag scan is stale.
        clearMetadataTagsCache()
    }

    fun getGridSpanCount(): Int = gridSpanCount

    fun setGridSpanCount(value: Int) {
        val clamped = value.coerceIn(2, 24)
        gridSpanCount = clamped
        prefs.edit().putInt(SimilaritySettings.KEY_GRID_SPAN_COUNT, clamped).apply()
    }

    fun sortByCosineDistance(searchEmbedding: FloatArray,
                          imageEmbeddingsList: List<FloatArray>,
                          imageIdxList: List<Long>,
                          minSimilarity: Float? = null,
                          isImageSearch: Boolean = false) {
        lastSearchEmbedding = searchEmbedding
        lastSearchIsImageSearch = isImageSearch

        val distances = LinkedHashMap<Long, Float>()
        for (i in imageEmbeddingsList.indices) {
            val dist = searchEmbedding.dot(imageEmbeddingsList[i])
            distances[imageIdxList[i]] = dist
        }
        val filtered = if (minSimilarity == null) {
            distances
        } else {
            distances.filterValues { it >= minSimilarity }
        }
        searchResults = filtered.toList().sortedByDescending { (_, v) -> v }.map { (k, _) -> k }
    }

    fun clearSelection() {
        selectedImageIds.clear()
    }

    fun clearTextSearchTokens() {
        textSearchTokens.clear()
        textSearchEmbeddingByToken.clear()
    }

    fun clearMetadataTagsCache() {
        metadataTagsCacheKey = null
        metadataTagsCacheScopeLabel = null
        metadataTagsCacheScannedImages = 0
        metadataTagsCacheTaggedImages = 0
        metadataTagCountsByNorm.clear()
        metadataTagDisplayByNorm.clear()
        metadataTagImageIdsByNorm.clear()
        metadataTagImageLocationById.clear()
    }

    fun bumpImageEditNonce(id: Long) {
        val next = (imageEditNonceById[id] ?: 0L) + 1L
        imageEditNonceById[id] = next
        imageEditNonceVersion += 1L
    }

    fun getImageEditNonce(id: Long): Long = imageEditNonceById[id] ?: 0L

    fun getImageEditNonceVersion(): Long = imageEditNonceVersion
}
