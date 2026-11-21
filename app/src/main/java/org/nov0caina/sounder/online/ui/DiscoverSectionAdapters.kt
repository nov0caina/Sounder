package org.nov0caina.sounder.online.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import org.nov0caina.sounder.R
import org.nov0caina.sounder.databinding.ItemDiscoverSectionHeaderBinding
import org.nov0caina.sounder.databinding.ItemDiscoverToggleFooterBinding
import org.nov0caina.sounder.databinding.ItemDiscoverTopResultBinding
import org.nov0caina.sounder.online.model.OnlineArtistSummary

/**
 * Single-item adapter rendering the #1 Top Result hero card inside [DiscoverFragment]'s
 * Artists tab RecyclerView.
 */
class DiscoverHeroAdapter(
    private val onArtistClick: (OnlineArtistSummary) -> Unit,
    private val onPlayClick: (OnlineArtistSummary) -> Unit,
) : RecyclerView.Adapter<DiscoverHeroAdapter.ViewHolder>() {

    var artist: OnlineArtistSummary? = null
        set(value) {
            val hadItem = field != null
            val hasItem = value != null
            field = value
            when {
                !hadItem && hasItem -> notifyItemInserted(0)
                hadItem && !hasItem -> notifyItemRemoved(0)
                hadItem && hasItem -> notifyItemChanged(0)
            }
        }

    override fun getItemCount(): Int = if (artist != null) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDiscoverTopResultBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        artist?.let { holder.bind(it) }
    }

    inner class ViewHolder(
        private val binding: ItemDiscoverTopResultBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: OnlineArtistSummary) {
            val ctx = binding.root.context
            binding.topResultTitle.text = item.name
            binding.topResultSubtitle.text = item.subtitle ?: ctx.getString(R.string.header_artists)
            binding.topResultAvatar.load(item.avatarUrl) { crossfade(true) }
            if (!item.avatarUrl.isNullOrBlank()) {
                binding.topResultAmbientGlow.bindData(item.avatarUrl, item.avatarUrl)
            } else {
                binding.topResultAmbientGlow.clearGlow()
            }
            binding.root.setOnClickListener { onArtistClick(item) }
            binding.topResultAvatar.setOnClickListener { onArtistClick(item) }
            binding.topResultTitle.setOnClickListener { onArtistClick(item) }
            binding.topResultExploreBtn.setOnClickListener { onArtistClick(item) }
            binding.topResultPlayBtn.setOnClickListener { onPlayClick(item) }
        }
    }
}

/**
 * Single-item section header adapter for [ConcatAdapter] sections in [DiscoverFragment].
 */
class DiscoverSectionHeaderAdapter(
    @StringRes private val titleRes: Int,
) : RecyclerView.Adapter<DiscoverSectionHeaderAdapter.ViewHolder>() {

    var visible: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) notifyItemInserted(0) else notifyItemRemoved(0)
        }

    override fun getItemCount(): Int = if (visible) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDiscoverSectionHeaderBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(titleRes)
    }

    class ViewHolder(
        private val binding: ItemDiscoverSectionHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(@StringRes titleRes: Int) {
            binding.discoverSectionTitle.setText(titleRes)
        }
    }
}

/**
 * Single-item Show more / Show less footer adapter for [ConcatAdapter] tabs in [DiscoverFragment].
 */
class DiscoverToggleFooterAdapter(
    private val onToggleClick: () -> Unit,
) : RecyclerView.Adapter<DiscoverToggleFooterAdapter.ViewHolder>() {

    @StringRes
    var textRes: Int? = null
        set(value) {
            val hadItem = field != null
            val hasItem = value != null
            field = value
            when {
                !hadItem && hasItem -> notifyItemInserted(0)
                hadItem && !hasItem -> notifyItemRemoved(0)
                hadItem && hasItem -> notifyItemChanged(0)
            }
        }

    override fun getItemCount(): Int = if (textRes != null) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDiscoverToggleFooterBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        textRes?.let { holder.bind(it) }
    }

    inner class ViewHolder(
        private val binding: ItemDiscoverToggleFooterBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(@StringRes resId: Int) {
            binding.discoverToggleBtn.setText(resId)
            binding.discoverToggleBtn.setOnClickListener { onToggleClick() }
        }
    }
}
