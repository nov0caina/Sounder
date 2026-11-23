package org.nov0caina.sounder.online.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import coil3.util.CoilUtils
import org.nov0caina.sounder.R
import org.nov0caina.sounder.databinding.ItemOnlineArtistGridBinding
import org.nov0caina.sounder.databinding.ItemRecentArtistBinding
import org.nov0caina.sounder.online.model.OnlineArtistSummary
import org.nov0caina.sounder.playback.ui.AmbientGlowView
import org.nov0caina.sounder.ui.MotionPhysics
import org.nov0caina.sounder.ui.attachSpringTouchFeedback

class HomeFavoriteArtistsAdapter(
    private val onArtistClick: (OnlineArtistSummary) -> Unit
) : ListAdapter<OnlineArtistSummary, HomeFavoriteArtistsAdapter.ViewHolder>(DiffCallback) {

    private var isGridMode: Boolean = false

    fun setGridMode(gridMode: Boolean) {
        if (isGridMode != gridMode) {
            isGridMode = gridMode
            notifyDataSetChanged()
        }
    }

    override fun getItemViewType(position: Int): Int =
        if (isGridMode) VIEW_TYPE_GRID else VIEW_TYPE_CAROUSEL

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_GRID) {
            val binding = ItemOnlineArtistGridBinding.inflate(inflater, parent, false)
            ViewHolder(
                binding.root,
                binding.artistAvatar,
                binding.artistName,
                binding.artistAmbientGlow
            )
        } else {
            val binding = ItemRecentArtistBinding.inflate(inflater, parent, false)
            ViewHolder(
                binding.root,
                binding.recentArtistAvatar,
                binding.recentArtistName,
                binding.recentArtistAmbientGlow
            )
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        rootView: View,
        private val avatarView: ImageView,
        private val nameView: TextView,
        private val glowView: AmbientGlowView?
    ) : RecyclerView.ViewHolder(rootView) {

        init {
            itemView.attachSpringTouchFeedback(MotionPhysics.DEFAULT_SQUISH_SCALE)
        }

        fun bind(artist: OnlineArtistSummary) {
            nameView.text = artist.name

            CoilUtils.dispose(avatarView)
            avatarView.setImageDrawable(null)
            if (!artist.avatarUrl.isNullOrBlank()) {
                avatarView.load(artist.avatarUrl) {
                    crossfade(true)
                }
                glowView?.bindData(artist.avatarUrl, artist.avatarUrl)
            } else {
                avatarView.setImageResource(R.drawable.ic_artist_24)
                glowView?.clearGlow()
            }

            itemView.setOnClickListener {
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

    private companion object {
        const val VIEW_TYPE_CAROUSEL = 0
        const val VIEW_TYPE_GRID = 1
    }
}
