package com.freetv.app

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.freetv.app.databinding.FragmentHomeBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ConcurrentModificationException

private const val GROUP_PREFIX = "group:" // id of a category row is "group:<name>" (RowBuilder)
private const val ALL_ROW_ID = "all"      // id of the "Channels" row, shown when the playlist has no categories

// Phase 29: lazy health checks. Only the first AHEAD_PER_ROW channels of the first AHEAD_ROWS rows are checked (never the whole playlist).
private const val AHEAD_ROWS = 5
private const val AHEAD_PER_ROW = 8
// Phase 29: a change of HealthStore.version rebuilds the rows at most once per this many milliseconds
private const val HEALTH_REBUILD_MS = 2_000L

/**
 * The start screen (Phase 20): wordmark + "All channels" button on top, then the hero banner (Phase 22) and rows of channels (HomeAdapter).
 * Rows and hero are built by RowBuilder on Dispatchers.Default every time the shared MainViewModel.channels changes,
 * and again after a favorite changed here or in the player, or after a channel was watched (Phase 25: "Continue watching").
 *
 * Phase 29: after the rows are built, the first 8 channels of the first 5 rows are tested in the background (vm.checkAhead). While the
 * screen is STARTED, a change of HealthStore.version redraws the cards (dimmed dead channels) and rebuilds the rows, at most once per 2 s.
 *
 * Phase 32: the settings of Prefs are applied. Prefs.hideDead is passed to RowBuilder.build (dead channels are left out of the rows),
 * and the background check (vm.checkAhead) only starts while Prefs.autoCheck is on. A change made in the Settings tab is picked up
 * when Home is shown again.
 *
 * Phase 42: under the banner / hero Home can show a "live match" card (HomeItem.Live). It comes from live.json on the cPanel (switched on and
 * off in admin.php); while it is off, missing, or its end time has passed, Home looks exactly as before. A click opens the channel chosen in admin.php.
 *
 * Phase 41: while the source is the start source "Sports + Pakistan" (Sources.HOME), the rows are built with homeMix = true:
 * the Sports row first, then the Pakistani rows, then "Continue watching" / "My List" (see RowBuilder.build). Every other source
 * is built as before.
 */
class HomeFragment : Fragment() {

    private var _b: FragmentHomeBinding? = null
    private val b get() = _b!!

    private val vm: MainViewModel by activityViewModels<MainViewModel>()
    private lateinit var adapter: HomeAdapter

    private var buildJob: Job? = null
    private var rowCount = 0
    private var cameFromPlayer = false

    // The rows shown now (the hero's "Watch" plays this channel first, then the items of the first row)
    private var currentRows: List<Row> = emptyList()

    // TV only: true until the focus was put on the hero's Watch button
    private var pendingHeroFocus = false

    // Phase 24: id of the row whose "See all >" opened a category grid; set while that grid covers Home, null otherwise
    private var seeAllRowId: String? = null

    // Phase 25: Prefs.recentsVersion that the rows on screen were built with (changes when a channel was watched meanwhile)
    private var shownRecentsVersion = -1

    // Phase 29: HealthStore.version that the rows on screen were built with (-1 = no build started yet) and the time of the last build
    private var shownHealthVersion = -1
    private var lastRebuildAt = 0L

    // Phase 32: the settings that the rows on screen were built with (a change in the Settings tab makes Home rebuild when it is shown again)
    private var shownHideDead = false
    private var shownAutoCheck = true

    // Banners from the cPanel: time of the last request (0 = none yet / the last one failed)
    private var adsFetchedAt = 0L

    // The banners to show in the hero box (empty = none: the hero channel is shown there as before) and the hero channel of the last build
    private var ads: List<AdBanner> = emptyList()
    private var heroChannel: Channel? = null

    // Phase 42: the live match card of live.json (null = none) and the time of the last request (0 = none yet / the last one failed)
    private var liveCard: LiveCard? = null
    private var liveFetchedAt = 0L

    private class Built(val rows: List<Row>, val hero: Channel?)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentHomeBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = HomeAdapter(
            isFavorite = { Prefs.isFavorite(it.url) },
            onPlay = { row, index -> play(row, index) },
            onLong = { ch -> toggleFavorite(ch) },
            onSeeAll = { row -> seeAll(row) },
            onHeroWatch = { ch -> playHero(ch) },
            onHeroList = { ch -> toggleFavorite(ch) },
            onBannersFailed = { dropBanners() },
            onLiveClick = { card -> playLive(card) },
            onLiveEnded = { endLive() }
        )
        b.homeList.layoutManager = LinearLayoutManager(requireContext())
        b.homeList.setHasFixedSize(true)
        b.homeList.adapter = adapter

        b.btnBrowse.setOnClickListener { (activity as? MainActivity)?.openBrowse() }
        // Source / filter button (same picker as in Browse): pick a country, category, language or custom link; Home rebuilds itself
        b.btnSource.setOnClickListener { openSourcePicker() }

        pendingHeroFocus = isTv()

        val owner = viewLifecycleOwner
        watchHealth()
        watchLive()
        vm.source.observe(owner) { src -> b.btnSource.text = getString(R.string.source_btn, src.name) }
        vm.channels.observe(owner) { rebuild() }
        vm.loading.observe(owner) { isLoading ->
            b.progress.isVisible = isLoading
            updateEmpty()
        }
        // This is the first screen now, so it shows the load / refresh messages the old start screen showed
        vm.message.observe(owner) { msg ->
            if (msg != null) {
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                vm.message.value = null
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The app is opened (again): Home starts at the top, also when Android restored the old scroll position.
        // Not when coming back from the player (cameFromPlayer is still true here, onResume clears it): there the list stays where it was.
        if (!cameFromPlayer) _b?.homeList?.scrollToPosition(0)
        if (!isHidden) setBannersActive(true)
        refreshAds()
    }

    override fun onStop() {
        setBannersActive(false)
        super.onStop()
    }

    // The banner (in the hero box) only runs while Home is shown
    private fun setBannersActive(active: Boolean) {
        if (_b != null) adapter.setBannersActive(active)
    }

    // The list of Home: the banners when there are some, else the hero channel (when there is one), then the rows
    private fun submitItems() {
        val items = ArrayList<HomeItem>(currentRows.size + 1)
        val hero = heroChannel
        if (ads.isNotEmpty()) items.add(HomeItem.Banner(ads))
        else if (hero != null) items.add(HomeItem.Hero(hero))
        // Phase 42: the live match card sits right under the banner / hero, and only while its end time has not passed
        val live = liveCard
        if (live != null && !live.isOver(System.currentTimeMillis())) items.add(HomeItem.Live(live))
        for (r in currentRows) items.add(HomeItem.RowItem(r))
        // The first item changes (first build, or the hero is replaced by the banners and back) while the list is at the top:
        // keep it at the top, so the new first item is the one on screen. Other rebuilds never move the list.
        val list = _b?.homeList
        val topChanged = adapter.currentList.firstOrNull()?.javaClass != items.firstOrNull()?.javaClass
        val keepTop = topChanged && list != null && !list.canScrollVertically(-1)
        adapter.submitList(items) {
            if (keepTop) _b?.homeList?.scrollToPosition(0)
            focusHeroIfNeeded()
        }
    }

    // None of the banners could be loaded (wrong file name, offline ...): the hero channel takes the box again
    private fun dropBanners() {
        if (_b == null || ads.isEmpty()) return
        ads = emptyList()
        submitItems()
    }

    // Asks the server for banners.json (at most every AdConfig.REFRESH_MS). On failure the banners already shown stay.
    private fun refreshAds() {
        if (!AdConfig.isConfigured || isHidden) return
        val now = SystemClock.elapsedRealtime()
        if (adsFetchedAt != 0L && now - adsFetchedAt < AdConfig.REFRESH_MS) return
        adsFetchedAt = now
        viewLifecycleOwner.lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { AdRepository.fetch() }
            if (list == null) {
                adsFetchedAt = 0L
            } else if (list != ads && _b != null) {
                ads = list
                // TV: the box changes under the focus, so give the focus to the new first item again (unless it is already in the list)
                if (isTv()) pendingHeroFocus = true
                submitItems()
            }
        }
    }

    // Phase 42: while Home is on screen, ask for live.json once a minute, so switching the card on (or off) in admin.php shows up soon.
    // repeatOnLifecycle(STARTED) stops the loop when the app goes to the background and starts it again (with an instant check) on return.
    private fun watchLive() {
        val owner = viewLifecycleOwner
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    refreshLive()
                    delay(LiveConfig.REFRESH_MS)
                }
            }
        }
    }

    // Asks the server for live.json (at most every LiveConfig.REFRESH_MS). On failure (offline ...) the card already shown stays.
    private fun refreshLive() {
        if (!AdConfig.isConfigured || isHidden || _b == null) return
        val now = SystemClock.elapsedRealtime()
        if (liveFetchedAt != 0L && now - liveFetchedAt < LiveConfig.REFRESH_MS) return
        liveFetchedAt = now
        viewLifecycleOwner.lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) { LiveRepository.fetch() }
            if (res == null) {
                liveFetchedAt = 0L
            } else if (res.card != liveCard && _b != null) {
                liveCard = res.card
                submitItems()
            }
        }
    }

    // The end time of the card passed while it was on screen: submitItems() leaves it out (the next request still brings it, and it is filtered again)
    private fun endLive() {
        if (_b != null) submitItems()
    }

    // The live card was clicked: play the channel chosen in admin.php. The playlist's own copy of the channel is used when it is loaded
    // (same referrer / user-agent as in the lists); otherwise the address from live.json plays as it is. UP / DOWN then zaps through the first row.
    private fun playLive(card: LiveCard) {
        val ch = vm.channels.value?.firstOrNull { it.url == card.channel.url } ?: card.channel
        val first = currentRows.firstOrNull()?.items.orEmpty()
        val list = ArrayList<Channel>(first.size + 1)
        list.add(ch)
        for (c in first) {
            if (c.url != ch.url) list.add(c)
        }
        ChannelStore.playlist = list
        ChannelStore.index = 0
        cameFromPlayer = true
        startActivity(Intent(requireContext(), PlayerActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        if (cameFromPlayer) {
            cameFromPlayer = false
            // Favorites may have changed inside the player: redraw the hearts and rebuild the "My List" row
            adapter.refreshCards()
            rebuild()
        }
    }

    // MainActivity hides Home (show / hide) while a category grid is open, so this runs when BACK closes the grid.
    // Favorites may have been changed in the grid or in the player started from it, so redraw the hearts and the "My List" row,
    // and (TV) put the focus back on the "See all >" that was pressed.
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            setBannersActive(false)   // no banner video running behind another tab
            return
        }
        setBannersActive(true)
        refreshAds()
        refreshLive()
        val id = seeAllRowId
        if (id == null) {
            // Phase 25: Home is shown again by a tab switch. A channel may have been watched from Browse meanwhile.
            if (_b != null && (Prefs.recentsVersion != shownRecentsVersion || settingsChanged())) rebuild()
            return
        }
        seeAllRowId = null
        if (_b == null) return
        adapter.refreshCards()
        rebuild()
        focusSeeAll(id)
    }

    override fun onDestroyView() {
        buildJob?.cancel()
        buildJob = null
        _b = null
        super.onDestroyView()
    }

    /** Builds the rows off the main thread, then hands them to the adapter on the main thread. A newer call replaces an older one. */
    private fun rebuild() {
        val all = vm.channels.value.orEmpty()
        shownRecentsVersion = Prefs.recentsVersion   // read BEFORE the build, so a newer recent only causes one extra rebuild
        shownHealthVersion = HealthStore.version.value // same for a health change that happens during the build
        val hideDead = Prefs.hideDead                  // Phase 32: plain read on the main thread, handed to the background build
        val homeMix = vm.source.value?.url == Sources.HOME.url // Phase 41: same for the start source "Sports + Pakistan"
        shownHideDead = hideDead
        shownAutoCheck = Prefs.autoCheck
        lastRebuildAt = SystemClock.elapsedRealtime()
        buildJob?.cancel()
        buildJob = viewLifecycleOwner.lifecycleScope.launch {
            val built = withContext(Dispatchers.Default) {
                Built(RowBuilder.build(all, loadFavorites(), Prefs.recents(), hideDead, homeMix), RowBuilder.pickHero(all))
            }
            val rows = built.rows
            val hero = built.hero
            currentRows = rows
            heroChannel = hero
            rowCount = rows.size

            // The banners (or, without banners, the hero) are the first item, then the rows
            submitItems()
            updateEmpty()
            checkAhead(rows)
        }
    }

    // Phase 29: starts the background check for the channels the user sees first: the first AHEAD_PER_ROW channels of the first AHEAD_ROWS rows.
    // At most 40 channels, so this is cheap on the main thread. An empty list (no rows) cancels the check that is still running.
    private fun checkAhead(rows: List<Row>) {
        if (!Prefs.autoCheck) return   // Phase 32: "Check channels in background" is off
        val list = ArrayList<Channel>(AHEAD_ROWS * AHEAD_PER_ROW)
        val rowLimit = minOf(rows.size, AHEAD_ROWS)
        for (i in 0 until rowLimit) {
            val items = rows[i].items
            val itemLimit = minOf(items.size, AHEAD_PER_ROW)
            for (j in 0 until itemLimit) list.add(items[j])
        }
        vm.checkAhead(list)
    }

    // Phase 32: true when "Hide offline channels" or "Check channels in background" was changed since the rows on screen were built
    private fun settingsChanged(): Boolean = Prefs.hideDead != shownHideDead || Prefs.autoCheck != shownAutoCheck

    // Phase 29: while the screen is STARTED, follow HealthStore.version. A change redraws the cards (dimmed dead channels) and rebuilds
    // the rows (the hero skips dead channels), but never more than once per HEALTH_REBUILD_MS: a newer version restarts the wait,
    // so the last change is always shown. A version that the rows on screen already have is ignored; so is the time before the first
    // build (shownHealthVersion == -1), because the channels observer builds the rows then. Coming back to STARTED after a pause
    // catches up on a change that happened meanwhile.
    private fun watchHealth() {
        val owner = viewLifecycleOwner
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                HealthStore.version.collectLatest { v ->
                    if (shownHealthVersion != -1 && v != shownHealthVersion) {
                        val wait = HEALTH_REBUILD_MS - (SystemClock.elapsedRealtime() - lastRebuildAt)
                        if (wait > 0L) delay(wait)
                        // another rebuild may have run during the wait and already shown this version
                        if (_b != null && HealthStore.version.value != shownHealthVersion) {
                            adapter.refreshCards() // the cards read HealthStore.state when they are bound
                            rebuild()
                        }
                    }
                }
            }
        }
    }

    // Prefs parses the favorites JSON on first use, so this runs on the background thread.
    // Prefs is not thread-safe: if a toggle on the main thread hits exactly while the list is copied, skip the "My List"
    // row for this pass (every toggle starts a new rebuild right after, which then sees the final state).
    private fun loadFavorites(): List<Channel> =
        try {
            Prefs.favorites()
        } catch (e: ConcurrentModificationException) {
            emptyList()
        }

    // Phase 23: the skeleton is shown while no rows have been submitted yet and something is loading or about to be built
    // (loading, or channels already there but the rows are still being built). It is hidden as soon as rows are submitted.
    private fun updateSkeleton() {
        b.skeleton.root.isVisible = rowCount == 0 && (vm.loading.value == true || !vm.channels.value.isNullOrEmpty())
    }

    private fun updateEmpty() {
        updateSkeleton()
        // never together with the skeleton (the "No channels" text must not flash while the rows are being built)
        b.empty.isVisible = rowCount == 0 && vm.loading.value != true && !b.skeleton.root.isVisible
    }

    private fun play(row: Row, index: Int) {
        if (index !in row.items.indices) return
        ChannelStore.playlist = row.items
        ChannelStore.index = index
        cameFromPlayer = true
        startActivity(Intent(requireContext(), PlayerActivity::class.java))
    }

    // "Watch" on the hero: the hero channel first, then the items of the first row (without the hero channel again)
    private fun playHero(ch: Channel) {
        val first = currentRows.firstOrNull()?.items.orEmpty()
        val list = ArrayList<Channel>(first.size + 1)
        list.add(ch)
        for (c in first) {
            if (c.url != ch.url) list.add(c)
        }
        ChannelStore.playlist = list
        ChannelStore.index = 0
        cameFromPlayer = true
        startActivity(Intent(requireContext(), PlayerActivity::class.java))
    }

    // "See all >" on a row. Category row ("group:<name>"): open that category as a grid above Home (MainActivity.openBrowse(group)).
    // "Channels" row (the playlist has no categories): open the Browse tab, which shows every channel.
    private fun seeAll(row: Row) {
        val main = activity as? MainActivity ?: return
        if (row.id.startsWith(GROUP_PREFIX)) {
            val name = row.id.substring(GROUP_PREFIX.length)
            if (name.isEmpty()) return
            seeAllRowId = row.id
            main.openBrowse(name)
        } else if (row.id == ALL_ROW_ID) {
            main.openBrowse()
        }
    }

    // Focus the "See all >" of that row again. Posted: when onHiddenChanged runs, Home is not visible yet.
    // Does nothing in touch mode (the button is not focusable there) or when the row is no longer on screen.
    private fun focusSeeAll(rowId: String) {
        val list = _b?.homeList ?: return
        list.post {
            val cur = _b ?: return@post
            val pos = adapter.currentList.indexOfFirst { it is HomeItem.RowItem && it.row.id == rowId }
            if (pos >= 0) {
                val vh = cur.homeList.findViewHolderForAdapterPosition(pos) as? HomeAdapter.RowVH
                vh?.b?.rowMore?.requestFocus()
            }
        }
    }

    /** Opens the full-screen source picker (countries / categories / languages / custom link). Does nothing if it is already open. */
    private fun openSourcePicker() {
        val fm = childFragmentManager
        if (fm.isStateSaved || fm.findFragmentByTag(SourcePicker.TAG) != null) return
        SourcePicker().show(fm, SourcePicker.TAG)
    }

    private fun isTv(): Boolean {
        val pm = requireContext().packageManager
        return pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    // TV: when Home first shows its hero, put the focus on "Watch". Runs after the list is committed; the hero holder only exists
    // after the next layout of the list. Does nothing if the user already moved the focus into the list, or if the view is gone.
    private fun focusHeroIfNeeded() {
        val bnd = _b ?: return
        if (!pendingHeroFocus) return
        bnd.homeList.doOnNextLayout {
            val cur = _b
            if (cur != null && pendingHeroFocus && !cur.homeList.hasFocus()) {
                val first = cur.homeList.findViewHolderForAdapterPosition(0)
                val focused = when (first) {
                    is HomeAdapter.HeroVH -> first.b.btnWatch.requestFocus()
                    is HomeAdapter.BannerVH -> first.view.requestFocus()
                    else -> false
                }
                if (focused) pendingHeroFocus = false
            }
        }
    }

    private fun toggleFavorite(ch: Channel) {
        val added = Prefs.toggleFavorite(ch)
        Toast.makeText(requireContext(), if (added) R.string.fav_added else R.string.fav_removed, Toast.LENGTH_SHORT).show()
        adapter.refreshCards() // hearts of this channel in every row
        rebuild()              // the "My List" row
    }
}
