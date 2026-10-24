package com.mackandall.jdvr.data

import com.mackandall.jdvr.data.LiveTvRepository.*
import java.time.LocalDateTime
import java.util.UUID

/**
 * Everything the screens ask of the server. [LiveTvRepository] is the Jellyfin one; the calls that
 * need our plugins answer null when the plugin isn't there, and the screens already hide what's missing.
 */
interface TvBackend {
    suspend fun channels(): List<Channel>

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

    /** Episode History's guide mark symbols (to show its marks as coloured chips). */
    suspend fun markSymbols(): MarkSymbols?

    suspend fun hasEpisodeHistory(): Boolean

    /** JellyDVR Schedule: was this airing skipped / moved, and is the show a Recording Favourite. */
    suspend fun scheduleState(programId: UUID): ScheduleState?

    /** "Other airings" of a library item or a guide entry (null without Episode History). */
    suspend fun airings(
        itemId: UUID? = null,
        programId: UUID? = null,
    ): AiringsResult?

    suspend fun rerecordState(itemId: UUID): RerecordState?

    /** Re-records a flawed recording at [programId], or the best airing found when null. */
    suspend fun rerecord(
        itemId: UUID,
        programId: String?,
        reason: String?,
    ): Message?

    suspend fun cancelRerecord(itemId: UUID): Boolean

    /** Which show a recording stands for, and the Recording Favourite covering it (if any). */
    suspend fun favouriteForItem(itemId: UUID): FavouriteFor?

    /** Deletes a recording: its file and the files beside it (Jellyfin's own delete). */
    suspend fun deleteItem(itemId: UUID)

    /** Episode History's trash: deleted recordings that can still be put back. Null without it. */
    suspend fun trash(): TrashList?

    /** Puts a deleted recording back; false when it couldn't (something is already where it was). */
    suspend fun restoreFromTrash(id: String): Boolean

    suspend fun deleteFromTrash(id: String): Boolean

    suspend fun emptyTrash(): Int?

    /** Days a delete stays in the trash, or null when deletes are for good (no trash). Asked again after 2 minutes. */
    suspend fun trashDays(): Int?

    /**
     * Deletes a recording through Episode History: a partial one takes the episode's other
     * partial versions with it (the pieces of an interrupted recording), so it records again;
     * a full one goes alone. Without that endpoint, Jellyfin's own delete. Returns how many
     * files went.
     */
    suspend fun deleteRecording(itemId: UUID): DeletedRecording

    /**
     * Episode History: the recordings being made right now behind an item (the item itself, or
     * for a show any of its episodes). Null on a server without that endpoint.
     */
    suspend fun activeRecordings(itemId: UUID): ActiveRecordings?

    /** Stops the recording writing this item's file; what was recorded so far is kept. */
    suspend fun stopRecording(recordingId: String): Boolean

    /**
     * A show's recordings in the recordings folders (archived copies stay): what deleting them
     * all would remove ([dryRun]), or delete them. With [archive] it's the archived copies
     * instead, and the recordings stay. Episode History only; null without it.
     */
    suspend fun deleteShowRecordings(
        seriesId: UUID,
        dryRun: Boolean,
        archive: Boolean = false,
    ): ShowDelete?

    /** This device's rights; null without JellyDVR Space (or one too old to have them). */
    suspend fun deviceRights(): DeviceRights?

    /** What Episode History knows about a guide programme: marked watched, a copy to play, forgettable. */
    suspend fun ehProgram(programId: UUID): EhProgram?

    suspend fun setProgramWatched(
        programId: UUID,
        watched: Boolean,
    ): kotlinx.serialization.json.JsonObject?

    /** "Forget this episode": the history no longer counts it as recorded or watched (admins). */
    suspend fun forgetProgram(programId: UUID): kotlinx.serialization.json.JsonObject?

    /**
     * "Forget this show": Episode History forgets every episode of the show [itemId] belongs to (a
     * show, an episode or a guide entry), so a series recording records them again ([dryRun]
     * counts). Administrators; null otherwise or without the plugin.
     */
    suspend fun forgetShow(itemId: UUID, dryRun: Boolean): ShowForget?

    suspend fun forgetRecording(itemId: UUID): Forgot?

    /** Comskip's badge per item (keys: ids without dashes); items never scanned are left out. */
    suspend fun commercialBadges(ids: List<UUID>): Map<String, CommercialBadge>

    /**
     * The same for show cards whose ids aren't items (NextPVR's shows): a summary of their
     * recordings. Jellyfin's shows are items, so [commercialBadges] covers them.
     */
    suspend fun showCommercialBadges(showIds: List<String>): Map<String, CommercialBadge> = emptyMap()

    /** Scan (again) for commercials; admins. */
    suspend fun scanCommercials(itemId: UUID): Message?

    /** Where Comskip is with an item: scanning now, found breaks, none, failed. */
    suspend fun commercialStatus(itemId: UUID): CommercialStatus?

    /**
     * Removes the commercials Comskip found (a scan that shouldn't have happened): only the
     * commercial marks go, and automatic detection leaves the item alone afterwards.
     */
    suspend fun removeCommercials(itemId: UUID): Cleared?

    /** Trim the stray 2.0 / 5.1 audio at the start and end of a recording; admins. */
    suspend fun fixAudioFormat(itemId: UUID): Message?

    /**
     * Comskip plugin: "Starts here (this recording)" at [seconds]; editors. [keyframe]: [seconds] is
     * a keyframe picked from [keyframes], and a cut starts exactly there.
     */
    suspend fun setProgramStart(itemId: UUID, seconds: Double, keyframe: Boolean = false): ProgramStartResult?

    /** The keyframes either side of [seconds]: a cut can only start on one (Comskip plugin; editors). */
    suspend fun keyframes(itemId: UUID, seconds: Double): Keyframes?

    /** The picture at [seconds] of a recording, to choose a keyframe by. */
    suspend fun frame(itemId: UUID, seconds: Double, width: Int = 400): androidx.compose.ui.graphics.ImageBitmap?

    /** "Remember card (all recordings)": the frame at [seconds] is the channel's card before its programs; editors. */
    suspend fun rememberStartCard(itemId: UUID, seconds: Double): ProgramStartResult?

    /** Comskip plugin: whether this user may edit recordings (administrators, and users the plugin allows), and what a trim keeps. */
    suspend fun editing(): Editing?

    /** The recording's program start (a skip marker), and whether a trim's original was kept; editors. */
    suspend fun programStartStatus(itemId: UUID): ProgramStartStatus?

    /** Remove the program start's skip marker; editors. */
    suspend fun clearProgramStart(itemId: UUID): ProgramStartResult?

    /** Put a trimmed recording's kept original back; editors. */
    suspend fun undoTrim(itemId: UUID): ProgramStartResult?

    /** Look for a remembered card in the recording's opening minutes; editors. */
    suspend fun findProgramStart(itemId: UUID): ProgramStartResult?

    /** Comskip plugin: recordings being cut right now (program start, audio format), with progress; everyone. */
    suspend fun cuts(): List<CutJob>?

    /** Stop a cut while the original is still untouched; editors. */
    suspend fun stopCut(itemId: UUID): Message?

    /** JellyDVR Space: recordings being moved to the archive right now, with progress; admins. */
    suspend fun moving(): List<MoveProgress>?

    /** JellyDVR Space: whether the item is a DVR recording, and the archive locations it can go to; admins. */
    suspend fun archiveChoices(itemId: UUID): ArchiveChoices?

    /** Move a recording to an archive location (in the background on the server); admins. */
    suspend fun archive(itemId: UUID, target: String): Message?

    suspend fun recordInstead(programId: UUID): Message?

    suspend fun addFavourite(programId: UUID): Message?

    suspend fun removeFavourite(ruleId: String): Message?

    /** Episode History's recordings: "current" (live recordings folder), "archived", or "all". */
    suspend fun recordedShows(filter: String = "current"): List<RecordingCard>?

    /** Every recording, newest first, one each with its channel. Null on an older Episode History. */
    suspend fun recordingsByDate(filter: String = "current"): DateRecordings?

    /** A show's recordings, newest first (archived ones left out: recordings folder only for now). */
    suspend fun showRecordings(
        seriesId: String,
        filter: String = "current",
    ): List<EpisodeRecording>?

    fun imageUrl(
        itemId: String,
        maxHeight: Int,
    ): String?

    /** Saves a series recording's options (new only, any channel / time, padding, keep…). */
    suspend fun updateSeriesTimer(t: org.jellyfin.sdk.model.api.SeriesTimerInfoDto)

    suspend fun seriesTimers(): List<org.jellyfin.sdk.model.api.SeriesTimerInfoDto>

    suspend fun favourites(): FavouritesList?

    suspend fun setFavouriteEnabled(
        ruleId: String,
        enabled: Boolean,
    ): Message?

    suspend fun addFavouriteByTitle(
        title: String,
        keyword: Boolean,
    ): Message?

    suspend fun upcoming(): List<Upcoming>?

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

    /**
     * Commercials found so far in a recording still being made (Comskip's live scan); Jellyfin has
     * none for it until it finishes. First: still in progress.
     */
    suspend fun liveSegments(itemId: UUID): Pair<Boolean, List<Segment>>

    /** The channel's buffers, oldest first; the last is the programme on now. */
    suspend fun buffers(channelId: UUID): List<Buffer>

    /** Ask JellyDVR Live to start buffering the channel now (Pause), skipping its surfing delay. */
    suspend fun startBuffering(channelId: UUID): List<Buffer>

    /**
     * Guide programmes that are only a live TV buffer (JellyDVR Live's, not kept), in a plain
     * lower-case no-dash form: their timers look like recordings everywhere else.
     */
    suspend fun bufferPrograms(): Set<String>

    /** The buffer an item is, if it is one (for Keep in the player). */
    suspend fun bufferOfItem(itemId: UUID): Buffer?

    suspend fun keepBuffer(
        bufferId: String,
        keep: Boolean,
    ): Buffer?

    /**
     * Stop pressed: JellyDVR Live stops buffering the channel now, freeing its tuner input (it
     * leaves kept buffers, real recordings, and channels someone else is watching). Runs on its
     * own scope so it finishes even as the player closes.
     */
    fun stopBuffering(channelId: UUID)

    /** Programmes being recorded now whose recording Comskip is scanning live (the guide's CS tag), with minutes found so far. */
    suspend fun guideScanning(): Map<String, Int?>

    /** The recording of a programme being recorded now, as a library item (Comskip plugin). */
    suspend fun recordingOfProgram(programId: UUID): UUID?

    /** Episode History's part of Home: what needs attention, and the recordings drive. Null without it. */
    suspend fun homeExtras(): HomeExtras?

    suspend fun tuners(channelId: UUID?): TunerStatus?
}
