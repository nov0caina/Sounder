package org.nov0caina.sounder.online.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import coil3.util.CoilUtils
import org.nov0caina.sounder.R
import org.nov0caina.sounder.databinding.ItemQuickPicksColumnBinding
import org.nov0caina.sounder.online.model.OnlineTrack
import org.nov0caina.sounder.ui.RippleFixMaterialButton
import org.nov0caina.sounder.ui.setAnimatedPlayingIndicator
import org.nov0caina.sounder.util.getAttrColorCompat

class QuickPicksColumnAdapter(
    private val onPlayClick: (OnlineTrack) -> Unit,
    private val onDownloadClick: (OnlineTrack) -> Unit,
    private val isDownloaded: (OnlineTrack) -> Boolean,
    private val isDownloading: (OnlineTrack) -> Boolean,
    private val isCurrentlyLoading: (String) -> Boolean,
    private val isCurrentlyPlaying: (OnlineTrack) -> Boolean = { false },
    private val onTrackLongClick: ((OnlineTrack, View) -> Boolean)? = null
) : ListAdapter<List<OnlineTrack>, QuickPicksColumnAdapter.ViewHolder>(DiffCallback) {

    fun refreshTrackStates() {
        if (itemCount > 0) {
            notifyItemRangeChanged(0, itemCount, PAYLOAD_STATE_CHANGED)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemQuickPicksColumnBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_STATE_CHANGED)) {
            holder.bindInteractiveStates(getItem(position))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    inner class ViewHolder(
        private val binding: ItemQuickPicksColumnBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val boundCovers = arrayOfNulls<String>(3)

        fun bindInteractiveStates(tracks: List<OnlineTrack>) {
            bindSlotState(tracks.getOrNull(0), binding.track1Root, binding.track1PlayingIndicator, binding.track1Loading, binding.track1PlayButton, binding.track1DownloadButton)
            bindSlotState(tracks.getOrNull(1), binding.track2Root, binding.track2PlayingIndicator, binding.track2Loading, binding.track2PlayButton, binding.track2DownloadButton)
            bindSlotState(tracks.getOrNull(2), binding.track3Root, binding.track3PlayingIndicator, binding.track3Loading, binding.track3PlayButton, binding.track3DownloadButton)
        }

        private fun bindSlotState(
            track: OnlineTrack?,
            root: View,
            playingIndicator: ImageView,
            loading: View,
            playBtn: RippleFixMaterialButton,
            downloadBtn: RippleFixMaterialButton,
        ) {
            if (track == null) {
                playingIndicator.setAnimatedPlayingIndicator(false)
                return
            }
            val isLoading = isCurrentlyLoading(track.id)
            val isPlaying = isCurrentlyPlaying(track)
            playingIndicator.setAnimatedPlayingIndicator(isPlaying && !isLoading)
            loading.isVisible = isLoading
            playBtn.isVisible = !isLoading
            playBtn.setIconResource(if (isPlaying) R.drawable.ic_pause_24 else R.drawable.ic_play_24)
            playBtn.contentDescription = root.context.getString(if (isPlaying) R.string.btn_pause else R.string.btn_play)

            val context = root.context
            val downloaded = isDownloaded(track)
            val downloading = isDownloading(track)
            when {
                downloaded -> {
                    downloadBtn.setIconResource(R.drawable.ic_check_24)
                    downloadBtn.isEnabled = true
                    downloadBtn.alpha = 1.0f
                    downloadBtn.iconTint =
                        context.getAttrColorCompat(androidx.appcompat.R.attr.colorPrimary)
                    downloadBtn.contentDescription = context.getString(R.string.btn_downloaded)
                    downloadBtn.setOnClickListener {
                        onDownloadClick(track)
                    }
                }
                downloading -> {
                    downloadBtn.setIconResource(R.drawable.ic_download_24)
                    downloadBtn.isEnabled = true
                    downloadBtn.alpha = 0.5f
                    downloadBtn.iconTint =
                        context.getAttrColorCompat(com.google.android.material.R.attr.colorOnSurfaceVariant)
                    downloadBtn.contentDescription = context.getString(R.string.btn_downloading)
                    downloadBtn.setOnClickListener {
                        onDownloadClick(track)
                    }
                }
                else -> {
                    downloadBtn.setIconResource(R.drawable.ic_download_24)
                    downloadBtn.isEnabled = true
                    downloadBtn.alpha = 1.0f
                    downloadBtn.iconTint =
                        context.getAttrColorCompat(com.google.android.material.R.attr.colorOnSurfaceVariant)
                    downloadBtn.contentDescription = context.getString(R.string.btn_download)
                    downloadBtn.setOnClickListener {
                        onDownloadClick(track)
                    }
                }
            }
        }

        fun bind(tracks: List<OnlineTrack>) {
            bindSlot(0, tracks.getOrNull(0), binding.track1Root, binding.track1Image, binding.track1PlayingIndicator, binding.track1Title, binding.track1Subtitle, binding.track1Loading, binding.track1PlayButton, binding.track1DownloadButton)
            bindSlot(1, tracks.getOrNull(1), binding.track2Root, binding.track2Image, binding.track2PlayingIndicator, binding.track2Title, binding.track2Subtitle, binding.track2Loading, binding.track2PlayButton, binding.track2DownloadButton)
            bindSlot(2, tracks.getOrNull(2), binding.track3Root, binding.track3Image, binding.track3PlayingIndicator, binding.track3Title, binding.track3Subtitle, binding.track3Loading, binding.track3PlayButton, binding.track3DownloadButton)
        }

        private fun bindSlot(
            slotIdx: Int,
            track: OnlineTrack?,
            root: View,
            image: ImageView,
            playingIndicator: ImageView,
            title: TextView,
            subtitle: TextView,
            loading: View,
            playBtn: RippleFixMaterialButton,
            downloadBtn: RippleFixMaterialButton
        ) {
            if (track == null) {
                playingIndicator.setAnimatedPlayingIndicator(false)
                root.isVisible = false
                boundCovers[slotIdx] = null
                return
            }
            root.isVisible = true

            title.text = track.title
            val subtitleText = if (track.durationMs > 0) {
                "${track.artist} • ${track.formattedDuration()}"
            } else {
                track.artist
            }
            subtitle.text = subtitleText

            val localCoverFile = track.localCoverPath?.let { java.io.File(it) }
                ?.takeIf { it.exists() && it.length() > 0L }
                ?: org.nov0caina.sounder.online.cache.StreamingCacheManager.findCachedCoverFile(track.id)
            val nextKey = when {
                localCoverFile != null -> "file:${localCoverFile.absolutePath}:${localCoverFile.lastModified()}"
                !track.artworkUrl.isNullOrBlank() -> "url:${track.artworkUrl}"
                else -> "placeholder"
            }
            if (boundCovers[slotIdx] != nextKey) {
                boundCovers[slotIdx] = nextKey
                CoilUtils.dispose(image)
                image.setImageDrawable(null)
                if (localCoverFile != null) {
                    image.load(localCoverFile) {
                        crossfade(true)
                    }
                } else if (!track.artworkUrl.isNullOrBlank()) {
                    image.load(track.artworkUrl) {
                        crossfade(true)
                    }
                } else {
                    image.setImageResource(R.drawable.ic_song_24)
                }
            }

            playBtn.setOnClickListener {
                onPlayClick(track)
            }

            bindSlotState(track, root, playingIndicator, loading, playBtn, downloadBtn)

            root.setOnClickListener {
                onPlayClick(track)
            }

            if (onTrackLongClick != null) {
                root.setOnLongClickListener {
                    onTrackLongClick.invoke(track, it)
                }
            } else {
                root.setOnLongClickListener(null)
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<List<OnlineTrack>>() {
        const val PAYLOAD_STATE_CHANGED = "payload_state_changed"
        override fun areItemsTheSame(
            oldItem: List<OnlineTrack>,
            newItem: List<OnlineTrack>
        ): Boolean {
            val oldIds = oldItem.map { it.id }
            val newIds = newItem.map { it.id }
            return oldIds == newIds
        }

        override fun areContentsTheSame(
            oldItem: List<OnlineTrack>,
            newItem: List<OnlineTrack>
        ): Boolean {
            return oldItem == newItem
        }
    }
}
