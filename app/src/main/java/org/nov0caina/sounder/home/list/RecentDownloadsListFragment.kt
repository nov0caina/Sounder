package org.nov0caina.sounder.home.list

import android.os.Bundle
import android.view.LayoutInflater
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import java.util.Calendar
import org.nov0caina.sounder.R
import org.nov0caina.sounder.databinding.FragmentHomeListBinding
import org.nov0caina.sounder.home.HomeViewModel
import org.nov0caina.sounder.list.ListFragment
import org.nov0caina.sounder.list.ListViewModel
import org.nov0caina.sounder.list.recycler.FastScrollRecyclerView
import org.nov0caina.sounder.list.recycler.SongViewHolder
import org.nov0caina.sounder.list.recycler.SwipeToQueueHelper
import org.nov0caina.sounder.music.MusicViewModel
import org.nov0caina.sounder.online.download.DownloadItem
import org.nov0caina.sounder.online.download.DownloadStatus
import org.nov0caina.sounder.playback.PlaybackViewModel
import org.nov0caina.sounder.util.collectImmediately
import org.nov0caina.sounder.util.showToast
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song

/**
 * A [ListFragment] in Tu Biblioteca that displays active/queued downloads at the top
 * and completed downloads below sorted chronologically from newest to oldest.
 */
@AndroidEntryPoint
class RecentDownloadsListFragment :
    ListFragment<Song, FragmentHomeListBinding>(),
    FastScrollRecyclerView.PopupProvider,
    FastScrollRecyclerView.Listener {

    private val homeModel: HomeViewModel by activityViewModels()
    private val recentDownloadsModel: RecentDownloadsViewModel by viewModels()
    override val listModel: ListViewModel by activityViewModels()
    override val musicModel: MusicViewModel by activityViewModels()
    override val playbackModel: PlaybackViewModel by activityViewModels()

    private var downloadsAdapter: RecentDownloadsAdapter? = null
    private var touchHelper: ItemTouchHelper? = null

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentHomeListBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentHomeListBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        val adapter = RecentDownloadsAdapter(
            songListener = this,
            onCancelDownload = { item ->
                recentDownloadsModel.cancelDownload(item.trackId)
            },
            onActiveDownloadClick = { item ->
                handleActiveDownloadClick(binding, item)
            },
        )
        downloadsAdapter = adapter

        binding.homeRecycler.apply {
            id = R.id.home_downloads_recycler
            this.adapter = adapter
            popupProvider = this@RecentDownloadsListFragment
            listener = this@RecentDownloadsListFragment
        }

        touchHelper =
            ItemTouchHelper(
                SwipeToQueueHelper(
                    canSwipe = { viewHolder ->
                        listModel.selected.value.isEmpty() && viewHolder is SongViewHolder
                    },
                    onEnqueue = { viewHolder ->
                        val pos = viewHolder.bindingAdapterPosition
                        if (pos != RecyclerView.NO_POSITION) {
                            val entry = downloadsAdapter?.currentList?.getOrNull(pos)
                            if (entry is RecentDownloadEntry.Completed) {
                                playbackModel.addToQueue(entry.song)
                                requireContext().showToast(R.string.lng_queue_added)
                            }
                        }
                    },
                )
            ).also {
                it.attachToRecyclerView(binding.homeRecycler)
            }

        binding.homeNoMusicPlaceholder.apply {
            setImageResource(R.drawable.ic_download_24)
            contentDescription = getString(R.string.lbl_recent_downloads)
        }
        binding.homeNoMusicMsg.text = getString(R.string.lng_empty_downloads)
        binding.homeNoMusicAction.isVisible = false

        collectImmediately(recentDownloadsModel.entries, ::updateEntries)
        collectImmediately(listModel.selected, ::updateSelection)
        collectImmediately(
            playbackModel.song,
            playbackModel.parent,
            playbackModel.isPlaying,
            ::updatePlayback,
        )
    }

    override fun onResume() {
        super.onResume()
        recentDownloadsModel.refreshCompletedSongs()
    }

    override fun onDestroyBinding(binding: FragmentHomeListBinding) {
        super.onDestroyBinding(binding)
        touchHelper?.attachToRecyclerView(null)
        touchHelper = null
        downloadsAdapter = null
        binding.homeRecycler.apply {
            adapter = null
            popupProvider = null
            listener = null
        }
    }

    override fun getPopupData(pos: Int): FastScrollRecyclerView.PopupProvider.PopupData? {
        val entry = downloadsAdapter?.currentList?.getOrNull(pos) ?: return null
        return when (entry) {
            is RecentDownloadEntry.Active -> null
            is RecentDownloadEntry.Completed -> {
                val calendar = Calendar.getInstance()
                calendar.timeInMillis = entry.song.addedMs
                FastScrollRecyclerView.PopupProvider.PopupData(
                    getString(R.string.fmt_number, calendar.get(Calendar.YEAR))
                )
            }
        }
    }

    override fun onFastScrollingChanged(isFastScrolling: Boolean) {
        homeModel.setFastScrolling(isFastScrolling)
    }

    override fun onRealClick(item: Song) {
        playbackModel.play(item, homeModel.playWith)
    }

    override fun onItemLongClick(item: Song): Boolean {
        if (listModel.selected.value.isNotEmpty()) {
            return false
        }
        onOpenMenu(item)
        return true
    }

    override fun onOpenMenu(item: Song) {
        listModel.openMenu(R.menu.song, item, homeModel.playWith)
    }

    private fun handleActiveDownloadClick(binding: FragmentHomeListBinding, item: DownloadItem) {
        when (item.status) {
            DownloadStatus.DOWNLOADING -> {
                Snackbar.make(
                    binding.root,
                    "${item.title} • ${item.progress}%",
                    Snackbar.LENGTH_SHORT,
                ).show()
            }
            DownloadStatus.QUEUED, DownloadStatus.TAGGING -> {
                Snackbar.make(
                    binding.root,
                    getString(R.string.download_status_queued, item.title),
                    Snackbar.LENGTH_SHORT,
                ).show()
            }
            DownloadStatus.FAILED, DownloadStatus.CANCELLED -> {
                recentDownloadsModel.retryDownload(item)
                Snackbar.make(
                    binding.root,
                    R.string.download_retrying,
                    Snackbar.LENGTH_SHORT,
                ).show()
            }
            else -> {}
        }
    }

    private fun updateEntries(entries: List<RecentDownloadEntry>) {
        val binding = requireBinding()
        downloadsAdapter?.submitList(entries)
        val isEmpty = entries.isEmpty()
        binding.homeRecycler.isInvisible = isEmpty
        binding.homeNoMusic.isInvisible = !isEmpty
        binding.homeNoMusicAction.isVisible = false
    }

    private fun updateSelection(selection: List<Music>) {
        downloadsAdapter?.setSelected(selection.filterIsInstanceTo(mutableSetOf()))
    }

    private fun updatePlayback(song: Song?, parent: MusicParent?, isPlaying: Boolean) {
        downloadsAdapter?.setPlaying(song.takeIf { parent == null }, isPlaying)
    }
}
