package com.mackandall.jdvr.data

import java.time.LocalDateTime
import java.util.UUID

data class Library(
    val id: UUID,
    val name: String,
    val kind: String?,
)

/** One library item: a movie, show, season or episode, with the user's watched / resume state. */
data class LibItem(
    val id: UUID,
    val name: String,
    val type: String,
    val year: Int?,
    val overview: String?,
    val runtimeMin: Long?,
    val played: Boolean,
    val resumeMs: Long,
    val unplayed: Int?,
    val season: Int?,
    val episode: Int?,
    val seriesId: UUID?,
    val seriesName: String?,
    val officialRating: String?,
) {
    val seasonEpisode: String? get() = if (season != null && episode != null) "S$season:E$episode" else null
}

/** A person in the credits, with their photo if Jellyfin has one. */
data class Person(
    val id: UUID,
    val name: String,
    val role: String?,
    val kind: String,
    val hasImage: Boolean,
)

/** A person's own page: photo, life dates, where they were born and their biography (from TMDB, once Jellyfin has fetched it). */
data class PersonDetails(
    val id: UUID,
    val name: String,
    val overview: String?,
    val born: java.time.LocalDate?,
    val died: java.time.LocalDate?,
    val birthplace: String?,
    val hasImage: Boolean,
)

/** Everything a details page shows: art, credits, ratings and what's in the file. */
data class ItemDetails(
    val item: LibItem,
    val tagline: String?,
    val genres: List<String>,
    val studios: List<String>,
    val communityRating: Float?,
    val criticRating: Float?,
    val premiere: java.time.LocalDate?,
    val backdropOf: UUID?,
    val people: List<Person>,
    val video: String?,
    val audio: List<String>,
    val subtitles: List<String>,
    val file: String?,
    // Where the file is: the live recordings folder, or the share it's on.
    val location: String?,
    // When it was recorded: only for a file still in the live recordings folder (an archive
    // or library copy's file date is when it was copied, not recorded).
    val recordedAt: java.time.LocalDateTime? = null,
    // Whether Jellyfin lets the signed-in user delete it.
    val canDelete: Boolean = true,
) {
    val directors get() = people.filter { it.kind == "Director" }
    val writers get() = people.filter { it.kind == "Writer" }
    val cast get() = people.filter { it.kind == "Actor" || it.kind == "GuestStar" }
}

/** The letter-strip bucket for a name: its first letter without accents, or "#" for anything else. */
fun letterOf(name: String?): String {
    val c =
        java.text.Normalizer
            .normalize(name?.trim()?.take(1) ?: "", java.text.Normalizer.Form.NFD)
            .firstOrNull()
            ?.uppercaseChar()
    return if (c != null && c in 'A'..'Z') c.toString() else "#"
}
