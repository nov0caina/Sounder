package org.nov0caina.sounder.online.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.nov0caina.sounder.R
import org.nov0caina.sounder.databinding.ItemAlbumTrackBinding
import org.nov0caina.sounder.online.model.OnlineTrack
import org.nov0caina.sounder.util.getAttrColorCompat

/**
 * Editorial tracklist adapter for album pages displaying numbered index rows (01, 02...),
 * title, duration, play/pause controls, and active playback highlighting without redundant covers.
 */
class OnlineAlbumTrackAdapter(
    private val onPlayClick: (OnlineTrack) -> Unit,
    private val onDownloadClick: (OnlineTrack) -> Unit,
    private val isDownloaded: (OnlineTrack) -> Boolean,
    private val isDownloading: (OnlineTrack) -> Boolean,
    private val isCurrentlyLoading: (String) -> Boolean,
    private val isCurrentlyPlaying: (OnlineTrack) -> Boolean = { false },
    private val albumArtist: String? = null,
    private val onTrackLongClick: ((OnlineTrack) -> Unit)? = null
) : ListAdapter<OnlineTrack, OnlineAlbumTrackAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAlbumTrackBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    inner class ViewHolder(
        private val binding: ItemAlbumTrackBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(track: OnlineTrack, position: Int) {
            val context = binding.root.context
            val loading = isCurrentlyLoading(track.id)
            val playing = isCurrentlyPlaying(track)

            // Numbered track index: 01, 02, etc.
            val trackNumber = String.format("%02d", position + 1)
            binding.albumTrackNumber.text = trackNumber

            if (playing) {
                binding.albumTrackNumber.setTextColor(
                    context.getAttrColorCompat(androidx.appcompat.R.attr.colorPrimary)
                )
                binding.albumTrackTitle.setTextColor(
                    context.getAttrColorCompat(androidx.appcompat.R.attr.colorPrimary)
                )
            } else {
                binding.albumTrackNumber.setTextColor(
                    context.getAttrColorCompat(com.google.android.material.R.attr.colorOnSurfaceVariant)
                )
                binding.albumTrackTitle.setTextColor(
                    context.getAttrColorCompat(com.google.android.material.R.attr.colorOnSurface)
                )
            }

            binding.albumTrackTitle.text = track.title

            // Subtitle: featured artist if different + duration
            val durationStr = track.formattedDuration()
            val hasDistinctArtist = !albumArtist.isNullOrBlank() &&
                track.artist.isNotBlank() &&
                !track.artist.equals(albumArtist, ignoreCase = true)

            val subtitleText = when {
                hasDistinctArtist && durationStr.isNotBlank() -> "${track.artist} • $durationStr"
                hasDistinctArtist -> track.artist
                durationStr.isNotBlank() -> durationStr
                else -> ""
            }
            binding.albumTrackSubtitle.isVisible = subtitleText.isNotBlank()
            binding.albumTrackSubtitle.text = subtitleText

            // Loading & Play/Pause state
            binding.albumTrackLoading.isVisible = loading
            binding.albumTrackPlayButton.isVisible = !loading
            binding.albumTrackPlayButton.setIconResource(
                if (playing) R.drawable.ic_pause_24 else R.drawable.ic_play_24
            )
            binding.albumTrackPlayButton.contentDescription = context.getString(
                if (playing) R.string.btn_pause else R.string.btn_play
            )

            binding.albumTrackPlayButton.setOnClickListener {
                onPlayClick(track)
            }

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

            // Download button state
            val downloaded = isDownloaded(track)
            val downloading = isDownloading(track)

            when {
                downloaded -> {
                    binding.albumTrackDownloadButton.setIconResource(R.drawable.ic_check_24)
                    binding.albumTrackDownloadButton.isEnabled = false
                    binding.albumTrackDownloadButton.alpha = 0.5f
                }
                downloading -> {
                    binding.albumTrackDownloadButton.setIconResource(R.drawable.ic_download_24)
                    binding.albumTrackDownloadButton.isEnabled = false
                    binding.albumTrackDownloadButton.alpha = 0.4f
                    binding.albumTrackDownloadButton.setOnClickListener(null)
                }
                else -> {
                    binding.albumTrackDownloadButton.setIconResource(R.drawable.ic_download_24)
                    binding.albumTrackDownloadButton.isEnabled = true
                    binding.albumTrackDownloadButton.alpha = 1.0f
                    binding.albumTrackDownloadButton.setOnClickListener {
                        onDownloadClick(track)
                    }
                }
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<OnlineTrack>() {
        override fun areItemsTheSame(oldItem: OnlineTrack, newItem: OnlineTrack): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: OnlineTrack, newItem: OnlineTrack): Boolean =
            oldItem == newItem
    }
}
