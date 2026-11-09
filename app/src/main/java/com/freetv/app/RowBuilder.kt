package com.freetv.app

/**
 * Builds the Home rows from the channel lists. Pure Kotlin: no UI, no Context, no shared mutable state,
 * so it is safe to call on Dispatchers.Default. (HealthStore.state is lock-protected and safe from any thread.)
 */
object RowBuilder {

    private const val RECENTS_MAX = 20
    private const val FAVS_MAX = 30
    private const val TOP_CATEGORIES = 14
    private const val ROW_ITEMS = 30
    private const val ALL_ITEMS = 60
    private const val UNDEFINED = "Undefined"

    // Phase 41 (start screen "Sports + Pakistan", homeMix = true): size of the Sports row ("See all >" opens the rest as a grid), and
    // where "Continue watching" / "My List" go: true = above the Sports row, false = below the last Pakistani row.
    private const val SPORTS_ITEMS = 100
    private const val PERSONAL_FIRST_IN_MIX = false

    /**
     * Rows in this order: "Continue watching" (id "recents"), "My List" (id "favs"),
     * up to 14 category rows (id "group:<name>", biggest category first), or one "Channels" row (id "all")
     * when no channel has a usable category. Empty rows are never returned.
     * With hideDead = true, channels that HealthStore reports as DEAD are left out of every row (and of the counts).
     *
     * Phase 41: with homeMix = true (the start source "Sports + Pakistan", channels made by HomeMix) the order is instead:
     * the "Sports" row (id "group:Sports", up to 100 channels), then ONE row per Pakistani category (ids "group:Pakistani News", ...;
     * biggest first, "Other Pakistani Channels" last, no limit on how many), then "Continue watching" and "My List".
     * homeMix = false is exactly the behaviour described above.
     */
    fun build(
        all: List<Channel>,
        favorites: List<Channel>,
        recents: List<Channel>,
        hideDead: Boolean,
        homeMix: Boolean = false
    ): List<Row> {
        // 1. Continue watching, 2. My List
        val personal = ArrayList<Row>(2)
        val rec = firstOf(recents, RECENTS_MAX, hideDead)
        if (rec.isNotEmpty()) personal.add(Row("recents", "Continue watching", rec, rec.size))
        val fav = firstOf(favorites, FAVS_MAX, hideDead)
        if (fav.isNotEmpty()) personal.add(Row("favs", "My List", fav, fav.size))

        // 3. Category rows. A channel's group may hold several categories separated by ';'.
        val alive: List<Channel> = if (hideDead) all.filter { !isDead(it) } else all
        val groups = groupByCategory(alive)

        val categories = ArrayList<Row>(TOP_CATEGORIES + 1)
        if (groups.isNotEmpty()) {
            if (homeMix) addHomeMixRows(categories, groups) else addTopRows(categories, groups)
        } else if (alive.isNotEmpty()) {
            // 4. Nobody has a category
            categories.add(Row("all", "Channels", alive.take(ALL_ITEMS), alive.size))
        }

        val rows = ArrayList<Row>(personal.size + categories.size)
        if (homeMix && !PERSONAL_FIRST_IN_MIX) {
            rows.addAll(categories)
            rows.addAll(personal)
        } else {
            rows.addAll(personal)
            rows.addAll(categories)
        }
        return rows
    }

    /** Category name -> its channels, in the order the categories first appear. A channel counts once per category. */
    private fun groupByCategory(alive: List<Channel>): LinkedHashMap<String, ArrayList<Channel>> {
        val groups = LinkedHashMap<String, ArrayList<Channel>>()
        for (c in alive) {
            val g = c.group
            if (g == null || g.isBlank()) continue
            val n = g.length
            var start = 0
            while (start <= n) {
                var end = g.indexOf(';', start)
                if (end < 0) end = n
                val name = g.substring(start, end).trim()
                if (name.isNotEmpty() && !name.equals(UNDEFINED, ignoreCase = true)) {
                    val list = groups.getOrPut(name) { ArrayList() }
                    // "News;News" on one channel must not add it twice
                    if (list.isEmpty() || list[list.size - 1] !== c) list.add(c)
                }
                start = end + 1
            }
        }
        return groups
    }

    /** The normal category rows: the 14 biggest categories. */
    private fun addTopRows(out: MutableList<Row>, groups: LinkedHashMap<String, ArrayList<Channel>>) {
        // sortedByDescending is stable: categories with the same count keep their first-seen order
        val top = groups.entries.sortedByDescending { it.value.size }.take(TOP_CATEGORIES)
        for (e in top) {
            out.add(Row("group:" + e.key, e.key, logoFirst(e.value, ROW_ITEMS), e.value.size))
        }
    }

    /** Phase 41: the "Sports" row first, then every Pakistani category (biggest first, "Other Pakistani Channels" last). */
    private fun addHomeMixRows(out: MutableList<Row>, groups: LinkedHashMap<String, ArrayList<Channel>>) {
        val sports = groups[HomeMix.SPORTS]
        if (sports != null) {
            out.add(Row("group:" + HomeMix.SPORTS, HomeMix.SPORTS, logoFirst(sports, SPORTS_ITEMS), sports.size))
        }
        val pakistani = groups.entries.filter { it.key != HomeMix.SPORTS }
        val ordered = pakistani.sortedWith(Comparator<Map.Entry<String, ArrayList<Channel>>> { a, b ->
            val aOther = a.key == HomeMix.PK_OTHER
            val bOther = b.key == HomeMix.PK_OTHER
            if (aOther != bOther) {
                if (aOther) 1 else -1
            } else {
                b.value.size.compareTo(a.value.size)
            }
        })
        for (e in ordered) {
            out.add(Row("group:" + e.key, e.key, logoFirst(e.value, ROW_ITEMS), e.value.size))
        }
    }

    /** The channel for the hero banner: the first one that has a logo and is not DEAD. */
    fun pickHero(all: List<Channel>): Channel? {
        for (c in all) {
            if (hasLogo(c) && !isDead(c)) return c
        }
        return null
    }

    private fun hasLogo(c: Channel): Boolean {
        val l = c.logo
        return l != null && l.isNotBlank()
    }

    private fun isDead(c: Channel): Boolean = HealthStore.state(c.url) == HealthStore.State.DEAD

    /** The first `max` channels (list order), skipping DEAD ones when hideDead. */
    private fun firstOf(list: List<Channel>, max: Int, hideDead: Boolean): List<Channel> {
        val out = ArrayList<Channel>(minOf(list.size, max))
        for (c in list) {
            if (out.size >= max) break
            if (hideDead && isDead(c)) continue
            out.add(c)
        }
        return out
    }

    /** Up to `max` channels: those with a logo first, then the rest, each part in the original order. */
    private fun logoFirst(list: List<Channel>, max: Int): List<Channel> {
        val out = ArrayList<Channel>(minOf(list.size, max))
        for (c in list) {
            if (out.size >= max) break
            if (hasLogo(c)) out.add(c)
        }
        if (out.size < max) {
            for (c in list) {
                if (out.size >= max) break
                if (!hasLogo(c)) out.add(c)
            }
        }
        return out
    }
}
