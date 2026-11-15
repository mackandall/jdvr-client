package com.mackandall.jdvr.ui

import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.attempt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.UUID

/** One question to the server: where its answer is kept, and how to ask it. */
internal class Ask<T>(
    val key: String,
    val load: suspend () -> T?,
)

/**
 * The server's answers, kept a few minutes and fetched ahead: resting on a recording (or a show)
 * asks what its details page and its menu need, so they open complete. A screen shows what's kept
 * straight away and still asks again, updating if anything changed (the same as the programme
 * menu, whose question is [ProgramAsk]). Asking for something already on its way waits for that
 * answer.
 */
internal object Answers {
    private const val FRESH_MS = 3 * 60_000L
    private const val KEEP = 600

    private class Kept(
        val value: Any?,
        val at: Long,
    )

    // Main thread only (the scope runs there; the calls themselves switch to I/O).
    private val done =
        object : LinkedHashMap<String, Kept>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Kept>?) = size > KEEP
        }
    private val pending = HashMap<String, Deferred<Any?>>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var server: String? = null

    // Another server: nothing from the last one applies.
    private fun checkServer() {
        val now = Jdvr.serverKey
        if (now != server) {
            server = now
            done.clear()
            pending.clear()
        }
    }

    private fun kept(key: String): Kept? {
        checkServer()
        return done[key]?.takeIf { System.currentTimeMillis() - it.at < FRESH_MS }
    }

    /** Whether a recent answer is kept (then a screen shows it, and asks again to update it). */
    fun known(a: Ask<*>): Boolean = kept(a.key) != null

    /** The kept answer, if it's recent. */
    @Suppress("UNCHECKED_CAST")
    fun <T> peek(a: Ask<T>): T? = kept(a.key)?.value as T?

    /** Keeps an answer got some other way (a screen checking back while something runs). */
    fun <T> put(
        a: Ask<T>,
        value: T?,
    ) {
        checkServer()
        done[a.key] = Kept(value, System.currentTimeMillis())
    }

    /** The answer: the one on its way, else a new ask. [fresh]: always a new ask. */
    @Suppress("UNCHECKED_CAST")
    fun <T> start(
        a: Ask<T>,
        fresh: Boolean = false,
    ): Deferred<T?> {
        checkServer()
        if (!fresh) pending[a.key]?.let { return it as Deferred<T?> }
        val d = scope.async { attempt { a.load() }.also { done[a.key] = Kept(it, System.currentTimeMillis()) } }
        pending[a.key] = d
        d.invokeOnCompletion { if (pending[a.key] === d) pending.remove(a.key) }
        return d as Deferred<T?>
    }

    /** For a screen opening: a kept answer is asked again (it may have changed); one on its way is waited for. */
    suspend fun <T> get(a: Ask<T>): T? = start(a, fresh = known(a)).await()

    /** Ask ahead, unless it's known or on its way. */
    fun prefetch(a: Ask<*>) {
        if (!known(a) && pending[a.key] == null) start(a)
    }
}

/** The questions a recording's (or a show's) details page and menus ask. */
internal object ItemAsk {
    fun details(id: UUID) = Ask("details:$id") { Jdvr.backend.details(id) }

    // Whether the server has Episode History (Forget and the like).
    val hasEpisodeHistory = Ask("hasEpisodeHistory") { Jdvr.backend.hasEpisodeHistory() }

    fun badge(id: UUID) = Ask("badge:$id") { Jdvr.backend.commercialBadges(listOf(id)).values.firstOrNull() }

    fun commercials(id: UUID) = Ask("commercials:$id") { Jdvr.backend.commercialStatus(id) }

    fun favourite(id: UUID) = Ask("favourite:$id") { Jdvr.backend.favouriteForItem(id) }

    fun programStart(id: UUID) = Ask("start:$id") { Jdvr.backend.programStartStatus(id) }

    fun archive(id: UUID) = Ask("archive:$id") { Jdvr.backend.archiveChoices(id) }

    fun active(id: UUID) = Ask("active:$id") { Jdvr.backend.activeRecordings(id) }

    fun rerecord(id: UUID) = Ask("rerecord:$id") { Jdvr.backend.rerecordState(id) }

    fun storage(id: UUID) = Ask("storage:$id") { Jdvr.backend.showStorage(id) }

    fun seriesEpisodes(seriesId: UUID) = Ask("episodes:$seriesId") { Jdvr.backend.episodes(seriesId, null) }

    fun showDelete(
        seriesId: UUID,
        archive: Boolean,
    ) = Ask("showDelete:$seriesId:$archive") { Jdvr.backend.deleteShowRecordings(seriesId, dryRun = true, archive = archive) }

    fun showForget(seriesId: UUID) = Ask("showForget:$seriesId") { Jdvr.backend.forgetShow(seriesId, dryRun = true) }

    /**
     * Everything an item's details page and menu ask, all at once. [recording]: also the recording
     * tools' questions (not for a library episode).
     */
    fun prefetchItem(
        id: UUID,
        recording: Boolean = true,
    ) {
        Answers.prefetch(details(id))
        // JDVR Embuary's details page has the season's episodes: they need the show, from the details.
        if (Jc.Style == UiStyle.Embuary && !Answers.known(details(id))) {
            val d = Answers.start(details(id))
            Answers.scope.launch { d.await()?.item?.seriesId?.let { Answers.prefetch(seriesEpisodes(it)) } }
        }
        if (!recording) return
        listOf(badge(id), commercials(id), favourite(id), active(id), rerecord(id), hasEpisodeHistory).forEach { Answers.prefetch(it) }
        // What this TV and user may do decides some of the buttons (both kept a few minutes).
        Answers.scope.launch {
            TvRights.load()
            EditPermission.load()
            val rights = TvRights.info
            if (rights.edit && EditPermission.info?.canEdit == true) Answers.prefetch(programStart(id))
            if (rights.archive) {
                Answers.prefetch(archive(id))
                Answers.prefetch(storage(id))
            }
        }
    }

    /** What a show's menu (Show actions) asks. */
    fun prefetchShow(seriesId: UUID) {
        Answers.prefetch(active(seriesId))
        Answers.prefetch(showForget(seriesId))
        Answers.scope.launch {
            TvRights.load()
            val rights = TvRights.info
            if (rights.delete) Answers.prefetch(showDelete(seriesId, archive = false))
            if (rights.deleteArchive) Answers.prefetch(showDelete(seriesId, archive = true))
            if (rights.archive) Answers.prefetch(archive(seriesId))
        }
    }
}
