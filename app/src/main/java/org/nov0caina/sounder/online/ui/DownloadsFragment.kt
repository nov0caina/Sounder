package org.nov0caina.sounder.online.ui

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.MaterialSharedAxis
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import org.nov0caina.sounder.R
import org.nov0caina.sounder.databinding.FragmentDownloadsBinding
import org.nov0caina.sounder.list.recycler.SwipeToQueueHelper
import org.nov0caina.sounder.music.MusicRepository
import org.nov0caina.sounder.music.resolve
import org.nov0caina.sounder.online.download.DownloadItem
import org.nov0caina.sounder.online.download.DownloadStatus
import org.nov0caina.sounder.online.model.OnlineSong
import org.nov0caina.sounder.online.model.OnlineTrack
import org.nov0caina.sounder.playback.PlaySong
import org.nov0caina.sounder.playback.PlaybackViewModel
import org.nov0caina.sounder.ui.ViewBindingFragment
import org.nov0caina.sounder.util.collect
import org.nov0caina.sounder.util.showToast

@AndroidEntryPoint
class DownloadsFragment : ViewBindingFragment<FragmentDownloadsBinding>() {

    @Inject lateinit var musicRepository: MusicRepository
    private val downloadsModel: DownloadsViewModel by viewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private var downloadsAdapter: DownloadsAdapter? = null
    private var downloadsTouchHelper: ItemTouchHelper? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.Z, false)
    }

    override fun onCreateBinding(inflater: LayoutInflater): FragmentDownloadsBinding =
        FragmentDownloadsBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentDownloadsBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        binding.downloadsToolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        downloadsAdapter = DownloadsAdapter(
            onCancelClick = { item ->
                downloadsModel.cancelDownload(item.trackId)
            },
            onItemClick = { item ->
                handleItemClick(binding, item)
            }
        )

        binding.downloadsRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = downloadsAdapter
        }

        downloadsTouchHelper = ItemTouchHelper(
            SwipeToQueueHelper(
                canSwipe = { viewHolder ->
                    val pos = viewHolder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        downloadsAdapter?.currentList?.getOrNull(pos)?.status == DownloadStatus.COMPLETED
                    } else false
                },
                onEnqueue = { viewHolder ->
                    val pos = viewHolder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        val item = downloadsAdapter?.currentList?.getOrNull(pos)
                        if (item != null && item.status == DownloadStatus.COMPLETED) {
                            val filePath = item.localFilePath
                            val localFile = filePath?.let { File(it) }?.takeIf { it.exists() }
                            val library = musicRepository.library
                            val matchingSong = library?.songs?.firstOrNull { song ->
                                (localFile != null && song.uri.path == localFile.absolutePath) ||
                                    (song.name.resolve(requireContext()).equals(item.title, ignoreCase = true) &&
                                        song.artists.any { it.name.resolve(requireContext()).equals(item.artist, ignoreCase = true) })
                            }
                            if (matchingSong != null) {
                                playbackModel.addToQueue(matchingSong)
                                requireContext().showToast(R.string.lng_queue_added)
                            } else if (localFile != null) {
                                val track = OnlineTrack(
                                    id = item.trackId,
                                    title = item.title,
                                    artist = item.artist,
                                    album = item.album,
                                    durationMs = item.durationMs,
                                    artworkUrl = item.artworkUrl,
                                )
                                val onlineSong = OnlineSong(track, Uri.fromFile(localFile))
                                playbackModel.addToQueue(onlineSong)
                                requireContext().showToast(R.string.lng_queue_added)
                            }
                        }
                    }
                }
            )
        ).also { it.attachToRecyclerView(binding.downloadsRecycler) }

        collect(downloadsModel.downloads) { items ->
            downloadsAdapter?.submitList(items)
            binding.downloadsEmptyState.isVisible = items.isEmpty()
        }
    }

    private fun handleItemClick(binding: FragmentDownloadsBinding, item: DownloadItem) {
        when (item.status) {
            DownloadStatus.COMPLETED -> {
                val filePath = item.localFilePath
                val localFile = filePath?.let { File(it) }?.takeIf { it.exists() }

                val library = musicRepository.library
                val matchingSong = library?.songs?.firstOrNull { song ->
                    (localFile != null && song.uri.path == localFile.absolutePath) ||
                        (song.name.resolve(requireContext()).equals(item.title, ignoreCase = true) &&
                            song.artists.any { it.name.resolve(requireContext()).equals(item.artist, ignoreCase = true) })
                }

                if (matchingSong != null) {
                    playbackModel.play(matchingSong, PlaySong.ByItself)
                } else if (localFile != null) {
                    val track = OnlineTrack(
                        id = item.trackId,
                        title = item.title,
                        artist = item.artist,
                        album = item.album,
                        durationMs = item.durationMs,
                        artworkUrl = item.artworkUrl,
                    )
                    val onlineSong = OnlineSong(track, Uri.fromFile(localFile))
                    playbackModel.play(onlineSong, PlaySong.ByItself)
                } else {
                    Snackbar.make(binding.root, R.string.err_local_file_not_found, Snackbar.LENGTH_SHORT).show()
                }
            }
            DownloadStatus.DOWNLOADING -> {
                Snackbar.make(
                    binding.root,
                    "${item.title} • ${item.progress}%",
                    Snackbar.LENGTH_SHORT
                ).show()
            }
            DownloadStatus.QUEUED, DownloadStatus.TAGGING -> {
                Snackbar.make(
                    binding.root,
                    getString(R.string.download_status_queued, item.title),
                    Snackbar.LENGTH_SHORT
                ).show()
            }
            DownloadStatus.FAILED, DownloadStatus.CANCELLED -> {
                downloadsModel.retryDownload(item)
                Snackbar.make(
                    binding.root,
                    R.string.download_retrying,
                    Snackbar.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onDestroyBinding(binding: FragmentDownloadsBinding) {
        super.onDestroyBinding(binding)
        downloadsTouchHelper?.attachToRecyclerView(null)
        downloadsTouchHelper = null
        downloadsAdapter = null
    }
}

