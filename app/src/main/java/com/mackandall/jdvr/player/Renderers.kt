package com.mackandall.jdvr.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory

/**
 * The device's own decoders first, then FFmpeg's. Broadcast channels and recordings carry AC3, which a
 * Shield can't decode itself: with a receiver it's passed through over HDMI (the device's renderer takes
 * it); without one, FFmpeg decodes it to PCM instead of the audio track being dropped silently.
 */
@OptIn(UnstableApi::class)
fun renderersFactory(context: Context) =
    DefaultRenderersFactory(context)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        .setEnableDecoderFallback(true)
