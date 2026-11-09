package com.freetv.app

import okhttp3.Request
import org.json.JSONObject
import java.net.URI

/**
 * The "live match" card under the top banner on Home (Phase 42).
 * You switch it on and off in the cPanel admin page (admin.php). The app reads live.json from the SAME folder as banners.json,
 * so it needs no second address. Until the card is switched on (or while live.json does not exist) Home looks exactly as before.
 */
object LiveConfig {
    /** live.json next to banners.json (derived from [AdConfig.FEED_URL]). */
    val LIVE_URL: String
        get() = AdConfig.FEED_URL.substringBeforeLast('/') + "/live.json"

    /** Home asks the server again this often while it is on screen (so switching the card on shows up within about a minute). */
    const val REFRESH_MS = 60_000L

    /** The progress bar of the card moves this often. */
    const val TICK_MS = 30_000L
}

/**
 * One live card. [channel] is what opens when the card is clicked (its stream address comes from live.json, so it plays even when the
 * channel is not in the playlist that is loaded now). [startAt] / [endAt] are seconds since 1970 (0 = not given): with both, the card shows
 * a progress bar and a time range, and it disappears by itself when the match is over.
 */
data class LiveCard(
    val title: String,
    val subtitle: String?,
    val image: String?,
    val channel: Channel,
    val startAt: Long,
    val endAt: Long
) {
    val hasTimes: Boolean get() = startAt > 0L && endAt > startAt

    /** True when an end time was given and it has passed. */
    fun isOver(nowMs: Long): Boolean = endAt > 0L && nowMs / 1000L >= endAt

    /** 0..1000, how far the match is (0 before the start, 1000 at the end). */
    fun progressPermille(nowMs: Long): Int {
        if (!hasTimes) return 0
        val nowSec = nowMs / 1000.0
        val p = (nowSec - startAt) / (endAt - startAt).toDouble()
        return (p.coerceIn(0.0, 1.0) * 1000).toInt()
    }
}

/** Answer of one request. [card] is null when the server says "nothing to show". A request that failed is a null LiveResult instead. */
class LiveResult(val card: LiveCard?)

object LiveRepository {

    /**
     * Downloads and reads live.json. Blocking: call it on a background thread.
     * Returns null when the server cannot be reached or the file is not valid (the caller then keeps what it already shows).
     * Returns a LiveResult with card = null when there is nothing to show (no file yet, or the card is switched off).
     */
    fun fetch(): LiveResult? {
        if (!AdConfig.isConfigured) return null
        return try {
            val req = Request.Builder()
                .url(LiveConfig.LIVE_URL + "?t=" + System.currentTimeMillis()) // the time makes sure no server / proxy hands out an old copy
                .header("Cache-Control", "no-cache")
                .build()
            Net.client.newCall(req).execute().use { resp ->
                if (resp.code == 404) return LiveResult(null) // no live.json yet = no live card
                if (!resp.isSuccessful) return null
                val text = resp.body?.string() ?: return null
                LiveResult(parse(text))
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parse(text: String): LiveCard? {
        val root = JSONObject(text.trim().removePrefix("\uFEFF"))
        if (!root.optBoolean("enabled", false)) return null
        val title = str(root, "title")
        if (title.isEmpty()) return null

        val ch = root.optJSONObject("channel") ?: return null
        val url = str(ch, "url")
        if (!isHttp(url)) return null
        val logo = str(ch, "logo")
        val channel = Channel(
            name = str(ch, "name").ifEmpty { title },
            url = url,
            logo = if (logo.startsWith("http", ignoreCase = true)) logo else null,
            group = str(ch, "group").ifEmpty { null },
            referrer = str(ch, "referrer").ifEmpty { null },
            userAgent = str(ch, "userAgent").ifEmpty { null }
        )

        val base = URI(LiveConfig.LIVE_URL)
        val image = str(root, "image").takeIf { it.isNotEmpty() }?.let { resolveHttp(base, it) }
        val subtitle = str(root, "subtitle").ifEmpty { null }
        return LiveCard(
            title = title,
            subtitle = subtitle,
            image = image,
            channel = channel,
            startAt = root.optLong("start", 0L),
            endAt = root.optLong("end", 0L)
        )
    }

    // A missing key and a JSON null both give "" (JSONObject.optString would turn a null into the text "null")
    private fun str(o: JSONObject, key: String): String = if (o.isNull(key)) "" else o.optString(key).trim()

    private fun isHttp(value: String): Boolean =
        try {
            val uri = URI(value.replace(" ", "%20"))
            val scheme = uri.scheme?.lowercase()
            (scheme == "http" || scheme == "https") && !uri.host.isNullOrEmpty()
        } catch (e: Exception) {
            false
        }

    /** "live_x.jpg" = next to live.json; a full address works too. Only http / https is accepted. */
    private fun resolveHttp(base: URI, value: String): String? =
        try {
            val uri = base.resolve(value.replace(" ", "%20"))
            val scheme = uri.scheme?.lowercase()
            if ((scheme == "http" || scheme == "https") && !uri.host.isNullOrEmpty()) uri.toString() else null
        } catch (e: Exception) {
            null
        }
}
