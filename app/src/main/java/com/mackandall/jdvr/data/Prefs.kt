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
    }
}
