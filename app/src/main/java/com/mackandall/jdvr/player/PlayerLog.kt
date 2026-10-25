package com.mackandall.jdvr.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import timber.log.Timber

/**
 * What a player is doing, in the debug build's log (tag JDVRPlayer): state changes, play and
 * pause, seeks and errors, each with the position. For finding stalls nobody saw an error for.
 */
fun playerLog(
    label: String,
    player: Player,
): Player.Listener =
    object : Player.Listener {
        private fun at() =
            "at ${player.currentPosition / 1000}s of ${player.duration
                .takeIf { it > 0 }
                ?.div(1000)
                ?.let { "${it}s" } ?: "?"}, buffered to ${player.bufferedPosition / 1000}s"

        private fun log(what: String) = Timber.tag("JDVRPlayer").i("%s: %s %s", label, what, at())

        override fun onPlaybackStateChanged(state: Int) =
            log(
                when (state) {
                    Player.STATE_IDLE -> "idle"
                    Player.STATE_BUFFERING -> "buffering"
                    Player.STATE_READY -> "ready"
                    Player.STATE_ENDED -> "ended"
                    else -> "state $state"
                },
            )

        override fun onIsPlayingChanged(isPlaying: Boolean) = log(if (isPlaying) "playing" else "not playing")

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                log("seek from ${oldPosition.positionMs / 1000}s to ${newPosition.positionMs / 1000}s")
            }
        }

        override fun onPlayerError(error: PlaybackException) = log("error ${error.errorCodeName}: ${error.message}")
    }
