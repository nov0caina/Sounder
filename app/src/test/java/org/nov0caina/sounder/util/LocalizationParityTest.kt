package org.nov0caina.sounder.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.regex.Pattern
import javax.xml.parsers.DocumentBuilderFactory

class LocalizationParityTest {

    private fun loadStringMap(path: String): Map<String, String> {
        val file = File(path)
        assertTrue("Resource file must exist: $path", file.exists())

        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(file)
        val stringNodes = doc.getElementsByTagName("string")

        val map = mutableMapOf<String, String>()
        for (i in 0 until stringNodes.length) {
            val node = stringNodes.item(i)
            val name = node.attributes.getNamedItem("name")?.nodeValue
            if (name != null) {
                map[name] = node.textContent.trim()
            }
        }
        return map
    }

    @Test
    fun testResourceBundleParityAcrossEnglishSpanishAndFrench() {
        val baseStrings = loadStringMap("src/main/res/values/strings.xml")
        val esStrings = loadStringMap("src/main/res/values-es/strings.xml")
        val frStrings = loadStringMap("src/main/res/values-fr/strings.xml")
        val deStrings = loadStringMap("src/main/res/values-de/strings.xml")
        val caStrings = loadStringMap("src/main/res/values-ca/strings.xml")

        // 1. Every key in default bundle must be present in Spanish, French, German and Catalan
        val missingInEs = baseStrings.keys - esStrings.keys
        val missingInFr = baseStrings.keys - frStrings.keys
        val missingInDe = baseStrings.keys - deStrings.keys
        val missingInCa = baseStrings.keys - caStrings.keys

        assertTrue("Missing Spanish keys: $missingInEs", missingInEs.isEmpty())
        assertTrue("Missing French keys: $missingInFr", missingInFr.isEmpty())
        assertTrue("Missing German keys: $missingInDe", missingInDe.isEmpty())
        assertTrue("Missing Catalan keys: $missingInCa", missingInCa.isEmpty())

        // 2. Format specifiers must match exactly
        val placeholderPattern = Pattern.compile("%(\\d+\\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z%]")

        baseStrings.forEach { (key, baseValue) ->
            fun extractTokens(text: String): List<String> {
                val matcher = placeholderPattern.matcher(text)
                val tokens = mutableListOf<String>()
                while (matcher.find()) {
                    tokens.add(matcher.group())
                }
                return tokens.sorted()
            }

            val baseTokens = extractTokens(baseValue)
            val esTokens = extractTokens(esStrings[key] ?: "")
            val frTokens = extractTokens(frStrings[key] ?: "")
            val deTokens = extractTokens(deStrings[key] ?: "")
            val caTokens = extractTokens(caStrings[key] ?: "")

            assertEquals("Placeholder mismatch in Spanish for key '$key'", baseTokens, esTokens)
            assertEquals("Placeholder mismatch in French for key '$key'", baseTokens, frTokens)
            assertEquals("Placeholder mismatch in German for key '$key'", baseTokens, deTokens)
            assertEquals("Placeholder mismatch in Catalan for key '$key'", baseTokens, caTokens)
        }
    }

    @Test
    fun testCoreStreamingTerminologyIsProperlyLocalized() {
        val baseStrings = loadStringMap("src/main/res/values/strings.xml")
        val esStrings = loadStringMap("src/main/res/values-es/strings.xml")
        val frStrings = loadStringMap("src/main/res/values-fr/strings.xml")
        val deStrings = loadStringMap("src/main/res/values-de/strings.xml")
        val caStrings = loadStringMap("src/main/res/values-ca/strings.xml")

        // English defaults
        assertEquals("Home", baseStrings["nav_home"])
        assertEquals("Search", baseStrings["nav_search"])
        assertEquals("Your Library", baseStrings["nav_library"])
        assertEquals("Trending Tracks", baseStrings["header_trending_tracks"])
        assertEquals("Download song", baseStrings["lbl_download_track"])
        assertEquals("Recent Downloads", baseStrings["lbl_recent_downloads"])

        // Spanish localizations
        assertEquals("Inicio", esStrings["nav_home"])
        assertEquals("Buscar", esStrings["nav_search"])
        assertEquals("Tu Biblioteca", esStrings["nav_library"])
        assertEquals("Canciones en Tendencia", esStrings["header_trending_tracks"])
        assertEquals("Descargar canción", esStrings["lbl_download_track"])
        assertEquals("Descargas Recientes", esStrings["lbl_recent_downloads"])

        // French localizations
        assertEquals("Accueil", frStrings["nav_home"])
        assertEquals("Rechercher", frStrings["nav_search"])
        assertEquals("Votre bibliothèque", frStrings["nav_library"])
        assertEquals("Titres populaires", frStrings["header_trending_tracks"])
        assertEquals("Télécharger le titre", frStrings["lbl_download_track"])

        // German localizations
        assertEquals("Start", deStrings["nav_home"])
        assertEquals("Suchen", deStrings["nav_search"])
        assertEquals("Deine Bibliothek", deStrings["nav_library"])
        assertEquals("Beliebte Titel", deStrings["header_trending_tracks"])
        assertEquals("Titel herunterladen", deStrings["lbl_download_track"])

        // Catalan localizations
        assertEquals("Inici", caStrings["nav_home"])
        assertEquals("Cerca", caStrings["nav_search"])
        assertEquals("La teva biblioteca", caStrings["nav_library"])
        assertEquals("Cançons en tendència", caStrings["header_trending_tracks"])
        assertEquals("Baixa la cançó", caStrings["lbl_download_track"])
        assertEquals("Baixades recents", caStrings["lbl_recent_downloads"])

        // Song Details terminology
        assertEquals("View details", baseStrings["lbl_song_detail"])
        assertEquals("Song details", baseStrings["lbl_props"])
        assertEquals("Ver Detalles", esStrings["lbl_song_detail"])
        assertEquals("Detalles de la canción", esStrings["lbl_props"])
        assertEquals("Go to online album", baseStrings["lbl_go_to_online_album"])
        assertEquals("Ir al álbum online", esStrings["lbl_go_to_online_album"])
    }

    @Test
    fun testRecentDownloadsAndLibraryTabsAcrossAll61Locales() {
        val baseStrings = loadStringMap("src/main/res/values/strings.xml")
        val resDir = File("src/main/res")
        val localeDirs = resDir.listFiles { file ->
            file.isDirectory && file.name.startsWith("values-") && File(file, "strings.xml").exists()
        }.orEmpty().sortedBy { it.name }

        assertEquals("Expected 61 language bundles", 61, localeDirs.size)

        val requiredKeys = listOf(
            "lbl_recent_downloads",
            "lng_empty_downloads",
            "download_status_queued",
            "download_retrying",
            "btn_downloading",
            "download_failed",
            "lbl_cancel",
            "lbl_ok",
            "set_lib_tabs",
            "desc_tab_handle",
            "lng_queue_added",
            "lbl_songs",
            "lbl_albums",
            "lbl_artists",
            "lbl_genres",
            "lbl_playlists",
            "lbl_new_playlist",
            "lbl_import_playlist",
            "lbl_edit_playlist",
            "lbl_choose_cover",
            "lbl_remove_cover",
            "set_streaming_cache_size",
            "set_clear_streaming_cache",
            "fmt_streaming_cache_usage",
            "msg_streaming_cache_cleared",
            "lbl_offline_backup",
            "offline_backup_subtitle",
            "offline_backup_storage_summary",
            "offline_backup_mode_badge",
            "btn_play_all",
            "btn_shuffle_play",
            "filter_chip_all",
            "filter_chip_artists",
            "filter_chip_albums",
            "lng_empty_offline_backup",
            "msg_offline_backup_ready",
            "btn_listen",
            "lbl_scan_device",
            "lbl_select_folders",
            "set_detected_folders",
            "lbl_rescan_folders",
            "desc_rescan_folders",
            "lbl_choose_another_folder",
            "lbl_folder_always_included",
            "lbl_custom_folder",
            "lbl_no_music_folders_found",
            "lng_no_music_folders_found",
            "lng_grant_storage_discover",
            "set_filter_settings",
            "set_exclude_short_audio",
            "set_exclude_short_audio_desc",
            "set_exclude_voice_notes",
            "set_exclude_voice_notes_desc",
            "set_exclude_non_music",
            "set_exclude_non_music_desc",
            "set_locations",
            "set_locations_desc",
            "set_scan_device_desc",
            "msg_scanning_device_music",
            "info_app_desc",
            "set_genre_christian",
            "set_genre_christian_desc",
            "genre_christian_worship",
            "set_genre_kpop",
            "set_genre_kpop_desc",
            "lbl_song_detail",
            "lbl_props",
            "btn_view_grid",
            "btn_view_carousel",
            "btn_show_more",
            "btn_show_less",
            "lbl_go_to_online_album",
            "set_privacy_insights_category",
            "set_local_listening_insights",
            "set_local_listening_insights_desc",
            "set_clear_listening_history",
            "set_clear_listening_history_desc",
            "msg_confirm_clear_listening_history",
            "msg_listening_history_cleared",
            "fmt_because_you_listen_to",
            "header_because_you_listen_sub",
            "fmt_monthly_listening_hours",
            "fmt_monthly_listening_minutes",
            "msg_download_deleted",
            "dialog_download_cellular_title",
            "dialog_download_cellular_message",
            "btn_download_now",
            "btn_wait_for_wifi",
            "msg_download_waiting_wifi",
            "dialog_download_waiting_title",
            "dialog_download_waiting_message",
        )

        val removedLegacyKeys = listOf(
            "lng_file_picker",
            "lng_system_database",
            "lng_grant_storage_anyway",
            "lng_grant_storage_required",
            "lng_include_folders",
            "lng_exclude_folders",
            "set_saf_multithread",
            "set_saf_multithread_desc",
            "set_with_hidden",
            "set_with_hidden_desc",
            "set_load_from",
            "set_file_picker",
            "set_system_database",
            "set_grant_storage_anyway",
            "set_folders_to_load",
            "set_folders_to_ignore",
            "set_filtered_folders",
            "set_filter_mode",
            "set_include",
            "set_exclude",
            "set_extra_settings",
            "set_excluded_locations",
            "set_excluded_locations_desc",
            "lbl_no_folders",
            "desc_add_folder",
            "set_reindex",
            "set_reindex_desc",
            "set_rescan",
            "set_rescan_desc",
        )

        for (legacyKey in removedLegacyKeys) {
            assertFalse("Removed legacy key '$legacyKey' still in values/strings.xml", baseStrings.containsKey(legacyKey))
        }

        val placeholderPattern = Pattern.compile("%(\\d+\\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z%]")
        fun extractTokens(text: String): List<String> {
            val matcher = placeholderPattern.matcher(text)
            val tokens = mutableListOf<String>()
            while (matcher.find()) {
                tokens.add(matcher.group())
            }
            return tokens.sorted()
        }

        for (dir in localeDirs) {
            val localeStrings = loadStringMap(File(dir, "strings.xml").path)
            for (key in requiredKeys) {
                val value = localeStrings[key]
                assertFalse("Missing or blank key '$key' in ${dir.name}", value.isNullOrBlank())
                assertEquals(
                    "Placeholder mismatch for '$key' in ${dir.name}",
                    extractTokens(baseStrings[key] ?: ""),
                    extractTokens(value ?: ""),
                )
            }
            for (legacyKey in removedLegacyKeys) {
                assertFalse(
                    "Removed legacy key '$legacyKey' still in ${dir.name}",
                    localeStrings.containsKey(legacyKey),
                )
            }
        }
    }
}
