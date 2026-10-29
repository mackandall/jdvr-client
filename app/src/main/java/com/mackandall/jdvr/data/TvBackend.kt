package com.mackandall.jdvr.data

import org.jellyfin.sdk.model.api.SeriesTimerInfoDto
import java.time.LocalDateTime
import java.util.UUID

/**
 * Everything the screens ask of the server. [JellyfinBackend] is the Jellyfin one. The
 * features in their own interfaces ([RecordingHistory], [Commercials], [Archiving], [RecordingRules],
 * [LiveBuffers]) come from our plugins there: they answer null when the plugin isn't installed, and
 * the screens hide what's missing.
 */
interface TvBackend :
    RecordingHistory,
    Commercials,
    Archiving,
    RecordingRules,
    LiveBuffers {
    /** The channels to show: those of the channel group picked in the guide. */
    suspend fun channels(): List<Channel>

    /** Every channel, whatever group is picked (for naming a programme's channel). */
    suspend fun allChannels(): List<Channel> = channels()

    /** The channel groups to pick from, all channels first; empty where there's no choice. */
    suspend fun channelGroups(): List<String> = emptyList()

    suspend fun programs(
        channelIds: List<UUID>,
        from: LocalDateTime,
        to: LocalDateTime,
    ): List<Program>

    suspend fun program(id: UUID): Program?

    fun imageUrl(
        itemId: UUID,
        maxHeight: Int,
    ): String

    suspend fun record(
        programId: UUID,
        series: Boolean,
    )

    /**
     * Cancels a recording (stops it when it is under way). Takes either Jellyfin's API id or the
     * DVR's own id, which JellyDVR Schedule's list gives (its timers.json); Jellyfin's API only
     * knows the first ("Timer … not found"), so the second is looked up among its timers.
     */
    suspend fun cancelRecording(timerId: String)

    suspend fun cancelSeries(seriesTimerId: String)

    /** Opens the channel's live stream and works out how to play it. */
    suspend fun startLive(channelId: UUID): LivePlayback

    /** Request the HLS playlist once, so the server starts the remux now (from tune-in). */
    suspend fun warmHls(url: String)

    /**
     * Where pause and rewind go for [p], asked once its stream plays: Jellyfin's HLS copy, or
     * NextPVR's live buffer file (only known once the tuner is writing it), never [notThis] (the
     * one before a fresh start).
     */
    suspend fun liveCopyUrl(
        p: LivePlayback,
        notThis: String? = null,
    ): String? = p.hlsUrl

    /**
     * The copy is a file that only grows (NextPVR): opened at its size then, so playing reaches
     * that end and it's opened again; how far behind live is the time since tune-in less the position.
     */
    val liveCopyIsFile: Boolean get() = false

    /** The copy's URL to open now (a growing file: pinned to its size at this moment). */
    fun liveCopyOpenUrl(url: String): String = url

    /** For a [liveCopyOpenUrl] still growing: the plain file, to read on past the size it was opened at. */
    fun liveCopyGrowingUrl(url: String): String? = null

    /**
     * On: the player is about to leave the live stream for the copy, and the stream must stay
     * open meanwhile (NextPVR drops the tuner and its buffer once it closes). Off: let it go.
     */
    fun holdLive(on: Boolean) = Unit

    suspend fun startItem(itemId: UUID): ItemPlayback

    suspend fun reportItem(
        p: ItemPlayback,
        positionMs: Long,
        paused: Boolean,
        event: String,
    )

    suspend fun reportStart(p: LivePlayback)

    suspend fun reportProgress(
        p: LivePlayback,
        paused: Boolean,
    )

    /** Closes a live stream nobody is playing (its player went away while it opened): frees the tuner. */
    suspend fun closeLiveStream(id: String)

    /** Must carry the live stream id, or Jellyfin keeps the tuner open (found with Moonfin). */
    suspend fun reportStopped(p: LivePlayback)

    /** The user's libraries that hold videos (Live TV and playlists left out). */
    suspend fun libraries(): List<Library>

    /** A library's movies and shows, a page at a time: by name, or newest first. */
    suspend fun libraryItems(
        libraryId: UUID,
        newestFirst: Boolean,
        start: Int,
        count: Int,
    ): Pair<List<LibItem>, Int>

    /**
     * Each item's letter ("#" for digits and symbols), in A–Z order: what the library's letter strip
     * jumps by. One small reply (names only), however big the library.
     */
    suspend fun libraryLetters(libraryId: UUID): List<String>

    /** Movies and shows in every library whose name matches. */
    suspend fun searchLibrary(term: String): List<LibItem>

    /** Guide programmes whose name matches, still to come or on now, soonest first. */
    suspend fun searchGuide(term: String): List<Program>

    suspend fun seasons(seriesId: UUID): List<LibItem>

    /** A show's episodes, in order (one season, or all of them). */
    suspend fun episodes(
        seriesId: UUID,
        seasonId: UUID?,
    ): List<LibItem>

    suspend fun details(id: UUID): ItemDetails

    fun backdropUrl(itemId: UUID): String

    fun personImageUrl(personId: UUID): String

    suspend fun libItem(id: UUID): LibItem

    /** A library's latest additions, as the web client's "Recently added in …" rows (episodes grouped by show). */
    suspend fun latestIn(
        libraryId: UUID,
        limit: Int = 16,
    ): List<LibItem>?

    suspend fun setPlayed(
        id: UUID,
        played: Boolean,
    )

    /** Deletes a recording: its file and the files beside it (Jellyfin's own delete). */
    suspend fun deleteItem(itemId: UUID)

    /**
     * Deletes a recording through Episode History: a partial one takes the episode's other
     * partial versions with it (the pieces of an interrupted recording), so it records again;
     * a full one goes alone. Without that endpoint, Jellyfin's own delete. Returns how many
     * files went.
     */
    suspend fun deleteRecording(itemId: UUID): DeletedRecording

    fun imageUrl(
        itemId: String,
        maxHeight: Int,
    ): String?

    /** One scheduled (or under way) recording's timer, by its id; null when it's gone. */
    suspend fun timer(timerId: String): org.jellyfin.sdk.model.api.TimerInfoDto? = null

    /** Saves one recording's options (start early, end late). */
    suspend fun updateTimer(t: org.jellyfin.sdk.model.api.TimerInfoDto) {}

    /** Saves a series recording's options (new only, any channel / time, padding, keep…). */
    suspend fun updateSeriesTimer(t: org.jellyfin.sdk.model.api.SeriesTimerInfoDto)

    /** What each series recording has scheduled, by its id ([plainId]): how many, and the first of them. */
    suspend fun seriesScheduled(): Map<String, SeriesScheduled> = emptyMap()

    /** A new series recording for [programId] as the server would set it up, to change before [createSeriesTimer]. */
    suspend fun newSeriesTimer(programId: UUID): org.jellyfin.sdk.model.api.SeriesTimerInfoDto? = null

    /** Sets up a series recording with the options chosen. */
    suspend fun createSeriesTimer(t: org.jellyfin.sdk.model.api.SeriesTimerInfoDto) {}

    suspend fun seriesTimers(): List<org.jellyfin.sdk.model.api.SeriesTimerInfoDto>

    /**
     * What will record: JellyDVR Schedule's list, or, on a server without it, Jellyfin's own
     * scheduled recordings in the same shape (no Recording Favourites there). Null only when
     * neither answers.
     */
    suspend fun schedule(): List<Upcoming>?

    /**
     * Whether this server is a DVR: it has Live TV channels, or has recorded something. False only
     * when both answers say no; an unanswered call counts as yes (keep what's shown).
     */
    suspend fun hasDvr(): Boolean

    /**
     * Jellyfin's own list of recordings, newest first, one card per recording (no grouping into
     * shows): Recorded Shows on a server without Episode History.
     */
    suspend fun jellyfinRecordings(): List<RecordingCard>?

    /**
     * Jellyfin's "recently added" (newest first, episodes and movies, not grouped), as cards: Home's
     * row on a server without Episode History.
     */
    suspend fun recentlyAdded(limit: Int = 5): List<RecordingCard>?

    /** Jellyfin's segments for a finished recording (Comskip's breaks, Intro Skipper's intros). */
    suspend fun segments(itemId: UUID): List<Segment>
}
