package com.freetv.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Outline
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.load
import coil.request.CachePolicy

/**
 * The banner box on Home (HomeAdapter puts it in the place of the hero card). Shows the pictures / videos from [AdRepository] one after the other
 * (pictures for their "seconds", videos until they end, muted). A click or OK on the remote opens the banner's link in the browser.
 * Hidden (GONE) until there is at least one banner, so without a server the screen looks exactly as before.
 *
 * Call [setBanners] with the list, and [start] / [stop] with the screen (HomeFragment does this). [stop] also frees the video player.
 */
@OptIn(UnstableApi::class)
class AdBannerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private companion object {
        const val VIDEO_MAX_MS = 90_000L // with several banners, a video is shown for 90 s at most
    }

    private val handler = Handler(Looper.getMainLooper())
    private val advanceTask = Runnable { next() }

    private val image = ImageView(context).apply {
        layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        scaleType = ImageView.ScaleType.CENTER_CROP
    }
    private var playerView: PlayerView? = null
    private var player: ExoPlayer? = null

    private var items: List<AdBanner> = emptyList()
    private var index = 0
    private var running = false

    /** Called (on the main thread) when every banner failed to load and the strip hid itself: the screen can show something else instead. */
    var onEmpty: (() -> Unit)? = null

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            // more than one banner: the next one starts when the video is over (a single video repeats, see playVideo)
            if (state == Player.STATE_ENDED && items.size > 1) next()
        }

        override fun onPlayerError(error: PlaybackException) {
            dropCurrent() // a video that cannot play is left out
        }
    }

    init {
        setBackgroundColor(ContextCompat.getColor(context, R.color.surface))
        addView(image)
        // Rounded corners
        val radius = resources.getDimension(R.dimen.card_radius)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        clipToOutline = true
        // The banner itself takes the click / the TV focus, never the picture or the video inside it
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        isClickable = true
        isFocusable = true
        foreground = ContextCompat.getDrawable(context, R.drawable.ad_banner_focus)
        setOnClickListener { openLink() }
        contentDescription = context.getString(R.string.ad_banner_desc)
    }

    /** Shows this list. The same list again changes nothing (no flicker when Home refreshes). An empty list hides the strip. */
    fun setBanners(list: List<AdBanner>) {
        if (list == items) return
        items = list
        index = 0
        handler.removeCallbacks(advanceTask)
        if (list.isEmpty()) {
            visibility = GONE
            releasePlayer()
            return
        }
        visibility = VISIBLE
        if (running) show()
    }

    fun start() {
        if (running) return
        running = true
        if (items.isNotEmpty()) show()
    }

    fun stop() {
        running = false
        handler.removeCallbacks(advanceTask)
        releasePlayer()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun show() {
        val b = items.getOrNull(index) ?: return
        handler.removeCallbacks(advanceTask)
        if (b.isVideo) playVideo(b) else showImage(b)
    }

    private fun next() {
        if (items.isEmpty()) return
        index = (index + 1) % items.size
        show()
    }

    private fun showImage(b: AdBanner) {
        releasePlayer()
        image.visibility = VISIBLE
        image.load(b.src) {
            // No saved copy on the disk: when you replace a picture on your hosting, the app shows the new one
            diskCachePolicy(CachePolicy.DISABLED)
            listener(onError = { _, _ ->
                if (items.getOrNull(index) === b) dropCurrent()
            })
        }
        if (items.size > 1) handler.postDelayed(advanceTask, b.seconds * 1000L)
    }

    private fun playVideo(b: AdBanner) {
        val pv = playerView ?: PlayerView(context).apply {
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            useController = false
            isFocusable = false
            isFocusableInTouchMode = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM // fills the strip like the pictures do
            setShutterBackgroundColor(ContextCompat.getColor(context, R.color.surface))
        }.also {
            playerView = it
            addView(it)
        }
        val p = player ?: run {
            val http = OkHttpDataSource.Factory(Net.client)
            ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(http))
                .build()
                .also {
                    it.volume = 0f // banners are silent
                    it.addListener(playerListener)
                    player = it
                    pv.player = it
                }
        }
        pv.visibility = VISIBLE
        p.repeatMode = if (items.size == 1) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        p.setMediaItem(MediaItem.fromUri(b.src))
        p.prepare()
        p.playWhenReady = true
        // Safety: a video that hangs (bad connection) must not block the other banners
        if (items.size > 1) handler.postDelayed(advanceTask, VIDEO_MAX_MS)
    }

    private fun releasePlayer() {
        playerView?.let { it.player = null; it.visibility = GONE }
        player?.let {
            it.removeListener(playerListener)
            it.release()
        }
        player = null
    }

    /** A banner that cannot be loaded is left out of the list; the next one is shown. */
    private fun dropCurrent() {
        if (items.isEmpty()) return
        val rest = items.toMutableList().also { it.removeAt(index.coerceIn(0, it.size - 1)) }
        items = rest
        if (rest.isEmpty()) {
            handler.removeCallbacks(advanceTask)
            releasePlayer()
            visibility = GONE
            onEmpty?.invoke()
            return
        }
        index = index.coerceAtMost(rest.size - 1)
        if (running) show()
    }

    private fun openLink() {
        val link = items.getOrNull(index)?.link ?: return
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE)
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.ad_no_browser, Toast.LENGTH_LONG).show()
        }
    }
}
