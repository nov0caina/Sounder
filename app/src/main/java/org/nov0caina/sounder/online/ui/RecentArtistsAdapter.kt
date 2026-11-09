package org.nov0caina.sounder.online.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import coil3.util.CoilUtils
import org.nov0caina.sounder.R
import org.nov0caina.sounder.databinding.ItemRecentArtistBinding
import org.nov0caina.sounder.online.model.OnlineArtistSummary

class RecentArtistsAdapter(
    private val onArtistClick: (OnlineArtistSummary) -> Unit
) : ListAdapter<OnlineArtistSummary, RecentArtistsAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRecentArtistBinding.inflate(
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
        private val binding: ItemRecentArtistBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(artist: OnlineArtistSummary) {
            binding.recentArtistName.text = artist.name

            CoilUtils.dispose(binding.recentArtistAvatar)
            binding.recentArtistAvatar.setImageDrawable(null)
            if (!artist.avatarUrl.isNullOrBlank()) {
                binding.recentArtistAvatar.load(artist.avatarUrl) {
                    crossfade(true)
                }
            } else {
                binding.recentArtistAvatar.setImageResource(R.drawable.ic_artist_24)
            }

            binding.root.setOnClickListener {
                onArtistClick(artist)
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<OnlineArtistSummary>() {
        override fun areItemsTheSame(oldItem: OnlineArtistSummary, newItem: OnlineArtistSummary): Boolean =
            if (oldItem.id.isNotBlank() && newItem.id.isNotBlank()) {
                oldItem.id == newItem.id
            } else {
                oldItem.name.equals(newItem.name, ignoreCase = true)
            }

        override fun areContentsTheSame(oldItem: OnlineArtistSummary, newItem: OnlineArtistSummary): Boolean =
            oldItem == newItem
    }
}

