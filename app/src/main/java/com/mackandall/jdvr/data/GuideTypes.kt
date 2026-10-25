package com.mackandall.jdvr.data

import java.time.LocalDateTime
import java.util.UUID
import org.jellyfin.sdk.model.api.PlayMethod

/** A live channel, with what's on now. */
data class Channel(
    val id: UUID,
    val number: String,
    val name: String,
    val logoUrl: String?,
    val now: Program?,
)

/** A guide entry. [rawEpisodeTitle] still carries Episode History's marks ("💾 Ranch Dressing"). */
data class Program(
    val id: UUID,
    val channelId: UUID,
    val name: String,
    val rawEpisodeTitle: String?,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val overview: String?,
    val isMovie: Boolean,
    val isSeries: Boolean,
    val isRepeat: Boolean,
    val season: Int?,
    val episode: Int?,
    val timerId: String?,
    val seriesTimerId: String?,
) {
    val isRecording: Boolean get() = timerId != null
    val seasonEpisode: String? get() = if (season != null && episode != null) "S$season:E$episode" else null

    fun isOn(at: LocalDateTime = LocalDateTime.now()) = !start.isAfter(at) && end.isAfter(at)
}

/** Everything needed to play a live channel and to tell Jellyfin about it. */
data class LivePlayback(
    val channelId: UUID,
    val url: String,
    val mediaSourceId: String?,
    val liveStreamId: String?,
    val playSessionId: String?,
    val playMethod: PlayMethod,
    // The same live stream as HLS (a remux the server keeps from tune-in on): where pause and
    // rewind go, so they're instant. Null if the server wouldn't give one.
    val hlsUrl: String? = null,
)

/** A recording (library item): how to play it, and where the user left off. */
data class ItemPlayback(
    val itemId: UUID,
    val name: String,
    val url: String,
    val mediaSourceId: String?,
    val playSessionId: String?,
    val playMethod: PlayMethod,
    val resumeMs: Long,
    val durationMs: Long?,
    val seriesId: String? = null,
    // Subtitle files Jellyfin serves beside the video (.srt, .vtt, .ass…).
    val subtitles: List<ExternalSubtitle> = emptyList(),
)

data class ExternalSubtitle(
    val url: String,
    val mimeType: String,
    val language: String?,
    val label: String?,
)

/** A series recording's scheduled airings: how many, and when and where the next one is. */
data class SeriesScheduled(
    val count: Int,
    val next: java.time.LocalDateTime,
    val nextChannel: String?,
)

/** A stretch to skip: "Commercial", "Intro", "Recap", "Preview" or "Outro". */
data class Segment(
    val type: String,
    val startMs: Long,
    val endMs: Long,
)

// The guide's channel groups on Jellyfin.
const val ALL_CHANNELS = "All channels"
const val FAVOURITE_CHANNELS = "Favourites"
