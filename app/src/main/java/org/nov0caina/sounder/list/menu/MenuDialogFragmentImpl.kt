/*
 * Copyright (c) 2023 Auxio Project
 * MenuDialogFragmentImpl.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.nov0caina.sounder.list.menu

import android.annotation.SuppressLint
import android.os.Build
import android.provider.MediaStore
import android.view.MenuItem
import androidx.appcompat.view.menu.MenuBuilder
import androidx.core.os.bundleOf
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nov0caina.sounder.MainActivity
import org.nov0caina.sounder.R
import org.nov0caina.sounder.databinding.DialogMenuBinding
import org.nov0caina.sounder.detail.DetailViewModel
import org.nov0caina.sounder.list.ListViewModel
import org.nov0caina.sounder.music.MusicViewModel
import org.nov0caina.sounder.music.resolve
import org.nov0caina.sounder.music.resolveName
import org.nov0caina.sounder.music.resolveNames
import org.nov0caina.sounder.online.download.DownloadManager
import org.nov0caina.sounder.online.model.OnlineSong
import org.nov0caina.sounder.playback.PlaybackViewModel
import org.nov0caina.sounder.playback.formatDurationMs
import org.nov0caina.sounder.util.MediaDeletionUtil
import org.nov0caina.sounder.util.NetworkUtil
import org.nov0caina.sounder.util.StoragePermissionHelper
import org.nov0caina.sounder.util.getPlural
import org.nov0caina.sounder.util.share
import org.nov0caina.sounder.util.showToast
import org.oxycblt.musikr.Artist
import org.oxycblt.musikr.Genre
import org.oxycblt.musikr.Playlist
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * [MenuDialogFragment] implementation for a [Song].
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class SongMenuDialogFragment : MenuDialogFragment<Menu.ForSong>() {
    override val menuModel: MenuViewModel by activityViewModels()
    override val listModel: ListViewModel by activityViewModels()
    private val detailModel: DetailViewModel by activityViewModels()
    private val musicModel: MusicViewModel by activityViewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val args: SongMenuDialogFragmentArgs by navArgs()

    @Inject lateinit var downloadManager: DownloadManager

    override val parcel
        get() = args.parcel

    // Nothing to disable in song menus.
    override fun getDisabledItemIds(menu: Menu.ForSong) = setOf<Int>()

    @SuppressLint("RestrictedApi")
    override fun customizeMenu(builder: MenuBuilder, menu: Menu.ForSong) {
        val isFav = musicModel.isFavorite(menu.song)
        val favItem = builder.findItem(R.id.action_favorite)
        favItem?.setIcon(if (isFav) R.drawable.ic_favorite_24 else R.drawable.ic_favorite_border_24)
        favItem?.setTitle(if (isFav) R.string.lbl_remove_favorite else R.string.lbl_add_favorite)

        val downloadItem = builder.findItem(R.id.action_download)
        val deleteDownloadItem = builder.findItem(R.id.action_delete_download)

        if (menu.song is OnlineSong) {
            val isDownloaded = downloadManager.isDownloaded(menu.song.onlineTrack)
            downloadItem?.isVisible = !isDownloaded
            deleteDownloadItem?.isVisible = isDownloaded
            deleteDownloadItem?.setTitle(R.string.lbl_delete_download)
        } else {
            downloadItem?.isVisible = false
            deleteDownloadItem?.isVisible = true
            val songFile = MediaDeletionUtil.resolveSongFile(requireContext(), menu.song)
            val isSounderDownload = songFile?.absolutePath?.contains("Sounder", ignoreCase = true) == true ||
                menu.song.path.components.toString().contains("Sounder", ignoreCase = true)
            deleteDownloadItem?.setTitle(if (isSounderDownload) R.string.lbl_delete_download else R.string.lbl_delete_file)
        }

        val removePlaylistItem = builder.findItem(R.id.action_remove_from_playlist)
        removePlaylistItem?.isVisible = (detailModel.currentPlaylist.value != null)
    }

    override fun updateMenu(binding: DialogMenuBinding, menu: Menu.ForSong) {
        val context = requireContext()
        binding.menuCover.bind(menu.song)
        binding.menuType.text = getString(R.string.lbl_song)
        binding.menuName.text = menu.song.name.resolve(context)
        binding.menuInfo.text = menu.song.artists.resolveNames(context)
    }

    override fun onClick(item: MenuItem, menu: Menu.ForSong) {
        when (item.itemId) {
            R.id.action_play -> playbackModel.playExplicit(menu.song, menu.playWith)
            R.id.action_shuffle -> playbackModel.shuffleExplicit(menu.song, menu.playWith)
            R.id.action_play_next -> {
                playbackModel.playNext(menu.song)
                requireContext().showToast(R.string.lng_play_next)
            }
            R.id.action_queue_add -> {
                playbackModel.addToQueue(menu.song)
                requireContext().showToast(R.string.lng_queue_added)
            }
            R.id.action_favorite -> {
                musicModel.toggleFavorite(menu.song) { isFav ->
                    requireContext().showToast(if (isFav) R.string.lng_added_favorite else R.string.lng_removed_favorite)
                }
            }
            R.id.action_download -> {
                if (menu.song is OnlineSong) {
                    if (!org.nov0caina.sounder.util.StoragePermissionHelper.hasPermission(requireContext())) {
                        requireContext().showToast(R.string.msg_storage_permission_required)
                        return
                    }
                    downloadManager.enqueue(menu.song.onlineTrack)
                    requireContext().showToast(getString(R.string.download_queued, menu.song.onlineTrack.title))
                }
            }
            R.id.action_delete_download -> {
                val act = activity ?: return
                val appContext = act.applicationContext
                val playback = ViewModelProvider(act)[PlaybackViewModel::class.java]
                val music = ViewModelProvider(act)[MusicViewModel::class.java]

                val targetSong: Song? = if (menu.song is OnlineSong) {
                    downloadManager.findSongInLibrary(menu.song.onlineTrack)
                } else {
                    menu.song
                }
                val trackIdToClean = (menu.song as? OnlineSong)?.onlineTrack?.id

                if (StoragePermissionHelper.hasAllFilesAccess(act)) {
                    MaterialAlertDialogBuilder(act)
                        .setMessage(R.string.msg_confirm_delete_song)
                        .setPositiveButton(R.string.btn_delete) { _, _ ->
                            val currentUid = playback.song.value?.uid
                            if (currentUid == menu.song.uid || (targetSong != null && currentUid == targetSong.uid)) {
                                playback.endSession()
                            }
                            CoroutineScope(Dispatchers.IO).launch {
                                var success = false
                                if (targetSong != null) {
                                    success = MediaDeletionUtil.deleteSongFile(appContext, targetSong)
                                } else if (menu.song is OnlineSong) {
                                    val file = downloadManager.getDownloadedFile(menu.song.onlineTrack)
                                    if (file != null && file.exists()) {
                                        val parent = file.parentFile
                                        if (parent != null) {
                                            java.io.File(parent, "${file.nameWithoutExtension}.lrc").delete()
                                            java.io.File(parent, "${file.nameWithoutExtension}.jpg").delete()
                                        }
                                        success = file.delete() || !file.exists()
                                        if (success) {
                                            android.media.MediaScannerConnection.scanFile(appContext, arrayOf(file.absolutePath), null, null)
                                            if (parent != null) {
                                                MediaDeletionUtil.cleanAlbumAndArtistDirs(parent)
                                            }
                                        }
                                    }
                                }
                                if (trackIdToClean != null) {
                                    downloadManager.removeDownload(trackIdToClean)
                                }
                                withContext(Dispatchers.Main) {
                                    if (success) {
                                        music.rescan()
                                        appContext.showToast(R.string.lbl_delete_download)
                                    } else {
                                        appContext.showToast(R.string.err_delete_failed)
                                    }
                                }
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val mainAct = act as? MainActivity
                    val targetUri = targetSong?.uri
                    if (mainAct != null && targetUri != null) {
                        try {
                            val pi = MediaStore.createDeleteRequest(act.contentResolver, listOf(targetUri))
                            mainAct.requestMediaDeletion(pi.intentSender) { granted ->
                                if (granted) {
                                    val currentUid = playback.song.value?.uid
                                    if (currentUid == menu.song.uid || currentUid == targetSong.uid) {
                                        playback.endSession()
                                    }
                                    CoroutineScope(Dispatchers.IO).launch {
                                        MediaDeletionUtil.cleanPostMediaStoreSongDeletion(appContext, targetSong)
                                        if (trackIdToClean != null) {
                                            downloadManager.removeDownload(trackIdToClean)
                                        }
                                        withContext(Dispatchers.Main) {
                                            music.rescan()
                                            appContext.showToast(R.string.lbl_delete_download)
                                        }
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            L.e(t, "Failed to launch MediaStore.createDeleteRequest for song")
                            appContext.showToast(R.string.err_delete_failed)
                        }
                    } else {
                        CoroutineScope(Dispatchers.IO).launch {
                            var success = false
                            if (menu.song is OnlineSong) {
                                val file = downloadManager.getDownloadedFile(menu.song.onlineTrack)
                                if (file != null && file.exists()) {
                                    val parent = file.parentFile
                                    if (parent != null) {
                                        java.io.File(parent, "${file.nameWithoutExtension}.lrc").delete()
                                        java.io.File(parent, "${file.nameWithoutExtension}.jpg").delete()
                                    }
                                    success = file.delete() || !file.exists()
                                    if (success) {
                                        android.media.MediaScannerConnection.scanFile(appContext, arrayOf(file.absolutePath), null, null)
                                        if (parent != null) {
                                            MediaDeletionUtil.cleanAlbumAndArtistDirs(parent)
                                        }
                                    }
                                }
                            }
                            if (trackIdToClean != null) {
                                downloadManager.removeDownload(trackIdToClean)
                            }
                            withContext(Dispatchers.Main) {
                                if (success) {
                                    music.rescan()
                                    appContext.showToast(R.string.lbl_delete_download)
                                } else {
                                    appContext.showToast(R.string.err_delete_failed)
                                }
                            }
                        }
                    }
                } else {
                    MaterialAlertDialogBuilder(act)
                        .setTitle(R.string.dlg_storage_manager_perm_title)
                        .setMessage(R.string.dlg_storage_manager_perm_msg)
                        .setPositiveButton(R.string.btn_open_settings) { _, _ ->
                            try {
                                act.startActivity(StoragePermissionHelper.createManageAllFilesAccessIntent(act))
                            } catch (t: Throwable) {
                                act.showToast(R.string.err_no_app)
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
            R.id.action_remove_from_playlist -> {
                detailModel.currentPlaylist.value?.let { playlist ->
                    musicModel.removeFromPlaylist(menu.song, playlist)
                    requireContext().showToast(R.string.lng_removed_from_playlist)
                }
            }
            R.id.action_playlist_add -> musicModel.addToPlaylist(menu.song)
            R.id.action_artist_details -> {
                playbackModel.openMain()
                val context = requireContext()
                val isOnline = NetworkUtil.isConnected(context)
                if (menu.song is OnlineSong && isOnline) {
                    val onlineTrack = (menu.song as OnlineSong).onlineTrack
                    val artistName = menu.song.artists.firstOrNull()?.name?.resolve(context)?.ifBlank { null } ?: onlineTrack.artist
                    val artistId = onlineTrack.artistId ?: ""
                    if (artistName.isNotBlank()) {
                        val args = bundleOf("artistId" to artistId, "artistName" to artistName)
                        findNavController().navigate(R.id.action_global_online_artist, args)
                    } else {
                        detailModel.showArtist(menu.song)
                    }
                } else {
                    detailModel.showArtist(menu.song)
                }
            }
            R.id.action_album_details -> {
                playbackModel.openMain()
                val context = requireContext()
                val isOnline = NetworkUtil.isConnected(context)
                if (menu.song is OnlineSong && isOnline) {
                    val onlineTrack = (menu.song as OnlineSong).onlineTrack
                    val rawAlbum = menu.song.album.name.resolve(context)
                    val separators = setOf("&", ",", "+", "feat.", "feat", "ft.", "ft", "with", "vs.")
                    val albumTitle = if (rawAlbum.isBlank() || rawAlbum.trim().lowercase() in separators) {
                        onlineTrack.album?.takeIf { it.lowercase() !in separators } ?: onlineTrack.title
                    } else {
                        rawAlbum
                    }
                    val artistName = menu.song.artists.firstOrNull()?.name?.resolve(context)?.ifBlank { null } ?: onlineTrack.artist
                    val albumId = onlineTrack.albumId ?: ""
                    if (albumTitle.isNotBlank()) {
                        val args = bundleOf("albumId" to albumId, "albumTitle" to albumTitle, "artistName" to artistName)
                        findNavController().navigate(R.id.action_global_online_album, args)
                    } else {
                        detailModel.showAlbum(menu.song.album)
                    }
                } else {
                    detailModel.showAlbum(menu.song.album)
                }
            }
            R.id.action_share -> requireContext().share(menu.song)
            R.id.action_detail -> detailModel.showSong(menu.song)
            else -> error("Unexpected menu item selected $item")
        }
    }
}

/**
 * [MenuDialogFragment] implementation for a [AlbumMenuDialogFragment].
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class AlbumMenuDialogFragment : MenuDialogFragment<Menu.ForAlbum>() {
    override val menuModel: MenuViewModel by viewModels()
    override val listModel: ListViewModel by activityViewModels()
    private val detailModel: DetailViewModel by activityViewModels()
    private val musicModel: MusicViewModel by activityViewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val args: AlbumMenuDialogFragmentArgs by navArgs()

    override val parcel
        get() = args.parcel

    // Nothing to disable in album menus.
    override fun getDisabledItemIds(menu: Menu.ForAlbum) = setOf<Int>()

    @SuppressLint("RestrictedApi")
    override fun customizeMenu(builder: MenuBuilder, menu: Menu.ForAlbum) {
        val deleteDownloadAlbumItem = builder.findItem(R.id.action_delete_download_album)
        val hasLocalSongs = menu.album.songs.any { it !is OnlineSong }
        deleteDownloadAlbumItem?.isVisible = hasLocalSongs
    }

    override fun updateMenu(binding: DialogMenuBinding, menu: Menu.ForAlbum) {
        val context = requireContext()
        binding.menuCover.bind(menu.album)
        binding.menuType.text = menu.album.releaseType.resolve(context)
        binding.menuName.text = menu.album.name.resolve(context)
        binding.menuInfo.text = menu.album.artists.resolveNames(context)
    }

    override fun onClick(item: MenuItem, menu: Menu.ForAlbum) {
        when (item.itemId) {
            R.id.action_play -> playbackModel.play(menu.album)
            R.id.action_shuffle -> playbackModel.shuffle(menu.album)
            R.id.action_detail -> detailModel.showAlbum(menu.album)
            R.id.action_play_next -> {
                playbackModel.playNext(menu.album)
                requireContext().showToast(R.string.lng_play_next)
            }
            R.id.action_queue_add -> {
                playbackModel.addToQueue(menu.album)
                requireContext().showToast(R.string.lng_queue_added)
            }
            R.id.action_artist_details -> {
                playbackModel.openMain()
                val context = requireContext()
                val artistName = menu.album.artists.firstOrNull()?.name?.resolve(context) ?: ""
                val isOnline = NetworkUtil.isConnected(context)
                val isOnlineAlbum = menu.album.songs.isNotEmpty() && menu.album.songs.all { it is OnlineSong }
                if (isOnlineAlbum && isOnline && artistName.isNotBlank()) {
                    val args = bundleOf("artistId" to "", "artistName" to artistName)
                    findNavController().navigate(R.id.action_global_online_artist, args)
                } else {
                    detailModel.showArtist(menu.album)
                }
            }
            R.id.action_delete_download_album -> {
                val act = activity ?: return
                val appContext = act.applicationContext
                val playback = ViewModelProvider(act)[PlaybackViewModel::class.java]
                val music = ViewModelProvider(act)[MusicViewModel::class.java]
                val localSongs = menu.album.songs.filter { it !is OnlineSong }
                if (localSongs.isEmpty()) return

                if (StoragePermissionHelper.hasAllFilesAccess(act)) {
                    MaterialAlertDialogBuilder(act)
                        .setMessage(R.string.msg_confirm_delete_album)
                        .setPositiveButton(R.string.btn_delete) { _, _ ->
                            val currentSong = playback.song.value
                            if (currentSong != null && menu.album.songs.any { it.uid == currentSong.uid }) {
                                playback.endSession()
                            }
                            CoroutineScope(Dispatchers.IO).launch {
                                val count = MediaDeletionUtil.deleteAlbumFiles(appContext, menu.album)
                                withContext(Dispatchers.Main) {
                                    if (count > 0) {
                                        music.rescan()
                                        appContext.showToast(R.string.lbl_delete_download_album)
                                    } else {
                                        appContext.showToast(R.string.err_delete_failed)
                                    }
                                }
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val mainAct = act as? MainActivity
                    if (mainAct != null) {
                        try {
                            val uris = localSongs.map { it.uri }
                            val pi = MediaStore.createDeleteRequest(act.contentResolver, uris)
                            mainAct.requestMediaDeletion(pi.intentSender) { granted ->
                                if (granted) {
                                    val currentSong = playback.song.value
                                    if (currentSong != null && menu.album.songs.any { it.uid == currentSong.uid }) {
                                        playback.endSession()
                                    }
                                    CoroutineScope(Dispatchers.IO).launch {
                                        MediaDeletionUtil.cleanPostMediaStoreAlbumDeletion(appContext, menu.album)
                                        withContext(Dispatchers.Main) {
                                            music.rescan()
                                            appContext.showToast(R.string.lbl_delete_download_album)
                                        }
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            L.e(t, "Failed to launch MediaStore.createDeleteRequest for album")
                            appContext.showToast(R.string.err_delete_failed)
                        }
                    } else {
                        appContext.showToast(R.string.err_delete_failed)
                    }
                } else {
                    MaterialAlertDialogBuilder(act)
                        .setTitle(R.string.dlg_storage_manager_perm_title)
                        .setMessage(R.string.dlg_storage_manager_perm_msg)
                        .setPositiveButton(R.string.btn_open_settings) { _, _ ->
                            try {
                                act.startActivity(StoragePermissionHelper.createManageAllFilesAccessIntent(act))
                            } catch (t: Throwable) {
                                act.showToast(R.string.err_no_app)
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
            R.id.action_playlist_add -> musicModel.addToPlaylist(menu.album)
            R.id.action_share -> requireContext().share(menu.album)
            else -> error("Unexpected menu item selected $item")
        }
    }
}

/**
 * [MenuDialogFragment] implementation for a [Artist].
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class ArtistMenuDialogFragment : MenuDialogFragment<Menu.ForArtist>() {
    override val menuModel: MenuViewModel by viewModels()
    override val listModel: ListViewModel by activityViewModels()
    private val detailModel: DetailViewModel by activityViewModels()
    private val musicModel: MusicViewModel by activityViewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val args: ArtistMenuDialogFragmentArgs by navArgs()

    override val parcel
        get() = args.parcel

    override fun getDisabledItemIds(menu: Menu.ForArtist) =
        if (menu.artist.songs.isEmpty()) {
            // Disable any operations that require some kind of songs to work with, as there won't
            // be any in an empty artist.
            setOf(
                R.id.action_play,
                R.id.action_shuffle,
                R.id.action_play_next,
                R.id.action_queue_add,
                R.id.action_playlist_add,
                R.id.action_share,
            )
        } else {
            setOf()
        }

    override fun updateMenu(binding: DialogMenuBinding, menu: Menu.ForArtist) {
        val context = requireContext()
        binding.menuCover.bind(menu.artist)
        binding.menuType.text = getString(R.string.lbl_artist)
        binding.menuName.text = menu.artist.name.resolve(context)
        binding.menuInfo.text =
            getString(
                R.string.fmt_two,
                if (menu.artist.explicitAlbums.isNotEmpty()) {
                    context.getPlural(R.plurals.fmt_album_count, menu.artist.explicitAlbums.size)
                } else {
                    context.getString(R.string.def_album_count)
                },
                if (menu.artist.songs.isNotEmpty()) {
                    context.getPlural(R.plurals.fmt_song_count, menu.artist.songs.size)
                } else {
                    getString(R.string.def_song_count)
                },
            )
    }

    override fun onClick(item: MenuItem, menu: Menu.ForArtist) {
        when (item.itemId) {
            R.id.action_play -> playbackModel.play(menu.artist)
            R.id.action_shuffle -> playbackModel.shuffle(menu.artist)
            R.id.action_detail -> detailModel.showArtist(menu.artist)
            R.id.action_play_next -> {
                playbackModel.playNext(menu.artist)
                requireContext().showToast(R.string.lng_play_next)
            }
            R.id.action_queue_add -> {
                playbackModel.addToQueue(menu.artist)
                requireContext().showToast(R.string.lng_queue_added)
            }
            R.id.action_playlist_add -> musicModel.addToPlaylist(menu.artist)
            R.id.action_online_artist -> {
                playbackModel.openMain()
                val context = requireContext()
                if (!NetworkUtil.isConnected(context)) {
                    context.showToast(R.string.home_offline_still_no_internet)
                    return
                }
                val artistName = menu.artist.name.resolve(context)
                if (artistName.isNotBlank()) {
                    val args = bundleOf("artistId" to "", "artistName" to artistName)
                    findNavController().navigate(R.id.action_global_online_artist, args)
                }
            }
            R.id.action_share -> requireContext().share(menu.artist)
            else -> error("Unexpected menu item $item")
        }
    }
}

/**
 * [MenuDialogFragment] implementation for a [Genre].
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class GenreMenuDialogFragment : MenuDialogFragment<Menu.ForGenre>() {
    override val menuModel: MenuViewModel by viewModels()
    override val listModel: ListViewModel by activityViewModels()
    private val detailModel: DetailViewModel by activityViewModels()
    private val musicModel: MusicViewModel by activityViewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val args: GenreMenuDialogFragmentArgs by navArgs()

    override val parcel
        get() = args.parcel

    override fun getDisabledItemIds(menu: Menu.ForGenre) = setOf<Int>()

    @SuppressLint("RestrictedApi")
    override fun customizeMenu(builder: MenuBuilder, menu: Menu.ForGenre) {
        builder.findItem(R.id.action_online_artist)?.isVisible = false
    }

    override fun updateMenu(binding: DialogMenuBinding, menu: Menu.ForGenre) {
        val context = requireContext()
        binding.menuCover.bind(menu.genre)
        binding.menuType.text = getString(R.string.lbl_genre)
        binding.menuName.text = menu.genre.name.resolve(context)
        binding.menuInfo.text =
            getString(
                R.string.fmt_two,
                context.getPlural(R.plurals.fmt_artist_count, menu.genre.artists.size),
                context.getPlural(R.plurals.fmt_song_count, menu.genre.songs.size),
            )
    }

    override fun onClick(item: MenuItem, menu: Menu.ForGenre) {
        when (item.itemId) {
            R.id.action_play -> playbackModel.play(menu.genre)
            R.id.action_shuffle -> playbackModel.shuffle(menu.genre)
            R.id.action_detail -> detailModel.showGenre(menu.genre)
            R.id.action_play_next -> {
                playbackModel.playNext(menu.genre)
                requireContext().showToast(R.string.lng_play_next)
            }
            R.id.action_queue_add -> {
                playbackModel.addToQueue(menu.genre)
                requireContext().showToast(R.string.lng_queue_added)
            }
            R.id.action_playlist_add -> musicModel.addToPlaylist(menu.genre)
            R.id.action_share -> requireContext().share(menu.genre)
            else -> error("Unexpected menu item $item")
        }
    }
}

/**
 * [MenuDialogFragment] implementation for a [Playlist].
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class PlaylistMenuDialogFragment : MenuDialogFragment<Menu.ForPlaylist>() {
    override val menuModel: MenuViewModel by viewModels()
    override val listModel: ListViewModel by activityViewModels()
    private val detailModel: DetailViewModel by activityViewModels()
    private val musicModel: MusicViewModel by activityViewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val args: PlaylistMenuDialogFragmentArgs by navArgs()

    override val parcel
        get() = args.parcel

    override fun getDisabledItemIds(menu: Menu.ForPlaylist) =
        if (menu.playlist.songs.isEmpty()) {
            // Disable any operations that require some kind of songs to work with, as there won't
            // be any in an empty playlist.
            setOf(
                R.id.action_play,
                R.id.action_shuffle,
                R.id.action_play_next,
                R.id.action_queue_add,
                R.id.action_playlist_add,
                R.id.action_export,
                R.id.action_share,
            )
        } else {
            setOf()
        }

    @SuppressLint("RestrictedApi")
    override fun customizeMenu(builder: MenuBuilder, menu: Menu.ForPlaylist) {
        val context = requireContext()
        val playlistName = menu.playlist.name.resolve(context)
        if (MusicViewModel.isFavoritesPlaylistName(context, playlistName)) {
            builder.findItem(R.id.action_rename)?.isVisible = false
            builder.findItem(R.id.action_delete)?.isVisible = false
        }
    }

    override fun updateMenu(binding: DialogMenuBinding, menu: Menu.ForPlaylist) {
        val context = requireContext()
        binding.menuCover.bind(menu.playlist)
        binding.menuType.text = getString(R.string.lbl_playlist)
        binding.menuName.text = menu.playlist.resolveName(context)
        binding.menuInfo.text =
            if (menu.playlist.songs.isNotEmpty()) {
                context.getPlural(R.plurals.fmt_song_count, menu.playlist.songs.size)
            } else {
                getString(R.string.def_song_count)
            }
    }

    override fun onClick(item: MenuItem, menu: Menu.ForPlaylist) {
        when (item.itemId) {
            R.id.action_play -> playbackModel.play(menu.playlist)
            R.id.action_shuffle -> playbackModel.shuffle(menu.playlist)
            R.id.action_detail -> detailModel.showPlaylist(menu.playlist)
            R.id.action_play_next -> {
                playbackModel.playNext(menu.playlist)
                requireContext().showToast(R.string.lng_play_next)
            }
            R.id.action_queue_add -> {
                playbackModel.addToQueue(menu.playlist)
                requireContext().showToast(R.string.lng_queue_added)
            }
            R.id.action_rename -> {
                val context = requireContext()
                if (MusicViewModel.isFavoritesPlaylistName(context, menu.playlist.name.resolve(context))) return
                musicModel.renamePlaylist(menu.playlist)
            }
            R.id.action_import -> musicModel.importPlaylist(target = menu.playlist)
            R.id.action_export -> musicModel.exportPlaylist(menu.playlist)
            R.id.action_delete -> {
                val context = requireContext()
                if (MusicViewModel.isFavoritesPlaylistName(context, menu.playlist.name.resolve(context))) return
                musicModel.deletePlaylist(menu.playlist)
            }
            R.id.action_share -> requireContext().share(menu.playlist)
            else -> error("Unexpected menu item $item")
        }
    }
}

/**
 * [MenuDialogFragment] implementation for a [Song] selection.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class SelectionMenuDialogFragment : MenuDialogFragment<Menu.ForSelection>() {
    override val menuModel: MenuViewModel by activityViewModels()
    override val listModel: ListViewModel by activityViewModels()
    private val musicModel: MusicViewModel by activityViewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val args: SelectionMenuDialogFragmentArgs by navArgs()

    override val parcel
        get() = args.parcel

    // Nothing to disable in song menus.
    override fun getDisabledItemIds(menu: Menu.ForSelection) = setOf<Int>()

    override fun updateMenu(binding: DialogMenuBinding, menu: Menu.ForSelection) {
        binding.menuCover.bind(
            menu.songs,
            getString(R.string.desc_selection_image),
            R.drawable.ic_song_24,
        )
        binding.menuType.text = getString(R.string.lbl_selection)
        binding.menuName.text =
            requireContext().getPlural(R.plurals.fmt_song_count, menu.songs.size)
        binding.menuInfo.text = menu.songs.sumOf { it.durationMs }.formatDurationMs(true)
    }

    override fun onClick(item: MenuItem, menu: Menu.ForSelection) {
        listModel.dropSelection()
        when (item.itemId) {
            R.id.action_play -> playbackModel.play(menu.songs)
            R.id.action_shuffle -> playbackModel.shuffle(menu.songs)
            R.id.action_play_next -> {
                playbackModel.playNext(menu.songs)
                requireContext().showToast(R.string.lng_play_next)
            }
            R.id.action_queue_add -> {
                playbackModel.addToQueue(menu.songs)
                requireContext().showToast(R.string.lng_queue_added)
            }
            R.id.action_playlist_add -> musicModel.addToPlaylist(menu.songs)
            R.id.action_share -> requireContext().share(menu.songs)
            else -> error("Unexpected menu item selected $item")
        }
    }
}
