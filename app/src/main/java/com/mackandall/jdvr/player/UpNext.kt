package com.mackandall.jdvr.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.LibItem
import com.mackandall.jdvr.data.Segment
import com.mackandall.jdvr.data.Upcoming
import com.mackandall.jdvr.ui.Jc
import com.mackandall.jdvr.ui.time
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * Up Next: near the end of a show's recording or archive episode, a card in the bottom right
 * names the next one and counts down to it (Plex's and Prime Video's card). Streaming services
 * tag where the credits start; a recording has the Comskip marks instead: the show ends where
 * its last commercial break begins, the credits just before.
 */

/** Where the card shows ([showAtMs], into the file) and why ([source], for the log). */
internal data class UpNextTiming(
    val showAtMs: Long,
    val source: String,
)

// A last break this close to the end of the file is the one after the show (the rest is padding).
private const val FINAL_BREAK_SLACK_MS = 120_000L

// Breaks this close together count as one (Comskip splits a long one).
private const val BREAK_JOIN_MS = 20_000L

/**
 * When the Up Next card shows: at the start of a credits (Outro) mark; else [beforeBreakMs] before
 * the commercial break that runs to the end of the file; else [beforeEndMs] before the end. Never
 * in the first half (a mis-marked break early on), and null for a file of unknown length.
 */
internal fun upNextTiming(
    durationMs: Long,
    segments: List<Segment>,
    beforeBreakMs: Long,
    beforeEndMs: Long,
): UpNextTiming? {
    if (durationMs <= 0) return null
    val half = durationMs / 2
    segments
        .firstOrNull { (it.type.equals("Outro", true) || it.type.equals("Credits", true)) && it.startMs >= half }
        ?.let { return UpNextTiming(it.startMs, "credits mark") }
    finalBreakStart(durationMs, segments)?.let { start ->
        return UpNextTiming((start - beforeBreakMs).coerceAtLeast(half), "last commercial break")
    }
    return UpNextTiming((durationMs - beforeEndMs).coerceAtLeast(half), "end of the file")
}

/** Where the commercial break that runs to (near) the end of the file starts, joining split pieces. */
internal fun finalBreakStart(
    durationMs: Long,
    segments: List<Segment>,
): Long? {
    val breaks = segments.filter(::isCommercial).sortedBy { it.startMs }
    val last = breaks.lastOrNull() ?: return null
    if (durationMs - last.endMs > FINAL_BREAK_SLACK_MS) return null
    var start = last.startMs
    for (b in breaks.dropLast(1).asReversed()) {
        if (start - b.endMs > BREAK_JOIN_MS) break
        start = minOf(start, b.startMs)
    }
    return start.takeIf { it >= durationMs / 2 }
}

/**
 * The episodes skipped between this one and the next recorded one ("S4:E13 not recorded",
 * "S4:E13–E15 not recorded", "S5:E1–E2 not recorded"), or null when there's no gap or the
 * numbers aren't known.
 */
internal fun missingEpisodes(
    season: Int?,
    episode: Int?,
    nextSeason: Int?,
    nextEpisode: Int?,
): String? {
    if (season == null || episode == null || nextSeason == null || nextEpisode == null) return null
    val (s, from) =
        when {
            nextSeason == season && nextEpisode > episode + 1 -> season to episode + 1
            nextSeason > season && nextEpisode > 1 -> nextSeason to 1
            else -> return null
        }
    val to = nextEpisode - 1
    return if (from == to) "S$s:E$from not recorded" else "S$s:E$from–E$to not recorded"
}

/** The next scheduled recording of [seriesName] ("S4:E15 The Visa · Thu 7:00 PM · WPIX"), or null. */
internal fun nextScheduled(
    seriesName: String?,
    upcoming: List<Upcoming>?,
    now: Instant = Instant.now(),
): String? {
    val name = seriesName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val u =
        upcoming
            .orEmpty()
            .filter { it.name.trim().equals(name, ignoreCase = true) }
            .mapNotNull { u -> runCatching { Instant.parse(u.start) }.getOrNull()?.takeIf { it > now }?.let { u to it } }
            .minByOrNull { it.second } ?: return null
    val at = LocalDateTime.ofInstant(u.second, ZoneId.systemDefault())
    val day =
        when (at.toLocalDate()) {
            LocalDate.now() -> "Today"
            LocalDate.now().plusDays(1) -> "Tomorrow"
            else -> at.format(DayFormat)
        }
    return listOfNotNull(
        listOfNotNull(u.first.seasonEpisode, u.first.episodeTitle).joinToString(" ").takeIf { it.isNotBlank() },
        "$day ${at.time()}",
        u.first.channelName,
    ).joinToString(" · ")
}

private val DayFormat = DateTimeFormatter.ofPattern("EEE")

/**
 * Episodes played one after another by Up Next with no key pressed in between: after
 * Settings › "Still watching?" of them, the next one waits to be asked for. Kept across players
 * (each episode gets a fresh one).
 */
internal object UpNextChain {
    @Volatile var autoPlayed = 0
}

/** What the card shows. */
internal sealed interface UpNextCard {
    /** The next episode, counting down when [secondsLeft] isn't null. */
    data class Next(
        val item: LibItem,
        val gap: String?,
        val secondsLeft: Int?,
        val countdownOf: Int,
    ) : UpNextCard

    /** Nothing more recorded: when the show records next, if it's scheduled. */
    data class Last(
        val seriesName: String?,
        val scheduled: String?,
    ) : UpNextCard

    /** Several episodes autoplayed with no key pressed. */
    data class StillWatching(
        val seriesName: String?,
        val episodes: Int,
    ) : UpNextCard
}

/** The card's two buttons: [selected] 0 is the first (Play now / Keep watching / Done), 1 Hide. */
@Composable
internal fun UpNextCardView(
    card: UpNextCard,
    selected: Int,
    modifier: Modifier = Modifier,
) {
    if (card is UpNextCard.StillWatching) {
        Box(modifier.fillMaxSize().background(Jc.Ink.copy(alpha = 0.82f)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Still watching${card.seriesName?.let { " $it" } ?: ""}?", color = Jc.Text, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
                Text("${card.episodes} episodes played without a key press", color = Jc.Muted, fontSize = 17.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    CardButton("▶  Keep watching", selected == 0)
                    CardButton("Stop", selected == 1)
                }
            }
        }
        return
    }
    Column(
        modifier
            .padding(end = 40.dp, bottom = 44.dp)
            .width(460.dp)
            .background(Jc.Sheet.copy(alpha = 0.95f), RoundedCornerShape(14.dp))
            .border(1.dp, Jc.Line, RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            when (card) {
                is UpNextCard.Next -> {
                    Thumb(card.item, card.secondsLeft, card.countdownOf)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            listOfNotNull("UP NEXT", card.item.seasonEpisode).joinToString(" · "),
                            color = Jc.Muted,
                            fontSize = 13.sp,
                            letterSpacing = 1.5.sp,
                        )
                        Text(card.item.name, color = Jc.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val sub =
                            listOfNotNull(
                                card.item.runtimeMin?.takeIf { it > 0 }?.let { "$it min" },
                                if (card.secondsLeft == null) "OK to play" else null,
                            ).joinToString(" · ")
                        if (sub.isNotEmpty()) Text(sub, color = Jc.Muted, fontSize = 14.sp)
                        card.gap?.let {
                            Text(
                                it,
                                color = Jc.Focus,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.background(Jc.FocusFill, RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                    }
                }

                is UpNextCard.Last -> {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("LAST RECORDED EPISODE", color = Jc.Muted, fontSize = 13.sp, letterSpacing = 1.5.sp)
                        Text(card.seriesName ?: "No more episodes", color = Jc.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (card.scheduled != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("●", color = Jc.Recording, fontSize = 13.sp)
                                Text("Records next: ${card.scheduled}", color = Jc.Muted, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        } else {
                            Text("No more episodes are recorded or scheduled", color = Jc.Muted, fontSize = 14.sp)
                        }
                    }
                }

                is UpNextCard.StillWatching -> {}
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CardButton(if (card is UpNextCard.Last) "Done" else "▶  Play now", selected == 0)
            CardButton("Hide", selected == 1)
        }
    }
}

@Composable
private fun CardButton(
    label: String,
    focused: Boolean,
) {
    Text(
        label,
        color = if (focused) Jc.OnFocus else Jc.Text,
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        modifier =
            Modifier
                .background(if (focused) Jc.Focus else Jc.Button, RoundedCornerShape(8.dp))
                .padding(horizontal = 18.dp, vertical = 9.dp),
    )
}

/** The next episode's picture (the show's behind it, for one without), with the countdown ring over it. */
@Composable
private fun Thumb(
    item: LibItem,
    secondsLeft: Int?,
    countdownOf: Int,
) {
    Box(
        Modifier
            .width(170.dp)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(8.dp))
            .background(Jc.Panel),
        contentAlignment = Alignment.Center,
    ) {
        item.seriesId?.let { AsyncImage(model = Jdvr.backend.backdropUrl(it), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        AsyncImage(model = Jdvr.backend.imageUrl(item.id, 240), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (secondsLeft != null) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
            val f = if (countdownOf > 0) secondsLeft.toFloat() / countdownOf else 0f
            Canvas(Modifier.size(54.dp)) {
                val w = 4.dp.toPx()
                drawArc(Color.White.copy(alpha = 0.3f), 0f, 360f, false, style = Stroke(w))
                drawArc(Color.White, -90f, -360f * f, false, style = Stroke(w, cap = StrokeCap.Round))
            }
            Text("$secondsLeft", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}
