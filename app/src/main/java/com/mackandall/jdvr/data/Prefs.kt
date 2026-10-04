package com.mackandall.jdvr.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * App-wide preferences (the Settings section): things set once. Per-screen actions stay on the
 * screens. Compose state, so screens follow a change at once.
 */
class Prefs(
    context: Context,
) {
    private val p = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    /** Guide: the programme's details beside the grid ("side") or above it ("top", grid full width). */
    var guideInfo by mutableStateOf(p.getString("guideInfo", SIDE) ?: SIDE)
        private set

    /** Commercials in recordings: "ask" (a Skip prompt), "auto" or "off". */
    var commercialSkip by mutableStateOf(p.getString("commercialSkip", ASK) ?: ASK)
        private set

    /** Intros / recaps / previews: "ask", "auto" or "off". */
    var introSkip by mutableStateOf(p.getString("introSkip", ASK) ?: ASK)
        private set

    /** Libraries kept out of the menu (by id); new ones show up. */
    var hiddenLibraries by mutableStateOf(p.getStringSet("hiddenLibraries", emptySet())?.toSet() ?: emptySet())
        private set

    /** Recorded Shows: "current" (the live recordings folder), "archived", or "all". */
    var recordingsFilter by mutableStateOf(p.getString("recordingsFilter", CURRENT) ?: CURRENT)
        private set

    /** A show's episodes: "list" (rows plus the focused episode's details) or "tiles" (web client style). */
    var episodeView by mutableStateOf(p.getString("episodeView", LIST) ?: LIST)
        private set

    fun changeEpisodeView(v: String) {
        episodeView = v
        p.edit().putString("episodeView", v).apply()
    }

    /** Subtitles and closed captions when something starts: "off", or "on" when there are any. */
    var subtitles by mutableStateOf(p.getString("subtitles", OFF) ?: OFF)
        private set

    fun changeSubtitles(v: String) {
        subtitles = v
        p.edit().putString("subtitles", v).apply()
    }

    /** The guide's background: the picture of the programme the cursor rests on, after this many seconds (0: off). */
    var guideArtSeconds by mutableStateOf(p.getInt("guideArtSeconds", 3))
        private set

    fun changeGuideArtSeconds(v: Int) {
        guideArtSeconds = v
        p.edit().putInt("guideArtSeconds", v).apply()
    }

    /** The colour theme (Settings › Appearance): a key of [com.mackandall.jdvr.ui.Palettes]. */
    var theme by mutableStateOf(p.getString("theme", "midnight") ?: "midnight")
        private set

    init {
        com.mackandall.jdvr.ui.Jc.palette = com.mackandall.jdvr.ui.paletteFor(theme)
    }

    fun changeTheme(v: String) {
        theme = v
        com.mackandall.jdvr.ui.Jc.palette = com.mackandall.jdvr.ui.paletteFor(v)
        p.edit().putString("theme", v).apply()
    }

    /** The box at the top of a recording or archive item while it plays (with the info bar). */
    var infoBoxTop by mutableStateOf(p.getBoolean("infoBoxTop", true))
        private set

    fun changeInfoBoxTop(v: Boolean) {
        infoBoxTop = v
        p.edit().putBoolean("infoBoxTop", v).apply()
    }

    /** Weather on Home (Open-Meteo): on/off, the town (name and coordinates), °F or °C. */
    var weatherOn by mutableStateOf(p.getBoolean("weatherOn", true))
        private set
    var weatherPlace by mutableStateOf(
        p.getString("weatherName", null)?.let { n -> Place(n, p.getFloat("weatherLat", 0f).toDouble(), p.getFloat("weatherLon", 0f).toDouble()) },
    )
        private set
    var fahrenheit by mutableStateOf(p.getBoolean("fahrenheit", true))
        private set

    fun changeWeatherOn(v: Boolean) {
        weatherOn = v
        p.edit().putBoolean("weatherOn", v).apply()
    }

    fun changeWeatherPlace(v: Place) {
        weatherPlace = v
        p.edit().putString("weatherName", v.name).putFloat("weatherLat", v.latitude.toFloat()).putFloat("weatherLon", v.longitude.toFloat()).apply()
    }

    fun changeFahrenheit(v: Boolean) {
        fahrenheit = v
        p.edit().putBoolean("fahrenheit", v).apply()
    }

    fun toggleLibrary(id: String) {
        hiddenLibraries = if (id in hiddenLibraries) hiddenLibraries - id else hiddenLibraries + id
        p.edit().putStringSet("hiddenLibraries", hiddenLibraries).apply()
    }

    fun changeRecordingsFilter(v: String) {
        recordingsFilter = v
        p.edit().putString("recordingsFilter", v).apply()
    }

    fun changeGuideInfo(v: String) {
        guideInfo = v
        p.edit().putString("guideInfo", v).apply()
    }

    fun changeCommercialSkip(v: String) {
        commercialSkip = v
        p.edit().putString("commercialSkip", v).apply()
    }

    fun changeIntroSkip(v: String) {
        introSkip = v
        p.edit().putString("introSkip", v).apply()
    }

    companion object {
        const val SIDE = "side"
        const val TOP = "top"
        const val ASK = "ask"
        const val AUTO = "auto"
        const val OFF = "off"
        const val ON = "on"
        const val CURRENT = "current"
        const val ARCHIVED = "archived"
        const val ALL = "all"
        const val LIST = "list"
        const val TILES = "tiles"
    }
}
