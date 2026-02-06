package com.yayahiwo.similarity.fragments

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.github.chrisbanes.photoview.PhotoView
import com.yayahiwo.similarity.R
import com.yayahiwo.similarity.dot
import com.yayahiwo.similarity.editor.ImageEditOperation
import com.yayahiwo.similarity.editor.LosslessJpegTurboRotate90CounterClockwiseOperation
import com.yayahiwo.similarity.editor.LosslessJpegTurboRotate90ClockwiseOperation
import com.yayahiwo.similarity.editor.JpegTurboNative
import com.yayahiwo.similarity.editor.MediaStoreImageEditor
import com.yayahiwo.similarity.editor.MediaStoreEditOperation
import com.yayahiwo.similarity.tokenizer.VocabAutocomplete
import com.yayahiwo.similarity.viewmodels.ORTImageViewModel
import com.yayahiwo.similarity.viewmodels.ORTTextViewModel
import com.yayahiwo.similarity.viewmodels.SearchViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.PriorityQueue
import java.text.DateFormat


class ImageFragment : Fragment() {
    private var imageUri: Uri? = null
    private var imageId: Long? = null
    private val mORTImageViewModel: ORTImageViewModel by activityViewModels()
    private val mORTTextViewModel: ORTTextViewModel by activityViewModels()
    private val mSearchViewModel: SearchViewModel by activityViewModels()

    private var infoTextView: TextView? = null
    private var singleImageView: PhotoView? = null
    private var xmpTextView: TextView? = null
    private var aiTagsTextView: TextView? = null
    private var buttonExif: ImageButton? = null
    private var buttonAiTags: ImageButton? = null
    private var buttonEdit: ImageButton? = null
    private var editActionsContainer: View? = null
    private var buttonRotate: ImageButton? = null
    private var buttonRotateCcw: ImageButton? = null
    private var exifGrid: GridLayout? = null
    private var showExif: Boolean = false
    private var basicInfoText: String = ""
    private var exifHeaderText: String? = null
    private var exifGridItems: List<Pair<String, String>>? = null
    private var aiTagsForImageId: Long? = null
    private var aiTagsTextCached: String? = null
    private var aiTagsJob: Job? = null
    private var editExpanded: Boolean = false
    private var editInProgress: Boolean = false
    private var imageReloadNonce: Long = 0L

    private enum class PendingAction {
        Delete,
        EditWrite,
    }

    private var pendingAction: PendingAction? = null
    private var pendingDeleteIds: List<Long> = emptyList()
    private var pendingBitmapEditOperation: ImageEditOperation? = null
    private var pendingMediaStoreEditOperation: MediaStoreEditOperation? = null

    private val intentSenderLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val action = pendingAction
            pendingAction = null

            if (action == null) return@registerForActivityResult
            if (result.resultCode != Activity.RESULT_OK) {
                if (action == PendingAction.EditWrite) {
                    pendingBitmapEditOperation = null
                    pendingMediaStoreEditOperation = null
                    if (isAdded) {
                        Toast.makeText(requireContext(), getString(R.string.edit_cancel), Toast.LENGTH_SHORT)
                            .show()
                    }
                }
                return@registerForActivityResult
            }

            when (action) {
                PendingAction.Delete -> {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        onMediaItemsDeleted(pendingDeleteIds)
                        parentFragmentManager.popBackStack()
                    } else {
                        startDelete(pendingDeleteIds)
                    }
                }
                PendingAction.EditWrite -> {
                    val bitmapOp = pendingBitmapEditOperation
                    val mediaStoreOp = pendingMediaStoreEditOperation
                    pendingBitmapEditOperation = null
                    pendingMediaStoreEditOperation = null
                    val uri = imageUri
                    if (uri != null) {
                        when {
                            mediaStoreOp != null -> applyEditOperation(uri, mediaStoreOp)
                            bitmapOp != null -> applyEditOperation(uri, bitmapOp)
                            else -> Unit
                        }
                    }
                }
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        val view = inflater.inflate(R.layout.fragment_image, container, false)
        val bundle = this.arguments
        bundle?.let {
            imageId = it.getLong("image_id")
            imageUri = it.getString("image_uri")?.toUri()
        }

        showExif = savedInstanceState?.getBoolean("show_exif") ?: false

        infoTextView = view.findViewById(R.id.imageInfoTextView)
        singleImageView = view.findViewById(R.id.singeImageView)
        xmpTextView = view.findViewById(R.id.xmpTextView)
        aiTagsTextView = view.findViewById(R.id.aiTagsTextView)
        buttonExif = view.findViewById(R.id.buttonExif)
        buttonAiTags = view.findViewById(R.id.buttonAiTags)
        buttonEdit = view.findViewById(R.id.buttonEdit)
        editActionsContainer = view.findViewById(R.id.editActionsContainer)
        buttonRotate = view.findViewById(R.id.buttonRotate)
        buttonRotateCcw = view.findViewById(R.id.buttonRotateCcw)
        exifGrid = view.findViewById(R.id.imageExifGrid)
        singleImageView?.apply {
            // Explicitly enable and configure pinch-to-zoom.
            setZoomable(true)
            minimumScale = 1.0f
            mediumScale = 2.0f
            maximumScale = 5.0f
        }

        // Don't override PhotoView's touch listener (it breaks pinch-to-zoom on some devices).
        // Instead, hook into PhotoView's built-in fling callback for left/right navigation.
        singleImageView?.setOnSingleFlingListener { e1, e2, velocityX, _ ->
            val photoView = singleImageView ?: return@setOnSingleFlingListener false
            // Only navigate when not zoomed in (otherwise swipes should pan the image).
            if (photoView.scale > 1.05f) return@setOnSingleFlingListener false

            val dx = e2.x - e1.x
            val dy = e2.y - e1.y
            if (kotlin.math.abs(dx) < kotlin.math.abs(dy)) return@setOnSingleFlingListener false
            if (kotlin.math.abs(dx) < 120) return@setOnSingleFlingListener false
            if (kotlin.math.abs(velocityX) < 400) return@setOnSingleFlingListener false

            if (dx < 0) showAdjacentImage(+1) else showAdjacentImage(-1)
            true
        }

        // Initial render
        imageId?.let { showImageById(it) }

        setEditExpanded(false)
        buttonEdit?.setOnClickListener {
            setEditExpanded(!editExpanded)
        }
        buttonRotateCcw?.setOnClickListener {
            val uri = imageUri ?: return@setOnClickListener
            val mime = runCatching { requireContext().contentResolver.getType(uri) }
                .getOrNull()
                ?.lowercase()
            if (mime == "image/jpeg") {
                if (runCatching { JpegTurboNative.isAvailable() }.getOrDefault(false)) {
                    confirmAndApplyEdit(uri, LosslessJpegTurboRotate90CounterClockwiseOperation())
                } else {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.edit_rotate_missing_turbo),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.edit_rotate_jpeg_only),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        buttonRotate?.setOnClickListener {
            val uri = imageUri ?: return@setOnClickListener
            val mime = runCatching { requireContext().contentResolver.getType(uri) }
                .getOrNull()
                ?.lowercase()
            if (mime == "image/jpeg") {
                if (runCatching { JpegTurboNative.isAvailable() }.getOrDefault(false)) {
                    confirmAndApplyEdit(uri, LosslessJpegTurboRotate90ClockwiseOperation())
                } else {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.edit_rotate_missing_turbo),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.edit_rotate_jpeg_only),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        val buttonBackToAllImages: ImageButton = view.findViewById(R.id.buttonBackToAllImages)
        buttonBackToAllImages.setOnClickListener {
            // Keep the current search/results state (text search, near-duplicates, image-to-image, etc.)
            // and simply go back to the grid.
            parentFragmentManager.popBackStack()
        }

        buttonExif?.setOnClickListener {
            showExif = !showExif
            updateInfoText()
        }

        buttonAiTags?.setOnClickListener {
            val id = imageId ?: return@setOnClickListener
            val tv = aiTagsTextView ?: return@setOnClickListener

            if (tv.visibility == View.VISIBLE) {
                aiTagsJob?.cancel()
                tv.visibility = View.GONE
                return@setOnClickListener
            }

            tv.visibility = View.VISIBLE
            if (aiTagsForImageId == id && !aiTagsTextCached.isNullOrBlank()) {
                tv.text = aiTagsTextCached
                return@setOnClickListener
            }

            tv.text = getString(R.string.ai_tags_loading)
            aiTagsJob?.cancel()
            aiTagsJob = lifecycleScope.launch {
                val scored = runCatching { computeAiTagsForImage(id, tv) }.getOrNull()

                if (!isAdded) return@launch
                if (imageId != id) return@launch

                if (scored.isNullOrEmpty()) {
                    tv.text = getString(R.string.ai_tags_unavailable)
                    aiTagsForImageId = null
                    aiTagsTextCached = null
                } else {
                    val anyAbove = scored.any { it.second >= 0.5f }
                    val text = buildString {
                        append(getString(R.string.ai_tags))
                        if (!anyAbove) {
                            append(" • ")
                            append(getString(R.string.ai_tags_none_above_threshold))
                        }
                        append(":\n")
                        for ((idx, pair) in scored.withIndex()) {
                            val (w, score) = pair
                            append(idx + 1)
                            append(". ")
                            append(w)
                            append(" (")
                            append(String.format(java.util.Locale.US, "%.3f", score))
                            append(")")
                            if (idx != scored.lastIndex) append('\n')
                        }
                    }
                    tv.text = text
                    aiTagsForImageId = id
                    aiTagsTextCached = text
                }
            }
        }

        val buttonImage2Image: ImageButton = view.findViewById(R.id.buttonImage2Image)
        buttonImage2Image.setOnClickListener {
            imageId?.let {
                val imageIndex = mORTImageViewModel.idxList.indexOf(it)
                if (imageIndex < 0) {
                    Toast.makeText(requireContext(), "Image is not indexed", Toast.LENGTH_SHORT)
                        .show()
                    return@setOnClickListener
                }
                val imageEmbedding = mORTImageViewModel.embeddingsList[imageIndex]
                mSearchViewModel.sortByCosineDistance(
                    imageEmbedding,
                    mORTImageViewModel.embeddingsList,
                    mORTImageViewModel.idxList,
                    minSimilarity = mSearchViewModel.getImageSimilarityThreshold(),
                    isImageSearch = true
                )
            }
            mSearchViewModel.showBackToAllImages = true
            mSearchViewModel.lastResultsAreNearDuplicates = false
            mSearchViewModel.fromImg2ImgFlag = true
            parentFragmentManager.popBackStack()
        }

        val buttonShare: ImageButton = view.findViewById(R.id.buttonShare)
        buttonShare.setOnClickListener {
            val sendIntent: Intent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_STREAM, imageUri)
                type = "image/*"
            }
            val shareIntent = Intent.createChooser(sendIntent, null)
            startActivity(shareIntent)
        }

        val buttonDelete: ImageButton = view.findViewById(R.id.buttonDelete)
        buttonDelete.setOnClickListener {
            val id = imageId ?: return@setOnClickListener
            startDelete(listOf(id))
        }
        return view
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("show_exif", showExif)
    }

    private fun startDelete(ids: List<Long>) {
        if (ids.isEmpty()) return
        val ctx = context ?: return
        val contentResolver = ctx.contentResolver
        val uris = ids.map { Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it.toString()) }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val pendingIntent = MediaStore.createDeleteRequest(contentResolver, uris)
            pendingAction = PendingAction.Delete
            pendingDeleteIds = ids
            intentSenderLauncher.launch(
                IntentSenderRequest.Builder(pendingIntent.intentSender).build()
            )
            return
        }

        val deletedIds = mutableListOf<Long>()
        for ((idx, uri) in uris.withIndex()) {
            try {
                val deleted = contentResolver.delete(uri, null, null)
                if (deleted > 0) deletedIds.add(ids[idx])
            } catch (e: SecurityException) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
                    e is RecoverableSecurityException
                ) {
                    pendingAction = PendingAction.Delete
                    pendingDeleteIds = ids
                    intentSenderLauncher.launch(
                        IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build()
                    )
                    return
                }
            }
        }

        if (deletedIds.isEmpty()) {
            Toast.makeText(requireContext(), "No items were deleted", Toast.LENGTH_SHORT).show()
            return
        }

        onMediaItemsDeleted(deletedIds)
        parentFragmentManager.popBackStack()
    }

    private fun onMediaItemsDeleted(ids: List<Long>) {
        val idSet = ids.toHashSet()
        mORTImageViewModel.removeFromIndex(ids)
        mSearchViewModel.searchResults =
            mSearchViewModel.searchResults?.filterNot { idSet.contains(it) }
        mSearchViewModel.selectedImageIds.removeAll(idSet)

        // If the currently shown image was deleted, avoid trying to re-render it.
        if (imageId != null && idSet.contains(imageId)) {
            imageId = null
            imageUri = null
        }
    }

    private fun showAdjacentImage(delta: Int) {
        val currentId = imageId ?: return
        val list = mSearchViewModel.searchResults ?: mORTImageViewModel.idxList.reversed()
        val idx = list.indexOf(currentId)
        if (idx < 0) return
        val nextIdx = idx + delta
        if (nextIdx !in list.indices) {
            Toast.makeText(requireContext(), "No more images", Toast.LENGTH_SHORT).show()
            return
        }
        showImageById(list[nextIdx])
    }

    private fun showImageById(id: Long) {
        imageId = id
        imageUri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
        exifHeaderText = null
        exifGridItems = null
        aiTagsForImageId = null
        aiTagsTextCached = null
        aiTagsJob?.cancel()
        aiTagsTextView?.visibility = View.GONE
        setEditExpanded(false)

        val ctx = context ?: return
        val cursor: Cursor? = ctx.contentResolver.query(imageUri!!, null, null, null, null)
        if (cursor == null || !cursor.moveToFirst()) {
            cursor?.close()
            return
        }

        val dateIdx: Int = cursor.getColumnIndex(MediaStore.Images.ImageColumns.DATE_MODIFIED)
        val date: Long =
            if (dateIdx >= 0) cursor.getLong(dateIdx) * 1000 else System.currentTimeMillis()

        val displayNameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
        val displayName: String? =
            if (displayNameIdx >= 0) cursor.getString(displayNameIdx) else null

        val relativePathIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
        val relativePath: String? =
            if (relativePathIdx >= 0) cursor.getString(relativePathIdx) else null

        val dataPathIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
        val dataPath: String? = if (dataPathIdx >= 0) cursor.getString(dataPathIdx) else null

        val sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
        val sizeBytes: Long? = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else null

        val widthIdx = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
        val heightIdx = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
        val width: Int? = if (widthIdx >= 0) cursor.getInt(widthIdx) else null
        val height: Int? = if (heightIdx >= 0) cursor.getInt(heightIdx) else null

        cursor.close()

        singleImageView?.let {
            Glide.with(it)
                .load(imageUri)
                .signature(ObjectKey("$id-$date-$imageReloadNonce"))
                .into(it)
        }
        updateXmpHeader(ctx, imageUri!!)

        val location = when {
            !relativePath.isNullOrBlank() && !displayName.isNullOrBlank() -> relativePath + displayName
            !relativePath.isNullOrBlank() -> relativePath
            !dataPath.isNullOrBlank() -> dataPath
            else -> imageUri.toString()
        }
        val dimensions = if (width != null && height != null && width > 0 && height > 0) {
            "${width}×${height}px"
        } else {
            "Unknown"
        }
        val sizeText = sizeBytes?.let { formatBytes(it) } ?: "Unknown"

        val dateText = DateFormat.getDateInstance().format(date)
        basicInfoText = "Date: $dateText\nLocation: $location\nSize: $sizeText\nDimensions: $dimensions"
        updateInfoText()
    }

    private fun setEditExpanded(expanded: Boolean) {
        editExpanded = expanded
        editActionsContainer?.visibility = if (expanded) View.VISIBLE else View.GONE
    }

    private fun confirmAndApplyEdit(uri: Uri, operation: ImageEditOperation) {
        val message = buildString {
            append(operation.confirmationMessage)
            append("\n\n")
            append(getString(R.string.edit_reencode_warning))
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.edit_confirm_title))
            .setMessage(message)
            .setPositiveButton(getString(R.string.edit_apply)) { _, _ ->
                applyEditOperation(uri, operation)
            }
            .setNegativeButton(getString(R.string.edit_cancel), null)
            .show()
    }

    private fun confirmAndApplyEdit(uri: Uri, operation: MediaStoreEditOperation) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.edit_confirm_title))
            .setMessage(operation.confirmationMessage)
            .setPositiveButton(getString(R.string.edit_apply)) { _, _ ->
                applyEditOperation(uri, operation)
            }
            .setNegativeButton(getString(R.string.edit_cancel), null)
            .show()
    }

    private fun applyEditOperation(uri: Uri, operation: ImageEditOperation) {
        if (editInProgress) return
        editInProgress = true
        setEditButtonsEnabled(false)

        Toast.makeText(requireContext(), operation.name + "…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val editor = MediaStoreImageEditor(requireContext().applicationContext)
            val result = withContext(Dispatchers.IO) {
                editor.applyOperation(uri, operation)
            }

            if (!isAdded) return@launch
            editInProgress = false
            setEditButtonsEnabled(true)

            when (result) {
                is MediaStoreImageEditor.Result.Success -> {
                    Toast.makeText(requireContext(), getString(R.string.edit_saved), Toast.LENGTH_SHORT)
                        .show()
                    setEditExpanded(false)
                    imageId?.let { mSearchViewModel.bumpImageEditNonce(it) }
                    imageReloadNonce += 1L
                    imageId?.let { showImageById(it) }
                }
                is MediaStoreImageEditor.Result.NeedsWritePermission -> {
                    pendingAction = PendingAction.EditWrite
                    pendingBitmapEditOperation = operation
                    intentSenderLauncher.launch(
                        IntentSenderRequest.Builder(result.pendingIntent.intentSender).build()
                    )
                }
                is MediaStoreImageEditor.Result.Error -> {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun applyEditOperation(uri: Uri, operation: MediaStoreEditOperation) {
        if (editInProgress) return
        editInProgress = true
        setEditButtonsEnabled(false)

        Toast.makeText(requireContext(), operation.name + "…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val editor = MediaStoreImageEditor(requireContext().applicationContext)
            val result = withContext(Dispatchers.IO) {
                editor.applyOperation(uri, operation)
            }

            if (!isAdded) return@launch
            editInProgress = false
            setEditButtonsEnabled(true)

            when (result) {
                is MediaStoreImageEditor.Result.Success -> {
                    Toast.makeText(requireContext(), getString(R.string.edit_saved), Toast.LENGTH_SHORT)
                        .show()
                    setEditExpanded(false)
                    imageId?.let { mSearchViewModel.bumpImageEditNonce(it) }
                    imageReloadNonce += 1L
                    imageId?.let { showImageById(it) }
                }
                is MediaStoreImageEditor.Result.NeedsWritePermission -> {
                    pendingAction = PendingAction.EditWrite
                    pendingMediaStoreEditOperation = operation
                    intentSenderLauncher.launch(
                        IntentSenderRequest.Builder(result.pendingIntent.intentSender).build()
                    )
                }
                is MediaStoreImageEditor.Result.Error -> {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setEditButtonsEnabled(enabled: Boolean) {
        buttonEdit?.isEnabled = enabled
        buttonRotate?.isEnabled = enabled
        buttonRotateCcw?.isEnabled = enabled
    }

    private fun selectCandidateWords(words: List<String>, limit: Int): List<String> {
        if (words.isEmpty() || limit <= 0) return emptyList()
        val out = ArrayList<String>(limit)
        val step = (words.size / limit).coerceAtLeast(1)
        var i = 0
        while (i < words.size && out.size < limit) {
            val w = words[i]
            if (w.length >= 3) out.add(w)
            i += step
        }
        return out
    }

    private suspend fun computeAiTagsForImage(
        id: Long,
        statusView: TextView,
    ): List<Pair<String, Float>>? {
        return withContext(Dispatchers.Default) {
            val imageEmbedding = mORTImageViewModel.getEmbeddingForImageId(id) ?: return@withContext null

            VocabAutocomplete.ensureLoaded(requireContext().applicationContext)
            val words = VocabAutocomplete.wordsOrEmpty()
            val candidates = selectCandidateWords(words, limit = 300)

            val minScore = 0.5f
            val topK = 20
            val minToScanBeforeEarlyExit = 200
            val heapAbove =
                PriorityQueue<Pair<String, Float>>(topK + 1) { a, b ->
                    a.second.compareTo(b.second)
                }
            val heapAny =
                PriorityQueue<Pair<String, Float>>(10 + 1) { a, b ->
                    a.second.compareTo(b.second)
                }

            for ((idx, w) in candidates.withIndex()) {
                currentCoroutineContext().ensureActive()
                val textEmbedding = runCatching { mORTTextViewModel.getTextEmbeddingCached(w) }.getOrNull()
                if (textEmbedding == null || textEmbedding.isEmpty()) continue
                val score = imageEmbedding dot textEmbedding
                if (score >= minScore) {
                    if (heapAbove.size < topK) {
                        heapAbove.add(w to score)
                    } else if (score > (heapAbove.peek()?.second ?: Float.NEGATIVE_INFINITY)) {
                        heapAbove.poll()
                        heapAbove.add(w to score)
                    }
                }
                if (heapAny.size < 10) {
                    heapAny.add(w to score)
                } else if (score > (heapAny.peek()?.second ?: Float.NEGATIVE_INFINITY)) {
                    heapAny.poll()
                    heapAny.add(w to score)
                }

                if ((idx + 1) % 50 == 0 || idx == candidates.lastIndex) {
                    val pct = (((idx + 1) * 100) / candidates.size).coerceIn(1, 100)
                    val preview =
                        (if (heapAbove.isNotEmpty()) heapAbove else heapAny).toList()
                            .sortedByDescending { it.second }
                            .take(10)
                            .joinToString(", ") { it.first }
                    withContext(Dispatchers.Main) main@{
                        if (!isAdded) return@main
                        if (imageId != id) return@main
                        val prefix = getString(R.string.ai_tags_loading) + " ($pct%)"
                        statusView.text = if (preview.isBlank()) prefix else "$prefix\n$preview"
                    }
                }

                if ((idx + 1) >= minToScanBeforeEarlyExit && heapAbove.size >= topK) {
                    // We've already found enough "confident" tags; stop early for responsiveness.
                    break
                }
            }

            if (heapAbove.isNotEmpty()) {
                heapAbove.toList().sortedByDescending { it.second }
            } else {
                heapAny.toList().sortedByDescending { it.second }
            }
        }
    }

    private fun updateXmpHeader(ctx: android.content.Context, uri: Uri) {
        val tv = xmpTextView ?: return
        val summary = loadXmpSummary(ctx, uri)
        if (summary.isNullOrBlank()) {
            tv.text = ""
            tv.visibility = View.GONE
        } else {
            tv.text = summary
            tv.visibility = View.VISIBLE
        }
    }

    private fun loadXmpSummary(ctx: android.content.Context, uri: Uri): String? {
        return try {
            val exif = ctx.contentResolver.openInputStream(uri)?.use { input ->
                ExifInterface(input)
            } ?: return null

            val xmp = exif.getAttribute(ExifInterface.TAG_XMP)?.trim()?.takeIf { it.isNotEmpty() }
                ?: return null

            // Extract a few common XMP/IPTC fields (best-effort, string-based parsing).
            fun extractFirstLi(tag: String): String? {
                val start = xmp.indexOf("<$tag")
                if (start < 0) return null
                val end = xmp.indexOf("</$tag>", start)
                if (end < 0) return null
                val block = xmp.substring(start, end)
                val liStart = block.indexOf("<rdf:li")
                if (liStart < 0) return null
                val liClose = block.indexOf('>', liStart)
                if (liClose < 0) return null
                val liEnd = block.indexOf("</rdf:li>", liClose)
                if (liEnd < 0) return null
                return decodeXml(block.substring(liClose + 1, liEnd).trim())
            }

            fun extractAllLi(tag: String, limit: Int = 3): List<String> {
                val start = xmp.indexOf("<$tag")
                if (start < 0) return emptyList()
                val end = xmp.indexOf("</$tag>", start)
                if (end < 0) return emptyList()
                val block = xmp.substring(start, end)
                val results = ArrayList<String>(limit)
                var idx = 0
                while (results.size < limit) {
                    val liStart = block.indexOf("<rdf:li", idx)
                    if (liStart < 0) break
                    val liClose = block.indexOf('>', liStart)
                    if (liClose < 0) break
                    val liEnd = block.indexOf("</rdf:li>", liClose)
                    if (liEnd < 0) break
                    val value = decodeXml(block.substring(liClose + 1, liEnd).trim())
                    if (value.isNotEmpty()) results.add(value)
                    idx = liEnd + 9
                }
                return results
            }

            val title = extractFirstLi("dc:title")
            val description = extractFirstLi("dc:description")
            val creator = extractAllLi("dc:creator", limit = 1).firstOrNull()
            val keywords = extractAllLi("dc:subject", limit = 3)

            val lines = ArrayList<String>(3)
            if (!title.isNullOrBlank()) lines.add(title)
            if (!description.isNullOrBlank() && description != title) lines.add(description)
            if (!creator.isNullOrBlank() && lines.size < 2) lines.add("By: $creator")

            if (lines.isEmpty() && keywords.isNotEmpty()) {
                lines.add("Keywords: " + keywords.joinToString(", "))
            }

            if (lines.isEmpty()) {
                // Fallback: show that XMP exists without dumping the full packet.
                "Metadata available"
            } else {
                lines.joinToString(" • ")
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeXml(s: String): String {
        return s
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }

    private fun updateInfoText() {
        if (!showExif) {
            exifGrid?.visibility = View.GONE
            infoTextView?.text = basicInfoText
            return
        }

        val uri = imageUri
        val ctx = context
        if (uri == null || ctx == null) {
            exifGrid?.visibility = View.GONE
            infoTextView?.text = "No EXIF data"
            return
        }

        if (exifHeaderText == null || exifGridItems == null) {
            val (header, items) = loadExifDisplay(ctx, uri)
            exifHeaderText = header
            exifGridItems = items
        }
        val header = exifHeaderText ?: "No EXIF data"
        val items = exifGridItems ?: emptyList()

        infoTextView?.text = header
        renderExifGrid(items)
    }

    private fun loadExifDisplay(
        ctx: android.content.Context,
        uri: Uri
    ): Pair<String, List<Pair<String, String>>> {
        return try {
            val contentResolver = ctx.contentResolver
            val exif = contentResolver.openInputStream(uri)?.use { input ->
                ExifInterface(input)
            } ?: return "No EXIF data" to emptyList()

            fun cleaned(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

            val make = cleaned(exif.getAttribute(ExifInterface.TAG_MAKE))
            val model = cleaned(exif.getAttribute(ExifInterface.TAG_MODEL))
            val camera = listOfNotNull(make, model).joinToString(" ").takeIf { it.isNotBlank() }
            val taken = cleaned(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))

            val headerLines = ArrayList<String>(2)
            if (camera != null) headerLines.add("Camera: $camera")
            if (taken != null) headerLines.add("Taken: $taken")
            val header = if (headerLines.isEmpty()) "No EXIF data" else headerLines.joinToString("\n")

            val items = ArrayList<Pair<String, String>>(8)

            cleaned(exif.getAttribute(ExifInterface.TAG_LENS_MODEL))?.let { items.add("Lens" to it) }

            cleaned(exif.getAttribute(ExifInterface.TAG_F_NUMBER))?.let { raw ->
                val v = if (raw.startsWith("f/")) raw else "f/$raw"
                items.add("F" to v)
            }

            cleaned(exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME))?.let { raw ->
                val v = if (raw.endsWith("s")) raw else "${raw}s"
                items.add("Shutter" to v)
            }

            cleaned(exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY))?.let {
                items.add("ISO" to it)
            }

            cleaned(exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH))?.let { raw ->
                val v = if (raw.endsWith("mm")) raw else "${raw}mm"
                items.add("Focal" to v)
            }

            val latLong = exif.latLong
            if (latLong != null) {
                val gps =
                    "${String.format(java.util.Locale.US, "%.5f", latLong[0])}, ${
                        String.format(java.util.Locale.US, "%.5f", latLong[1])
                    }"
                items.add("GPS" to gps)
            }

            header to items
        } catch (_: Exception) {
            "No EXIF data" to emptyList()
        }
    }

    private fun renderExifGrid(items: List<Pair<String, String>>) {
        val grid = exifGrid ?: return
        val ctx = grid.context

        grid.removeAllViews()
        if (items.isEmpty() || !showExif) {
            grid.visibility = View.GONE
            return
        }

        val colCount =
            if (ctx.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                3
            } else {
                2
            }
        grid.columnCount = colCount

        val infoColor = infoTextView?.currentTextColor
        val density = ctx.resources.displayMetrics.density
        val colGapPx = (6 * density).toInt()
        val rowGapPx = 1

        items.forEachIndexed { index, (label, value) ->
            val tv = TextView(ctx).apply {
                text = "$label: $value"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                alpha = 0.7f
                if (infoColor != null) setTextColor(infoColor)
            }

            val col = index % colCount
            val lp = GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                val left = if (col == 0) 0 else colGapPx
                val right = if (col == colCount - 1) 0 else colGapPx
                setMargins(left, rowGapPx, right, rowGapPx)
            }
            grid.addView(tv, lp)
        }

        grid.visibility = View.VISIBLE
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "${bytes} B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(java.util.Locale.US, "%.1f GB", gb)
    }
}
