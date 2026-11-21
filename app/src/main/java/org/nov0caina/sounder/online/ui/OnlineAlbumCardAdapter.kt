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

class OnlineAlbumCardAdapter(
    private val onAlbumClick: (OnlineAlbumSummary) -> Unit
) : ListAdapter<OnlineAlbumSummary, OnlineAlbumCardAdapter.ViewHolder>(DiffCallback) {

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

        fun bind(album: OnlineAlbumSummary) {
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

