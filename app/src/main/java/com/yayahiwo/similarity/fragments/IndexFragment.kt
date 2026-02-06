package com.yayahiwo.similarity.fragments

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yayahiwo.similarity.SimilaritySettings
import com.yayahiwo.similarity.viewmodels.ORTImageViewModel
import com.yayahiwo.similarity.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

class IndexFragment : Fragment() {
    private var progressBarView: ProgressBar? = null
    private var progressBarTextView: TextView? = null
    private var indexedCountTextView: TextView? = null
    private var selectedFoldersTextView: TextView? = null
    private var indexControlsView: View? = null
    private var selectFoldersButton: Button? = null
    private var startIndexButton: Button? = null
    private var isIndexingActive: Boolean = false
    private val mORTImageViewModel: ORTImageViewModel by activityViewModels()

    private enum class PendingAction {
        SelectFolders,
        StartIndex,
    }

    private var pendingAction: PendingAction? = null
    private var startAfterFolderSelection: Boolean = false

    private val permissionsRequest: ActivityResultLauncher<String> = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        val action = pendingAction
        pendingAction = null

        if (!isGranted) {
            Toast.makeText(context, "The app requires storage permissions!", Toast.LENGTH_SHORT)
                .show()
            return@registerForActivityResult
        }

        when (action) {
            PendingAction.SelectFolders -> showFolderSelectionDialog()
            PendingAction.StartIndex -> startIndexing()
            null -> Unit
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        val view = inflater.inflate(R.layout.fragment_index, container, false)

        // App startup now happens on SearchFragment (folder selection + reindex progress UI).
        // If we land here as the first screen (e.g., restored stale back stack), redirect and
        // skip doing any work in this legacy fragment.
        val navController = findNavController()
        val shouldRedirect = navController.previousBackStackEntry == null
        if (shouldRedirect) {
            view.post {
                if (!isAdded) return@post
                try {
                    val opts = NavOptions.Builder()
                        .setPopUpTo(R.id.indexFragment, true)
                        .build()
                    navController.navigate(R.id.searchFragment, null, opts)
                } catch (e: Exception) {
                    Log.w("Similarity", "Failed to redirect IndexFragment -> SearchFragment", e)
                }
            }
            return view
        }

        progressBarView = view.findViewById(R.id.progressBar)
        progressBarTextView = view.findViewById(R.id.progressBarText)
        indexedCountTextView = view.findViewById(R.id.indexedCountText)
        selectedFoldersTextView = view.findViewById(R.id.selectedFoldersText)
        indexControlsView = view.findViewById(R.id.indexControls)
        selectFoldersButton = view.findViewById(R.id.selectFoldersButton)
        startIndexButton = view.findViewById(R.id.startIndexButton)

        progressBarView?.visibility = View.GONE
        indexedCountTextView?.visibility = View.GONE
        progressBarTextView?.text = "Select folders to index"

        updateSelectedFoldersSummary()

        selectFoldersButton?.setOnClickListener {
            startAfterFolderSelection = false
            ensurePermission(PendingAction.SelectFolders)
        }
        startIndexButton?.setOnClickListener {
            ensurePermission(PendingAction.StartIndex)
        }

        val prefs = SimilaritySettings.prefs(requireContext())
        val configured = prefs.getBoolean(SimilaritySettings.KEY_INDEX_FOLDERS_CONFIGURED, false)
        startIndexButton?.isEnabled = configured

        val autoStart = arguments?.getBoolean("auto_start", false) == true
        if (autoStart && configured) {
            ensurePermission(PendingAction.StartIndex)
        }
        if (!configured) {
            startAfterFolderSelection = true
            ensurePermission(PendingAction.SelectFolders)
        }

        mORTImageViewModel.progress.observe(viewLifecycleOwner) { progress ->
            var progressPercent: Int = (progress * 100).toInt()
            if (isIndexingActive) {
                progressBarView?.progress = progressPercent
                progressBarTextView?.text = "Updating image index: ${progressPercent}%"
            }

            if (isIndexingActive && progress == 1.0) {
                isIndexingActive = false
                activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                findNavController().navigate(R.id.action_indexFragment_to_searchFragment)
            }
        }

        mORTImageViewModel.indexedCount.observe(viewLifecycleOwner) { count ->
            indexedCountTextView?.text = "Indexed photos: $count"
        }
        return view
    }

    private fun ensurePermission(action: PendingAction) {
        pendingAction = action
        val permission = requiredPermission()
        val ctx = context ?: return
        val granted = ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            pendingAction = null
            when (action) {
                PendingAction.SelectFolders -> showFolderSelectionDialog()
                PendingAction.StartIndex -> startIndexing()
            }
            return
        }
        permissionsRequest.launch(permission)
    }

    private fun requiredPermission(): String {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    private fun updateSelectedFoldersSummary() {
        val prefs = SimilaritySettings.prefs(requireContext())
        val configured = prefs.getBoolean(SimilaritySettings.KEY_INDEX_FOLDERS_CONFIGURED, false)
        val bucketIds = prefs.getStringSet(SimilaritySettings.KEY_INDEXED_BUCKET_IDS, emptySet())?.toSet() ?: emptySet()
        selectedFoldersTextView?.text = if (!configured) {
            "Folders: not selected"
        } else if (bucketIds.isEmpty()) {
            "Folders: all"
        } else {
            "Folders: ${bucketIds.size} selected"
        }
    }

    private fun showFolderSelectionDialog() {
        val ctx = context ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val buckets = queryImageBuckets(ctx)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext

                val prefs = SimilaritySettings.prefs(requireContext())
                val saved = prefs.getStringSet(SimilaritySettings.KEY_INDEXED_BUCKET_IDS, emptySet())?.toSet() ?: emptySet()
                val configured = prefs.getBoolean(SimilaritySettings.KEY_INDEX_FOLDERS_CONFIGURED, false)

                val items = ArrayList<String>(buckets.size + 1)
                items.add("All folders")
                for ((_, name) in buckets) items.add(name)

                val checked = BooleanArray(items.size)
                if (configured && saved.isEmpty()) {
                    checked[0] = true
                } else {
                    for (i in buckets.indices) {
                        checked[i + 1] = saved.contains(buckets[i].first.toString())
                    }
                }

                val dialog = MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Folders to index")
                    .setMultiChoiceItems(items.toTypedArray(), checked) { _, which, isChecked ->
                        if (which == 0) {
                            if (isChecked) {
                                for (i in 1 until checked.size) checked[i] = false
                            }
                            checked[0] = isChecked
                        } else {
                            checked[which] = isChecked
                            if (isChecked) checked[0] = false
                        }
                    }
                    .setPositiveButton("OK") { _, _ ->
                        val selected = buckets
                            .filterIndexed { idx, _ -> checked[idx + 1] }
                            .map { it.first.toString() }
                            .toSet()

                        if (!checked[0] && selected.isEmpty()) {
                            Toast.makeText(
                                requireContext(),
                                "Select at least one folder (or All folders).",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@setPositiveButton
                        }

                        val bucketIds = if (checked[0]) emptySet() else selected
                        prefs.edit()
                            .putStringSet(SimilaritySettings.KEY_INDEXED_BUCKET_IDS, bucketIds)
                            .putBoolean(SimilaritySettings.KEY_INDEX_FOLDERS_CONFIGURED, true)
                            .apply()

                        updateSelectedFoldersSummary()
                        startIndexButton?.isEnabled = true
                        if (startAfterFolderSelection) {
                            startAfterFolderSelection = false
                            startIndexing()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .create()

                dialog.setOnShowListener {
                    val listView = dialog.listView ?: return@setOnShowListener
                    listView.post {
                        val window = dialog.window ?: return@post
                        val visibleFrame = Rect()
                        window.decorView.getWindowVisibleDisplayFrame(visibleFrame)
                        val screenHeight =
                            visibleFrame.height().takeIf { it > 0 }
                                ?: requireContext().resources.displayMetrics.heightPixels

                        val density = requireContext().resources.displayMetrics.density
                        fun dpToPx(dp: Int): Int = (dp * density).roundToInt()

                        val contentHeightLimit = (screenHeight * 0.75f).roundToInt()
                        val buttonsHeight =
                            (dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.parent as? View)?.height
                                ?: 0
                        val reserved = buttonsHeight + dpToPx(120)
                        val maxListHeight = (contentHeightLimit - reserved).coerceAtLeast(dpToPx(220))

                        if (listView.height > maxListHeight) {
                            listView.layoutParams = listView.layoutParams.apply {
                                height = maxListHeight
                            }
                        }
                    }
                }

                dialog.show()
            }
        }
    }

    private fun queryImageBuckets(ctx: Context): List<Pair<Long, String>> {
        val contentResolver = ctx.contentResolver
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )

        val bucketMap = linkedMapOf<Long, String>()
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val bucketId = cursor.getLong(idCol)
                val bucketName = cursor.getString(nameCol) ?: "(Unnamed)"
                if (bucketName == "Screenshots") continue
                if (!bucketMap.containsKey(bucketId)) bucketMap[bucketId] = bucketName
            }
        }

        return bucketMap.entries
            .sortedBy { it.value.lowercase(Locale.getDefault()) }
            .map { it.key to it.value }
    }

    private fun startIndexing() {
        val prefs = SimilaritySettings.prefs(requireContext())
        val configured = prefs.getBoolean(SimilaritySettings.KEY_INDEX_FOLDERS_CONFIGURED, false)
        if (!configured) {
            startAfterFolderSelection = true
            showFolderSelectionDialog()
            return
        }

        isIndexingActive = true
        activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        indexControlsView?.visibility = View.GONE
        progressBarView?.visibility = View.VISIBLE
        indexedCountTextView?.visibility = View.VISIBLE
        progressBarView?.progress = 0
        progressBarTextView?.text = "Updating image index: 0%"
        mORTImageViewModel.generateIndex()
    }
}
