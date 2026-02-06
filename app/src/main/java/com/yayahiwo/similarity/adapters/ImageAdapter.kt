package com.yayahiwo.similarity.adapters

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentTransaction
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.signature.ObjectKey
import com.yayahiwo.similarity.fragments.ImageFragment
import com.yayahiwo.similarity.MainActivity
import com.yayahiwo.similarity.R


class ImageAdapter(
    private val context: Context,
    private val dataset: List<Long>,
    private val selectedIds: MutableSet<Long> = linkedSetOf(),
    private val onSelectionChanged: ((selectedCount: Int) -> Unit)? = null,
    private val showDimensions: Boolean = false,
    private val dimensionsById: Map<Long, String> = emptyMap(),
    private val lowPriorityThumbnails: Boolean = false,
    private val editNonceProvider: ((id: Long) -> Long)? = null,
) :
    RecyclerView.Adapter<ImageAdapter.ImageViewHolder>() {
    private val uri: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    private var selectionMode: Boolean = selectedIds.isNotEmpty()

    class ImageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.item_image)
        val checkBox: CheckBox = view.findViewById(R.id.item_checkbox)
        val selectionOverlay: View = view.findViewById(R.id.item_selection_overlay)
        val dimensionsText: TextView = view.findViewById(R.id.item_dimensions)
        val expandButton: View = view.findViewById(R.id.item_expand)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val adapterLayout =
            LayoutInflater.from(parent.context).inflate(R.layout.list_item, parent, false)
        return ImageViewHolder(adapterLayout)
    }

    override fun getItemCount(): Int = dataset.size

    override fun getItemId(position: Int): Long = dataset[position]

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        val item = dataset[position]
        val imageUri = Uri.withAppendedPath(uri, item.toString())

        val editNonce = editNonceProvider?.invoke(item) ?: 0L
        val request = Glide.with(context)
            .load(imageUri)
            .signature(ObjectKey("$item-$editNonce"))
            .thumbnail(0.1f)
            .dontAnimate()

        if (lowPriorityThumbnails) {
            request
                .format(DecodeFormat.PREFER_RGB_565)
                .priority(Priority.LOW)
                .override(256)
                .into(holder.imageView)
        } else {
            request.into(holder.imageView)
        }

        if (showDimensions) {
            val dims = dimensionsById[item]
            if (dims.isNullOrBlank()) {
                holder.dimensionsText.visibility = View.GONE
            } else {
                holder.dimensionsText.text = dims
                holder.dimensionsText.visibility = View.VISIBLE
            }
        } else {
            holder.dimensionsText.visibility = View.GONE
        }

        val isSelected = selectedIds.contains(item)
        holder.checkBox.visibility = if (selectionMode) View.VISIBLE else View.GONE
        holder.checkBox.isChecked = isSelected
        holder.selectionOverlay.visibility = if (isSelected) View.VISIBLE else View.GONE
        holder.expandButton.visibility = if (selectionMode) View.VISIBLE else View.GONE

        holder.checkBox.setOnClickListener {
            if (!selectionMode) {
                selectionMode = true
                notifyDataSetChanged()
            }
            toggleSelection(item)
            notifyItemChanged(position)
        }

        holder.expandButton.setOnClickListener {
            openImageViewer(item, imageUri)
        }

        holder.imageView.setOnClickListener {
            if (selectionMode) {
                toggleSelection(item)
                notifyItemChanged(position)
                return@setOnClickListener
            }

            val arguments = Bundle()
            arguments.putLong("image_id", item)
            arguments.putString("image_uri", imageUri.toString())

            val activity = context as? MainActivity
            if (activity == null) {
                Toast.makeText(context, "Unable to open image", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val transaction: FragmentTransaction = activity.supportFragmentManager.beginTransaction()
            val fragment = ImageFragment()
            fragment.arguments = arguments
            transaction.replace(R.id.fragmentContainerView, fragment)
            transaction.addToBackStack("image_fragment")
            transaction.commit()
        }

        holder.imageView.setOnLongClickListener {
            if (!selectionMode) {
                selectionMode = true
                selectedIds.add(item)
                notifyDataSetChanged()
                onSelectionChanged?.invoke(selectedIds.size)
                return@setOnLongClickListener true
            }
            toggleSelection(item)
            notifyItemChanged(position)
            true
        }
    }

    private fun toggleSelection(id: Long) {
        if (selectedIds.contains(id)) {
            selectedIds.remove(id)
        } else {
            selectedIds.add(id)
        }
        if (selectedIds.isEmpty() && selectionMode) {
            selectionMode = false
            notifyDataSetChanged()
        }
        onSelectionChanged?.invoke(selectedIds.size)
    }

    private fun openImageViewer(id: Long, imageUri: Uri) {
        val arguments = Bundle()
        arguments.putLong("image_id", id)
        arguments.putString("image_uri", imageUri.toString())

        val activity = context as? MainActivity
        if (activity == null) {
            Toast.makeText(context, "Unable to open image", Toast.LENGTH_SHORT).show()
            return
        }

        val transaction: FragmentTransaction = activity.supportFragmentManager.beginTransaction()
        val fragment = ImageFragment()
        fragment.arguments = arguments
        transaction.replace(R.id.fragmentContainerView, fragment)
        transaction.addToBackStack("image_fragment")
        transaction.commit()
    }

    fun getSelectedIds(): Set<Long> = selectedIds.toSet()

    fun clearSelection() {
        selectedIds.clear()
        selectionMode = false
        notifyDataSetChanged()
        onSelectionChanged?.invoke(0)
    }

    init {
        setHasStableIds(true)
    }
}
