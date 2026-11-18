package com.mackandall.jdvr.player

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * The device's own decoders first, then FFmpeg's. Broadcast channels and recordings carry AC3, which a
 * Shield can't decode itself: with a receiver it's passed through over HDMI (the device's renderer takes
 * it); without one, FFmpeg decodes it to PCM instead of the audio track being dropped silently.
 *
 * Video moving to another surface (full screen → the guide's picture, the corner of picture in
 * picture) restarts the decoder there rather than pointing its output at the new surface: the onn
 * sticks' Amlogic MPEG-2 decoder froze on the last picture after two quick moves ("queueBuffer
 * failed: -32", 2026-11-17).
 */
@OptIn(UnstableApi::class)
fun renderersFactory(context: Context): DefaultRenderersFactory =
    object : DefaultRenderersFactory(context) {
        override fun buildVideoRenderers(
            context: Context,
            extensionRendererMode: Int,
            mediaCodecSelector: MediaCodecSelector,
            enableDecoderFallback: Boolean,
            eventHandler: Handler,
            eventListener: VideoRendererEventListener,
            allowedVideoJoiningTimeMs: Long,
            out: ArrayList<Renderer>,
        ) {
            val from = out.size
            super.buildVideoRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, allowedVideoJoiningTimeMs, out)
            for (i in from until out.size) {
                if (out[i].javaClass == MediaCodecVideoRenderer::class.java) {
                    out[i] =
                        object : MediaCodecVideoRenderer(context, mediaCodecSelector, allowedVideoJoiningTimeMs, enableDecoderFallback, eventHandler, eventListener, MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY) {
                            override fun codecNeedsSetOutputSurfaceWorkaround(name: String) = true
                        }
                }
            }
        }
    }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        .setEnableDecoderFallback(true)
