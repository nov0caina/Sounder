package org.nov0caina.sounder.online.lyrics

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nov0caina.sounder.R
import org.nov0caina.sounder.databinding.DialogLyricsBinding
import org.nov0caina.sounder.music.resolve
import org.nov0caina.sounder.music.resolveNames
import org.nov0caina.sounder.online.model.LyricsResult
import org.nov0caina.sounder.playback.PlaybackViewModel
import com.google.android.material.bottomsheet.BackportBottomSheetBehavior
import com.google.android.material.bottomsheet.BackportBottomSheetDialog
import org.nov0caina.sounder.ui.ViewBindingBottomSheetDialogFragment
import org.nov0caina.sounder.util.NetworkUtil
import org.nov0caina.sounder.util.StorageVolumeHelper
import org.nov0caina.sounder.util.collect
import timber.log.Timber as L

@AndroidEntryPoint
class LyricsBottomSheetDialogFragment : ViewBindingBottomSheetDialogFragment<DialogLyricsBinding>() {

    @Inject lateinit var lrcLibClient: LrcLibClient

    private val playbackModel: PlaybackViewModel by activityViewModels()
    private var lyricLineAdapter: LyricLineAdapter? = null
    private var currentLyricsResult: LyricsResult? = null
    private var isAutoScrollEnabled: Boolean = true

    override fun onCreateBinding(inflater: LayoutInflater): DialogLyricsBinding =
        DialogLyricsBinding.inflate(inflater)

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BackportBottomSheetDialog ?: return
        dialog.behavior.state = BackportBottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
    }

    override fun onBindingCreated(binding: DialogLyricsBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        binding.lyricsAutoscrollToggle.apply {
            isChecked = isAutoScrollEnabled
            setOnCheckedChangeListener { _, isChecked ->
                isAutoScrollEnabled = isChecked
                if (isChecked) {
                    val result = currentLyricsResult
                    if (result?.hasSyncedLyrics == true) {
                        val currentMs = playbackModel.positionDs.value * 100
                        val activeIndex = result.syncedLyrics.indexOfLast { it.timeMs <= currentMs }
                        if (activeIndex >= 0) {
                            scrollToActivePosition(activeIndex, smooth = true)
                        }
                    }
                }
            }
        }

        lyricLineAdapter = LyricLineAdapter { position, line ->
            // Tap-to-seek: seek player directly to the selected line's timestamp (in deciseconds)
            val targetDs = line.timeMs / 100
            playbackModel.seekTo(targetDs)
            lyricLineAdapter?.setActiveLine(position)
            if (isAutoScrollEnabled) {
                scrollToActivePosition(position, smooth = true)
            }
        }

        binding.lyricsRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = lyricLineAdapter
            doOnLayout {
                val active = lyricLineAdapter?.activeIndex ?: -1
                if (active >= 0 && isAutoScrollEnabled) {
                    scrollToActivePosition(active, smooth = false)
                }
            }
        }

        collect(playbackModel.song) { song ->
            if (song == null) {
                dismiss()
                return@collect
            }

            val context = requireContext()
            val rawTitle = song.name.resolve(context)
            val rawArtist = song.artists.resolveNames(context)
            val rawAlbum = song.album.name.resolve(context)
            val durationSec = (song.durationMs / 1000).toInt()

            val cleanTitle = LyricsSanitizer.cleanTitle(rawTitle, rawArtist)
            val cleanArtist = LyricsSanitizer.cleanArtist(rawArtist)

            binding.lyricsSongTitle.text = rawTitle
            binding.lyricsSongArtist.text = rawArtist

            loadLyrics(context.applicationContext, cleanTitle, cleanArtist, rawAlbum, durationSec, song.uri.path)
        }

        collect(playbackModel.positionDs) { positionDs ->
            val result = currentLyricsResult ?: return@collect
            if (result.hasSyncedLyrics) {
                val currentMs = positionDs * 100
                val lines = result.syncedLyrics
                val activeIndex = lines.indexOfLast { it.timeMs <= currentMs }

                if (activeIndex >= 0) {
                    val changed = lyricLineAdapter?.setActiveLine(activeIndex) == true
                    if (changed && isAutoScrollEnabled) {
                        scrollToActivePosition(activeIndex, smooth = true)
                    }
                }
            } else if (result.hasPlainLyrics && isAutoScrollEnabled) {
                val durationMs = playbackModel.song.value?.durationMs ?: 0L
                if (durationMs > 0) {
                    val progress = (positionDs * 100).toFloat() / durationMs.toFloat()
                    val maxScroll = binding.lyricsPlainText.height - binding.lyricsPlainScroll.height
                    if (maxScroll > 0) {
                        val targetY = (maxScroll * progress).toInt().coerceAtLeast(0)
                        binding.lyricsPlainScroll.smoothScrollTo(0, targetY)
                    }
                }
            }
        }
    }

    private fun scrollToActivePosition(position: Int, smooth: Boolean = true) {
        if (!isAutoScrollEnabled) return
        val recycler = binding?.lyricsRecycler ?: return
        val layoutManager = recycler.layoutManager as? LinearLayoutManager ?: return
        val adapter = lyricLineAdapter ?: return
        if (position !in 0 until adapter.itemCount) return

        if (recycler.height <= 0) {
            recycler.post { scrollToActivePosition(position, smooth) }
            return
        }

        // Avoid fighting user gesture when user is actively dragging
        if (recycler.scrollState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_DRAGGING) {
            return
        }

        val targetY = (recycler.height * 0.35f).toInt().coerceAtLeast(0)
        val child = layoutManager.findViewByPosition(position)

        if (child != null && smooth) {
            val dy = child.top - targetY
            if (kotlin.math.abs(dy) > 4) {
                recycler.smoothScrollBy(0, dy)
            }
        } else if (smooth) {
            val firstVisible = layoutManager.findFirstVisibleItemPosition()
            val distance = kotlin.math.abs(position - firstVisible)
            if (distance in 1..8) {
                val context = context ?: return
                val scroller = object : LinearSmoothScroller(context) {
                    override fun getVerticalSnapPreference(): Int = SNAP_TO_START
                    override fun calculateDtToFit(
                        viewStart: Int,
                        viewEnd: Int,
                        boxStart: Int,
                        boxEnd: Int,
                        snapPreference: Int
                    ): Int {
                        return (boxStart + targetY) - viewStart
                    }
                }
                scroller.targetPosition = position
                layoutManager.startSmoothScroll(scroller)
            } else {
                layoutManager.scrollToPositionWithOffset(position, targetY)
            }
        } else {
            layoutManager.scrollToPositionWithOffset(position, targetY)
        }
    }

    private fun loadLyrics(
        appContext: Context,
        cleanTitle: String,
        cleanArtist: String,
        album: String?,
        durationSec: Int,
        path: String?
    ) {
        val binding = requireBinding()
        binding.lyricsProgress.isVisible = true
        binding.lyricsRecycler.isVisible = false
        binding.lyricsPlainScroll.isVisible = false
        binding.lyricsEmptyState.isVisible = false

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                // 1. Check local offline LRC file first across storage volumes
                checkOfflineLrcFile(appContext, cleanArtist, cleanTitle, album, path)
                    // 2. Fetch from progressive LrcLib network client
                    ?: lrcLibClient.getLyrics(cleanTitle, cleanArtist, album, durationSec)
            }

            currentLyricsResult = result
            binding.lyricsProgress.isVisible = false

            when {
                result.hasSyncedLyrics -> {
                    lyricLineAdapter?.submitList(result.syncedLyrics)
                    binding.lyricsRecycler.isVisible = true
                    binding.lyricsPlainScroll.isVisible = false
                    binding.lyricsEmptyState.isVisible = false

                    // Immediately jump to current playback position on load
                    val currentMs = playbackModel.positionDs.value * 100
                    val activeIndex = result.syncedLyrics.indexOfLast { it.timeMs <= currentMs }
                    if (activeIndex >= 0) {
                        lyricLineAdapter?.setActiveLine(activeIndex)
                        if (isAutoScrollEnabled) {
                            binding.lyricsRecycler.post {
                                scrollToActivePosition(activeIndex, smooth = false)
                            }
                        } else {
                            binding.lyricsRecycler.scrollToPosition(activeIndex)
                        }
                    }
                }
                result.hasPlainLyrics -> {
                    binding.lyricsPlainText.text = result.plainLyrics
                    binding.lyricsPlainScroll.isVisible = true
                    binding.lyricsRecycler.isVisible = false
                    binding.lyricsEmptyState.isVisible = false
                }
                result.isInstrumental -> {
                    binding.lyricsEmptyState.text = getString(R.string.lyrics_instrumental)
                    binding.lyricsEmptyState.isVisible = true
                    binding.lyricsRecycler.isVisible = false
                    binding.lyricsPlainScroll.isVisible = false
                }
                else -> {
                    val isOffline = !NetworkUtil.isConnected(appContext)
                    binding.lyricsEmptyState.text = if (isOffline) {
                        getString(R.string.lyrics_offline)
                    } else {
                        getString(R.string.lyrics_none)
                    }
                    binding.lyricsEmptyState.isVisible = true
                    binding.lyricsRecycler.isVisible = false
                    binding.lyricsPlainScroll.isVisible = false
                }
            }
        }
    }

    private fun checkOfflineLrcFile(
        context: Context,
        artist: String,
        title: String,
        album: String?,
        path: String?
    ): LyricsResult? {
        // 1. Sibling of audio file if path is a local file
        if (!path.isNullOrBlank() && path.startsWith("/")) {
            try {
                val file = File(path)
                val siblingLrc = File(file.parentFile, "${file.nameWithoutExtension}.lrc")
                readLrcFile(siblingLrc)?.let { return it }
            } catch (e: Exception) {
                L.w(e, "Error checking sibling LRC for %s", path)
            }
        }

        // 2. Check all storage directories (Internal and SD card)
        val candidateBaseDirs = mutableListOf<File>()
        candidateBaseDirs.add(StorageVolumeHelper.getInternalSounderDir())
        StorageVolumeHelper.getSdCardSounderDir(context)?.let { candidateBaseDirs.add(it) }

        val sanitizedArtist = sanitizeFileName(artist)
        val sanitizedTitle = sanitizeFileName(title)
        val baseFileName = "$sanitizedArtist - $sanitizedTitle"

        val candidateSubPaths = mutableListOf<String>()
        // Structured: <Artist>/<Album>/<Artist - Title>.lrc
        if (!album.isNullOrBlank()) {
            val sanitizedAlbum = sanitizeFileName(album)
            candidateSubPaths.add("$sanitizedArtist/$sanitizedAlbum/$baseFileName.lrc")
            candidateSubPaths.add("$sanitizedArtist/$sanitizedAlbum/$sanitizedTitle.lrc")
        }
        // Structured: <Artist>/<Artist - Title>.lrc
        candidateSubPaths.add("$sanitizedArtist/$baseFileName.lrc")
        candidateSubPaths.add("$sanitizedArtist/$sanitizedTitle.lrc")
        // Flat fallback: <BaseDir>/<Artist - Title>.lrc
        candidateSubPaths.add("$baseFileName.lrc")
        candidateSubPaths.add("$sanitizedTitle.lrc")

        for (baseDir in candidateBaseDirs) {
            for (subPath in candidateSubPaths) {
                val candidateFile = File(baseDir, subPath)
                readLrcFile(candidateFile)?.let { return it }
            }
        }

        return null
    }

    private fun readLrcFile(file: File): LyricsResult? {
        if (!file.exists() || !file.canRead() || file.length() == 0L) return null
        return try {
            val content = file.readText()
            val lines = LrcParser.parse(content)
            if (lines.isNotEmpty()) {
                LyricsResult(syncedLyrics = lines)
            } else if (content.isNotBlank()) {
                LyricsResult(plainLyrics = content)
            } else {
                null
            }
        } catch (e: Exception) {
            L.w(e, "Failed to read offline LRC file at %s", file.absolutePath)
            null
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
    }

    override fun onDestroyBinding(binding: DialogLyricsBinding) {
        super.onDestroyBinding(binding)
        lyricLineAdapter = null
    }
}

