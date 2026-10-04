package com.mackandall.jdvr.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.mackandall.jdvr.data.Sky
import kotlin.random.Random

/**
 * The Home screen's sky for the current weather, drawn (no pictures to download): a sky colour for
 * the conditions and time of day, the sun or moon and stars, clouds, and gently falling rain or
 * snow, or flashes for a storm.
 */
/**
 * SageTV style: a picture for the conditions and time of day, shipped with the app (Wikimedia
 * Commons, public domain / CC0; assets/weather/CREDITS.txt), with rain, snow or lightning drawn
 * falling over it. Clear days alternate between two pictures by the hour.
 */
@Composable
fun WeatherPicture(
    sky: Sky,
    isDay: Boolean,
    modifier: Modifier = Modifier,
) {
    val name =
        when (sky) {
            Sky.Clear -> if (isDay) (if (java.time.LocalTime.now().hour % 2 == 0) "clear-day-1" else "clear-day-2") else "clear-night"
            Sky.PartlyCloudy -> if (isDay) "partly-day" else "cloudy-night"
            Sky.Cloudy -> if (isDay) "cloudy-day" else "cloudy-night"
            Sky.Fog -> if (isDay) "fog-day" else "fog-night"
            Sky.Rain -> if (isDay) "rain-day" else "rain-night"
            Sky.Snow -> if (isDay) "snow-day" else "snow-night"
            Sky.Storm -> "storm"
        }
    androidx.compose.foundation.layout.Box(modifier.fillMaxSize()) {
        coil3.compose.AsyncImage(
            model = "file:///android_asset/weather/$name.jpg",
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // Falling rain / snow and storm flashes over the picture.
        if (sky == Sky.Rain || sky == Sky.Snow || sky == Sky.Storm) WeatherBackdrop(sky, isDay, overlayOnly = true)
    }
}

@Composable
fun WeatherBackdrop(
    sky: Sky,
    isDay: Boolean,
    modifier: Modifier = Modifier,
    // Only the falling rain / snow and flashes (over a picture), no sky, sun or clouds.
    overlayOnly: Boolean = false,
) {
    val falling = sky == Sky.Rain || sky == Sky.Snow || sky == Sky.Storm
    val t =
        if (falling) {
            val tr = rememberInfiniteTransition(label = "weather")
            tr.animateFloat(0f, 1f, infiniteRepeatable(tween(if (sky == Sky.Snow) 14_000 else 2_200, easing = LinearEasing)), label = "fall").value
        } else {
            0f
        }
    // Fixed positions for stars, drops and flakes (the same every frame).
    val dots = remember(sky) { val r = Random(7); List(140) { Offset(r.nextFloat(), r.nextFloat()) to r.nextFloat() } }

    Canvas(modifier.fillMaxSize()) {
        val top: Color
        val bottom: Color
        when {
            sky == Sky.Storm -> { top = Color(0xFF1B2030); bottom = Color(0xFF3A3F52) }
            sky == Sky.Rain -> { top = if (isDay) Color(0xFF4A5868) else Color(0xFF151C26); bottom = if (isDay) Color(0xFF7D8A98) else Color(0xFF2A3442) }
            sky == Sky.Snow -> { top = if (isDay) Color(0xFF8C9BAD) else Color(0xFF1E2633); bottom = if (isDay) Color(0xFFD5DEE8) else Color(0xFF3B4757) }
            sky == Sky.Fog -> { top = if (isDay) Color(0xFF8E979F) else Color(0xFF242A30); bottom = if (isDay) Color(0xFFC3C9CE) else Color(0xFF3E454C) }
            sky == Sky.Cloudy -> { top = if (isDay) Color(0xFF6E7F92) else Color(0xFF161D28); bottom = if (isDay) Color(0xFFA9B6C3) else Color(0xFF2E3846) }
            isDay -> { top = Color(0xFF2F78C4); bottom = Color(0xFF9CCBF0) }
            else -> { top = Color(0xFF070B1A); bottom = Color(0xFF1C2A4A) }
        }
        val w = size.width
        val h = size.height
        if (!overlayOnly) {
        drawRect(Brush.verticalGradient(listOf(top, bottom)))

        // Stars on a clear-ish night.
        if (!isDay && (sky == Sky.Clear || sky == Sky.PartlyCloudy)) {
            dots.take(90).forEach { (p, s) -> drawCircle(Color.White.copy(alpha = 0.35f + 0.5f * s), radius = 1f + 2f * s, center = Offset(p.x * w, p.y * h * 0.6f)) }
        }
        // Sun or moon.
        if (sky == Sky.Clear || sky == Sky.PartlyCloudy) {
            val c = Offset(w * 0.8f, h * 0.2f)
            if (isDay) {
                drawCircle(Brush.radialGradient(listOf(Color(0x66FFF3B0), Color.Transparent), center = c, radius = h * 0.4f), radius = h * 0.4f, center = c)
                drawCircle(Color(0xFFFFE680), radius = h * 0.08f, center = c)
            } else {
                drawCircle(Brush.radialGradient(listOf(Color(0x33E8EEFF), Color.Transparent), center = c, radius = h * 0.25f), radius = h * 0.25f, center = c)
                drawCircle(Color(0xFFE8ECF5), radius = h * 0.06f, center = c)
                drawCircle(top.copy(alpha = 0.95f), radius = h * 0.055f, center = c + Offset(h * 0.03f, -h * 0.015f))
            }
        }
        // Clouds.
        val clouds =
            when (sky) {
                Sky.PartlyCloudy -> 3
                Sky.Cloudy, Sky.Rain, Sky.Snow -> 6
                Sky.Storm -> 7
                Sky.Fog -> 0
                else -> 0
            }
        val cloudColor =
            when {
                sky == Sky.Storm -> Color(0xFF2B3040)
                !isDay -> Color(0xFF3A4556)
                sky == Sky.PartlyCloudy -> Color(0xFFF4F7FA)
                else -> Color(0xFFDCE2E9)
            }
        repeat(clouds) { i ->
            val cx = w * (0.08f + 0.17f * i + (if (i % 2 == 0) 0.02f else -0.03f))
            val cy = h * (0.14f + 0.07f * (i % 3))
            cloud(Offset(cx, cy), h * (0.07f + 0.015f * (i % 2)), cloudColor.copy(alpha = if (sky == Sky.PartlyCloudy) 0.85f else 0.75f))
        }
        // Fog bands.
        if (sky == Sky.Fog) {
            repeat(5) { i -> drawRect(Color.White.copy(alpha = 0.12f), topLeft = Offset(0f, h * (0.3f + 0.12f * i)), size = androidx.compose.ui.geometry.Size(w, h * 0.06f)) }
        }
        }
        // Rain and storm: falling streaks; snow: drifting flakes.
        if (sky == Sky.Rain || sky == Sky.Storm) {
            dots.forEach { (p, s) ->
                val y = ((p.y + t * (0.8f + 0.4f * s)) % 1f) * h
                val x = p.x * w + y * 0.08f
                drawLine(Color(0x88C8D6E6), Offset(x, y), Offset(x + h * 0.006f, y + h * 0.035f), strokeWidth = 1.5f + s)
            }
        }
        if (sky == Sky.Snow) {
            dots.forEach { (p, s) ->
                val y = ((p.y + t * (0.5f + 0.5f * s)) % 1f) * h
                val x = p.x * w + kotlin.math.sin((t * 6.28f + s * 6.28f).toDouble()).toFloat() * h * 0.015f
                drawCircle(Color.White.copy(alpha = 0.55f + 0.35f * s), radius = 1.5f + 3f * s, center = Offset(x, y))
            }
        }
        // A storm's faint flash now and then.
        if (sky == Sky.Storm && t in 0.40f..0.43f) drawRect(Color.White.copy(alpha = 0.12f))
    }
}

private fun DrawScope.cloud(
    c: Offset,
    r: Float,
    color: Color,
) {
    drawCircle(color, radius = r, center = c)
    drawCircle(color, radius = r * 0.8f, center = c + Offset(-r * 1.0f, r * 0.25f))
    drawCircle(color, radius = r * 0.9f, center = c + Offset(r * 1.0f, r * 0.2f))
    drawCircle(color, radius = r * 0.7f, center = c + Offset(r * 1.8f, r * 0.4f))
    drawRect(color, topLeft = c + Offset(-r * 1.0f, r * 0.25f), size = androidx.compose.ui.geometry.Size(r * 2.8f, r * 0.85f))
}
