package org.nov0caina.sounder.home.list

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import org.nov0caina.sounder.R
import org.nov0caina.sounder.databinding.ItemDownloadBinding
import org.nov0caina.sounder.list.SelectableListListener
import org.nov0caina.sounder.list.recycler.SongViewHolder
import org.nov0caina.sounder.online.download.DownloadItem
import org.nov0caina.sounder.online.download.DownloadStatus
import org.oxycblt.musikr.Song

class RecentDownloadsAdapter(
    private val songListener: SelectableListListener<Song>,
    private val onCancelDownload: (DownloadItem) -> Unit,
    private val onActiveDownloadClick: (DownloadItem) -> Unit,
) : ListAdapter<RecentDownloadEntry, RecyclerView.ViewHolder>(DiffCallback) {

    private var currentPlayingSong: Song? = null
    private var isPlaying: Boolean = false
    private var selectedSongs: Set<Song> = emptySet()

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is RecentDownloadEntry.Active -> VIEW_TYPE_ACTIVE_DOWNLOAD
            is RecentDownloadEntry.Completed -> SongViewHolder.VIEW_TYPE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_ACTIVE_DOWNLOAD -> {
                val binding = ItemDownloadBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
                ActiveDownloadViewHolder(binding)
            }
            SongViewHolder.VIEW_TYPE -> SongViewHolder.from(parent)
            else -> error("Invalid viewType $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val entry = getItem(position)) {
            is RecentDownloadEntry.Active -> {
                (holder as ActiveDownloadViewHolder).bind(entry.item, bindArtwork = true)
            }
            is RecentDownloadEntry.Completed -> {
                val songHolder = holder as SongViewHolder
                songHolder.bind(entry.song, songListener)
                val isActive = entry.song == currentPlayingSong
                songHolder.updatePlayingIndicator(isActive, isActive && isPlaying)
                songHolder.updateSelectionIndicator(selectedSongs.contains(entry.song))
            }
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
            return
        }

        val entry = getItem(position)
        when {
            entry is RecentDownloadEntry.Active && holder is ActiveDownloadViewHolder -> {
                holder.bind(entry.item, bindArtwork = false)
            }
            entry is RecentDownloadEntry.Completed && holder is SongViewHolder -> {
                val isActive = entry.song == currentPlayingSong
                holder.updatePlayingIndicator(isActive, isActive && isPlaying)
                holder.updateSelectionIndicator(selectedSongs.contains(entry.song))
            }
            else -> onBindViewHolder(holder, position)
        }
    }

    fun setPlaying(song: Song?, isPlaying: Boolean) {
        val oldSong = currentPlayingSong
        val oldPlaying = this.isPlaying
        this.currentPlayingSong = song
        this.isPlaying = isPlaying

        if (oldSong != song) {
            if (oldSong != null) {
                val oldIdx = currentList.indexOfFirst {
                    it is RecentDownloadEntry.Completed && it.song == oldSong
                }
                if (oldIdx > -1) notifyItemChanged(oldIdx, PAYLOAD_PLAYING_CHANGED)
            }
            if (song != null) {
                val newIdx = currentList.indexOfFirst {
                    it is RecentDownloadEntry.Completed && it.song == song
                }
                if (newIdx > -1) notifyItemChanged(newIdx, PAYLOAD_PLAYING_CHANGED)
            }
        } else if (oldPlaying != isPlaying && song != null) {
            val idx = currentList.indexOfFirst {
                it is RecentDownloadEntry.Completed && it.song == song
            }
            if (idx > -1) notifyItemChanged(idx, PAYLOAD_PLAYING_CHANGED)
        }
    }

    fun setSelected(selected: Set<Song>) {
        val oldSelected = selectedSongs
        if (oldSelected == selected) return
        selectedSongs = selected.toSet()

        for (i in currentList.indices) {
            val entry = currentList[i]
            if (entry is RecentDownloadEntry.Completed) {
                if (oldSelected.contains(entry.song) xor selectedSongs.contains(entry.song)) {
                    notifyItemChanged(i, PAYLOAD_SELECTION_CHANGED)
                }
            }
        }
    }

    inner class ActiveDownloadViewHolder(
        private val binding: ItemDownloadBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DownloadItem, bindArtwork: Boolean) {
            val context = binding.root.context
            binding.downloadItemTitle.text = item.title

            if (bindArtwork) {
                if (!item.artworkUrl.isNullOrBlank()) {
                    binding.downloadItemImage.load(item.artworkUrl) {
                        crossfade(true)
                    }
                } else {
                    binding.downloadItemImage.setImageResource(R.drawable.ic_download_24)
                }
            }

            when (item.status) {
                DownloadStatus.QUEUED -> {
                    binding.downloadItemSubtitle.text =
                        context.getString(R.string.download_status_queued, item.artist)
                    binding.downloadItemProgress.isVisible = true
                    binding.downloadItemProgress.isIndeterminate = true
                    binding.downloadItemCancel.isVisible = true
                }
                DownloadStatus.DOWNLOADING -> {
                    binding.downloadItemSubtitle.text = "${item.artist} • ${item.progress}%"
                    binding.downloadItemProgress.isVisible = true
                    binding.downloadItemProgress.isIndeterminate = false
                    binding.downloadItemProgress.progress = item.progress
                    binding.downloadItemCancel.isVisible = true
                }
                DownloadStatus.TAGGING -> {
                    binding.downloadItemSubtitle.text =
                        "${item.artist} • ${context.getString(R.string.btn_downloading)}"
                    binding.downloadItemProgress.isVisible = true
                    binding.downloadItemProgress.isIndeterminate = true
                    binding.downloadItemCancel.isVisible = false
                }
                DownloadStatus.FAILED -> {
                    binding.downloadItemSubtitle.text =
                        context.getString(R.string.download_failed, item.error ?: item.artist)
                    binding.downloadItemProgress.isVisible = false
                    binding.downloadItemCancel.isVisible = true
                }
                else -> {
                    binding.downloadItemSubtitle.text = item.artist
                    binding.downloadItemProgress.isVisible = false
                    binding.downloadItemCancel.isVisible = false
                }
            }

            binding.downloadItemCancel.setOnClickListener {
                onCancelDownload(item)
            }

            binding.root.setOnClickListener {
                onActiveDownloadClick(item)
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<RecentDownloadEntry>() {
        override fun areItemsTheSame(
            oldItem: RecentDownloadEntry,
            newItem: RecentDownloadEntry,
        ): Boolean {
            return when {
                oldItem is RecentDownloadEntry.Active && newItem is RecentDownloadEntry.Active ->
                    oldItem.item.trackId == newItem.item.trackId
                oldItem is RecentDownloadEntry.Completed && newItem is RecentDownloadEntry.Completed ->
                    oldItem.song.uid == newItem.song.uid
                else -> false
            }
        }

        override fun areContentsTheSame(
            oldItem: RecentDownloadEntry,
            newItem: RecentDownloadEntry,
        ): Boolean {
            return when {
                oldItem is RecentDownloadEntry.Active && newItem is RecentDownloadEntry.Active ->
                    oldItem.item == newItem.item
                oldItem is RecentDownloadEntry.Completed && newItem is RecentDownloadEntry.Completed ->
                    SongViewHolder.DIFF_CALLBACK.areContentsTheSame(oldItem.song, newItem.song)
                else -> false
            }
        }

        override fun getChangePayload(
            oldItem: RecentDownloadEntry,
            newItem: RecentDownloadEntry,
        ): Any? {
            if (oldItem is RecentDownloadEntry.Active && newItem is RecentDownloadEntry.Active) {
                if (oldItem.item.artworkUrl == newItem.item.artworkUrl) {
                    return PAYLOAD_PROGRESS_CHANGED
                }
            }
            return null
        }
    }

    companion object {
        const val VIEW_TYPE_ACTIVE_DOWNLOAD = 0xA0F0
        private val PAYLOAD_PLAYING_CHANGED = Any()
        private val PAYLOAD_SELECTION_CHANGED = Any()
        private val PAYLOAD_PROGRESS_CHANGED = Any()
    }
}
