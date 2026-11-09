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

        // Spanish localizations
        assertEquals("Inicio", esStrings["nav_home"])
        assertEquals("Buscar", esStrings["nav_search"])
        assertEquals("Tu Biblioteca", esStrings["nav_library"])
        assertEquals("Canciones en Tendencia", esStrings["header_trending_tracks"])
        assertEquals("Descargar canción", esStrings["lbl_download_track"])

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
    }
}
