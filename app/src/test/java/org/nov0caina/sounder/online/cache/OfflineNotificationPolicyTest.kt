package org.nov0caina.sounder.online.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineNotificationPolicyTest {

    @Test
    fun `shows notice when connectivity drops while streaming an online track with cached backup`() {
        val show = OfflineNotificationPolicy.shouldShowOfflineBackupNotice(
            wasOnline = true,
            isNowOnline = false,
            hasCachedTracks = true,
            isPlaying = true,
            isCurrentTrackLocalOrDownloaded = false,
            isOnOnlineScreen = false,
        )
        assertTrue(show)
    }

    @Test
    fun `shows notice when connectivity drops while browsing online catalog with cached backup`() {
        val show = OfflineNotificationPolicy.shouldShowOfflineBackupNotice(
            wasOnline = true,
            isNowOnline = false,
            hasCachedTracks = true,
            isPlaying = false,
            isCurrentTrackLocalOrDownloaded = false,
            isOnOnlineScreen = true,
        )
        assertTrue(show)
    }

    @Test
    fun `suppresses notice when user is actively playing local or downloaded music`() {
        val showOnLibrary = OfflineNotificationPolicy.shouldShowOfflineBackupNotice(
            wasOnline = true,
            isNowOnline = false,
            hasCachedTracks = true,
            isPlaying = true,
            isCurrentTrackLocalOrDownloaded = true,
            isOnOnlineScreen = false,
        )
        val showEvenOnHome = OfflineNotificationPolicy.shouldShowOfflineBackupNotice(
            wasOnline = true,
            isNowOnline = false,
            hasCachedTracks = true,
            isPlaying = true,
            isCurrentTrackLocalOrDownloaded = true,
            isOnOnlineScreen = true,
        )
        assertFalse(showOnLibrary)
        assertFalse(showEvenOnHome)
    }

    @Test
    fun `suppresses notice when offline backup cache is empty`() {
        val show = OfflineNotificationPolicy.shouldShowOfflineBackupNotice(
            wasOnline = true,
            isNowOnline = false,
            hasCachedTracks = false,
            isPlaying = true,
            isCurrentTrackLocalOrDownloaded = false,
            isOnOnlineScreen = true,
        )
        assertFalse(show)
    }

    @Test
    fun `suppresses notice when not transitioning from online to offline`() {
        assertFalse(
            OfflineNotificationPolicy.shouldShowOfflineBackupNotice(
                wasOnline = false,
                isNowOnline = false,
                hasCachedTracks = true,
                isPlaying = true,
                isCurrentTrackLocalOrDownloaded = false,
                isOnOnlineScreen = true,
            )
        )
        assertFalse(
            OfflineNotificationPolicy.shouldShowOfflineBackupNotice(
                wasOnline = false,
                isNowOnline = true,
                hasCachedTracks = true,
                isPlaying = true,
                isCurrentTrackLocalOrDownloaded = false,
                isOnOnlineScreen = true,
            )
        )
    }

    @Test
    fun `selectAnchor prefers collapsed mini-player over bottom nav to avoid obstructing controls`() {
        assertEquals(
            OfflineNoticeAnchor.PLAYBACK_MINI_PLAYER,
            OfflineNotificationPolicy.selectAnchor(
                isPlaybackBarCollapsedVisible = true,
                isBottomNavVisible = true,
            )
        )
        assertEquals(
            OfflineNoticeAnchor.BOTTOM_NAV,
            OfflineNotificationPolicy.selectAnchor(
                isPlaybackBarCollapsedVisible = false,
                isBottomNavVisible = true,
            )
        )
        assertEquals(
            OfflineNoticeAnchor.NONE,
            OfflineNotificationPolicy.selectAnchor(
                isPlaybackBarCollapsedVisible = false,
                isBottomNavVisible = false,
            )
        )
    }
}
