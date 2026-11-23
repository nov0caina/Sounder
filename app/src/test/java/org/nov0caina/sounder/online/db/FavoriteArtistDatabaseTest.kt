package org.nov0caina.sounder.online.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.nov0caina.sounder.online.model.OnlineArtistSummary

class FavoriteArtistDatabaseTest {

    @Test
    fun favoriteArtistEntity_conversionsPreserveFields() {
        val summary = OnlineArtistSummary(
            id = "UC_TOOL",
            name = "TOOL",
            avatarUrl = "https://img/tool.jpg",
            subtitle = "7.01M monthly audience"
        )
        val entity = FavoriteArtistEntity.fromSummary(summary, timestamp = 123456789L)

        assertEquals("UC_TOOL", entity.id)
        assertEquals("TOOL", entity.name)
        assertEquals("https://img/tool.jpg", entity.avatarUrl)
        assertEquals("7.01M monthly audience", entity.subtitle)
        assertEquals(123456789L, entity.addedAt)

        val restored = entity.toSummary()
        assertEquals(summary.id, restored.id)
        assertEquals(summary.name, restored.name)
        assertEquals(summary.avatarUrl, restored.avatarUrl)
        assertEquals(summary.subtitle, restored.subtitle)
    }

    @Test
    fun migration_4_5_isDefinedWithCorrectVersions() {
        assertNotNull(OnlineDatabase.MIGRATION_4_5)
        assertEquals(4, OnlineDatabase.MIGRATION_4_5.startVersion)
        assertEquals(5, OnlineDatabase.MIGRATION_4_5.endVersion)
    }
}
