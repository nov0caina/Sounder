package org.nov0caina.sounder.online.cache

enum class OfflineNoticeAnchor {
    PLAYBACK_MINI_PLAYER,
    BOTTOM_NAV,
    NONE,
}

/**
 * Pure decision policy governing when and where the contextual "Offline Backup ready"
 * notification appears upon losing internet connectivity.
 */
object OfflineNotificationPolicy {

    /**
     * Returns true only when:
     * - Connectivity actually transitioned from online -> offline.
     * - At least one playable cached track exists in the Offline Backup.
     * - The user is NOT actively playing local/downloaded music (never disrupt undisturbed local playback).
     * - The user is actively streaming an online track OR browsing an online catalog screen.
     */
    fun shouldShowOfflineBackupNotice(
        wasOnline: Boolean,
        isNowOnline: Boolean,
        hasCachedTracks: Boolean,
        isPlaying: Boolean,
        isCurrentTrackLocalOrDownloaded: Boolean,
        isOnOnlineScreen: Boolean,
    ): Boolean {
        if (!wasOnline || isNowOnline) return false
        if (!hasCachedTracks) return false
        if (isPlaying && isCurrentTrackLocalOrDownloaded) return false
        val isStreamingOnlineTrack = isPlaying && !isCurrentTrackLocalOrDownloaded
        return isStreamingOnlineTrack || isOnOnlineScreen
    }

    /**
     * Determines the cleanest Snackbar anchor so controls in the mini-player and bottom nav
     * are never obscured.
     */
    fun selectAnchor(
        isPlaybackBarCollapsedVisible: Boolean,
        isBottomNavVisible: Boolean,
    ): OfflineNoticeAnchor = when {
        isPlaybackBarCollapsedVisible -> OfflineNoticeAnchor.PLAYBACK_MINI_PLAYER
        isBottomNavVisible -> OfflineNoticeAnchor.BOTTOM_NAV
        else -> OfflineNoticeAnchor.NONE
    }
}
