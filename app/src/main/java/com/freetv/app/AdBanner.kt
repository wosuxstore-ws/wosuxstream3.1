package com.freetv.app

import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Top banners for the shop products. The pictures / videos and a small list file (banners.json) sit on your own web hosting (cPanel).
 * The app talks to that hosting directly: no Firebase, no ad network, no other service.
 */
object AdConfig {
    /** CHANGE THIS: the full address of your banners.json on your cPanel hosting (see README, "Banners from your cPanel"). */
    const val FEED_URL = "https://wosux.com/ads/banners.json"

    /** Seconds a picture stays on screen when banners.json gives no "seconds". */
    const val DEFAULT_SECONDS = 6

    /** At most this many banners are used. */
    const val MAX_BANNERS = 10

    /** Home asks the server again for the list after this many milliseconds (when Home is shown again). */
    const val REFRESH_MS = 5 * 60 * 1000L

    /** False while FEED_URL still is the placeholder: then the app does not call any server and shows no banner. */
    val isConfigured: Boolean
        get() = FEED_URL.startsWith("http") && !FEED_URL.contains("yourdomain.com")
}

/** One banner. [src] is a full address of a picture or video. [link] (http / https only) opens in the phone's browser when the banner is clicked. */
data class AdBanner(
    val src: String,
    val link: String?,
    val isVideo: Boolean,
    val seconds: Int
)

object AdRepository {

    private val VIDEO_EXT = listOf(".mp4", ".webm", ".m3u8", ".mkv", ".3gp", ".mov")

    /**
     * Downloads and reads banners.json. Blocking: call it on a background thread.
     * Returns null when the server cannot be reached or the file is not valid (the caller then keeps what it already shows).
     * Returns an empty list when the file is fine but has no banners.
     */
    fun fetch(): List<AdBanner>? {
        if (!AdConfig.isConfigured) return null
        return try {
            val req = Request.Builder()
                .url(AdConfig.FEED_URL)
                .header("Cache-Control", "no-cache")
                .build()
            Net.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val text = resp.body?.string() ?: return null
                parse(text)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parse(text: String): List<AdBanner>? {
        val trimmed = text.trim().removePrefix("\uFEFF") // a BOM at the start is common in files saved by Windows editors
        val defaultSeconds: Int
        val arr: JSONArray
        if (trimmed.startsWith("[")) {
            arr = JSONArray(trimmed)
            defaultSeconds = AdConfig.DEFAULT_SECONDS
        } else {
            val root = JSONObject(trimmed)
            arr = root.optJSONArray("banners") ?: return null
            defaultSeconds = root.optInt("seconds", AdConfig.DEFAULT_SECONDS).coerceIn(2, 120)
        }
        val base = URI(AdConfig.FEED_URL)
        val out = ArrayList<AdBanner>()
        for (i in 0 until arr.length()) {
            if (out.size >= AdConfig.MAX_BANNERS) break
            val o = arr.optJSONObject(i) ?: continue
            if (o.optBoolean("disabled", false)) continue   // lets you pause one banner without deleting it
            val rawSrc = o.optString("src").trim()
            if (rawSrc.isEmpty()) continue
            val src = resolveHttp(base, rawSrc) ?: continue  // "ad1.jpg" = next to banners.json; a full address works too
            val link = o.optString("link").trim().takeIf { it.isNotEmpty() }?.let { resolveHttp(null, it) }
            val type = o.optString("type").lowercase()
            val isVideo = when (type) {
                "video" -> true
                "image" -> false
                else -> {
                    val path = src.substringBefore('?').lowercase()
                    VIDEO_EXT.any { path.endsWith(it) }
                }
            }
            val seconds = o.optInt("seconds", defaultSeconds).coerceIn(2, 120)
            out.add(AdBanner(src, link, isVideo, seconds))
        }
        return out
    }

    /** Full http / https address, or null. Anything else (file:, intent:, javascript: ...) is refused. */
    private fun resolveHttp(base: URI?, value: String): String? =
        try {
            val uri = if (base != null) base.resolve(value.replace(" ", "%20")) else URI(value.replace(" ", "%20"))
            val scheme = uri.scheme?.lowercase()
            if ((scheme == "http" || scheme == "https") && !uri.host.isNullOrEmpty()) uri.toString() else null
        } catch (e: Exception) {
            null
        }
}
