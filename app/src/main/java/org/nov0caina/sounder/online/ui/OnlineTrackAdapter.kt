package org.nov0caina.sounder.online.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import coil3.util.CoilUtils
import java.io.File
import org.nov0caina.sounder.R
import org.nov0caina.sounder.databinding.ItemOnlineTrackBinding
import org.nov0caina.sounder.online.cache.StreamingCacheManager
import org.nov0caina.sounder.online.model.OnlineTrack
import org.nov0caina.sounder.ui.setAnimatedPlayingIndicator
import org.nov0caina.sounder.util.getAttrColorCompat

class OnlineTrackAdapter(
    private val onPlayClick: (OnlineTrack) -> Unit,
    private val onDownloadClick: (OnlineTrack) -> Unit,
    private val isDownloaded: (OnlineTrack) -> Boolean,
    private val isDownloading: (OnlineTrack) -> Boolean,
    private val isCurrentlyLoading: (String) -> Boolean,
    private val isCurrentlyPlaying: (OnlineTrack) -> Boolean = { false },
    private val showRanking: Boolean = false,
    private val onTrackLongClick: ((OnlineTrack) -> Unit)? = null,
    private val onTrackMenuClick: ((OnlineTrack) -> Unit)? = onTrackLongClick
) : RecyclerView.Adapter<OnlineTrackAdapter.ViewHolder>() {

    private var items: List<OnlineTrack> = emptyList()

    val currentList: List<OnlineTrack>
        get() = items

    var compactMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    fun submitList(newList: List<OnlineTrack>?, commitCallback: Runnable? = null) {
        val target = newList ?: emptyList()
        val old = items
        if (old === target) {
            commitCallback?.run()
            return
        }

        when {
            old.isEmpty() && target.isEmpty() -> {
                commitCallback?.run()
                return
            }
            old.isEmpty() -> {
                items = target
                notifyItemRangeInserted(0, target.size)
            }
            target.isEmpty() -> {
                val count = old.size
                items = emptyList()
                notifyItemRangeRemoved(0, count)
            }
            isIdPrefix(old, target) -> {
                val start = old.size
                items = target
                notifyItemRangeInserted(start, target.size - start)
            }
            isIdPrefix(target, old) -> {
                val removeStart = target.size
                val removeCount = old.size - target.size
                items = target
                notifyItemRangeRemoved(removeStart, removeCount)
            }
            else -> {
                items = target
                notifyDataSetChanged()
            }
        }
        commitCallback?.run()
    }

    private fun isIdPrefix(shorter: List<OnlineTrack>, longer: List<OnlineTrack>): Boolean {
        if (shorter.size >= longer.size) return false
        for (i in shorter.indices) {
            if (shorter[i].id != longer[i].id) return false
        }
        return true
    }

    companion object {
        const val PAYLOAD_STATE_CHANGED = "payload_state_changed"
    }

    /**
     * Refreshes only download, loading, and playback indicator states across visible items without
     * reloading cover artwork or rebinding titles.
     */
    fun refreshTrackStates() {
        if (items.isNotEmpty()) {
            notifyItemRangeChanged(0, items.size, PAYLOAD_STATE_CHANGED)
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemOnlineTrackBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_STATE_CHANGED)) {
            holder.bindInteractiveState(items[position], position)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    inner class ViewHolder(
        private val binding: ItemOnlineTrackBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var boundCoverKey: String? = null

        fun bindInteractiveState(track: OnlineTrack, position: Int) {
            val context = binding.root.context
            val loading = isCurrentlyLoading(track.id)
            val playing = isCurrentlyPlaying(track)

            if (showRanking && !compactMode) {
                binding.onlineTrackRank.isVisible = true
                val rankNumber = position + 1
                binding.onlineTrackRank.text = rankNumber.toString()
                if (playing || rankNumber <= 5) {
                    binding.onlineTrackRank.setTextColor(
                        context.getAttrColorCompat(androidx.appcompat.R.attr.colorPrimary)
                    )
                    binding.onlineTrackRank.alpha = 1.0f
                } else {
                    binding.onlineTrackRank.setTextColor(
                        context.getAttrColorCompat(com.google.android.material.R.attr.colorOnSurfaceVariant)
                    )
                    binding.onlineTrackRank.alpha = 0.7f
                }
            } else {
                binding.onlineTrackRank.isVisible = false
            }

            if (playing) {
                binding.onlineTrackTitle.setTextColor(
                    context.getAttrColorCompat(androidx.appcompat.R.attr.colorPrimary)
                )
            } else {
                binding.onlineTrackTitle.setTextColor(
                    context.getAttrColorCompat(com.google.android.material.R.attr.colorOnSurface)
                )
            }

            binding.onlineTrackLoading.isVisible = loading
            binding.onlineTrackPlayingIndicator.setAnimatedPlayingIndicator(playing && !loading)
            binding.onlineTrackPlayButton.isVisible = !loading && !compactMode
            binding.onlineTrackPlayButton.setIconResource(if (playing) R.drawable.ic_pause_24 else R.drawable.ic_play_24)
            binding.onlineTrackPlayButton.contentDescription = context.getString(if (playing) R.string.btn_pause else R.string.btn_play)

            val downloaded = isDownloaded(track)
            val downloading = isDownloading(track)

            when {
                downloaded -> {
                    binding.onlineTrackDownloadButton.setIconResource(R.drawable.ic_check_24)
                    binding.onlineTrackDownloadButton.isEnabled = true
                    binding.onlineTrackDownloadButton.alpha = 1.0f
                    binding.onlineTrackDownloadButton.iconTint =
                        context.getAttrColorCompat(androidx.appcompat.R.attr.colorPrimary)
                    binding.onlineTrackDownloadButton.contentDescription =
                        context.getString(R.string.btn_downloaded)
                    binding.onlineTrackDownloadButton.setOnClickListener {
                        onDownloadClick(track)
                    }
                }
                downloading -> {
                    binding.onlineTrackDownloadButton.setIconResource(R.drawable.ic_download_24)
                    binding.onlineTrackDownloadButton.isEnabled = true
                    binding.onlineTrackDownloadButton.alpha = 0.5f
                    binding.onlineTrackDownloadButton.iconTint =
                        context.getAttrColorCompat(com.google.android.material.R.attr.colorOnSurfaceVariant)
                    binding.onlineTrackDownloadButton.contentDescription =
                        context.getString(R.string.btn_downloading)
                    binding.onlineTrackDownloadButton.setOnClickListener {
                        onDownloadClick(track)
                    }
                }
                else -> {
                    binding.onlineTrackDownloadButton.setIconResource(R.drawable.ic_download_24)
                    binding.onlineTrackDownloadButton.isEnabled = true
                    binding.onlineTrackDownloadButton.alpha = 1.0f
                    binding.onlineTrackDownloadButton.iconTint =
                        context.getAttrColorCompat(com.google.android.material.R.attr.colorOnSurfaceVariant)
                    binding.onlineTrackDownloadButton.contentDescription =
                        context.getString(R.string.btn_download)
                    binding.onlineTrackDownloadButton.setOnClickListener {
                        onDownloadClick(track)
                    }
                }
            }
        }

        fun bind(track: OnlineTrack, position: Int) {
            val context = binding.root.context
            binding.onlineTrackTitle.text = track.title

            val subtitle = if (track.durationMs > 0) {
                "${track.artist} • ${track.formattedDuration()}"
            } else {
                track.artist
            }
            binding.onlineTrackSubtitle.text = subtitle

            // Artwork loading via Coil (skip redundant reload when bound to identical cover)
            val localCoverFile = track.localCoverPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
                ?: StreamingCacheManager.findCachedCoverFile(track.id)
            val nextCoverKey = when {
                localCoverFile != null -> "file:${localCoverFile.absolutePath}:${localCoverFile.lastModified()}"
                !track.artworkUrl.isNullOrBlank() -> "url:${track.artworkUrl}"
                else -> "placeholder"
            }
            if (boundCoverKey != nextCoverKey) {
                boundCoverKey = nextCoverKey
                CoilUtils.dispose(binding.onlineTrackImage)
                binding.onlineTrackImage.setImageDrawable(null)
                if (localCoverFile != null) {
                    binding.onlineTrackImage.load(localCoverFile) {
                        crossfade(true)
                    }
                } else if (!track.artworkUrl.isNullOrBlank()) {
                    binding.onlineTrackImage.load(track.artworkUrl) {
                        crossfade(true)
                    }
                } else {
                    binding.onlineTrackImage.setImageResource(R.drawable.ic_song_24)
                }
            }

            binding.onlineTrackPlayButton.setOnClickListener {
                onPlayClick(track)
            }

            binding.onlineTrackMenuButton.isVisible = compactMode && onTrackMenuClick != null
            binding.onlineTrackMenuButton.setOnClickListener {
                onTrackMenuClick?.invoke(track)
            }

            val vPad = context.resources.getDimensionPixelSize(
                if (compactMode) R.dimen.spacing_small else R.dimen.spacing_mid_medium
            )
            binding.root.setPadding(
                binding.root.paddingLeft,
                vPad,
                binding.root.paddingRight,
                vPad
            )

            bindInteractiveState(track, position)

            binding.root.setOnClickListener {
                onPlayClick(track)
            }

            if (onTrackLongClick != null) {
                binding.root.setOnLongClickListener {
                    onTrackLongClick.invoke(track)
                    true
                }
            } else {
                binding.root.setOnLongClickListener(null)
            }
        }
    }
}

