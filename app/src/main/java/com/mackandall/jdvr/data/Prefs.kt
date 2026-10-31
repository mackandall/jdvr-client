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
    var guideInfo by mutableStateOf(p.getString("guideInfo", BOTTOM) ?: BOTTOM)
        private set

    /** Guide and search: a programme's actions "docked" on the right, or "floating" in the middle. */
    var programActions by mutableStateOf(p.getString("programActions", DOCKED) ?: DOCKED)
        private set

    /** Commercials in recordings: "ask" (a Skip prompt), "auto" or "off". */
    var commercialSkip by mutableStateOf(p.getString("commercialSkip", ASK) ?: ASK)
        private set

    /** Intros / recaps / previews: "ask", "auto" or "off". */
    var introSkip by mutableStateOf(p.getString("introSkip", ASK) ?: ASK)
        private set

    /** The channel group the guide shows ("" = the first, all channels). */
    var channelGroup by mutableStateOf(p.getString("channelGroup", "") ?: "")
        private set

    fun changeChannelGroup(v: String) {
        channelGroup = v
        p.edit().putString("channelGroup", v).apply()
    }

    /** Libraries kept out of the menu (by id); new ones show up. */
    var hiddenLibraries by mutableStateOf(p.getStringSet("hiddenLibraries", emptySet())?.toSet() ?: emptySet())
        private set

    /** Libraries left without a "Recently added" row on Home (by id); new ones get one. */
    var homeHiddenLibraries by mutableStateOf(p.getStringSet("homeHiddenLibraries", emptySet())?.toSet() ?: emptySet())
        private set

    fun toggleHomeLibrary(id: String) {
        homeHiddenLibraries = if (id in homeHiddenLibraries) homeHiddenLibraries - id else homeHiddenLibraries + id
        p.edit().putStringSet("homeHiddenLibraries", homeHiddenLibraries).apply()
    }

    /** Libraries listed newest first (by id); the rest are A–Z. */
    var newestLibraries by mutableStateOf(p.getStringSet("newestLibraries", emptySet())?.toSet() ?: emptySet())
        private set

    fun changeLibraryNewest(
        libraryId: String,
        newest: Boolean,
    ) {
        newestLibraries = if (newest) newestLibraries + libraryId else newestLibraries - libraryId
        p.edit().putStringSet("newestLibraries", newestLibraries).apply()
    }

    /** Recorded Shows: "current" (the live recordings folder), "archived", or "all". */
    var recordingsFilter by mutableStateOf(p.getString("recordingsFilter", CURRENT) ?: CURRENT)

    // Recorded Shows as cards per show (SHOWS) or one line per recording by date (BY_DATE).
    var recordingsView by mutableStateOf(p.getString("recordingsView", SHOWS) ?: SHOWS)
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

    /** How many hours the guide spans (SageTV's wider guide); programme names shorten to fit. */
    var guideHours by mutableStateOf(p.getInt("guideHours", 2).takeIf { it in GUIDE_HOURS } ?: 2)
        private set

    fun changeGuideHours(v: Int) {
        guideHours = v
        p.edit().putInt("guideHours", v).apply()
    }

    /** Shorter programme names in narrow guide cells ("Law & Order: Special Victims Unit" → "L&O: SVU"). */
    var guideShortNames by mutableStateOf(p.getBoolean("guideShortNames", true))
        private set

    fun changeGuideShortNames(v: Boolean) {
        guideShortNames = v
        p.edit().putBoolean("guideShortNames", v).apply()
    }

    /** A tab at the end of a guide row counting that channel's recordings past the window. */
    var guideLaterTabs by mutableStateOf(p.getBoolean("guideLaterTabs", true))
        private set

    fun changeGuideLaterTabs(v: Boolean) {
        guideLaterTabs = v
        p.edit().putBoolean("guideLaterTabs", v).apply()
    }

    /** Record series opens the series options first (which episodes, channel, keep…); off: set up with the server's defaults. */
    var seriesOptionsFirst by mutableStateOf(p.getBoolean("seriesOptionsFirst", true))
        private set

    fun changeSeriesOptionsFirst(v: Boolean) {
        seriesOptionsFirst = v
        p.edit().putBoolean("seriesOptionsFirst", v).apply()
    }

    /** The colour theme (Settings › Appearance): a key of [com.mackandall.jdvr.ui.Palettes]. */
    var theme by mutableStateOf(p.getString("theme", "midnight") ?: "midnight")
        private set

    /** A light theme's backdrop (Settings › Backdrop): a key of [com.mackandall.jdvr.ui.Washes]. */
    var wash by mutableStateOf(p.getString("wash", "blue") ?: "blue")
        private set

    /** Gemstone's colour (the theme gallery's swatches): a key of [com.mackandall.jdvr.ui.Gems]. */
    var gem by mutableStateOf(p.getString("gem", "emerald") ?: "emerald")
        private set

    fun changeGem(v: String) {
        gem = v
        com.mackandall.jdvr.ui.Jc.palette =
            com.mackandall.jdvr.ui
                .paletteFor(theme, v)
        p.edit().putString("gem", v).apply()
    }

    init {
        com.mackandall.jdvr.ui.Jc.palette =
            com.mackandall.jdvr.ui
                .paletteFor(theme, gem)
        com.mackandall.jdvr.ui.Jc.wash =
            com.mackandall.jdvr.ui
                .washFor(wash)
    }

    fun changeWash(v: String) {
        wash = v
        com.mackandall.jdvr.ui.Jc.wash =
            com.mackandall.jdvr.ui
                .washFor(v)
        p.edit().putString("wash", v).apply()
    }

    fun changeTheme(v: String) {
        theme = v
        com.mackandall.jdvr.ui.Jc.palette =
            com.mackandall.jdvr.ui
                .paletteFor(v, gem)
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
        p
            .edit()
            .putString("weatherName", v.name)
            .putFloat("weatherLat", v.latitude.toFloat())
            .putFloat("weatherLon", v.longitude.toFloat())
            .apply()
    }

    fun changeFahrenheit(v: Boolean) {
        fahrenheit = v
        p.edit().putBoolean("fahrenheit", v).apply()
    }

    // ---- the menu (Settings › Menu) ---------------------------------------------------------

    /** The menu's entries in order, by key ([MENU_DEFAULT]); "libraries" is the libraries' place. */
    var menuOrder by mutableStateOf(normalizeMenu(p.getString("menuOrder", null)))
        private set

    /** Menu entries left out (by key). Home always stays. */
    var hiddenMenu by mutableStateOf(p.getStringSet("hiddenMenu", emptySet())?.toSet() ?: emptySet())
        private set

    /** The libraries' order in the menu (by id); new ones go after these, in the server's order. */
    var libraryOrder by mutableStateOf(p.getString("libraryOrder", "")!!.split(',').filter { it.isNotEmpty() })
        private set

    /** Libraries under one "Libraries" entry that opens and closes, or each listed on its own. */
    var librariesGrouped by mutableStateOf(p.getBoolean("librariesGrouped", true))
        private set

    /** How the Libraries group is when the menu opens: [OPEN], [CLOSED] or [LAST] (as last left). */
    var librariesStart by mutableStateOf(p.getString("librariesStart", LAST) ?: LAST)
        private set

    /** Whether the Libraries group was last left open. */
    var librariesOpen by mutableStateOf(p.getBoolean("librariesOpen", false))
        private set

    /** Settings and Sign out as icons beside JDVR at the top of the menu, or as the last entries. */
    var menuIcons by mutableStateOf(p.getBoolean("menuIcons", true))
        private set

    fun changeMenuOrder(v: List<String>) {
        menuOrder = v
        p.edit().putString("menuOrder", v.joinToString(",")).apply()
    }

    fun toggleMenu(key: String) {
        if (key == "home") return
        hiddenMenu = if (key in hiddenMenu) hiddenMenu - key else hiddenMenu + key
        p.edit().putStringSet("hiddenMenu", hiddenMenu).apply()
    }

    fun changeLibraryOrder(v: List<String>) {
        libraryOrder = v
        p.edit().putString("libraryOrder", v.joinToString(",")).apply()
    }

    fun changeLibrariesGrouped(v: Boolean) {
        librariesGrouped = v
        p.edit().putBoolean("librariesGrouped", v).apply()
    }

    fun changeLibrariesStart(v: String) {
        librariesStart = v
        p.edit().putString("librariesStart", v).apply()
    }

    fun changeLibrariesOpen(v: Boolean) {
        librariesOpen = v
        p.edit().putBoolean("librariesOpen", v).apply()
    }

    /** The menu's icons: a set from ui/MenuIcons.kt ("line", "solid", "tile"), or OFF. */
    var menuIconSet by mutableStateOf(p.getString("menuIconSet", "line") ?: "line")
        private set

    /** How the icons move with focus: OFF, MAGNIFY (the focused one) or WAVE (and its neighbours). */
    var menuIconMotion by mutableStateOf(p.getString("menuIconMotion", MAGNIFY) ?: MAGNIFY)
        private set

    /** How big the focused icon gets: SMALL, MEDIUM or LARGE (never past its row). */
    var menuIconSize by mutableStateOf(p.getString("menuIconSize", MEDIUM) ?: MEDIUM)
        private set

    /** Home's tiles (Settings › Home): ON, OFF, or AUTO (only when there is something to show). */
    var homeTiles by mutableStateOf(parseTiles(p.getString("homeTiles", null)))
        private set

    fun homeTile(key: String): String = homeTiles[key] ?: HOME_PRESETS.getValue(STANDARD).getValue(key)

    fun changeHomeTile(
        key: String,
        v: String,
    ) {
        homeTiles = HOME_TILES.associateWith { homeTile(it) } + (key to v)
        p.edit().putString("homeTiles", homeTiles.entries.joinToString(",") { "${it.key}=${it.value}" }).apply()
    }

    fun applyHomePreset(name: String) {
        HOME_PRESETS[name]?.forEach { (k, v) -> changeHomeTile(k, v) }
    }

    /** The preset Home matches now, or null (set by hand). */
    fun homePreset(): String? = HOME_PRESETS.entries.firstOrNull { (_, tiles) -> tiles.all { (k, v) -> homeTile(k) == v } }?.key

    private fun parseTiles(v: String?): Map<String, String> =
        v
            .orEmpty()
            .split(',')
            .mapNotNull { it.split('=').takeIf { kv -> kv.size == 2 }?.let { kv -> kv[0] to kv[1] } }
            .toMap()

    fun changeMenuIconSet(v: String) {
        menuIconSet = v
        p.edit().putString("menuIconSet", v).apply()
    }

    fun changeMenuIconMotion(v: String) {
        menuIconMotion = v
        p.edit().putString("menuIconMotion", v).apply()
    }

    fun changeMenuIconSize(v: String) {
        menuIconSize = v
        p.edit().putString("menuIconSize", v).apply()
    }

    fun changeMenuIcons(v: Boolean) {
        menuIcons = v
        p.edit().putBoolean("menuIcons", v).apply()
    }

    /** The standard order, everything shown (libraries too). */
    fun resetMenu() {
        changeMenuOrder(MENU_DEFAULT)
        changeLibraryOrder(emptyList())
        hiddenMenu = emptySet()
        hiddenLibraries = emptySet()
        p
            .edit()
            .putStringSet("hiddenMenu", hiddenMenu)
            .putStringSet("hiddenLibraries", hiddenLibraries)
            .apply()
    }

    fun toggleLibrary(id: String) {
        hiddenLibraries = if (id in hiddenLibraries) hiddenLibraries - id else hiddenLibraries + id
        p.edit().putStringSet("hiddenLibraries", hiddenLibraries).apply()
    }

    fun changeRecordingsFilter(v: String) {
        recordingsFilter = v
        p.edit().putString("recordingsFilter", v).apply()
    }

    fun changeRecordingsView(v: String) {
        recordingsView = v
        p.edit().putString("recordingsView", v).apply()
    }

    fun changeGuideInfo(v: String) {
        guideInfo = v
        p.edit().putString("guideInfo", v).apply()
    }

    fun changeProgramActions(v: String) {
        programActions = v
        p.edit().putString("programActions", v).apply()
    }

    fun changeCommercialSkip(v: String) {
        commercialSkip = v
        p.edit().putString("commercialSkip", v).apply()
    }

    fun changeIntroSkip(v: String) {
        introSkip = v
        p.edit().putString("introSkip", v).apply()
    }

    /** Holding OK (or Menu) on a poster, card or episode: "off" (as a press), "menu" (quick actions) or "details" (its details page). */
    var holdOk by mutableStateOf(p.getString("holdOk", HOLD_OFF) ?: HOLD_OFF)
        private set

    fun changeHoldOk(v: String) {
        holdOk = v
        p.edit().putString("holdOk", v).apply()
    }

    companion object {
        // Home's tiles, row by row, and the presets of Settings › Home.
        val HOME_TILES = listOf("live", "weather", "now", "next", "attention", "tuners", "fresh", "libraries")
        const val CALM = "calm"
        const val STANDARD = "standard"
        const val EVERYTHING = "everything"
        val HOME_PRESETS =
            linkedMapOf(
                CALM to mapOf("live" to "on", "weather" to "off", "now" to "auto", "next" to "on", "attention" to "auto", "tuners" to "off", "fresh" to "on", "libraries" to "on"),
                STANDARD to mapOf("live" to "on", "weather" to "on", "now" to "auto", "next" to "on", "attention" to "auto", "tuners" to "on", "fresh" to "on", "libraries" to "on"),
                EVERYTHING to mapOf("live" to "on", "weather" to "on", "now" to "on", "next" to "on", "attention" to "on", "tuners" to "on", "fresh" to "on", "libraries" to "on"),
            )
        const val MAGNIFY = "magnify"
        const val WAVE = "wave"
        const val SMALL = "small"
        const val MEDIUM = "medium"
        const val LARGE = "large"
        const val HOLD_OFF = "off"
        const val HOLD_MENU = "menu"
        const val HOLD_DETAILS = "details"
        // The guide's widths, in hours (Settings › Guide, ⏪/⏩ and the Hours button in the guide).
        val GUIDE_HOURS = listOf(2, 3, 4, 6, 8)
        const val SIDE = "side"
        const val TOP = "top"
        const val BOTTOM = "bottom"
        const val DOCKED = "docked"
        const val FLOATING = "floating"
        const val ASK = "ask"
        const val AUTO = "auto"
        const val OFF = "off"
        const val ON = "on"
        const val CURRENT = "current"
        const val ARCHIVED = "archived"
        const val ALL = "all"
        const val SHOWS = "shows"
        const val BY_DATE = "date"
        const val LIST = "list"
        const val TILES = "tiles"
        const val OPEN = "open"
        const val CLOSED = "closed"
        const val LAST = "last"
        val MENU_DEFAULT = listOf("search", "home", "guide", "livetv", "recorded", "libraries", "scheduled", "series", "trash")

        /** A saved order, minus keys no longer known, plus new ones at the end. */
        fun normalizeMenu(saved: String?): List<String> {
            val known =
                saved
                    .orEmpty()
                    .split(',')
                    .filter { it in MENU_DEFAULT }
                    .distinct()
            return known + MENU_DEFAULT.filter { it !in known }
        }
    }
}
