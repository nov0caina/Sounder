package org.nov0caina.sounder.online.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import coil3.util.CoilUtils
import org.nov0caina.sounder.R
import org.nov0caina.sounder.databinding.ItemOnlineAlbumCardBinding
import org.nov0caina.sounder.online.model.OnlineAlbumSummary
import org.nov0caina.sounder.ui.MotionPhysics
import org.nov0caina.sounder.ui.attachSpringTouchFeedback

class OnlineAlbumCardAdapter(
    private val onAlbumClick: (OnlineAlbumSummary) -> Unit
) : ListAdapter<OnlineAlbumSummary, OnlineAlbumCardAdapter.ViewHolder>(DiffCallback) {

    private var isGridMode: Boolean = false

    fun setGridMode(gridMode: Boolean) {
        if (isGridMode != gridMode) {
            isGridMode = gridMode
            if (itemCount > 0) {
                notifyItemRangeChanged(0, itemCount)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemOnlineAlbumCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemOnlineAlbumCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.attachSpringTouchFeedback(MotionPhysics.DEFAULT_SQUISH_SCALE)
        }

        private var appliedGridMode: Boolean? = null

        private fun dpToPx(dp: Int): Int =
            (dp * itemView.resources.displayMetrics.density + 0.5f).toInt()

        private fun applyLayoutMode(gridMode: Boolean) {
            if (appliedGridMode == gridMode) return
            appliedGridMode = gridMode

            val rootParams = binding.root.layoutParams as? ViewGroup.MarginLayoutParams
            if (rootParams != null) {
                if (gridMode) {
                    rootParams.width = ViewGroup.LayoutParams.MATCH_PARENT
                    val marginH = dpToPx(4)
                    rootParams.marginStart = marginH
                    rootParams.marginEnd = marginH
                } else {
                    rootParams.width = dpToPx(132)
                    rootParams.marginStart = 0
                    rootParams.marginEnd = dpToPx(6)
                }
                binding.root.layoutParams = rootParams
            }

            val containerParams = binding.albumCoverContainer.layoutParams
            containerParams.height = dpToPx(if (gridMode) 118 else 144)
            binding.albumCoverContainer.layoutParams = containerParams

            val coverPx = dpToPx(if (gridMode) 100 else 120)
            val coverParams = binding.albumCover.layoutParams
            coverParams.width = coverPx
            coverParams.height = coverPx
            binding.albumCover.layoutParams = coverParams

            val glowPx = dpToPx(if (gridMode) 120 else 148)
            val glowParams = binding.albumAmbientGlow.layoutParams
            glowParams.width = glowPx
            glowParams.height = glowPx
            binding.albumAmbientGlow.layoutParams = glowParams
        }

        fun bind(album: OnlineAlbumSummary) {
            applyLayoutMode(isGridMode)
            binding.albumTitle.text = album.title
            binding.albumYear.isVisible = !album.year.isNullOrBlank()
            binding.albumYear.text = album.year

            CoilUtils.dispose(binding.albumCover)
            binding.albumCover.setImageDrawable(null)
            if (!album.coverUrl.isNullOrBlank()) {
                binding.albumCover.load(album.coverUrl) {
                    crossfade(true)
                }
                binding.albumAmbientGlow.bindData(album.coverUrl, album.coverUrl)
            } else {
                binding.albumCover.setImageResource(R.drawable.ic_album_48)
                binding.albumAmbientGlow.clearGlow()
            }

            binding.root.setOnClickListener {
                onAlbumClick(album)
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<OnlineAlbumSummary>() {
        override fun areItemsTheSame(oldItem: OnlineAlbumSummary, newItem: OnlineAlbumSummary): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: OnlineAlbumSummary, newItem: OnlineAlbumSummary): Boolean =
            oldItem == newItem
    }
}

