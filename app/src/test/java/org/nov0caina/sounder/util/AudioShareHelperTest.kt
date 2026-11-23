package org.nov0caina.sounder.util

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioShareHelperTest {

    @Test
    fun `detectContainer identifies MP4 OGG WebM MP3 and FLAC headers accurately`() {
        val tempDir = Files.createTempDirectory("audio_share_test").toFile()

        val mp4 = File(tempDir, "song.m4a").apply {
            writeBytes(byteArrayOf(0x00, 0x00, 0x00, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 'M'.code.toByte(), '4'.code.toByte(), 'A'.code.toByte(), ' '.code.toByte()))
        }
        val ogg = File(tempDir, "song.opus").apply {
            writeBytes(byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte(), 0, 2, 0, 0, 0, 0, 0, 0))
        }
        val webm = File(tempDir, "legacy.opus").apply {
            writeBytes(byteArrayOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte(), 0x9F.toByte(), 0x42, 0x86.toByte(), 0x81.toByte(), 1, 0, 0, 0))
        }
        val mp3 = File(tempDir, "song.mp3").apply {
            writeBytes(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0, 0, 0, 0, 0))
        }
        val flac = File(tempDir, "song.flac").apply {
            writeBytes(byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte(), 0, 0, 0, 0x22, 0, 0, 0, 0))
        }

        assertEquals(AudioShareHelper.AudioContainer.MP4, AudioShareHelper.detectContainer(mp4))
        assertEquals("audio/mp4", AudioShareHelper.mimeTypeForContainer(AudioShareHelper.AudioContainer.MP4, "m4a"))

        assertEquals(AudioShareHelper.AudioContainer.OGG, AudioShareHelper.detectContainer(ogg))
        assertEquals("audio/ogg", AudioShareHelper.mimeTypeForContainer(AudioShareHelper.AudioContainer.OGG, "opus"))

        assertEquals(AudioShareHelper.AudioContainer.WEBM, AudioShareHelper.detectContainer(webm))
        assertEquals("audio/webm", AudioShareHelper.mimeTypeForContainer(AudioShareHelper.AudioContainer.WEBM, "opus"))

        assertEquals(AudioShareHelper.AudioContainer.MP3, AudioShareHelper.detectContainer(mp3))
        assertEquals("audio/mpeg", AudioShareHelper.mimeTypeForContainer(AudioShareHelper.AudioContainer.MP3, "mp3"))

        assertEquals(AudioShareHelper.AudioContainer.FLAC, AudioShareHelper.detectContainer(flac))
        assertEquals("audio/flac", AudioShareHelper.mimeTypeForContainer(AudioShareHelper.AudioContainer.FLAC, "flac"))
    }
}
