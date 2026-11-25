package com.mackandall.jdvr.ui

import com.mackandall.jdvr.Screen
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.Library

/** How a place sits in the menu: a section, the Libraries group, or a library under it. */
enum class PlaceKind { Item, Group, Child }

/** One place in the menu, as both the slide-out menu and the icon strip show it. */
data class MenuPlace(
    val key: String,
    val label: String,
    val icon: MenuIcon,
    val kind: PlaceKind,
    // Where it goes: a section, or a library (null for the Libraries group).
    val screen: Screen? = null,
    val library: Library? = null,
    // A line above it (the libraries listed one by one, and the entry after them).
    val divider: Boolean = false,
    // A count beside it (open problems).
    val count: Int = 0,
)

/**
 * The menu's places in Settings › Menu's order: hidden ones left out (Home always stays), the
 * libraries as one group ([librariesOpen]: with them under it) or one by one, Problems only while
 * there are some (or it's the screen shown), Recorded Shows and Trash only where they apply, and
 * what this TV may not do (JellyDVR Space's device rights) left out. The menu and the icon strip
 * both come from this, so they always agree.
 */
fun menuPlaces(
    libraries: List<Library>,
    showRecorded: Boolean,
    showTrash: Boolean,
    problems: Int,
    selected: Screen,
    rights: com.mackandall.jdvr.data.DeviceRights,
    librariesOpen: Boolean,
): List<MenuPlace> {
    val prefs = Jdvr.prefs
    val libs = libraries.inMenuOrder()
    return buildList {
        prefs.menuOrder.filter { it == "home" || it !in prefs.hiddenMenu }.forEach { k ->
            if (k == "libraries") {
                if (libs.isEmpty()) return@forEach
                if (prefs.librariesGrouped) {
                    add(MenuPlace(k, "Libraries", MenuIcon.Libraries, PlaceKind.Group, count = 0))
                    if (librariesOpen) libs.forEach { l -> add(MenuPlace("lib:${l.id}", l.name, l.menuIcon(), PlaceKind.Child, library = l)) }
                } else {
                    libs.forEachIndexed { i, l -> add(MenuPlace("lib:${l.id}", l.name, l.menuIcon(), PlaceKind.Item, library = l, divider = i == 0)) }
                }
            } else {
                val s = Screen.entries.firstOrNull { it.key == k } ?: return@forEach
                if (s == Screen.RecordedShows && !showRecorded) return@forEach
                if (s == Screen.Trash && !showTrash) return@forEach
                if (s == Screen.Problems && problems <= 0 && selected != Screen.Problems) return@forEach
                if (s == Screen.LiveTv && !rights.liveTv) return@forEach
                if (s == Screen.Series && !rights.record) return@forEach
                // A line under the libraries when they're listed one by one.
                val afterLibs = lastOrNull()?.let { it.kind == PlaceKind.Item && it.library != null } == true
                add(MenuPlace(k, s.label, s.menuIcon(), PlaceKind.Item, screen = s, divider = afterLibs, count = if (s == Screen.Problems) problems else 0))
            }
        }
    }
}
