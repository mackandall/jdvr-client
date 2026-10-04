package com.mackandall.jdvr.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.LiveTvRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * What a recording offers besides playing it: add its show to Recording Favourites (or, if it's
 * one already, take it off), and delete it. Taking a favourite off and deleting both ask for a
 * second press. [onNote] reports what happened; [onDeleted] follows a delete.
 */
@Composable
fun RecordingActions(
    itemId: UUID,
    onNote: (String) -> Unit,
    onDeleted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var fav by remember(itemId) { mutableStateOf<LiveTvRepository.FavouriteFor?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var armedDelete by remember(itemId) { mutableStateOf(false) }
    var armedUnfav by remember(itemId) { mutableStateOf(false) }
    LaunchedEffect(itemId, reload) { fav = runCatching { Jdvr.liveTv.favouriteForItem(itemId) }.getOrNull() }
    LaunchedEffect(armedDelete, armedUnfav) {
        if (armedDelete || armedUnfav) {
            delay(4000)
            armedDelete = false
            armedUnfav = false
        }
    }

    val f = fav
    if (f != null && !f.isMovie) {
        val rule = f.favourite
        if (rule == null) {
            Button("★ Add to Recording Favourites") {
                scope.launch {
                    onNote(runCatching { Jdvr.liveTv.addFavourite(itemId)?.message ?: "Added ${f.title} to Recording Favourites" }.getOrElse { "Couldn't add it: ${it.message}" })
                    reload++
                }
            }
        } else {
            Button(if (armedUnfav) "Press again: remove from favourites" else "★ In Recording Favourites") {
                if (!armedUnfav) {
                    armedUnfav = true
                } else {
                    armedUnfav = false
                    scope.launch {
                        onNote(runCatching { Jdvr.liveTv.removeFavourite(rule.id)?.message ?: "Removed ${rule.title} from Recording Favourites" }.getOrElse { "Couldn't remove it: ${it.message}" })
                        reload++
                    }
                }
            }
        }
    }
    Button(if (armedDelete) "Press again to delete" else "Delete recording", leading = { Dot(Jc.Recording) }) {
        if (!armedDelete) {
            armedDelete = true
        } else {
            armedDelete = false
            scope.launch {
                runCatching { Jdvr.liveTv.deleteItem(itemId) }
                    .onSuccess {
                        onNote("Recording deleted")
                        onDeleted()
                    }.onFailure { onNote("Couldn't delete it: ${it.message}") }
            }
        }
    }
}
