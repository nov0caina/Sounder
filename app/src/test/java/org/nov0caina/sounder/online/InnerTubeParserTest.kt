package org.nov0caina.sounder.online

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nov0caina.sounder.online.innertube.InnerTubeParser
import org.nov0caina.sounder.online.model.AudioQuality
import org.nov0caina.sounder.online.model.OnlineTrack

class InnerTubeParserTest {

    @Test
    fun testParseDuration() {
        assertEquals(202000L, InnerTubeParser.parseDuration("3:22"))
        assertEquals(45000L, InnerTubeParser.parseDuration("0:45"))
        assertEquals(3910000L, InnerTubeParser.parseDuration("1:05:10"))
        assertEquals(0L, InnerTubeParser.parseDuration(""))
        assertEquals(0L, InnerTubeParser.parseDuration("invalid"))
    }

    @Test
    fun testUpgradeArtworkResolution() {
        val original = "https://yt3.googleusercontent.com/test=w120-h120-l90-rj"
        val upgraded = InnerTubeParser.upgradeArtworkResolution(original)
        assertEquals("https://yt3.googleusercontent.com/test=w544-h544-l90-rj", upgraded)
    }

    @Test
    fun testParseSearchResults() {
        val sampleJson = """
        {
          "contents": {
            "tabbedSearchResultsRenderer": {
              "tabs": [
                {
                  "tabRenderer": {
                    "content": {
                      "sectionListRenderer": {
                        "contents": [
                          {
                            "musicShelfRenderer": {
                              "contents": [
                                {
                                  "musicResponsiveListItemRenderer": {
                                    "playlistItemData": {
                                      "videoId": "test_video_123"
                                    },
                                    "flexColumns": [
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": {
                                            "runs": [
                                              { "text": "Blinding Lights" }
                                            ]
                                          }
                                        }
                                      },
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": {
                                            "runs": [
                                              { "text": "The Weeknd" },
                                              { "text": " • " },
                                              { "text": "After Hours" },
                                              { "text": " • " },
                                              { "text": "3:20" }
                                            ]
                                          }
                                        }
                                      }
                                    ],
                                    "thumbnail": {
                                      "musicThumbnailRenderer": {
                                        "thumbnail": {
                                          "thumbnails": [
                                            { "url": "https://i.ytimg.com/vi/test/hqdefault.jpg=w120-h120" }
                                          ]
                                        }
                                      }
                                    }
                                  }
                                }
                              ]
                            }
                          }
                        ]
                      }
                    }
                  }
                }
              ]
            }
          }
        }
        """.trimIndent()

        val results = InnerTubeParser.parseSearchResults(sampleJson)
        assertEquals(1, results.size)

        val track = results[0]
        assertEquals("test_video_123", track.id)
        assertEquals("Blinding Lights", track.title)
        assertEquals("The Weeknd", track.artist)
        assertEquals("After Hours", track.album)
        assertEquals(200000L, track.durationMs)
        assertNotNull(track.artworkUrl)
    }

    @Test
    fun testParseMultiCategorySearch() {
        val sampleJson = """
        {
          "contents": {
            "tabbedSearchResultsRenderer": {
              "tabs": [
                {
                  "tabRenderer": {
                    "content": {
                      "sectionListRenderer": {
                        "contents": [
                          {
                            "musicCardShelfRenderer": {
                              "title": {
                                "runs": [
                                  {
                                    "text": "Rammstein",
                                    "navigationEndpoint": {
                                      "browseEndpoint": {
                                        "browseId": "UCs6GGpd9zvsYghuYe0VDFUQ",
                                        "browseEndpointContextSupportedConfigs": {
                                          "browseEndpointContextMusicConfig": {
                                            "pageType": "MUSIC_PAGE_TYPE_ARTIST"
                                          }
                                        }
                                      }
                                    }
                                  }
                                ]
                              },
                              "subtitle": {
                                "runs": [
                                  { "text": "Artist" },
                                  { "text": " • " },
                                  { "text": "45.9M monthly audience" }
                                ]
                              },
                              "thumbnail": {
                                "musicThumbnailRenderer": {
                                  "thumbnail": {
                                    "thumbnails": [
                                      { "url": "https://lh3.googleusercontent.com/avatar=w120-h120" }
                                    ]
                                  }
                                }
                              }
                            }
                          },
                          {
                            "itemSectionRenderer": {
                              "contents": [
                                {
                                  "musicResponsiveListItemRenderer": {
                                    "playlistItemData": { "videoId": "vid_du_hast" },
                                    "flexColumns": [
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": { "runs": [{ "text": "Du hast" }] }
                                        }
                                      },
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": {
                                            "runs": [
                                              { "text": "Song" },
                                              { "text": " • " },
                                              { "text": "Rammstein" },
                                              { "text": " • " },
                                              { "text": "Sehnsucht" },
                                              { "text": " • " },
                                              { "text": "3:54" }
                                            ]
                                          }
                                        }
                                      }
                                    ]
                                  }
                                }
                              ]
                            }
                          }
                        ]
                      }
                    }
                  }
                }
              ]
            }
          }
        }
        """.trimIndent()

        val result = InnerTubeParser.parseMultiCategorySearch(sampleJson)
        assertEquals(1, result.artists.size)
        assertEquals("UCs6GGpd9zvsYghuYe0VDFUQ", result.artists[0].id)
        assertEquals("Rammstein", result.artists[0].name)
        assertEquals("45.9M monthly audience", result.artists[0].subtitle)

        assertEquals(1, result.tracks.size)
        assertEquals("vid_du_hast", result.tracks[0].id)
        assertEquals("Du hast", result.tracks[0].title)
        assertEquals(234000L, result.tracks[0].durationMs)
    }

    @Test
    fun testTrackItemsDoNotGeneratePhantomArtistsWithoutAvatars() {
        val sampleJson = """
        {
          "contents": {
            "tabbedSearchResultsRenderer": {
              "tabs": [
                {
                  "tabRenderer": {
                    "content": {
                      "sectionListRenderer": {
                        "contents": [
                          {
                            "itemSectionRenderer": {
                              "contents": [
                                {
                                  "musicResponsiveListItemRenderer": {
                                    "playlistItemData": { "videoId": "track_123" },
                                    "flexColumns": [
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": { "runs": [{ "text": "Song Name" }] }
                                        }
                                      },
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": {
                                            "runs": [
                                              { "text": "Song" },
                                              { "text": " • " },
                                              {
                                                "text": "Song Artist",
                                                "navigationEndpoint": {
                                                  "browseEndpoint": {
                                                    "browseId": "UCartist123",
                                                    "browseEndpointContextSupportedConfigs": {
                                                      "browseEndpointContextMusicConfig": {
                                                        "pageType": "MUSIC_PAGE_TYPE_ARTIST"
                                                      }
                                                    }
                                                  }
                                                }
                                              }
                                            ]
                                          }
                                        }
                                      }
                                    ]
                                  }
                                }
                              ]
                            }
                          }
                        ]
                      }
                    }
                  }
                }
              ]
            }
          }
        }
        """.trimIndent()

        val result = InnerTubeParser.parseMultiCategorySearch(sampleJson)
        assertEquals(1, result.tracks.size)
        assertTrue("Artists list should not contain phantom artists extracted from song items", result.artists.isEmpty())
    }

    @Test
    fun testParseArtistDetail() {
        val sampleJson = """
        {
          "header": {
            "musicImmersiveHeaderRenderer": {
              "title": { "runs": [{ "text": "Rammstein" }] },
              "description": { "runs": [{ "text": "Legendary German Neue Deutsche Härte band." }] },
              "thumbnail": {
                "musicThumbnailRenderer": {
                  "thumbnail": { "thumbnails": [{ "url": "https://lh3.googleusercontent.com/hero=w120-h120" }] }
                }
              }
            }
          },
          "contents": {
            "singleColumnBrowseResultsRenderer": {
              "tabs": [
                {
                  "tabRenderer": {
                    "content": {
                      "sectionListRenderer": {
                        "contents": [
                          {
                            "musicShelfRenderer": {
                              "title": { "runs": [{ "text": "Top songs" }] },
                              "contents": [
                                {
                                  "musicResponsiveListItemRenderer": {
                                    "playlistItemData": { "videoId": "top_vid_1" },
                                    "flexColumns": [
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": { "runs": [{ "text": "Sonne" }] }
                                        }
                                      },
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": { "runs": [{ "text": "Rammstein" }, { "text": " • " }, { "text": "4:32" }] }
                                        }
                                      }
                                    ]
                                  }
                                }
                              ]
                            }
                          },
                          {
                            "musicCarouselShelfRenderer": {
                              "header": {
                                "musicCarouselShelfBasicHeaderRenderer": {
                                  "title": { "runs": [{ "text": "Albums" }] }
                                }
                              },
                              "contents": [
                                {
                                  "musicTwoRowItemRenderer": {
                                    "title": { "runs": [{ "text": "Zeit" }] },
                                    "navigationEndpoint": {
                                      "browseEndpoint": { "browseId": "MPREb_zeit_album" }
                                    },
                                    "subtitle": { "runs": [{ "text": "2022" }] },
                                    "thumbnailRenderer": {
                                      "musicThumbnailRenderer": {
                                        "thumbnail": { "thumbnails": [{ "url": "https://yt3.googleusercontent.com/zeit=w120-h120" }] }
                                      }
                                    }
                                  }
                                }
                              ]
                            }
                          },
                          {
                            "musicCarouselShelfRenderer": {
                              "header": {
                                "musicCarouselShelfBasicHeaderRenderer": {
                                  "title": { "runs": [{ "text": "Fans might also like" }] }
                                }
                              },
                              "contents": [
                                {
                                  "musicTwoRowItemRenderer": {
                                    "title": { "runs": [{ "text": "Eisbrecher" }] },
                                    "navigationEndpoint": {
                                      "browseEndpoint": { "browseId": "UC_eisbrecher" }
                                    },
                                    "subtitle": { "runs": [{ "text": "1.2M subscribers" }] }
                                  }
                                }
                              ]
                            }
                          }
                        ]
                      }
                    }
                  }
                }
              ]
            }
          }
        }
        """.trimIndent()

        val detail = InnerTubeParser.parseArtistDetail("UCs6GGpd9zvsYghuYe0VDFUQ", sampleJson)
        assertEquals("Rammstein", detail.name)
        assertEquals("Legendary German Neue Deutsche Härte band.", detail.description)
        assertEquals(1, detail.topSongs.size)
        assertEquals("Sonne", detail.topSongs[0].title)
        assertEquals(1, detail.albums.size)
        assertEquals("Zeit", detail.albums[0].title)
        assertEquals("2022", detail.albums[0].year)
        assertEquals(1, detail.relatedArtists.size)
        assertEquals("Eisbrecher", detail.relatedArtists[0].name)
    }

    @Test
    fun testParseArtistDetailPrefersMonthlyListenerCount() {
        val sampleJson = """
        {
          "header": {
            "musicImmersiveHeaderRenderer": {
              "title": { "runs": [{ "text": "Korn" }] },
              "subscriptionButton": {
                "subscribeButtonRenderer": {
                  "subscriberCountText": { "runs": [{ "text": "3.81M" }] },
                  "longSubscriberCountText": { "runs": [{ "text": "3.81M subscribers" }] }
                }
              },
              "monthlyListenerCount": {
                "runs": [{ "text": "16.9M monthly audience" }]
              }
            }
          }
        }
        """.trimIndent()

        val detail = InnerTubeParser.parseArtistDetail("UCdSgOgQ0WtelXBMKF3tDsqg", sampleJson)
        assertEquals("Korn", detail.name)
        assertEquals("16.9M monthly audience", detail.subscribers)
    }

    @Test
    fun testCleanArtistDescription() {
        val raw = "AC/DC are an Australian rock band formed in Sydney in 1973.\n\nFrom Wikipedia (https://en.wikipedia.org/wiki/AC/DC) under Creative Commons"
        val cleaned = InnerTubeParser.cleanArtistDescription(raw)
        assertEquals("AC/DC are an Australian rock band formed in Sydney in 1973.", cleaned)
    }

    @Test
    fun testParseAlbumDetail() {
        val sampleJson = """
        {
          "contents": {
            "twoColumnBrowseResultsRenderer": {
              "tabs": [
                {
                  "tabRenderer": {
                    "content": {
                      "sectionListRenderer": {
                        "contents": [
                          {
                            "musicResponsiveHeaderRenderer": {
                              "title": { "runs": [{ "text": "Zeit" }] },
                              "straplineTextOne": { "runs": [{ "text": "Rammstein" }] },
                              "subtitle": { "runs": [{ "text": "Album" }, { "text": " • " }, { "text": "2022" }] },
                              "thumbnail": {
                                "musicThumbnailRenderer": {
                                  "thumbnail": { "thumbnails": [{ "url": "https://yt3.googleusercontent.com/cover=w120-h120" }] }
                                }
                              }
                            }
                          }
                        ]
                      }
                    }
                  }
                }
              ],
              "secondaryContents": {
                "sectionListRenderer": {
                  "contents": [
                    {
                      "musicShelfRenderer": {
                        "contents": [
                          {
                            "musicResponsiveListItemRenderer": {
                              "playlistItemData": { "videoId": "track_zeit_1" },
                              "flexColumns": [
                                {
                                  "musicResponsiveListItemFlexColumnRenderer": {
                                    "text": { "runs": [{ "text": "Armee der Tristen" }] }
                                  }
                                }
                              ],
                              "fixedColumns": [
                                {
                                  "musicResponsiveListItemFixedColumnRenderer": {
                                    "text": { "runs": [{ "text": "3:26" }] }
                                  }
                                }
                              ]
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
              }
            }
          }
        }
        """.trimIndent()

        val album = InnerTubeParser.parseAlbumDetail("MPREb_zeit_album", sampleJson)
        assertEquals("Zeit", album.title)
        assertEquals("Rammstein", album.artist)
        assertEquals("2022", album.year)
        assertEquals(1, album.tracks.size)
        assertEquals("Armee der Tristen", album.tracks[0].title)
        assertEquals("track_zeit_1", album.tracks[0].id)
        assertEquals(206000L, album.tracks[0].durationMs)
    }

    @Test
    fun testParseStreamUrl() {
        val playerJson = """
        {
          "streamingData": {
            "adaptiveFormats": [
              {
                "itag": 249,
                "mimeType": "audio/webm; codecs=\"opus\"",
                "bitrate": 50000,
                "url": "https://stream.audio/low"
              },
              {
                "itag": 251,
                "mimeType": "audio/webm; codecs=\"opus\"",
                "bitrate": 160000,
                "url": "https://stream.audio/high"
              }
            ]
          }
        }
        """.trimIndent()

        val highUrl = InnerTubeParser.parseStreamUrl(playerJson, AudioQuality.HIGH_QUALITY)
        assertEquals("https://stream.audio/high", highUrl)

        val dataSaverUrl = InnerTubeParser.parseStreamUrl(playerJson, AudioQuality.DATA_SAVER)
        assertEquals("https://stream.audio/low", dataSaverUrl)

        val progressiveJson = """
        {
          "streamingData": {
            "formats": [
              {
                "itag": 18,
                "mimeType": "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"",
                "bitrate": 381467,
                "url": "https://stream.progressive/itag18"
              }
            ]
          }
        }
        """.trimIndent()
        val fallbackUrl = InnerTubeParser.parseStreamUrl(progressiveJson, AudioQuality.HIGH_QUALITY)
        assertEquals("https://stream.progressive/itag18", fallbackUrl)
    }

    @Test
    fun testTrackStudioClassification() {
        // 1. Studio track with MUSIC_VIDEO_TYPE_ATV
        val atvJson = JSONObject("""
        {
          "playlistItemData": { "videoId": "studio_1" },
          "doubleTapCommand": {
            "watchEndpoint": {
              "videoId": "studio_1",
              "watchEndpointMusicSupportedConfigs": {
                "watchEndpointMusicConfig": {
                  "musicVideoType": "MUSIC_VIDEO_TYPE_ATV"
                }
              }
            }
          },
          "flexColumns": [
            {
              "musicResponsiveListItemFlexColumnRenderer": {
                "text": { "runs": [{ "text": "Thunderstruck" }] }
              }
            },
            {
              "musicResponsiveListItemFlexColumnRenderer": {
                "text": { "runs": [{ "text": "AC/DC" }, { "text": " • " }, { "text": "4:52" }] }
              }
            }
          ]
        }
        """.trimIndent())
        val atvTrack = InnerTubeParser.parseTrackItem(atvJson)
        assertNotNull(atvTrack)
        assertTrue(atvTrack!!.isStudioTrack)

        // 2. Video track with MUSIC_VIDEO_TYPE_OMV
        val omvJson = JSONObject("""
        {
          "playlistItemData": { "videoId": "video_1" },
          "doubleTapCommand": {
            "watchEndpoint": {
              "videoId": "video_1",
              "watchEndpointMusicSupportedConfigs": {
                "watchEndpointMusicConfig": {
                  "musicVideoType": "MUSIC_VIDEO_TYPE_OMV"
                }
              }
            }
          },
          "flexColumns": [
            {
              "musicResponsiveListItemFlexColumnRenderer": {
                "text": { "runs": [{ "text": "Thunderstruck (Official Video)" }] }
              }
            },
            {
              "musicResponsiveListItemFlexColumnRenderer": {
                "text": { "runs": [{ "text": "AC/DC" }, { "text": " • " }, { "text": "4:52" }] }
              }
            }
          ]
        }
        """.trimIndent())
        val omvTrack = InnerTubeParser.parseTrackItem(omvJson)
        assertNotNull(omvTrack)
        assertFalse(omvTrack!!.isStudioTrack)

        // 3. Track with "Canción" run badge
        val badgeSongJson = JSONObject("""
        {
          "playlistItemData": { "videoId": "badge_song" },
          "flexColumns": [
            {
              "musicResponsiveListItemFlexColumnRenderer": {
                "text": { "runs": [{ "text": "Down with the Sickness" }] }
              }
            },
            {
              "musicResponsiveListItemFlexColumnRenderer": {
                "text": { "runs": [{ "text": "Canción" }, { "text": " • " }, { "text": "Disturbed" }] }
              }
            }
          ]
        }
        """.trimIndent())
        val badgeSongTrack = InnerTubeParser.parseTrackItem(badgeSongJson)
        assertNotNull(badgeSongTrack)
        assertTrue(badgeSongTrack!!.isStudioTrack)

        // 4. Track with "Vídeo" run badge
        val badgeVideoJson = JSONObject("""
        {
          "playlistItemData": { "videoId": "badge_video" },
          "flexColumns": [
            {
              "musicResponsiveListItemFlexColumnRenderer": {
                "text": { "runs": [{ "text": "Down with the Sickness Live" }] }
              }
            },
            {
              "musicResponsiveListItemFlexColumnRenderer": {
                "text": { "runs": [{ "text": "Vídeo" }, { "text": " • " }, { "text": "Disturbed" }] }
              }
            }
          ]
        }
        """.trimIndent())
        val badgeVideoTrack = InnerTubeParser.parseTrackItem(badgeVideoJson)
        assertNotNull(badgeVideoTrack)
        assertFalse(badgeVideoTrack!!.isStudioTrack)
    }

    @Test
    fun testPrioritizeStudioTracksDeduplicationAndFallback() {
        val videoThunderstruck = OnlineTrack(
            id = "vid_thunder",
            title = "Thunderstruck (Official Video)",
            artist = "AC/DC",
            album = null,
            durationMs = 292000L,
            artworkUrl = null,
            isStudioTrack = false
        )
        val studioThunderstruck = OnlineTrack(
            id = "studio_thunder",
            title = "Thunderstruck",
            artist = "AC/DC",
            album = "The Razors Edge",
            durationMs = 292000L,
            artworkUrl = null,
            isStudioTrack = true
        )
        val videoJam = OnlineTrack(
            id = "vid_jam",
            title = "Acoustic Live Jam",
            artist = "AC/DC",
            album = null,
            durationMs = 180000L,
            artworkUrl = null,
            isStudioTrack = false
        )
        val studioHighway = OnlineTrack(
            id = "studio_highway",
            title = "Highway to Hell",
            artist = "AC/DC",
            album = "Highway to Hell",
            durationMs = 208000L,
            artworkUrl = null,
            isStudioTrack = true
        )

        val mixedTracks = listOf(videoThunderstruck, studioThunderstruck, videoJam, studioHighway)

        // Prioritized: Studio tracks come first, duplicate video is dropped, non-duplicate video kept as fallback
        val prioritized = InnerTubeParser.prioritizeStudioTracks(mixedTracks, preferStudio = true)
        assertEquals(3, prioritized.size)
        assertEquals("studio_thunder", prioritized[0].id)
        assertEquals("studio_highway", prioritized[1].id)
        assertEquals("vid_jam", prioritized[2].id)

        // Non-prioritized: exact original order and count preserved
        val untouched = InnerTubeParser.prioritizeStudioTracks(mixedTracks, preferStudio = false)
        assertEquals(4, untouched.size)
        assertEquals("vid_thunder", untouched[0].id)
        assertEquals("studio_thunder", untouched[1].id)
        assertEquals("vid_jam", untouched[2].id)
        assertEquals("studio_highway", untouched[3].id)
    }

    @Test
    fun testPrioritizeStudioTracksAllVideosFallback() {
        val video1 = OnlineTrack("v1", "Live Performance 1", "Artist", null, 200000L, null, isStudioTrack = false)
        val video2 = OnlineTrack("v2", "Live Performance 2", "Artist", null, 250000L, null, isStudioTrack = false)
        val videoList = listOf(video1, video2)

        val result = InnerTubeParser.prioritizeStudioTracks(videoList, preferStudio = true)
        assertEquals(2, result.size)
        assertEquals("v1", result[0].id)
        assertEquals("v2", result[1].id)
    }

    @Test
    fun testPodcastEpisodeAndNonMusicTrackRejection() {
        // 1. Episode via pageType MUSIC_PAGE_TYPE_PODCAST_EPISODE
        val episodePageTypeJson = JSONObject("""
        {
          "playlistItemData": { "videoId": "ep_vid_1" },
          "navigationEndpoint": {
            "browseEndpoint": {
              "browseId": "MPED_123",
              "browseEndpointContextSupportedConfigs": {
                "browseEndpointContextMusicConfig": {
                  "pageType": "MUSIC_PAGE_TYPE_PODCAST_EPISODE"
                }
              }
            }
          },
          "flexColumns": [
            {
              "musicResponsiveListItemFlexColumnRenderer": {
                "text": { "runs": [{ "text": "Episode 42: Deep Tech Talks" }] }
              }
            },
            {
              "musicResponsiveListItemFlexColumnRenderer": {
                "text": { "runs": [{ "text": "Tech Channel" }, { "text": " • " }, { "text": "45:00" }] }
              }
            }
          ]
        }
        """.trimIndent())
        assertNull(InnerTubeParser.parseTrackItem(episodePageTypeJson))

        // 2. Non-music track via MUSIC_PAGE_TYPE_NON_MUSIC_AUDIO_TRACK_PAGE
        val nonMusicAudioJson = JSONObject("""
        {
          "playlistItemData": { "videoId": "non_music_vid" },
          "navigationEndpoint": {
            "browseEndpoint": {
              "browseId": "audiobook_track",
              "browseEndpointContextSupportedConfigs": {
                "browseEndpointContextMusicConfig": {
                  "pageType": "MUSIC_PAGE_TYPE_NON_MUSIC_AUDIO_TRACK_PAGE"
                }
              }
            }
          },
          "flexColumns": [
            {
              "musicResponsiveListItemFlexColumnRenderer": {
                "text": { "runs": [{ "text": "Chapter 1: The Beginning" }] }
              }
            },
            {
              "musicResponsiveListItemFlexColumnRenderer": {
                "text": { "runs": [{ "text": "Narrator Guy" }, { "text": " • " }, { "text": "12:30" }] }
              }
            }
          ]
        }
        """.trimIndent())
        assertNull(InnerTubeParser.parseTrackItem(nonMusicAudioJson))

        // 3. Episode via "Episode" text badge run in subtitle
        val episodeBadgeRunJson = JSONObject("""
        {
          "playlistItemData": { "videoId": "ep_run_vid" },
          "flexColumns": [
            {
              "musicResponsiveListItemFlexColumnRenderer": {
                "text": { "runs": [{ "text": "Cave Art Secrets" }] }
              }
            },
            {
              "musicResponsiveListItemFlexColumnRenderer": {
                "text": { "runs": [{ "text": "Episode" }, { "text": " • " }, { "text": "Ancient Origins" }] }
              }
            }
          ]
        }
        """.trimIndent())
        assertNull(InnerTubeParser.parseTrackItem(episodeBadgeRunJson))

        // 4. Episode via "Episodio" Spanish text badge
        val episodioBadgeRunJson = JSONObject("""
        {
          "playlistItemData": { "videoId": "ep_es_vid" },
          "flexColumns": [
            {
              "musicResponsiveListItemFlexColumnRenderer": {
                "text": { "runs": [{ "text": "Misterios del Universo" }] }
              }
            },
            {
              "musicResponsiveListItemFlexColumnRenderer": {
                "text": { "runs": [{ "text": "Episodio" }, { "text": " • " }, { "text": "Ciencia Hoy" }] }
              }
            }
          ]
        }
        """.trimIndent())
        assertNull(InnerTubeParser.parseTrackItem(episodioBadgeRunJson))

        // 5. Podcast via inline badge accessibility data
        val podcastBadgeJson = JSONObject("""
        {
          "playlistItemData": { "videoId": "podcast_badge_vid" },
          "flexColumns": [
            {
              "musicResponsiveListItemFlexColumnRenderer": {
                "text": { "runs": [{ "text": "Daily News Digest" }] }
              }
            },
            {
              "musicResponsiveListItemFlexColumnRenderer": {
                "text": { "runs": [{ "text": "Global News" }] }
              }
            }
          ],
          "badges": [
            {
              "musicInlineBadgeRenderer": {
                "accessibilityData": "Podcast episode"
              }
            }
          ]
        }
        """.trimIndent())
        assertNull(InnerTubeParser.parseTrackItem(podcastBadgeJson))

        // 6. Podcast show browseId in artist or album navigation
        val podcastShowNavJson = JSONObject("""
        {
          "playlistItemData": { "videoId": "podcast_show_vid" },
          "flexColumns": [
            {
              "musicResponsiveListItemFlexColumnRenderer": {
                "text": { "runs": [{ "text": "Interviews with Scientists" }] }
              }
            },
            {
              "musicResponsiveListItemFlexColumnRenderer": {
                "text": {
                  "runs": [
                    {
                      "text": "Science Show",
                      "navigationEndpoint": {
                        "browseEndpoint": {
                          "browseId": "MPSP_science_show_123",
                          "browseEndpointContextSupportedConfigs": {
                            "browseEndpointContextMusicConfig": {
                              "pageType": "MUSIC_PAGE_TYPE_PODCAST_SHOW"
                            }
                          }
                        }
                      }
                    }
                  ]
                }
              }
            }
          ]
        }
        """.trimIndent())
        assertNull(InnerTubeParser.parseTrackItem(podcastShowNavJson))
    }

    @Test
    fun testUserChannelAndPodcastShowRejectionFromSearch() {
        val searchJson = JSONObject("""
        {
          "contents": {
            "tabbedSearchResultsRenderer": {
              "tabs": [
                {
                  "tabRenderer": {
                    "content": {
                      "sectionListRenderer": {
                        "contents": [
                          {
                            "musicCardShelfRenderer": {
                              "title": {
                                "runs": [
                                  {
                                    "text": "Gaming Creator Daily",
                                    "navigationEndpoint": {
                                      "browseEndpoint": {
                                        "browseId": "UC_gamer_channel_xyz",
                                        "browseEndpointContextSupportedConfigs": {
                                          "browseEndpointContextMusicConfig": {
                                            "pageType": "MUSIC_PAGE_TYPE_USER_CHANNEL"
                                          }
                                        }
                                      }
                                    }
                                  }
                                ]
                              },
                              "subtitle": {
                                "runs": [{ "text": "Channel" }, { "text": " • " }, { "text": "2M subscribers" }]
                              }
                            }
                          },
                          {
                            "musicShelfRenderer": {
                              "contents": [
                                {
                                  "musicResponsiveListItemRenderer": {
                                    "navigationEndpoint": {
                                      "browseEndpoint": {
                                        "browseId": "UC_vlogger_profile",
                                        "browseEndpointContextSupportedConfigs": {
                                          "browseEndpointContextMusicConfig": {
                                            "pageType": "MUSIC_PAGE_TYPE_USER_PROFILE"
                                          }
                                        }
                                      }
                                    },
                                    "flexColumns": [
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": { "runs": [{ "text": "Vlogger Guy" }] }
                                        }
                                      },
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": { "runs": [{ "text": "Profile" }] }
                                        }
                                      }
                                    ]
                                  }
                                },
                                {
                                  "musicResponsiveListItemRenderer": {
                                    "playlistItemData": { "videoId": "legit_metal_song" },
                                    "doubleTapCommand": {
                                      "watchEndpoint": {
                                        "watchEndpointMusicSupportedConfigs": {
                                          "watchEndpointMusicConfig": {
                                            "musicVideoType": "MUSIC_VIDEO_TYPE_ATV"
                                          }
                                        }
                                      }
                                    },
                                    "flexColumns": [
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": { "runs": [{ "text": "Holy Wars" }] }
                                        }
                                      },
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": {
                                            "runs": [
                                              {
                                                "text": "Megadeth",
                                                "navigationEndpoint": {
                                                  "browseEndpoint": {
                                                    "browseId": "UC_megadeth_id",
                                                    "browseEndpointContextSupportedConfigs": {
                                                      "browseEndpointContextMusicConfig": {
                                                        "pageType": "MUSIC_PAGE_TYPE_ARTIST"
                                                      }
                                                    }
                                                  }
                                                }
                                              },
                                              { "text": " • " },
                                              { "text": "Rust in Peace" }
                                            ]
                                          }
                                        }
                                      }
                                    ]
                                  }
                                }
                              ]
                            }
                          }
                        ]
                      }
                    }
                  }
                }
              ]
            }
          }
        }
        """.trimIndent())

        val result = InnerTubeParser.parseMultiCategorySearch(searchJson.toString())

        // Non-music user channel and profile MUST NOT be added to artists
        assertTrue(result.artists.none { it.id == "UC_gamer_channel_xyz" })
        assertTrue(result.artists.none { it.id == "UC_vlogger_profile" })

        // Neither user channel nor user profile should leak into tracks
        assertEquals(1, result.tracks.size)
        assertEquals("legit_metal_song", result.tracks[0].id)
        assertEquals("Holy Wars", result.tracks[0].title)
        assertEquals("Megadeth", result.tracks[0].artist)
    }

    @Test
    fun testLegitimateSongWithEpisodeOrShowInTitlePreserved() {
        val songWithEpisodeInTitle = JSONObject("""
        {
          "playlistItemData": { "videoId": "dr_dre_ep_1" },
          "doubleTapCommand": {
            "watchEndpoint": {
              "watchEndpointMusicSupportedConfigs": {
                "watchEndpointMusicConfig": {
                  "musicVideoType": "MUSIC_VIDEO_TYPE_ATV"
                }
              }
            }
          },
          "flexColumns": [
            {
              "musicResponsiveListItemFlexColumnRenderer": {
                "text": { "runs": [{ "text": "Episode 1" }] }
              }
            },
            {
              "musicResponsiveListItemFlexColumnRenderer": {
                "text": {
                  "runs": [
                    {
                      "text": "Dr. Dre",
                      "navigationEndpoint": {
                        "browseEndpoint": {
                          "browseId": "UC_dr_dre_id",
                          "browseEndpointContextSupportedConfigs": {
                            "browseEndpointContextMusicConfig": {
                              "pageType": "MUSIC_PAGE_TYPE_ARTIST"
                            }
                          }
                        }
                      }
                    },
                    { "text": " • " },
                    {
                      "text": "2001",
                      "navigationEndpoint": {
                        "browseEndpoint": {
                          "browseId": "MPRE_2001_album",
                          "browseEndpointContextSupportedConfigs": {
                            "browseEndpointContextMusicConfig": {
                              "pageType": "MUSIC_PAGE_TYPE_ALBUM"
                            }
                          }
                        }
                      }
                    }
                  ]
                }
              }
            }
          ]
        }
        """.trimIndent())

        val track = InnerTubeParser.parseTrackItem(songWithEpisodeInTitle)
        assertNotNull(track)
        assertEquals("dr_dre_ep_1", track!!.id)
        assertEquals("Episode 1", track.title)
        assertEquals("Dr. Dre", track.artist)
        assertEquals("2001", track.album)
        assertTrue(track.isStudioTrack)

        val songWithShowInTitle = JSONObject("""
        {
          "playlistItemData": { "videoId": "queen_show_vid" },
          "doubleTapCommand": {
            "watchEndpoint": {
              "watchEndpointMusicSupportedConfigs": {
                "watchEndpointMusicConfig": {
                  "musicVideoType": "MUSIC_VIDEO_TYPE_ATV"
                }
              }
            }
          },
          "flexColumns": [
            {
              "musicResponsiveListItemFlexColumnRenderer": {
                "text": { "runs": [{ "text": "The Show Must Go On" }] }
              }
            },
            {
              "musicResponsiveListItemFlexColumnRenderer": {
                "text": {
                  "runs": [
                    {
                      "text": "Queen",
                      "navigationEndpoint": {
                        "browseEndpoint": {
                          "browseId": "UC_queen_id",
                          "browseEndpointContextSupportedConfigs": {
                            "browseEndpointContextMusicConfig": {
                              "pageType": "MUSIC_PAGE_TYPE_ARTIST"
                            }
                          }
                        }
                      }
                    },
                    { "text": " • " },
                    { "text": "Innuendo" }
                  ]
                }
              }
            }
          ]
        }
        """.trimIndent())

        val trackQueen = InnerTubeParser.parseTrackItem(songWithShowInTitle)
        assertNotNull(trackQueen)
        assertEquals("queen_show_vid", trackQueen!!.id)
        assertEquals("The Show Must Go On", trackQueen.title)
        assertEquals("Queen", trackQueen.artist)
        assertTrue(trackQueen.isStudioTrack)
    }

    @Test
    fun testAlbumDisambiguationRejectsPodcastShow() {
        val albumDetail = InnerTubeParser.parseAlbumDetail("MPSP_podcast_show_123", "{}")
        assertEquals(0, albumDetail.tracks.size)
        assertEquals("MPSP_podcast_show_123", albumDetail.id)
    }

    @Test
    fun testLowQualityChannelFilterExcludesRandomSubscribersWhileKeepingVerifiedArtists() {
        assertFalse(InnerTubeParser.isLowQualityUserChannel("Metallica", "75.8M monthly audience"))
        assertFalse(InnerTubeParser.isLowQualityUserChannel("Bloodywood", "317K oyentes mensuales"))
        assertFalse(InnerTubeParser.isLowQualityUserChannel("Queen", "10M Subscribers"))
        // Legitimate emerging/independent artists with < 50K subscribers remain visible
        assertFalse(InnerTubeParser.isLowQualityUserChannel("Abel Meza", "800 suscriptores"))
        assertFalse(InnerTubeParser.isLowQualityUserChannel("Sofía Valdés", "3.4K subscribers"))

        // Generic genre spam, AI music channels, and low-quality reupload channels are excluded
        assertTrue(InnerTubeParser.isLowQualityUserChannel("HeavyPulse Metal", "2 subscribers"))
        assertTrue(InnerTubeParser.isLowQualityUserChannel("Heavy Metal Songs", "79 subscribers"))
        assertTrue(InnerTubeParser.isLowQualityUserChannel("Acordes de Metal", "114 suscriptores"))
        assertTrue(InnerTubeParser.isLowQualityUserChannel("Metal Scar Radio", "17.9K subscribers"))
        assertTrue(InnerTubeParser.isLowQualityUserChannel("Acordes de Metal Pro", "200K subscribers"))
        assertTrue(InnerTubeParser.isLowQualityUserChannel("Suno AI Music", "15K subscribers"))
        assertTrue(InnerTubeParser.isLowQualityUserChannel("Rock Covers - IA", "80K subscribers"))
        assertTrue(InnerTubeParser.isLowQualityUserChannel("Canciones 8D Slowed", "120K subscribers"))
    }

    @Test
    fun testParseStreamUrlPrefersMp4OverWebmForCompatibility() {
        val mixedJson = """
        {
          "streamingData": {
            "adaptiveFormats": [
              {
                "itag": 251,
                "mimeType": "audio/webm; codecs=\"opus\"",
                "bitrate": 160000,
                "url": "https://stream.audio/webm"
              },
              {
                "itag": 140,
                "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                "bitrate": 129000,
                "url": "https://stream.audio/m4a"
              }
            ]
          }
        }
        """.trimIndent()

        val selected = InnerTubeParser.parseStreamUrl(mixedJson, AudioQuality.HIGH_QUALITY)
        assertEquals("https://stream.audio/m4a", selected)
    }

    @Test
    fun testExplicitBadgeDetectionAndCleanVersionDeduplication() {
        val explicitJson = JSONObject("""
        {
          "playlistItemData": { "videoId": "vid_explicit" },
          "badges": [
            {
              "musicInlineBadgeRenderer": {
                "icon": { "iconType": "MUSIC_EXPLICIT_BADGE" },
                "accessibilityData": {
                  "accessibilityData": { "label": "Explicit" }
                }
              }
            }
          ],
          "flexColumns": [
            {
              "musicResponsiveListItemFlexColumnRenderer": {
                "text": { "runs": [{ "text": "HUMBLE." }] }
              }
            },
            {
              "musicResponsiveListItemFlexColumnRenderer": {
                "text": { "runs": [{ "text": "Kendrick Lamar" }, { "text": " • " }, { "text": "2:57" }] }
              }
            }
          ]
        }
        """.trimIndent())

        val explicitTrack = InnerTubeParser.parseTrackItem(explicitJson)
        assertNotNull(explicitTrack)
        assertTrue("MUSIC_EXPLICIT_BADGE must set isExplicit=true", explicitTrack!!.isExplicit)

        val cleanVersion = OnlineTrack(
            id = "vid_clean",
            title = "HUMBLE. (Clean)",
            artist = "Kendrick Lamar",
            album = "DAMN.",
            durationMs = 177_000L,
            artworkUrl = null,
            isStudioTrack = true,
            isExplicit = false
        )
        val nonExplicitDup = explicitTrack.copy(id = "vid_unlabeled", isExplicit = false)

        val prioritized = InnerTubeParser.prioritizeExplicitTracks(
            listOf(cleanVersion, nonExplicitDup, explicitTrack)
        )
        assertEquals(1, prioritized.size)
        assertEquals("vid_explicit", prioritized[0].id)
    }

    @Test
    fun testParseArtistDetailAndDiscographyBrowseExtractsAllAlbums() {
        val artistOverviewJson = """
        {
          "header": {
            "musicImmersiveHeaderRenderer": {
              "title": { "runs": [{ "text": "Kendrick Lamar" }] }
            }
          },
          "contents": {
            "singleColumnBrowseResultsRenderer": {
              "tabs": [
                {
                  "tabRenderer": {
                    "content": {
                      "sectionListRenderer": {
                        "contents": [
                          {
                            "musicCarouselShelfRenderer": {
                              "header": {
                                "musicCarouselShelfBasicHeaderRenderer": {
                                  "title": { "runs": [{ "text": "Álbumes" }] },
                                  "moreContentButton": {
                                    "buttonRenderer": {
                                      "navigationEndpoint": {
                                        "browseEndpoint": {
                                          "browseId": "MPAD_kendrick",
                                          "params": "ggMI..."
                                        }
                                      }
                                    }
                                  }
                                }
                              },
                              "contents": [
                                {
                                  "musicTwoRowItemRenderer": {
                                    "title": { "runs": [{ "text": "GNX" }] },
                                    "subtitle": { "runs": [{ "text": "2024" }] },
                                    "navigationEndpoint": {
                                      "browseEndpoint": { "browseId": "MPREb_gnx" }
                                    }
                                  }
                                }
                              ]
                            }
                          }
                        ]
                      }
                    }
                  }
                }
              ]
            }
          }
        }
        """.trimIndent()

        val detail = InnerTubeParser.parseArtistDetail("UC_kendrick", artistOverviewJson)
        assertEquals("MPAD_kendrick", detail.albumsBrowseId)
        assertEquals("ggMI...", detail.albumsBrowseParams)
        assertEquals(1, detail.albums.size)
        assertEquals("GNX", detail.albums[0].title)

        val discographyGridJson = """
        {
          "contents": {
            "singleColumnBrowseResultsRenderer": {
              "tabs": [
                {
                  "tabRenderer": {
                    "content": {
                      "sectionListRenderer": {
                        "contents": [
                          {
                            "gridRenderer": {
                              "items": [
                                {
                                  "musicTwoRowItemRenderer": {
                                    "title": { "runs": [{ "text": "GNX" }] },
                                    "subtitle": { "runs": [{ "text": "2024" }] },
                                    "navigationEndpoint": {
                                      "browseEndpoint": { "browseId": "MPREb_gnx" }
                                    }
                                  }
                                },
                                {
                                  "musicTwoRowItemRenderer": {
                                    "title": { "runs": [{ "text": "DAMN." }] },
                                    "subtitle": { "runs": [{ "text": "2017" }] },
                                    "navigationEndpoint": {
                                      "browseEndpoint": { "browseId": "MPREb_damn" }
                                    }
                                  }
                                },
                                {
                                  "musicTwoRowItemRenderer": {
                                    "title": { "runs": [{ "text": "good kid, m.A.A.d city" }] },
                                    "subtitle": { "runs": [{ "text": "2012" }] },
                                    "navigationEndpoint": {
                                      "browseEndpoint": { "browseId": "MPREb_gkmc" }
                                    }
                                  }
                                }
                              ],
                              "continuations": [
                                {
                                  "nextContinuationData": {
                                    "continuation": "next_album_page_token"
                                  }
                                }
                              ]
                            }
                          }
                        ]
                      }
                    }
                  }
                }
              ]
            }
          }
        }
        """.trimIndent()

        val (parsedAlbums, token) = InnerTubeParser.parseArtistDiscographyBrowseWithContinuation(discographyGridJson)
        assertEquals(3, parsedAlbums.size)
        assertEquals("DAMN.", parsedAlbums[1].title)
        assertEquals("2012", parsedAlbums[2].year)
        assertEquals("next_album_page_token", token)
    }

    @Test
    fun testAbelMezaRetainedInLowQualityFilterAndArtistSearchParsingEvenWithLowSubscribers() {
        assertFalse(InnerTubeParser.isLowQualityUserChannel("Abel Meza", "12K subscribers"))
        assertFalse(InnerTubeParser.isLowQualityUserChannel("Abel Meza", "800 suscriptores"))

        val searchJson = """
        {
          "contents": {
            "tabbedSearchResultsRenderer": {
              "tabs": [
                {
                  "tabRenderer": {
                    "content": {
                      "sectionListRenderer": {
                        "contents": [
                          {
                            "musicCardShelfRenderer": {
                              "title": {
                                "runs": [
                                  {
                                    "text": "Abel Meza",
                                    "navigationEndpoint": {
                                      "browseEndpoint": {
                                        "browseId": "UC_abel_card",
                                        "browseEndpointContextSupportedConfigs": {
                                          "browseEndpointContextMusicConfig": {
                                            "pageType": "MUSIC_PAGE_TYPE_ARTIST"
                                          }
                                        }
                                      }
                                    }
                                  }
                                ]
                              },
                              "subtitle": {
                                "runs": [{ "text": "Artist" }, { "text": " • " }, { "text": "800 suscriptores" }]
                              },
                              "thumbnail": {
                                "musicThumbnailRenderer": {
                                  "thumbnail": { "thumbnails": [{ "url": "https://yt3.googleusercontent.com/abel=w120-h120" }] }
                                }
                              }
                            }
                          },
                          {
                            "musicShelfRenderer": {
                              "contents": [
                                {
                                  "musicResponsiveListItemRenderer": {
                                    "navigationEndpoint": {
                                      "browseEndpoint": {
                                        "browseId": "UC_abel_list",
                                        "browseEndpointContextSupportedConfigs": {
                                          "browseEndpointContextMusicConfig": {
                                            "pageType": "MUSIC_PAGE_TYPE_ARTIST"
                                          }
                                        }
                                      }
                                    },
                                    "flexColumns": [
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": { "runs": [{ "text": "Abel Meza" }] }
                                        }
                                      },
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": { "runs": [{ "text": "12K subscribers" }] }
                                        }
                                      }
                                    ],
                                    "thumbnail": {
                                      "musicThumbnailRenderer": {
                                        "thumbnail": { "thumbnails": [{ "url": "https://yt3.googleusercontent.com/abel2=w120-h120" }] }
                                      }
                                    }
                                  }
                                }
                              ]
                            }
                          }
                        ]
                      }
                    }
                  }
                }
              ]
            }
          }
        }
        """.trimIndent()

        val result = InnerTubeParser.parseMultiCategorySearch(searchJson)
        assertEquals(2, result.artists.size)
        assertEquals("Abel Meza", result.artists[0].name)
        assertEquals("UC_abel_card", result.artists[0].id)
        assertEquals("Abel Meza", result.artists[1].name)
        assertEquals("UC_abel_list", result.artists[1].id)
    }

    @Test
    fun testParseTrackItemRejectsArtistRowsAndQuickAccessSelectsSongsOnly() {
        val artistRowWithAudience = JSONObject("""
        {
          "navigationEndpoint": {
            "browseEndpoint": {
              "browseId": "UC_fuerza_regida",
              "browseEndpointContextSupportedConfigs": {
                "browseEndpointContextMusicConfig": {
                  "pageType": "MUSIC_PAGE_TYPE_ARTIST"
                }
              }
            }
          },
          "playlistItemData": { "videoId": "unexpected_vid" },
          "flexColumns": [
            {
              "musicResponsiveListItemFlexColumnRenderer": {
                "text": { "runs": [{ "text": "Fuerza Regida" }] }
              }
            },
            {
              "musicResponsiveListItemFlexColumnRenderer": {
                "text": { "runs": [{ "text": "7,26 M de oyentes mensuales" }] }
              }
            }
          ]
        }
        """.trimIndent())
        assertNull(InnerTubeParser.parseTrackItem(artistRowWithAudience))

        val artistSubtitleMetricOnly = JSONObject("""
        {
          "playlistItemData": { "videoId": "artist_metric_vid" },
          "flexColumns": [
            {
              "musicResponsiveListItemFlexColumnRenderer": {
                "text": { "runs": [{ "text": "Fuerza Regida" }] }
              }
            },
            {
              "musicResponsiveListItemFlexColumnRenderer": {
                "text": { "runs": [{ "text": "7.26M monthly audience" }] }
              }
            }
          ]
        }
        """.trimIndent())
        assertNull(InnerTubeParser.parseTrackItem(artistSubtitleMetricOnly))

        val sampleFeed = org.nov0caina.sounder.online.model.HomeFeedData(
            popularArtists = listOf(
                org.nov0caina.sounder.online.model.OnlineArtistSummary(
                    id = "UC_fuerza_regida",
                    name = "Fuerza Regida",
                    avatarUrl = "https://art/fr.jpg",
                    subtitle = "7,26 M monthly audience"
                )
            ),
            quickPicks = listOf(
                org.nov0caina.sounder.online.model.OnlineTrack("s1", "Sin Fin De Lucro", "LA DIFERENCIA", "Album 1", 180_000L, "https://art/1.jpg"),
                org.nov0caina.sounder.online.model.OnlineTrack("s2", "Dreamer Hats", "Omar C", "Album 2", 190_000L, "https://art/2.jpg"),
                org.nov0caina.sounder.online.model.OnlineTrack("bogus_artist", "Fuerza Regida", "7,26 M subscribers", null, 0L, null)
            ),
            trendingTracks = listOf(
                org.nov0caina.sounder.online.model.OnlineTrack("s2", "Dreamer Hats", "Omar C", "Album 2", 190_000L, "https://art/2.jpg"),
                org.nov0caina.sounder.online.model.OnlineTrack("s3", "Kalashnikov", "Cartel de Santa", "Album 3", 210_000L, "https://art/3.jpg"),
                org.nov0caina.sounder.online.model.OnlineTrack("s4", "Curados y Alucinados", "Cartel de Santa", "Album 4", 205_000L, "https://art/4.jpg"),
                org.nov0caina.sounder.online.model.OnlineTrack("s5", "Extra Song", "Artist 5", "Album 5", 200_000L, "https://art/5.jpg"),
                org.nov0caina.sounder.online.model.OnlineTrack("s6", "Sixth Song", "Artist 6", "Album 6", 200_000L, "https://art/6.jpg")
            )
        )

        val selected = org.nov0caina.sounder.online.ui.HomeFeedViewModel.selectQuickAccessTracks(sampleFeed, maxTracks = 5)
        assertEquals(listOf("s1", "s2", "s3", "s4", "s5"), selected.map { it.id })
    }
}
