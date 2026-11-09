package org.nov0caina.sounder.util

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MediaDeletionUtilTest {

    private lateinit var tempRoot: File

    @Before
    fun setUp() {
        tempRoot = Files.createTempDirectory("sounder_deletion_test").toFile()
    }

    @Test
    fun testPreservesArtistDirectoryWhenOtherAlbumsExist() {
        // Artist directory with two albums
        val artistDir = File(tempRoot, "Queen").apply { mkdirs() }
        val album1Dir = File(artistDir, "A Night at the Opera").apply { mkdirs() }
        val album2Dir = File(artistDir, "News of the World").apply { mkdirs() }

        // Album 1 has no remaining audio, only companions
        File(album1Dir, "cover.jpg").writeText("cover artwork")
        File(album1Dir, "Bohemian Rhapsody.lrc").writeText("[00:00.00] Lyrics")

        // Album 2 has an audio file
        File(album2Dir, "We Will Rock You.m4a").writeText("audio content")

        val result = MediaDeletionUtil.cleanAlbumAndArtistDirs(album1Dir)

        assertTrue("Should clean album 1 directory", result)
        assertFalse("Album 1 directory should be deleted", album1Dir.exists())
        assertTrue("Artist directory must remain intact because Album 2 exists", artistDir.exists())
        assertTrue("Album 2 directory must remain intact", album2Dir.exists())
    }

    @Test
    fun testDeletesArtistDirectoryWhenLastAlbumIsDeleted() {
        // Artist directory with a single album
        val artistDir = File(tempRoot, "Queen").apply { mkdirs() }
        val albumDir = File(artistDir, "A Night at the Opera").apply { mkdirs() }

        // Album has only companion files left
        File(albumDir, "cover.jpg").writeText("cover artwork")
        File(albumDir, "Bohemian Rhapsody.lrc").writeText("[00:00.00] Lyrics")

        val result = MediaDeletionUtil.cleanAlbumAndArtistDirs(albumDir)

        assertTrue("Should clean album directory", result)
        assertFalse("Album directory should be deleted", albumDir.exists())
        assertFalse("Artist directory should be deleted since no albums remain", artistDir.exists())
    }

    @Test
    fun testAbortsCleanupIfAudioFilesStillRemainInAlbum() {
        val artistDir = File(tempRoot, "Pink Floyd").apply { mkdirs() }
        val albumDir = File(artistDir, "The Dark Side of the Moon").apply { mkdirs() }
        val audioFile = File(albumDir, "Time.flac").apply { writeText("audio content") }

        val result = MediaDeletionUtil.cleanAlbumAndArtistDirs(albumDir)

        assertFalse("Should not delete album when audio files remain", result)
        assertTrue("Album directory must still exist", albumDir.exists())
        assertTrue("Audio file must still exist", audioFile.exists())
        assertTrue("Artist directory must still exist", artistDir.exists())
    }
}
