package org.nov0caina.sounder.image

import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistCoverManagerTest {

    @Test
    fun normalizeKey_isCaseAndWhitespaceInsensitive() {
        val key1 = PlaylistCoverManager.normalizeKey("Chill Vibes")
        val key2 = PlaylistCoverManager.normalizeKey("  chill vibes  ")
        assertEquals(key1, key2)
    }

    @Test
    fun saveLookupRenameAndDeleteCover_lifecycleWorks() {
        val tempDir = Files.createTempDirectory("playlist_covers_test").toFile()
        try {
            val sampleBytes = byteArrayOf(1, 2, 3, 4, 5, 6)
            assertNull(PlaylistCoverManager.getCoverFile(tempDir, "Gym Mix"))

            val saved = PlaylistCoverManager.saveCoverBytes(tempDir, "Gym Mix", sampleBytes)
            assertNotNull(saved)
            assertTrue(saved!!.exists())

            val lookedUp = PlaylistCoverManager.getCoverFile(tempDir, "  gym mix ")
            assertNotNull(lookedUp)
            assertArrayEquals(sampleBytes, lookedUp!!.readBytes())
            assertTrue(PlaylistCoverManager.getCoverSignature("Gym Mix") > 0L)

            // Rename playlist -> migrates custom cover
            val renamed = PlaylistCoverManager.renameCover(tempDir, "Gym Mix", "Workout Hits")
            assertTrue(renamed)
            assertNull(PlaylistCoverManager.getCoverFile(tempDir, "Gym Mix"))
            val renamedFile = PlaylistCoverManager.getCoverFile(tempDir, "Workout Hits")
            assertNotNull(renamedFile)
            assertArrayEquals(sampleBytes, renamedFile!!.readBytes())

            // Delete playlist -> removes custom cover
            val deleted = PlaylistCoverManager.deleteCover(tempDir, "Workout Hits")
            assertTrue(deleted)
            assertNull(PlaylistCoverManager.getCoverFile(tempDir, "Workout Hits"))
            assertFalse(PlaylistCoverManager.deleteCover(tempDir, "Workout Hits"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun coverChanges_incrementsOnSaveRenameAndDelete() {
        val tempDir = Files.createTempDirectory("playlist_covers_rev_test").toFile()
        try {
            val initialRev = PlaylistCoverManager.coverChanges.value
            PlaylistCoverManager.saveCoverBytes(tempDir, "Indie", byteArrayOf(9, 8, 7))
            val afterSaveRev = PlaylistCoverManager.coverChanges.value
            assertTrue(afterSaveRev > initialRev)

            // Same normalized name rename is a no-op and preserves cover
            assertFalse(PlaylistCoverManager.renameCover(tempDir, "Indie", "  indie "))
            assertNotNull(PlaylistCoverManager.getCoverFile(tempDir, "Indie"))

            // Rename to new name increments revision
            assertTrue(PlaylistCoverManager.renameCover(tempDir, "Indie", "Indie Rock"))
            val afterRenameRev = PlaylistCoverManager.coverChanges.value
            assertTrue(afterRenameRev > afterSaveRev)

            // Delete increments revision
            assertTrue(PlaylistCoverManager.deleteCover(tempDir, "Indie Rock"))
            assertTrue(PlaylistCoverManager.coverChanges.value > afterRenameRev)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
