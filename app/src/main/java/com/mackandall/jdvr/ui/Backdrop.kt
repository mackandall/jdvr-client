package com.mackandall.jdvr.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

/**
 * A page's background picture: a wide one (fanart, a backdrop) at the top right, fading into the
 * page on the left where the text is and towards the bottom; [strength] is how much it shows.
 * With no wide picture (none, it failed, or it's portrait) the [poster] fills the screen instead,
 * blown up and blurred: loaded tiny and scaled up, which blurs on every Android version.
 */
@Composable
fun Backdrop(
    wide: String?,
    poster: String?,
    strength: Float = 0.9f,
) {
    // Whether the wide picture is usable: null until it has loaded.
    var wideOk by remember(wide) { mutableStateOf<Boolean?>(if (wide.isNullOrEmpty()) false else null) }
    Box(Modifier.fillMaxSize()) {
        if (wideOk != false) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val w = maxWidth * 0.7f
                // The picture itself fades out (to whatever is behind: the theme's own background,
                // a gradient on some), rather than under a coat of one colour.
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(w, w * 9f / 16f)
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                Brush.horizontalGradient(0f to Color.Transparent, 0.28f to Color.Black.copy(alpha = 0.18f), 0.7f to Color.Black.copy(alpha = 0.85f), 1f to Color.Black),
                                blendMode = BlendMode.DstIn,
                            )
                            drawRect(Brush.verticalGradient(0.45f to Color.Black, 0.96f to Color.Transparent), blendMode = BlendMode.DstIn)
                        },
                ) {
                    AsyncImage(
                        model = wide,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        onSuccess = { wideOk = it.result.image.width > it.result.image.height },
                        onError = { wideOk = false },
                        modifier = Modifier.fillMaxSize().alpha(if (wideOk == true) strength else 0f),
                    )
                }
            }
        }
        val blurred = poster?.takeIf { it.isNotEmpty() } ?: wide?.takeIf { wideOk == false && it.isNotEmpty() }
        if (wideOk == false && blurred != null) {
            val context = LocalContext.current
            val request = remember(blurred) { ImageRequest.Builder(context).data(blurred).size(24, 36).build() }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().scale(1.15f).alpha(strength),
            )
            Box(Modifier.fillMaxSize().background(Jc.Ink.copy(alpha = 0.55f)))
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(0f to Jc.Ink.copy(alpha = 0.75f), 0.6f to Jc.Ink.copy(alpha = 0.2f), 1f to Jc.Ink.copy(alpha = 0f)),
                ),
            )
        }
    }
}

/** [Backdrop], fading from one picture to the next as [key] changes (the focused programme or episode). */
@Composable
fun FadingBackdrop(
    key: Any?,
    wide: String?,
    poster: String?,
    strength: Float = 0.9f,
) {
    Crossfade(targetState = Triple(key, wide, poster), animationSpec = tween(600), label = "backdrop") { (k, w, p) ->
        if (k != null) Backdrop(w, p, strength)
    }
}
