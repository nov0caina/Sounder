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

class QuickPicksColumnAdapter(
    private val onPlayClick: (OnlineTrack) -> Unit,
    private val onDownloadClick: (OnlineTrack) -> Unit,
    private val isDownloaded: (OnlineTrack) -> Boolean,
    private val isDownloading: (OnlineTrack) -> Boolean,
    private val isCurrentlyLoading: (String) -> Boolean,
    private val isCurrentlyPlaying: (OnlineTrack) -> Boolean = { false },
    private val onTrackLongClick: ((OnlineTrack, View) -> Boolean)? = null
) : ListAdapter<List<OnlineTrack>, QuickPicksColumnAdapter.ViewHolder>(DiffCallback) {

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

    inner class ViewHolder(
        private val binding: ItemQuickPicksColumnBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(tracks: List<OnlineTrack>) {
            val t1 = tracks.getOrNull(0)
            val t2 = tracks.getOrNull(1)
            val t3 = tracks.getOrNull(2)

            bindSlot(
                track = t1,
                root = binding.track1Root,
                image = binding.track1Image,
                title = binding.track1Title,
                subtitle = binding.track1Subtitle,
                loading = binding.track1Loading,
                playBtn = binding.track1PlayButton,
                downloadBtn = binding.track1DownloadButton
            )

            bindSlot(
                track = t2,
                root = binding.track2Root,
                image = binding.track2Image,
                title = binding.track2Title,
                subtitle = binding.track2Subtitle,
                loading = binding.track2Loading,
                playBtn = binding.track2PlayButton,
                downloadBtn = binding.track2DownloadButton
            )

            bindSlot(
                track = t3,
                root = binding.track3Root,
                image = binding.track3Image,
                title = binding.track3Title,
                subtitle = binding.track3Subtitle,
                loading = binding.track3Loading,
                playBtn = binding.track3PlayButton,
                downloadBtn = binding.track3DownloadButton
            )
        }

        private fun bindSlot(
            track: OnlineTrack?,
            root: View,
            image: ImageView,
            title: TextView,
            subtitle: TextView,
            loading: View,
            playBtn: RippleFixMaterialButton,
            downloadBtn: RippleFixMaterialButton
        ) {
            if (track == null) {
                root.isVisible = false
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

            CoilUtils.dispose(image)
            image.setImageDrawable(null)
            if (!track.artworkUrl.isNullOrBlank()) {
                image.load(track.artworkUrl) {
                    crossfade(true)
                }
            } else {
                image.setImageResource(R.drawable.ic_song_24)
            }

            val isLoading = isCurrentlyLoading(track.id)
            val isPlaying = isCurrentlyPlaying(track)
            loading.isVisible = isLoading
            playBtn.isVisible = !isLoading
            playBtn.setIconResource(if (isPlaying) R.drawable.ic_pause_24 else R.drawable.ic_play_24)
            playBtn.contentDescription = root.context.getString(if (isPlaying) R.string.btn_pause else R.string.btn_play)
            playBtn.setOnClickListener {
                onPlayClick(track)
            }

            val downloaded = isDownloaded(track)
            val downloading = isDownloading(track)

            when {
                downloaded -> {
                    downloadBtn.setIconResource(R.drawable.ic_check_24)
                    downloadBtn.isEnabled = false
                    downloadBtn.alpha = 0.6f
                    downloadBtn.setOnClickListener(null)
                }
                downloading -> {
                    downloadBtn.setIconResource(R.drawable.ic_download_24)
                    downloadBtn.isEnabled = false
                    downloadBtn.alpha = 0.4f
                    downloadBtn.setOnClickListener(null)
                }
                else -> {
                    downloadBtn.setIconResource(R.drawable.ic_download_24)
                    downloadBtn.isEnabled = true
                    downloadBtn.alpha = 1.0f
                    downloadBtn.setOnClickListener {
                        onDownloadClick(track)
                    }
                }
            }

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

    private object DiffCallback : DiffUtil.ItemCallback<List<OnlineTrack>>() {
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
