package com.mackandall.jdvr.data

import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import java.util.UUID

/** An id compared across APIs: some send dashes, some don't. */
fun String.plainId(): String = replace("-", "").lowercase()

/** An id as our plugins' paths take it: without dashes. */
fun UUID.plain(): String = toString().replace("-", "")

/** Jellyfin ids come without dashes; UUID.fromString needs them. */
fun String.toUuid(): UUID? = toUUIDOrNull()
