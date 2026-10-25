package com.mackandall.jdvr.player

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.Prefs
import com.mackandall.jdvr.ui.Button
import java.util.Locale

/** One audio or subtitle track the player can switch to. */
private data class TrackOption(
    val group: Tracks.Group,
    val index: Int,
    val label: String,
    val selected: Boolean,
)

private fun languageName(code: String?): String? = code?.takeIf { it.isNotBlank() && it != "und" }?.let { Locale.forLanguageTag(it).displayLanguage.ifBlank { it } }

@OptIn(UnstableApi::class)
private fun options(
    player: Player,
    type: Int,
): List<TrackOption> =
    player.currentTracks.groups.filter { it.type == type && it.isSupported }.flatMap { g ->
        (0 until g.length).filter { g.isTrackSupported(it) }.map { i ->
            val f = g.getTrackFormat(i)
            val kind =
                when {
                    f.sampleMimeType?.contains("cea", ignoreCase = true) == true -> "CC"
                    f.channelCount == 6 -> "5.1"
                    f.channelCount == 2 -> "Stereo"
                    else -> null
                }
            val label = listOfNotNull(f.label ?: languageName(f.language), kind).joinToString(" ").ifBlank { "Track ${i + 1}" }
            TrackOption(g, i, label, g.isTrackSelected(i))
        }
    }

/**
 * Applies Settings › Subtitles when something starts: "on" picks a subtitle or caption track
 * when there is one (the device's language first, else any), "off" leaves them off.
 */
@OptIn(UnstableApi::class)
fun applySubtitlePreference(player: ExoPlayer) {
    val on = Jdvr.prefs.subtitles == Prefs.ON
    player.trackSelectionParameters =
        player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !on)
            .setPreferredTextLanguage(if (on) Locale.getDefault().language else null)
            .setSelectUndeterminedTextLanguage(on)
            .build()
}

/**
 * Audio and Subtitles buttons for a player's controls row, each shown only when there's a choice
 * (more than one audio track; any subtitle or caption track). OK steps to the next option;
 * subtitles go Off → each track → Off.
 */
@Composable
fun TrackButtons(player: Player) {
    // Redraw when the tracks change (a new channel, a file's tracks once known).
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    version++
                }
            }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    version.let { }

    val audio = options(player, C.TRACK_TYPE_AUDIO)
    if (audio.size > 1) {
        val current = audio.indexOfFirst { it.selected }
        Button("Audio: " + (audio.getOrNull(current)?.label ?: "Auto")) {
            val next = audio[(current + 1).mod(audio.size)]
            player.trackSelectionParameters =
                player.trackSelectionParameters
                    .buildUpon()
                    .setOverrideForType(TrackSelectionOverride(next.group.mediaTrackGroup, next.index))
                    .build()
        }
    }

    val text = options(player, C.TRACK_TYPE_TEXT)
    if (text.isNotEmpty()) {
        val disabled = C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes
        val current = if (disabled) -1 else text.indexOfFirst { it.selected }
        Button("Subtitles: " + (text.getOrNull(current)?.label ?: "Off")) {
            val nextIndex = current + 1
            val params = player.trackSelectionParameters.buildUpon()
            if (nextIndex >= text.size) {
                params.clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            } else {
                val next = text[nextIndex]
                params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(next.group.mediaTrackGroup, next.index))
            }
            player.trackSelectionParameters = params.build()
        }
    }
}
