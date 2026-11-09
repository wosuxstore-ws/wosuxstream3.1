package com.freetv.app

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Parcelable
import android.text.format.DateFormat
import android.widget.ImageView
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import coil.load
import com.freetv.app.databinding.ItemHeroBinding
import com.freetv.app.databinding.ItemLiveBinding
import com.freetv.app.databinding.ItemRowBinding
import java.util.Date

private val HOME_DIFF = object : DiffUtil.ItemCallback<HomeItem>() {
    override fun areItemsTheSame(oldItem: HomeItem, newItem: HomeItem): Boolean = when (oldItem) {
        is HomeItem.RowItem -> newItem is HomeItem.RowItem && oldItem.row.id == newItem.row.id
        is HomeItem.Hero -> newItem is HomeItem.Hero
        is HomeItem.Banner -> newItem is HomeItem.Banner
        is HomeItem.Live -> newItem is HomeItem.Live
    }

    override fun areContentsTheSame(oldItem: HomeItem, newItem: HomeItem): Boolean = oldItem == newItem
}

private const val VT_ROW = 0
private const val VT_HERO = 1          // the hero banner (Phase 22)
private const val VT_BANNER = 2        // the shop banners from your cPanel (take the place of the hero)
private const val VT_LIVE = 3          // the live match card (Phase 42), under the banner / hero

private const val PREFETCH_CARDS = 5   // cards the inner list prefetches when a row scrolls into view
private const val POOL_MAX_CARDS = 24  // channel cards kept in the ONE pool shared by all rows (default would be 5)
private const val PAYLOAD_CARDS = "cards"  // payload of refreshCards(): re-bind the cards of a row, do not rebuild the row

// Gradients (start, end) for the hero's initials plate, picked with name.hashCode(). Dark enough for white text.
private val HERO_GRADIENTS: Array<IntArray> = arrayOf(
    intArrayOf(0xFF0A4FD6.toInt(), 0xFF02AEF9.toInt()),
    intArrayOf(0xFF6D28D9.toInt(), 0xFF0088FF.toInt()),
    intArrayOf(0xFF0E7490.toInt(), 0xFF14B8A6.toInt()),
    intArrayOf(0xFFBE185D.toInt(), 0xFFF97316.toInt()),
    intArrayOf(0xFF047857.toInt(), 0xFF10B981.toInt()),
    intArrayOf(0xFFB91C1C.toInt(), 0xFFF43F5E.toInt())
)

/**
 * Vertical list of a hero banner (HomeItem.Hero, Phase 22) and horizontal rows (HomeItem.RowItem, Phase 19).
 *  - isFavorite: heart on a card, and the state of the hero's "My List" button
 *  - onPlay(row, indexInRow): a card was clicked (index = position in row.items)
 *  - onLong(channel): a card was long-pressed (OK held on the remote)
 *  - onSeeAll(row): "See all >" was clicked (shown only when row.total > row.items.size)
 *  - onHeroWatch(channel): the hero's "Watch" button was clicked
 *  - onHeroList(channel): the hero's "My List" button was clicked (add / remove favorite)
 */
class HomeAdapter(
    private val isFavorite: (Channel) -> Boolean,
    private val onPlay: (Row, Int) -> Unit,
    private val onLong: (Channel) -> Unit,
    private val onSeeAll: (Row) -> Unit,
    private val onHeroWatch: (Channel) -> Unit = { },
    private val onHeroList: (Channel) -> Unit = { },
    private val onBannersFailed: () -> Unit = { },
    private val onLiveClick: (LiveCard) -> Unit = { },   // Phase 42: the live match card was clicked
    private val onLiveEnded: () -> Unit = { }            // Phase 42: the card's end time passed while it was on screen
) : ListAdapter<HomeItem, RecyclerView.ViewHolder>(HOME_DIFF) {

    class RowVH(val b: ItemRowBinding, val lm: LinearLayoutManager) : RecyclerView.ViewHolder(b.root) {
        lateinit var inner: ChannelAdapter
        // the row this holder shows now (null while the holder sits in the pool)
        var row: Row? = null
        // value of HomeAdapter.cardsEpoch the cards of this holder were last bound under
        var epoch = 0
    }

    // The hero banner (Phase 22)
    class HeroVH(val b: ItemHeroBinding) : RecyclerView.ViewHolder(b.root) {
        // the channel the banner shows now
        var channel: Channel? = null
        // url of that channel (guards a late logo-error callback after the holder was re-bound)
        var boundUrl: String? = null
    }

    // The shop banners (take the place of the hero when banners.json has at least one banner)
    class BannerVH(val view: AdBannerView) : RecyclerView.ViewHolder(view)

    // The live match card (Phase 42)
    class LiveVH(val b: ItemLiveBinding) : RecyclerView.ViewHolder(b.root) {
        // the card shown now
        var card: LiveCard? = null
        // picture + logo addresses that are on screen now (a re-bind with the same values does not reload the picture)
        var boundKey: String? = null
        // moves the progress bar every LiveConfig.TICK_MS while the card is on screen
        lateinit var tick: Runnable
    }

    // The banner holder that is on screen now (null when there is none), and whether Home is showing (the banner only runs then)
    private var bannerHolder: BannerVH? = null
    private var bannersActive = false

    /** HomeFragment: true while Home is shown (banner pictures rotate, a video plays), false when Home is stopped or hidden. */
    fun setBannersActive(active: Boolean) {
        bannersActive = active
        val v = bannerHolder?.view ?: return
        if (active) v.start() else v.stop()
    }

    // ONE pool for the cards of every row
    private val pool = RecyclerView.RecycledViewPool().apply { setMaxRecycledViews(0, POOL_MAX_CARDS) }

    // rowId -> horizontal scroll state of that row (saved when its holder is recycled, restored when the row is bound again)
    private val scrollStates = HashMap<String, Parcelable>()

    // Raised by refreshCards(). A row whose cards were bound under an older value re-binds its cards the next time it is shown.
    private var cardsEpoch = 0

    /**
     * Redraw the cards of every row (hearts, dead badge) after something outside the rows changed, e.g. a favorite.
     * Needed because DiffUtil sees equal rows and would leave the cards untouched.
     * Visible and cached rows are re-bound now; rows waiting in the pool refresh when they are bound again.
     */
    fun refreshCards() {
        cardsEpoch++
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, PAYLOAD_CARDS)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        // A changed row must not cross-fade into a second holder (it would also double the scroll-state work)
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
    }

    override fun getItemViewType(position: Int): Int {
        val item: HomeItem = getItem(position)
        return when (item) {
            is HomeItem.RowItem -> VT_ROW
            is HomeItem.Hero -> VT_HERO
            is HomeItem.Banner -> VT_BANNER
            is HomeItem.Live -> VT_LIVE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == VT_HERO) return createHero(parent)
        if (viewType == VT_BANNER) return createBanner(parent)
        if (viewType == VT_LIVE) return createLive(parent)

        val b = ItemRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        val lm = LinearLayoutManager(parent.context, LinearLayoutManager.HORIZONTAL, false)
        lm.initialPrefetchItemCount = PREFETCH_CARDS
        val vh = RowVH(b, lm)
        vh.epoch = cardsEpoch

        val cardW = parent.resources.getDimensionPixelSize(R.dimen.card_w_row)
        vh.inner = ChannelAdapter(
            isFavorite = isFavorite,
            onClick = { _, index ->
                val r = vh.row
                if (r != null) onPlay(r, index)
            },
            onLongClick = { ch, _ -> onLong(ch) },
            cardWidthPx = cardW
        )

        val list = b.rowList
        list.layoutManager = lm
        list.setHasFixedSize(true)
        list.itemAnimator = null
        list.setRecycledViewPool(pool)
        list.adapter = vh.inner

        b.rowMore.setOnClickListener {
            val r = vh.row
            if (r != null) onSeeAll(r)
        }
        return vh
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        if (holder is RowVH && item is HomeItem.RowItem) bindRow(holder, item.row)
        else if (holder is HeroVH && item is HomeItem.Hero) bindHero(holder, item.channel)
        else if (holder is BannerVH && item is HomeItem.Banner) bindBanner(holder, item.banners)
        else if (holder is LiveVH && item is HomeItem.Live) bindLive(holder, item.card)
    }

    // refreshCards(): only the cards are re-bound, the row itself (title, list, scroll position) stays as it is
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && holder is RowVH && holder.row != null) {
            rebindCards(holder)
        } else if (payloads.isNotEmpty() && holder is BannerVH) {
            // refreshCards(): the banner has no cards, and must not restart
        } else if (payloads.isNotEmpty() && holder is LiveVH) {
            // refreshCards(): the live card has no cards either
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    // The banner box has the size of the hero box (same height, same margins), so it takes its place on screen
    private fun createBanner(parent: ViewGroup): BannerVH {
        val view = AdBannerView(parent.context)
        val res = parent.resources
        val pad = res.getDimensionPixelSize(R.dimen.screen_pad)
        val lp = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, res.getDimensionPixelSize(R.dimen.hero_h))
        lp.setMargins(pad, pad, pad, pad)
        view.layoutParams = lp
        // Every banner failed to load: HomeFragment shows the hero again (posted, this runs inside an image / player callback)
        view.onEmpty = { view.post { onBannersFailed() } }
        return BannerVH(view)
    }

    private fun bindBanner(h: BannerVH, list: List<AdBanner>) {
        bannerHolder = h
        h.view.setBanners(list)
        if (bannersActive) h.view.start()
    }

    // A banner that scrolled back into view (or was bound) runs again, when Home is showing. start() does nothing if it already runs.
    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        if (holder is BannerVH && bannersActive) holder.view.start()
        if (holder is LiveVH) startLiveTick(holder)
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        if (holder is LiveVH) holder.itemView.removeCallbacks(holder.tick)
        super.onViewDetachedFromWindow(holder)
    }

    // ---- Live match card (Phase 42) ----

    private fun createLive(parent: ViewGroup): LiveVH {
        val vh = LiveVH(ItemLiveBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        vh.tick = Runnable { tickLive(vh) }
        // card = vh.card at click time, so a re-used holder always opens the channel of the card it shows now
        vh.b.root.setOnClickListener {
            val c = vh.card
            if (c != null) onLiveClick(c)
        }
        return vh
    }

    // Cheap work only: texts, one visibility, one progress value. The picture is loaded only when its address changed.
    private fun bindLive(h: LiveVH, c: LiveCard) {
        h.card = c
        val b = h.b
        b.liveTitle.text = c.title
        val sub = c.subtitle ?: timeRangeText(h.itemView.context, c)
        b.liveTime.text = sub.orEmpty()
        b.liveTime.isVisible = !sub.isNullOrEmpty()
        b.liveProgress.isVisible = c.hasTimes
        b.liveProgress.progress = c.progressPermille(System.currentTimeMillis())
        h.itemView.contentDescription = c.title
        loadLiveImage(h, c)
        startLiveTick(h)
    }

    // "12:00 - 12:30" in the phone's own time zone and 12 / 24 hour setting; null when the card has no times
    private fun timeRangeText(ctx: Context, c: LiveCard): String? {
        if (!c.hasTimes) return null
        val f = DateFormat.getTimeFormat(ctx)
        return f.format(Date(c.startAt * 1000L)) + " - " + f.format(Date(c.endAt * 1000L))
    }

    // The picture from live.json (cropped to fill the box). No picture, or one that cannot load: the channel's logo on a light plate.
    private fun loadLiveImage(h: LiveVH, c: LiveCard) {
        val img = c.image
        val logo = c.channel.logo
        val key = (img ?: "") + "|" + (logo ?: "")
        if (h.boundKey == key) return
        h.boundKey = key
        if (img.isNullOrBlank()) {
            showLiveLogo(h, logo)
            return
        }
        val view = h.b.liveImage
        view.background = null
        view.setPadding(0, 0, 0, 0)
        view.scaleType = ImageView.ScaleType.CENTER_CROP
        view.load(img) {
            listener(onError = { _, _ ->
                if (h.boundKey == key) showLiveLogo(h, logo)
            })
        }
    }

    private fun showLiveLogo(h: LiveVH, logo: String?) {
        val view = h.b.liveImage
        val pad = (10 * h.itemView.resources.displayMetrics.density).toInt()
        view.setBackgroundResource(R.drawable.bg_logo)
        view.setPadding(pad, pad, pad, pad)
        view.scaleType = ImageView.ScaleType.FIT_CENTER
        if (logo.isNullOrBlank()) view.setImageResource(R.drawable.ic_tv) else view.load(logo) { error(R.drawable.ic_tv) }
    }

    private fun startLiveTick(h: LiveVH) {
        h.itemView.removeCallbacks(h.tick)
        val c = h.card ?: return
        if (!c.hasTimes) return
        h.b.liveProgress.progress = c.progressPermille(System.currentTimeMillis())
        h.itemView.postDelayed(h.tick, LiveConfig.TICK_MS)
    }

    // Every LiveConfig.TICK_MS: move the bar; when the end time has passed, tell HomeFragment (it removes the card)
    private fun tickLive(h: LiveVH) {
        val c = h.card ?: return
        if (c.isOver(System.currentTimeMillis())) {
            onLiveEnded()
            return
        }
        h.b.liveProgress.progress = c.progressPermille(System.currentTimeMillis())
        h.itemView.postDelayed(h.tick, LiveConfig.TICK_MS)
    }

    private fun createHero(parent: ViewGroup): HeroVH {
        val vh = HeroVH(ItemHeroBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        vh.b.btnWatch.setOnClickListener {
            val c = vh.channel
            if (c != null) onHeroWatch(c)
        }
        vh.b.btnHeroList.setOnClickListener {
            val c = vh.channel
            if (c != null) onHeroList(c)
        }
        return vh
    }

    // Cheap work only. Also called for the refreshCards() payload, which is how the "My List" button follows a favorite change.
    private fun bindHero(h: HeroVH, c: Channel) {
        val sameChannel = h.boundUrl == c.url
        h.channel = c
        h.boundUrl = c.url
        val b = h.b
        b.heroName.text = c.name
        val cat = categoryText(c.group)
        b.heroCategory.text = cat
        b.heroCategory.isVisible = cat.isNotEmpty()
        b.btnHeroList.setText(if (isFavorite(c)) R.string.hero_in_list else R.string.hero_list)

        // Same channel as before (e.g. only the favorite changed): keep the logo or initials that are already on screen
        if (sameChannel) return
        val logo = c.logo
        if (logo.isNullOrBlank()) {
            b.heroLogo.setImageDrawable(null)
            showHeroMono(h, c.name)
        } else {
            b.heroMono.isVisible = false
            b.heroLogo.isVisible = true
            val url = c.url
            val name = c.name
            b.heroLogo.load(logo) {
                placeholder(R.drawable.ic_tv)
                listener(onError = { _, _ ->
                    if (h.boundUrl == url) showHeroMono(h, name)
                })
            }
        }
    }

    // Hide the logo, show the first 1-2 letters of the name on a gradient chosen by the name's hash
    private fun showHeroMono(h: HeroVH, name: String) {
        val colors = HERO_GRADIENTS[(name.hashCode() and Int.MAX_VALUE) % HERO_GRADIENTS.size]
        val bg = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors)
        bg.cornerRadius = 8f * h.itemView.resources.displayMetrics.density // same radius as the bg_logo plate
        h.b.heroLogo.isVisible = false
        h.b.heroMono.text = heroMonogram(name)
        h.b.heroMono.background = bg
        h.b.heroMono.isVisible = true
    }

    private fun heroMonogram(name: String): String {
        val sb = StringBuilder(2)
        for (ch in name) {
            if (ch.isLetterOrDigit()) {
                sb.append(ch)
                if (sb.length == 2) break
            }
        }
        return if (sb.isEmpty()) "TV" else sb.toString().uppercase()
    }

    // Up to 3 categories of the channel, "News  ·  Sports"; empty when it has none ("Undefined" is ignored)
    private fun categoryText(group: String?): String {
        if (group.isNullOrBlank()) return ""
        val sb = StringBuilder()
        var count = 0
        for (part in group.split(';')) {
            val p = part.trim()
            if (p.isEmpty() || p.equals("Undefined", ignoreCase = true)) continue
            if (count > 0) sb.append("  ·  ")
            sb.append(p)
            count++
            if (count == 3) break
        }
        return sb.toString()
    }

    private fun rebindCards(h: RowVH) {
        if (h.epoch == cardsEpoch) return
        h.epoch = cardsEpoch
        h.inner.notifyItemRangeChanged(0, h.inner.itemCount)
    }

    private fun bindRow(h: RowVH, row: Row) {
        val prev = h.row
        val rowChanged = prev?.id != row.id
        if (rowChanged) {
            // This holder is being re-used for another row: keep the scroll position of the one it showed
            if (prev != null) saveState(h, prev.id)
            h.b.rowList.stopScroll()
        }
        h.row = row
        h.b.rowTitle.text = row.title
        h.b.rowMore.isVisible = row.total > row.items.size

        if (rowChanged || h.epoch != cardsEpoch) {
            // Both steps run after the cards of THIS row are in the inner adapter (the diff may finish later)
            h.inner.submitList(row.items) {
                if (h.row?.id == row.id) {   // the holder was not re-bound or recycled meanwhile
                    rebindCards(h)
                    if (rowChanged) restoreState(h, row.id)
                }
            }
        } else {
            h.inner.submitList(row.items)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is BannerVH) {
            holder.view.stop() // frees the video player of a banner that was scrolled far away or replaced
            if (bannerHolder === holder) bannerHolder = null
        }
        if (holder is LiveVH) holder.itemView.removeCallbacks(holder.tick)
        if (holder is RowVH) {
            val r = holder.row
            if (r != null) saveState(holder, r.id)
            holder.b.rowList.stopScroll()
            holder.row = null
        }
        super.onViewRecycled(holder)
    }

    private fun saveState(h: RowVH, rowId: String) {
        // A list that was never laid out has no position worth keeping
        if (h.lm.childCount == 0) return
        val st = h.lm.onSaveInstanceState()
        if (st != null) scrollStates[rowId] = st
    }

    private fun restoreState(h: RowVH, rowId: String) {
        if (h.row?.id != rowId) return   // the holder was re-bound or recycled again before the list was ready
        val st = scrollStates.remove(rowId)
        if (st != null) h.lm.onRestoreInstanceState(st) else h.lm.scrollToPositionWithOffset(0, 0)
    }
}
