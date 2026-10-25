package com.mackandall.jdvr.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.mackandall.jdvr.data.Channel
import com.mackandall.jdvr.data.HomeExtras
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.RecordingCard
import com.mackandall.jdvr.data.TunerStatus
import com.mackandall.jdvr.data.Upcoming
import com.mackandall.jdvr.data.attempt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What both Home screens (tiles and SageTV's menu) show from the server. */
@Stable
class HomeData {
    /** Null until the server first answers. Here and below, a failed call keeps what was there. */
    var channels by mutableStateOf<List<Channel>?>(null)
        private set

    /** What will record. Null until the first round. */
    var schedule by mutableStateOf<List<Upcoming>?>(null)
        private set

    /** Recorded Shows; null without Episode History (or when it didn't answer). */
    var shows by mutableStateOf<List<RecordingCard>?>(null)
        private set
    var tuners by mutableStateOf<TunerStatus?>(null)
        private set

    /** Episode History's attention list and the recordings drive. */
    var extras by mutableStateOf<HomeExtras?>(null)
        private set

    /** Programmes JellyDVR Live is only buffering ([plainId]s): not real recordings. */
    var bufferIds by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Rounds done: 0 until the first has answered (until then, empty means "not yet", not "none"). */
    var round by mutableIntStateOf(0)
        private set

    val loaded get() = round > 0

    /** The channel watched last, or the first one. */
    val lastChannel: Channel?
        get() = channels?.let { cs -> cs.firstOrNull { it.id.toString() == Jdvr.session.lastChannelId } ?: cs.firstOrNull() }

    suspend fun refresh() {
        // All at once: the channel list is slow on a small box, and the other panels needn't wait for it.
        coroutineScope {
            launch { attempt { Jdvr.backend.channels() }?.let { channels = it } }
            launch { schedule = Jdvr.backend.schedule() ?: schedule.orEmpty() }
            launch { Jdvr.backend.recordedShows()?.let { shows = it } }
            launch { Jdvr.backend.tuners(null)?.let { tuners = it } }
            launch { attempt { Jdvr.backend.homeExtras() }?.let { extras = it } }
            launch { bufferIds = Jdvr.backend.bufferPrograms() }
        }
        round++
    }
}

/** [HomeData] refreshed every minute while Home is open; soon again while the channels haven't come (say just after the TV woke). */
@Composable
fun rememberHomeData(): HomeData {
    val data = remember { HomeData() }
    LaunchedEffect(data) {
        while (true) {
            data.refresh()
            delay(if (data.channels == null) 5_000 else 60_000)
        }
    }
    return data
}
