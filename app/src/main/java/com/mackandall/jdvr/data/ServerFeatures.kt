package com.mackandall.jdvr.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.extensions.pluginsApi
import java.util.UUID

/**
 * The server plugins JDVR does more with: which this server has, what each adds, and where to get
 * the ones it doesn't (Settings › Server features).
 */
object ServerFeatures {
    data class Plugin(
        val id: UUID,
        val name: String,
        // What it adds in JDVR, in a line.
        val adds: String,
        // A plugin repository to add in Jellyfin's dashboard (Plugins › Repositories), or null
        // when it isn't published yet.
        val repository: String?,
        // An endpoint any signed-in user may call: it answering means the plugin is there. Null:
        // only an administrator can tell.
        val probe: String?,
    )

    val plugins =
        listOf(
            Plugin(
                UUID.fromString("62b08fb5-2008-4b2b-ace8-e73d3e584405"),
                "Episode History",
                "New recordings on Home, Recorded Shows by show, watched / in library / recorded marks in the guide, Forget this episode",
                null,
                "/EpisodeHistory/Symbols",
            ),
            Plugin(
                UUID.fromString("e8f9a7c2-4b3d-4e1a-9c6f-2a1b0c3d4e5f"),
                "Comskip Commercial Segments",
                "Commercials found and skipped (also while recording), ✂ badges, program start, recording edits",
                "https://raw.githubusercontent.com/mackandall/jellyfin-plugin-comskip-segments/main/manifest.json",
                "/ComskipSegments/Editing",
            ),
            Plugin(
                UUID.fromString("c83d86bb-a1e0-4c35-a113-e2101cf4ee6b"),
                "Intro Skipper",
                "Intros, recaps and previews found and skipped",
                "https://intro-skipper.org/manifest.json",
                null,
            ),
            Plugin(
                UUID.fromString("7c1dd9fc-123a-477b-b646-e735ed726d58"),
                "JellyDVR Schedule",
                "Tuners in use, \"all tuners are busy\" before tuning, recording conflicts and priorities, Recording Favourites",
                null,
                "/JellyDvrSchedule/Tuners",
            ),
            Plugin(
                UUID.fromString("cfd76dc2-a07d-4fc9-a2d9-c5d2aaa67215"),
                "JellyDVR Live",
                "Pause and rewind live TV past when you tuned in; keep what you're watching as a recording",
                null,
                "/JellyDvrLive/Programs",
            ),
            Plugin(
                UUID.fromString("c709ebd6-431f-493c-93cf-1c345db59d49"),
                "JellyDVR Space",
                "Move to archive…, keeping the recordings drive from filling up",
                null,
                null,
            ),
            Plugin(
                UUID.fromString("110ae7e3-0c55-4684-b0f8-3e86eb2b53af"),
                "XMLTV Guide Cleaner",
                "Cleaner movie titles in the guide (for guides that put the year in the title)",
                null,
                null,
            ),
        )

    /** Installed (true), not installed (false) or unknown (null: only an administrator can tell). */
    suspend fun check(): Map<UUID, Boolean?> =
        withContext(Dispatchers.IO) {
            val api = Jdvr.api ?: return@withContext emptyMap()
            // An administrator sees the server's own list (exact, versions and all).
            val installed =
                attemptQuietly {
                    api.pluginsApi
                        .getPlugins()
                        .content
                        .filter { it.status.serialName == "Active" || it.status.serialName == "Restart" }
                        .map { it.id }
                        .toSet()
                }
            plugins.associate { p ->
                p.id to
                    when {
                        installed != null -> {
                            p.id in installed
                        }

                        p.probe != null -> {
                            attemptQuietly {
                                api.request(method = HttpMethod.GET, pathTemplate = p.probe)
                                true
                            } ?: false
                        }

                        else -> {
                            null
                        }
                    }
            }
        }
}
