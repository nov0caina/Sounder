/*
 * Copyright (c) 2023 Auxio Project
 * SystemModule.kt is part of Auxio.
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
 
package org.nov0caina.sounder.playback.service

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.flac.FlacExtractor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.ogg.OggExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.ts.AdtsExtractor
import androidx.media3.extractor.wav.WavExtractor
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import kotlinx.coroutines.runBlocking
import org.nov0caina.sounder.online.repository.OnlineMusicRepository

@Module
@InstallIn(SingletonComponent::class)
@OptIn(UnstableApi::class)
class SystemModule {
    @Provides
    fun mediaSourceFactory(
        dataSourceFactory: DataSource.Factory,
        extractorsFactory: ExtractorsFactory,
    ): MediaSource.Factory = ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)

    @Provides
    fun dataSourceFactory(
        @ApplicationContext context: Context,
        onlineRepository: Lazy<OnlineMusicRepository>,
    ): DataSource.Factory {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Sounder")
            .setAllowCrossProtocolRedirects(true)
        val defaultDataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        return ResolvingDataSource.Factory(defaultDataSourceFactory) { dataSpec ->
            val uriStr = dataSpec.uri.toString()
            if (uriStr.contains("watch?v=") || uriStr.startsWith("https://music.youtube.com/watch") || uriStr.startsWith("sounder://")) {
                val videoId = dataSpec.uri.getQueryParameter("v")
                    ?: uriStr.substringAfterLast("watch?v=").substringBefore("&")
                    .takeIf { it.isNotBlank() }
                    ?: dataSpec.uri.lastPathSegment ?: ""

                if (videoId.isNotBlank()) {
                    val sp = context.getSharedPreferences("sounder_downloads_store", Context.MODE_PRIVATE)
                    val localPath = sp.getString(videoId, null) ?: sp.getString("path_$videoId", null)
                    if (localPath != null && File(localPath).exists()) {
                        return@Factory dataSpec.buildUpon().setUri(android.net.Uri.fromFile(File(localPath))).build()
                    }
                    val streamUrl = runBlocking {
                        try {
                            onlineRepository.get().resolveStreamUrl(videoId)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (!streamUrl.isNullOrBlank()) {
                        return@Factory dataSpec.buildUpon().setUri(android.net.Uri.parse(streamUrl)).build()
                    }
                }
            }
            dataSpec
        }
    }

    @Provides
    fun extractorsFactory() = ExtractorsFactory {
        // Define our own extractors so we can exclude non-audio parsers.
        // Ordering is derived from the DefaultExtractorsFactory's optimized ordering:
        // https://docs.google.com/document/d/1w2mKaWMxfz2Ei8-LdxqbPs1VLe_oudB-eryXXw9OvQQ.
        arrayOf(
            FlacExtractor(),
            WavExtractor(),
            FragmentedMp4Extractor(DefaultSubtitleParserFactory.UNSUPPORTED),
            Mp4Extractor(DefaultSubtitleParserFactory.UNSUPPORTED),
            OggExtractor(),
            MatroskaExtractor(DefaultSubtitleParserFactory.UNSUPPORTED),
            // Enable constant bitrate seeking so that certain MP3s/AACs are seekable
            AdtsExtractor(AdtsExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING),
            Mp3Extractor(Mp3Extractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING),
        )
    }
}
