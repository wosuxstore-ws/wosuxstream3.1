package com.freetv.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val channels = MutableLiveData<List<Channel>>(emptyList())
    val loading = MutableLiveData(false)
    val message = MutableLiveData<String?>()
    val source = MutableLiveData(Prefs.source)

    var query: String = ""
    var favoritesOnly: Boolean = false

    private var job: Job? = null

    // Phase 29: the background health check started by checkAhead()
    private var checkJob: Job? = null

    init {
        load(force = false)
    }

    fun setSource(s: Source) {
        Prefs.source = s
        source.value = s
        channels.value = emptyList()
        favoritesOnly = false
        load(force = false)
    }

    /**
     * Shows the saved copy instantly (works offline), then refreshes from the internet
     * when forced or when the saved copy is older than a few hours.
     *
     * Phase 41: a source can have several playlists (Sources.partsOf). The start source "Sports + Pakistan" has two: each one has
     * its own saved copy and is refreshed on its own, and `channels` always holds the mix of what is there so far (HomeMix).
     * A normal source has exactly one playlist and works as before.
     */
    fun load(force: Boolean) {
        job?.cancel()
        val s = source.value ?: Sources.HOME
        val parts = Sources.partsOf(s)
        val mix = parts.size > 1
        job = viewModelScope.launch {
            loading.value = true
            try {
                // Phase 39: every file system call (filesDir, exists, lastModified) and the parse run on the IO thread
                val copies = withContext(Dispatchers.IO) {
                    parts.map { p ->
                        val f = cacheFile(p)
                        val exists = f.exists()
                        val list = if (exists) parseFile(f) else emptyList()
                        LocalCopy(f, list, !exists || System.currentTimeMillis() - f.lastModified() > STALE_MS)
                    }
                }

                // The channels of every playlist, in the order of `parts`. A playlist is replaced below when it was refreshed.
                val lists = ArrayList<List<Channel>>(copies.size)
                for (c in copies) lists.add(c.channels)
                var shown = false

                val saved = withContext(Dispatchers.Default) { combine(mix, lists) }
                if (saved.isNotEmpty()) {
                    channels.value = saved
                    shown = true
                }

                var failure: Exception? = null
                for (i in parts.indices) {
                    if (!(force || copies[i].stale)) continue
                    try {
                        val cache = copies[i].file
                        val url = parts[i].url
                        val had = lists[i].isNotEmpty()
                        // download() returns false when the server answered 304 (unchanged)
                        val fresh = withContext(Dispatchers.IO) {
                            val changed = download(url, cache)
                            if (changed || !had) parseFile(cache) else null
                        }
                        if (fresh != null) {
                            lists[i] = fresh
                            val now = withContext(Dispatchers.Default) { combine(mix, lists) }
                            channels.value = now
                            if (now.isEmpty()) {
                                message.value = getApplication<Application>().getString(R.string.err_empty)
                            } else {
                                shown = true
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (failure == null) failure = e
                    }
                }

                // One message for the whole load, even when two playlists failed
                val err = failure
                if (err != null) {
                    val app = getApplication<Application>()
                    message.value = if (shown) {
                        app.getString(R.string.err_refresh)
                    } else {
                        app.getString(R.string.err_load, err.message ?: err.javaClass.simpleName)
                    }
                }
            } finally {
                if (isActive) loading.value = false
            }
        }
    }

    /**
     * Phase 29: tests the given channels in the background (StreamChecker.checkAll, at most 40 real checks; results go to HealthStore).
     * A check that is still running from the previous call is cancelled first (its unfinished results are not stored).
     * An empty list only cancels. Channels that already have a fresh result are skipped by StreamChecker.
     */
    fun checkAhead(list: List<Channel>) {
        checkJob?.cancel()
        checkJob = if (list.isEmpty()) {
            null
        } else {
            viewModelScope.launch {
                try {
                    StreamChecker.checkAll(list, limit = 40)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // a failed background check must never crash the app
                }
            }
        }
    }

    /**
     * Phase 41: the channel list of the source from the lists of its playlists (same order as Sources.partsOf).
     * Two playlists (the start source) are mixed by HomeMix; one playlist is returned as it is. Pure: safe on any thread.
     */
    private fun combine(mix: Boolean, lists: List<List<Channel>>): List<Channel> =
        if (mix && lists.size == 2) HomeMix.merge(lists[0], lists[1]) else lists[0]

    private fun cacheFile(s: Source): File =
        File(getApplication<Application>().filesDir, "pl_" + Integer.toHexString(s.url.hashCode()) + ".m3u")

    private fun parseFile(f: File): List<Channel> =
        try {
            f.bufferedReader().use { M3uParser.parse(it) }
        } catch (e: Exception) {
            emptyList()
        }

    /**
     * Downloads the playlist with the shared OkHttp client (OkHttp follows redirects itself).
     * The ETag is kept in a sidecar file next to the cache file (name + ".etag") and sent back
     * as If-None-Match while that cache file still exists.
     *
     * @return true = a new file was saved, false = server said 304 (unchanged, cache file touched).
     */
    private fun download(urlStr: String, dest: File): Boolean {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        val etagFile = File(dest.parentFile, dest.name + ".etag")

        // An ETag only makes sense while we still hold the file it belongs to.
        val oldEtag: String? = if (dest.length() > 0L && etagFile.exists()) {
            try {
                etagFile.readText().trim().takeIf { t -> t.isNotEmpty() && t.all { it in ' '..'~' } }
            } catch (e: IOException) {
                null
            }
        } else {
            null
        }

        val request = Request.Builder().url(urlStr).apply {
            if (oldEtag != null) header("If-None-Match", oldEtag)
        }.build()

        val newEtag: String? = Net.client.newCall(request).execute().use { response ->
            if (response.code == 304 && oldEtag != null) {
                dest.setLastModified(System.currentTimeMillis())
                return false
            }
            if (response.code != 200) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty playlist")
            body.byteStream().use { input -> tmp.outputStream().use { out -> input.copyTo(out) } }
            response.header("ETag")
        }

        if (tmp.length() == 0L) {
            tmp.delete()
            throw IOException("Empty playlist")
        }
        etagFile.delete() // the old ETag belongs to the old file: drop it before the file is replaced
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        newEtag?.trim()?.takeIf { it.isNotEmpty() }?.let { etagFile.writeText(it) }
        return true
    }

    // Phase 39: what the IO thread found about the saved copy of the playlist
    private class LocalCopy(val file: File, val channels: List<Channel>, val stale: Boolean)

    private companion object {
        const val STALE_MS = 6L * 60 * 60 * 1000
    }
}
