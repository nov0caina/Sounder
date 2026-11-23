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
import org.nov0caina.sounder.databinding.ItemOnlineTrackTopPickBinding
import org.nov0caina.sounder.online.cache.StreamingCacheManager
import org.nov0caina.sounder.online.model.OnlineTrack
import org.nov0caina.sounder.util.getAttrColorCompat

class OnlineTopPickAdapter(
    private val onPlayClick: (OnlineTrack) -> Unit,
    private val onDownloadClick: (OnlineTrack) -> Unit,
    private val onMenuClick: (OnlineTrack) -> Unit,
    private val isDownloaded: (OnlineTrack) -> Boolean,
    private val isDownloading: (OnlineTrack) -> Boolean,
    private val isCurrentlyLoading: (String) -> Boolean,
    private val isCurrentlyPlaying: (OnlineTrack) -> Boolean = { false },
) : RecyclerView.Adapter<OnlineTopPickAdapter.ViewHolder>() {

    private var items: List<OnlineTrack> = emptyList()

    fun submitList(newList: List<OnlineTrack>?) {
        val target = newList ?: emptyList()
        if (items === target) return
        if (items == target) return
        items = target
        notifyDataSetChanged()
    }

    companion object {
        const val PAYLOAD_STATE_CHANGED = "payload_state_changed"
    }

    fun refreshTrackStates() {
        if (items.isNotEmpty()) {
            notifyItemRangeChanged(0, items.size, PAYLOAD_STATE_CHANGED)
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemOnlineTrackTopPickBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_STATE_CHANGED)) {
            holder.bindInteractiveState(items[position])
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    inner class ViewHolder(
        private val binding: ItemOnlineTrackTopPickBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var boundCoverKey: String? = null

        fun bindInteractiveState(track: OnlineTrack) {
            val context = binding.root.context
            val loading = isCurrentlyLoading(track.id)
            val playing = isCurrentlyPlaying(track)

            binding.topPickTitle.setTextColor(
                context.getAttrColorCompat(
                    if (playing) androidx.appcompat.R.attr.colorPrimary
                    else com.google.android.material.R.attr.colorOnSurface
                )
            )
            binding.topPickLoading.isVisible = loading

            val downloaded = isDownloaded(track)
            val downloading = isDownloading(track)
            when {
                downloaded -> {
                    binding.topPickDownloadButton.setIconResource(R.drawable.ic_check_24)
                    binding.topPickDownloadButton.isEnabled = true
                    binding.topPickDownloadButton.alpha = 1.0f
                    binding.topPickDownloadButton.iconTint =
                        context.getAttrColorCompat(androidx.appcompat.R.attr.colorPrimary)
                    binding.topPickDownloadButton.contentDescription =
                        context.getString(R.string.btn_downloaded)
                    binding.topPickDownloadButton.setOnClickListener { onDownloadClick(track) }
                }
                downloading -> {
                    binding.topPickDownloadButton.setIconResource(R.drawable.ic_download_24)
                    binding.topPickDownloadButton.isEnabled = true
                    binding.topPickDownloadButton.alpha = 0.5f
                    binding.topPickDownloadButton.iconTint =
                        context.getAttrColorCompat(com.google.android.material.R.attr.colorOnSurfaceVariant)
                    binding.topPickDownloadButton.contentDescription =
                        context.getString(R.string.btn_downloading)
                    binding.topPickDownloadButton.setOnClickListener { onDownloadClick(track) }
                }
                else -> {
                    binding.topPickDownloadButton.setIconResource(R.drawable.ic_download_24)
                    binding.topPickDownloadButton.isEnabled = true
                    binding.topPickDownloadButton.alpha = 1.0f
                    binding.topPickDownloadButton.iconTint =
                        context.getAttrColorCompat(com.google.android.material.R.attr.colorOnSurfaceVariant)
                    binding.topPickDownloadButton.contentDescription =
                        context.getString(R.string.btn_download)
                    binding.topPickDownloadButton.setOnClickListener { onDownloadClick(track) }
                }
            }
        }

        fun bind(track: OnlineTrack) {
            binding.topPickTitle.text = track.title
            binding.topPickArtist.text = track.artist
            binding.topPickDuration.isVisible = track.durationMs > 0
            binding.topPickDuration.text = track.formattedDuration()

            val localCoverFile = track.localCoverPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
                ?: StreamingCacheManager.findCachedCoverFile(track.id)
            val nextCoverKey = when {
                localCoverFile != null -> "file:${localCoverFile.absolutePath}:${localCoverFile.lastModified()}"
                !track.artworkUrl.isNullOrBlank() -> "url:${track.artworkUrl}"
                else -> "placeholder"
            }
            if (boundCoverKey != nextCoverKey) {
                boundCoverKey = nextCoverKey
                CoilUtils.dispose(binding.topPickImage)
                binding.topPickImage.setImageDrawable(null)
                if (localCoverFile != null) {
                    binding.topPickImage.load(localCoverFile) { crossfade(true) }
                    val key = "${localCoverFile.absolutePath}_${localCoverFile.lastModified()}"
                    binding.topPickAmbientGlow.bindData(localCoverFile, key)
                } else if (!track.artworkUrl.isNullOrBlank()) {
                    binding.topPickImage.load(track.artworkUrl) { crossfade(true) }
                    binding.topPickAmbientGlow.bindData(track.artworkUrl, track.artworkUrl)
                } else {
                    binding.topPickImage.setImageResource(R.drawable.ic_song_24)
                    binding.topPickAmbientGlow.clearGlow()
                }
            }

            bindInteractiveState(track)

            binding.topPickMenuButton.setOnClickListener { onMenuClick(track) }
            binding.root.setOnClickListener { onPlayClick(track) }
            binding.root.setOnLongClickListener {
                onMenuClick(track)
                true
            }
        }
    }
}
