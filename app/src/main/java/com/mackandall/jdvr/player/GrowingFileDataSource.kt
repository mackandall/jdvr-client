package com.mackandall.jdvr.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.mackandall.jdvr.data.Jdvr
import timber.log.Timber
import java.io.IOException
import java.io.InterruptedIOException

/**
 * A live buffer file still being written (NextPVR's): opened at the size it has then, so it seeks
 * like a recording, but at that size it doesn't end; it reads on from the plain file as the tuner
 * writes more, waiting while nothing new has come. Reopening the file at each end stopped the
 * picture for 2-3 s about once a minute (2026-10-25). Anything else passes straight through.
 */
@UnstableApi
internal class GrowingFileDataSource(
    private val upstream: DataSource.Factory,
) : DataSource {
    class Factory(
        private val upstream: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource() = GrowingFileDataSource(upstream)
    }

    private val listeners = mutableListOf<TransferListener>()
    private var current: DataSource? = null
    private var spec: DataSpec? = null

    // The plain file to read on from, and where the next byte is in it.
    private var growing: Uri? = null
    private var offset = 0L

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        val length = openUpstream(dataSpec)
        // Only a read to the end can go on past it (a read of a set length stops where it was asked).
        growing =
            dataSpec.length
                .takeIf { it == C.LENGTH_UNSET.toLong() }
                ?.let { Jdvr.backend.liveCopyGrowingUrl(dataSpec.uri.toString()) }
                ?.let(Uri::parse)
        offset = dataSpec.position
        return length
    }

    private fun openUpstream(dataSpec: DataSpec): Long {
        val source = upstream.createDataSource()
        listeners.forEach(source::addTransferListener)
        current = source
        return source.open(dataSpec)
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        val source = current ?: return C.RESULT_END_OF_INPUT
        val n = source.read(buffer, offset, length)
        if (n != C.RESULT_END_OF_INPUT) {
            this.offset += n
            return n
        }
        val file = growing ?: return C.RESULT_END_OF_INPUT
        return readOn(file, buffer, offset, length)
    }

    // At the end of what's there: the bytes after it, once the tuner has written them. Nothing new
    // for a while: the file has stopped growing (the server let the tuner go), and it ends.
    private fun readOn(
        file: Uri,
        buffer: ByteArray,
        bufferOffset: Int,
        length: Int,
    ): Int {
        val giveUpAt = android.os.SystemClock.elapsedRealtime() + GIVE_UP_MS
        while (android.os.SystemClock.elapsedRealtime() < giveUpAt) {
            closeCurrent()
            val more =
                try {
                    openUpstream(
                        spec!!
                            .buildUpon()
                            .setUri(file)
                            .setPosition(offset)
                            .setLength(C.LENGTH_UNSET.toLong())
                            .build(),
                    )
                    current!!.read(buffer, bufferOffset, length)
                } catch (e: IOException) {
                    // Asking past the end of the file (416) or a dropped connection: try again shortly.
                    if (e is InterruptedIOException) throw e
                    C.RESULT_END_OF_INPUT
                }
            if (more != C.RESULT_END_OF_INPUT && more > 0) {
                offset += more
                return more
            }
            try {
                Thread.sleep(POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Stopped waiting for the buffer file to grow")
            }
        }
        Timber.i("Buffer file stopped growing at %d bytes", offset)
        growing = null
        return C.RESULT_END_OF_INPUT
    }

    private fun closeCurrent() {
        val source = current ?: return
        current = null
        runCatching { source.close() }
    }

    override fun getUri(): Uri? = current?.uri ?: spec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        growing = null
        spec = null
        val source = current ?: return
        current = null
        source.close()
    }

    private companion object {
        const val POLL_MS = 300L
        const val GIVE_UP_MS = 15_000L
    }
}
