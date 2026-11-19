package org.nov0caina.sounder.home.list

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import org.nov0caina.sounder.R
import org.nov0caina.sounder.databinding.FragmentOfflineBackupListBinding
import org.nov0caina.sounder.list.ListViewModel
import org.nov0caina.sounder.list.recycler.SwipeToQueueHelper
import org.nov0caina.sounder.music.MusicViewModel
import org.nov0caina.sounder.online.model.OnlineSong
import org.nov0caina.sounder.online.model.OnlineTrack
import org.nov0caina.sounder.online.ui.OfflineBackupUiBinder
import org.nov0caina.sounder.online.ui.OnlineTrackAdapter
import org.nov0caina.sounder.playback.PlaySong
import org.nov0caina.sounder.playback.PlaybackViewModel
import org.nov0caina.sounder.ui.ViewBindingFragment
import org.nov0caina.sounder.util.collect
import org.nov0caina.sounder.util.collectImmediately
import org.nov0caina.sounder.util.showToast

/**
 * Persistent "Respaldo Musical" (Offline Backup) tab in Tu Biblioteca, displaying all
 * rotating cached streaming tracks, storage footprint in MB, and quick filter chips.
 */
@AndroidEntryPoint
class OfflineBackupListFragment : ViewBindingFragment<FragmentOfflineBackupListBinding>() {

    private val backupModel: OfflineBackupViewModel by viewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val listModel: ListViewModel by activityViewModels()
    private val musicModel: MusicViewModel by activityViewModels()

    private var trackAdapter: OnlineTrackAdapter? = null
    private var touchHelper: ItemTouchHelper? = null

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentOfflineBackupListBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentOfflineBackupListBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        val content = binding.offlineBackupContent
        content.offlineBackupModeBadge.isVisible = false
        content.offlineBackupRoot.id = R.id.home_offline_backup_recycler

        val adapter = OnlineTrackAdapter(
            onPlayClick = { track ->
                if (isTrackPlaying(track)) {
                    playbackModel.togglePlaying()
                } else {
                    backupModel.play(startTrack = track, shuffle = false, playbackModel = playbackModel)
                }
            },
            onDownloadClick = { track ->
                backupModel.downloadTrack(track)
                Snackbar.make(
                    requireView(),
                    getString(R.string.download_queued, track.title),
                    Snackbar.LENGTH_SHORT
                ).show()
                trackAdapter?.notifyDataSetChanged()
            },
            isDownloaded = { track -> backupModel.isDownloaded(track) },
            isDownloading = { track -> backupModel.isEnqueuedOrDownloading(track) },
            isCurrentlyLoading = { false },
            isCurrentlyPlaying = { track -> isTrackPlaying(track) },
            onTrackLongClick = { track ->
                val onlineSong = OnlineSong(track, Uri.parse("sounder://stream/${track.id}"))
                listModel.openMenu(R.menu.song, onlineSong, PlaySong.ByItself)
            },
        )
        trackAdapter = adapter

        content.offlineBackupRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = adapter
        }

        touchHelper = ItemTouchHelper(
            SwipeToQueueHelper(
                canSwipe = { true },
                onEnqueue = { viewHolder ->
                    val pos = viewHolder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        val track = trackAdapter?.currentList?.getOrNull(pos) ?: return@SwipeToQueueHelper
                        val song = OnlineSong(track, Uri.parse("sounder://stream/${track.id}"))
                        playbackModel.addToQueue(song)
                        requireContext().showToast(R.string.lng_queue_added)
                    }
                },
            )
        ).also {
            it.attachToRecyclerView(content.offlineBackupRecycler)
        }

        content.offlineBackupPlayAllBtn.setOnClickListener {
            backupModel.play(startTrack = null, shuffle = false, playbackModel = playbackModel)
        }
        content.offlineBackupShuffleBtn.setOnClickListener {
            backupModel.play(startTrack = null, shuffle = true, playbackModel = playbackModel)
        }

        OfflineBackupUiBinder.bindFilterChips(
            binding = content,
            onModeSelected = { mode -> backupModel.selectFilterMode(mode) }
        )

        collectImmediately(backupModel.cachedTracks, backupModel.cacheSizeBytes) { allTracks, bytes ->
            val hasTracks = allTracks.isNotEmpty()
            content.root.isVisible = hasTracks
            binding.offlineBackupEmpty.isInvisible = hasTracks
            val mb = (bytes / (1024L * 1024L)).toInt()
            content.offlineBackupSubtitle.text =
                getString(R.string.offline_backup_storage_summary, allTracks.size, mb)
            OfflineBackupUiBinder.renderFilterState(
                binding = content,
                allTracks = allTracks,
                state = backupModel.filterState.value,
                onValueSelected = { value -> backupModel.selectFilterValue(value) }
            )
        }

        collect(backupModel.filterState) { state ->
            OfflineBackupUiBinder.renderFilterState(
                binding = content,
                allTracks = backupModel.cachedTracks.value,
                state = state,
                onValueSelected = { value -> backupModel.selectFilterValue(value) }
            )
        }

        collectImmediately(backupModel.filteredTracks) { filtered ->
            trackAdapter?.submitList(filtered)
        }

        collect(backupModel.downloads) {
            trackAdapter?.notifyDataSetChanged()
        }

        collect(musicModel.indexingState) {
            trackAdapter?.notifyDataSetChanged()
        }

        collect(playbackModel.song) {
            trackAdapter?.notifyDataSetChanged()
        }

        collect(playbackModel.isPlaying) {
            trackAdapter?.notifyDataSetChanged()
        }
    }

    override fun onResume() {
        super.onResume()
        backupModel.refresh()
    }

    override fun onDestroyBinding(binding: FragmentOfflineBackupListBinding) {
        super.onDestroyBinding(binding)
        touchHelper?.attachToRecyclerView(null)
        touchHelper = null
        binding.offlineBackupContent.offlineBackupRecycler.adapter = null
        trackAdapter = null
    }

    private fun isTrackPlaying(track: OnlineTrack): Boolean {
        val current = playbackModel.song.value as? OnlineSong ?: return false
        return current.onlineTrack.id == track.id && playbackModel.isPlaying.value
    }
}
