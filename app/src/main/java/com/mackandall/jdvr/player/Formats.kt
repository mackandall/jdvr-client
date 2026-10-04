package com.mackandall.jdvr.player

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.tv.material3.Text
import com.mackandall.jdvr.ui.Jc
import kotlin.math.roundToInt

/**
 * What's playing right now: the video and audio formats the player is decoding (not what the
 * file's metadata says), and whether the audio leaves the TV box as-is (passthrough to a
 * receiver) or decoded to PCM. Null while unknown.
 */
data class PlayingFormats(
    val video: Format? = null,
    val audio: Format? = null,
    val passthrough: Boolean? = null,
)

@OptIn(UnstableApi::class)
@Composable
fun rememberPlayingFormats(player: ExoPlayer): PlayingFormats {
    var formats by remember { mutableStateOf(PlayingFormats()) }
    DisposableEffect(player) {
        fun selected(type: Int): Format? =
            player.currentTracks.groups
                .filter { it.type == type && it.isSelected }
                .firstNotNullOfOrNull { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } }
        val listener =
            object : AnalyticsListener {
                override fun onTracksChanged(
                    eventTime: AnalyticsListener.EventTime,
                    tracks: Tracks,
                ) {
                    formats = formats.copy(video = selected(C.TRACK_TYPE_VIDEO), audio = selected(C.TRACK_TYPE_AUDIO))
                }

                // The decoder's input: has the frame rate and channel count the track list may lack.
                override fun onVideoInputFormatChanged(
                    eventTime: AnalyticsListener.EventTime,
                    format: Format,
                    decoderReuseEvaluation: DecoderReuseEvaluation?,
                ) {
                    formats = formats.copy(video = format)
                }

                override fun onAudioInputFormatChanged(
                    eventTime: AnalyticsListener.EventTime,
                    format: Format,
                    decoderReuseEvaluation: DecoderReuseEvaluation?,
                ) {
                    formats = formats.copy(audio = format)
                }

                override fun onAudioTrackInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    audioTrackConfig: AudioSink.AudioTrackConfig,
                ) {
                    formats = formats.copy(passthrough = !Util.isEncodingLinearPcm(audioTrackConfig.encoding))
                }
            }
        formats = PlayingFormats(selected(C.TRACK_TYPE_VIDEO), selected(C.TRACK_TYPE_AUDIO))
        player.addAnalyticsListener(listener)
        onDispose { player.removeAnalyticsListener(listener) }
    }
    return formats
}

/**
 * A player's title line with the format badges at its right end. The badges keep their size;
 * when the title needs the room, the grey detail (channels, sample rate, audio path) goes
 * first, then the title ends in "…" (its text should be maxLines = 1 with an ellipsis).
 */
@Composable
fun TitleWithFormats(
    formats: PlayingFormats?,
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit,
) {
    Layout(
        contents = listOf(title, { if (formats != null) FormatPills(formats) }, { if (formats != null) FormatDetail(formats) }),
        modifier = modifier,
    ) { (titleM, pillsM, detailM), constraints ->
        val gap = 28.dp.roundToPx()
        val small = 12.dp.roundToPx()
        val pills = pillsM.map { it.measure(Constraints()) }
        val detail = detailM.map { it.measure(Constraints()) }
        val pillsW = pills.sumOf { it.width }
        val detailW = detail.sumOf { it.width }
        val avail = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE / 2
        val wanted = titleM.sumOf { it.maxIntrinsicWidth(Constraints.Infinity) }
        val showDetail = pillsW > 0 && detailW > 0 && wanted + gap + pillsW + small + detailW <= avail
        val badgesW = if (pillsW == 0) 0 else pillsW + if (showDetail) small + detailW else 0
        val titles = titleM.map { it.measure(Constraints(maxWidth = (avail - if (badgesW > 0) gap + badgesW else 0).coerceAtLeast(0))) }
        val shown = if (showDetail) detail else emptyList()
        val height = (titles + pills + shown).maxOfOrNull { it.height } ?: 0
        val width = if (constraints.hasBoundedWidth) avail else titles.sumOf { it.width } + (if (badgesW > 0) gap + badgesW else 0)
        layout(width, height) {
            // Everything sits on the line's bottom, like the title row it replaces.
            titles.forEach { it.place(0, height - it.height) }
            var x = width - badgesW
            pills.forEach {
                it.place(x, height - it.height)
                x += it.width
            }
            x += small
            shown.forEach { it.place(x, height - it.height - 2.dp.roundToPx()) }
        }
    }
}

/** "HEVC | 1080p60", "HDR10", then "DOLBY DIGITAL | 5.1": the codec pills. */
@Composable
private fun FormatPills(f: PlayingFormats) {
    val video = f.video?.let { videoCodec(it) }
    val audio = f.audio?.let { audioCodec(it) }
    if (video == null && audio == null) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 2.dp)) {
        f.video?.let { v ->
            video?.let { Pill(it, resolution(v)) }
            hdr(v)?.let { Pill(it, null) }
        }
        if (video != null && audio != null) Box(Modifier.padding(horizontal = 3.dp).width(1.dp).height(20.dp).background(PillBorder))
        f.audio?.let { a -> audio?.let { Pill(it, channels(a.channelCount)) } }
    }
}

/** "6 ch · 48 kHz · to receiver": the small grey line after the pills. */
@Composable
private fun FormatDetail(f: PlayingFormats) {
    val a = f.audio ?: return
    val text =
        listOfNotNull(
            a.channelCount.takeIf { it > 0 }?.let { "$it ch" },
            a.sampleRate.takeIf { it > 0 }?.let { "%d kHz".format((it / 1000f).roundToInt()) },
            when (f.passthrough) {
                true -> "to receiver"
                false -> "decoded"
                null -> null
            },
        ).joinToString(" · ")
    if (text.isNotEmpty()) Text(text, color = Jc.Muted, fontSize = 12.sp, maxLines = 1, softWrap = false)
}

private val PillBorder = Color(0xFF3A4452)

@Composable
private fun Pill(
    key: String,
    value: String?,
) {
    Row(Modifier.clip(RoundedCornerShape(5.dp)).border(1.dp, PillBorder, RoundedCornerShape(5.dp))) {
        PillPart(key, Jc.Panel2)
        value?.let { PillPart(it, Color(0xFF2C3644)) }
    }
}

@Composable
private fun PillPart(
    text: String,
    background: Color,
) = Text(
    text,
    color = Jc.Text,
    fontSize = 13.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = 0.5.sp,
    maxLines = 1,
    softWrap = false,
    modifier = Modifier.background(background).padding(horizontal = 7.dp, vertical = 4.dp),
)

private fun videoCodec(f: Format): String? =
    when (f.sampleMimeType) {
        MimeTypes.VIDEO_H265 -> "HEVC"
        MimeTypes.VIDEO_H264 -> "H.264"
        MimeTypes.VIDEO_AV1 -> "AV1"
        MimeTypes.VIDEO_VP9 -> "VP9"
        MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
        MimeTypes.VIDEO_DOLBY_VISION -> "DOLBY VISION"
        MimeTypes.VIDEO_VC1 -> "VC-1"
        null -> null
        else -> f.sampleMimeType?.substringAfter('/')?.uppercase()
    }

/** "1080p60": the height as TVs name it, and the frame rate rounded (59.94 → 60). */
private fun resolution(f: Format): String? {
    if (f.height <= 0) return null
    val lines =
        when {
            f.height >= 2000 -> "2160p"
            f.height >= 1000 -> "1080p"
            f.height >= 700 -> "720p"
            f.height >= 560 -> "576p"
            else -> "${f.height}p"
        }
    return lines + (f.frameRate.takeIf { it > 0 }?.roundToInt()?.toString() ?: "")
}

private fun hdr(f: Format): String? =
    when (f.colorInfo?.colorTransfer) {
        C.COLOR_TRANSFER_ST2084 -> "HDR10"
        C.COLOR_TRANSFER_HLG -> "HLG"
        else -> null
    }

private fun audioCodec(f: Format): String? =
    when (f.sampleMimeType) {
        MimeTypes.AUDIO_AC3 -> "DOLBY DIGITAL"
        MimeTypes.AUDIO_E_AC3 -> "DOLBY DIGITAL PLUS"
        MimeTypes.AUDIO_E_AC3_JOC -> "DOLBY ATMOS"
        MimeTypes.AUDIO_AC4 -> "DOLBY AC-4"
        MimeTypes.AUDIO_TRUEHD -> "DOLBY TRUEHD"
        MimeTypes.AUDIO_DTS -> "DTS"
        MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
        MimeTypes.AUDIO_DTS_EXPRESS -> "DTS EXPRESS"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_MPEG -> "MP3"
        MimeTypes.AUDIO_MPEG_L2 -> "MP2"
        MimeTypes.AUDIO_OPUS -> "OPUS"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        MimeTypes.AUDIO_RAW -> "PCM"
        null -> null
        else -> f.sampleMimeType?.substringAfter('/')?.uppercase()
    }

private fun channels(count: Int): String? =
    when (count) {
        1 -> "MONO"
        2 -> "2.0"
        3 -> "2.1"
        6 -> "5.1"
        8 -> "7.1"
        else -> count.takeIf { it > 0 }?.let { "$it CH" }
    }
