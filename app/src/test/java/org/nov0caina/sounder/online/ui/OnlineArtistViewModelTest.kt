package org.nov0caina.sounder.online.ui

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nov0caina.sounder.online.db.FavoriteArtistDao
import org.nov0caina.sounder.online.db.FavoriteArtistEntity
import org.nov0caina.sounder.online.model.OnlineArtistDetail
import org.nov0caina.sounder.online.model.OnlineTrack

class OnlineArtistViewModelTest {

    private class FakeFavoriteArtistDao : FavoriteArtistDao {
        val storage = MutableStateFlow<Map<String, FavoriteArtistEntity>>(emptyMap())

        override fun observeFavoriteArtists(): Flow<List<FavoriteArtistEntity>> =
            storage.map { it.values.toList() }

        override suspend fun getAll(): List<FavoriteArtistEntity> =
            storage.value.values.toList()

        override fun observeIsFavorite(id: String): Flow<Boolean> =
            storage.map { it.containsKey(id) }

        override suspend fun isFavorite(id: String): Boolean =
            storage.value.containsKey(id)

        override suspend fun insertOrUpdate(artist: FavoriteArtistEntity) {
            storage.value = storage.value + (artist.id to artist)
        }

        override suspend fun deleteById(id: String) {
            storage.value = storage.value - id
        }

        override suspend fun clearAll() {
            storage.value = emptyMap()
        }
    }

    @Test
    fun performToggleFavorite_addsAndRemovesFavoriteArtistCleanly() = runTest {
        val dao = FakeFavoriteArtistDao()
        val artist = OnlineArtistDetail(
            id = "UC_TOOL",
            name = "TOOL",
            avatarUrl = "https://img/tool.jpg",
            description = "Progressive metal band",
            subscribers = "7.01M monthly audience"
        )

        assertFalse(dao.isFavorite("UC_TOOL"))

        // First toggle: adds to favorites
        val added = OnlineArtistViewModel.performToggleFavorite(artist, dao, timestamp = 9999L)
        assertTrue(added)
        assertTrue(dao.isFavorite("UC_TOOL"))

        val stored = dao.getAll().first()
        assertEquals("UC_TOOL", stored.id)
        assertEquals("TOOL", stored.name)
        assertEquals("https://img/tool.jpg", stored.avatarUrl)
        assertEquals("7.01M monthly audience", stored.subtitle)
        assertEquals(9999L, stored.addedAt)

        // Second toggle: removes from favorites
        val removed = OnlineArtistViewModel.performToggleFavorite(artist, dao)
        assertFalse(removed)
        assertFalse(dao.isFavorite("UC_TOOL"))
        assertTrue(dao.getAll().isEmpty())
    }

    @Test
    fun isArtistMatch_strictlyValidatesArtistAttribution() {
        // True matches: same artist or featuring credit
        assertTrue(OnlineArtistViewModel.isArtistMatch("Audioslave", "Audioslave"))
        assertTrue(OnlineArtistViewModel.isArtistMatch("Audioslave ft. Guest", "Audioslave"))
        assertTrue(OnlineArtistViewModel.isArtistMatch("Guest with Audioslave", "Audioslave"))

        // False matches: related bands, solo spin-offs, and cover artists
        assertFalse(OnlineArtistViewModel.isArtistMatch("Pearl Jam", "Audioslave"))
        assertFalse(OnlineArtistViewModel.isArtistMatch("Chris Cornell", "Audioslave"))
        assertFalse(OnlineArtistViewModel.isArtistMatch("Soundgarden", "Audioslave"))
        assertFalse(OnlineArtistViewModel.isArtistMatch("Miledi", "Audioslave"))
        assertFalse(OnlineArtistViewModel.isArtistMatch("Kenia OS", "Placebo"))
        assertFalse(OnlineArtistViewModel.isArtistMatch("TAXMEN, PUNPEE", "Placebo"))
    }

    @Test
    fun isDuplicateTrack_identifiesDuplicateTitlesAndIds() {
        val baseList = listOf(
            OnlineTrack(id = "track_1", title = "Like a Stone", artist = "Audioslave", album = "Audioslave", durationMs = 300000L, artworkUrl = null),
            OnlineTrack(id = "track_2", title = "Cochise", artist = "Audioslave", album = "Audioslave", durationMs = 220000L, artworkUrl = null)
        )

        // Exact ID match is duplicate
        assertTrue(OnlineArtistViewModel.isDuplicateTrack(baseList, OnlineTrack(id = "track_1", title = "Different Title", artist = "Audioslave", album = null, durationMs = 0L, artworkUrl = null)))

        // Same title with video suffix and different ID is duplicate
        assertTrue(OnlineArtistViewModel.isDuplicateTrack(baseList, OnlineTrack(id = "track_99", title = "Cochise (Official Video)", artist = "Audioslave", album = null, durationMs = 220000L, artworkUrl = null)))
        assertTrue(OnlineArtistViewModel.isDuplicateTrack(baseList, OnlineTrack(id = "track_98", title = "like a stone", artist = "Audioslave", album = null, durationMs = 300000L, artworkUrl = null)))

        // Distinct title is not duplicate
        assertFalse(OnlineArtistViewModel.isDuplicateTrack(baseList, OnlineTrack(id = "track_3", title = "Be Yourself", artist = "Audioslave", album = null, durationMs = 280000L, artworkUrl = null)))
    }
}
