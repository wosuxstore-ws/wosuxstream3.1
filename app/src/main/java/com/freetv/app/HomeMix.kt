package com.freetv.app

/**
 * Phase 41: mixes the two playlists of the start source (Sources.HOME) into ONE channel list. Pure Kotlin: no UI, no Context,
 * no shared state, so it is safe to call on any thread.
 *
 * The Home screen (RowBuilder, homeMix = true) and the "See all" grid (BrowseFragment) already work from each channel's `group`
 * text (categories separated by ';'), so the mix only rewrites that text:
 *  - a sports channel gets the group "Sports" (the categories it has in the playlist are replaced, so there is exactly ONE Sports row),
 *  - a Pakistani channel gets one "Pakistani <category>" part for every category it has ("Pakistani News", "Pakistani Entertainment", ...),
 *    or "Other Pakistani Channels" when it has no usable category,
 *  - a channel that is in BOTH playlists is listed once, with both: "Sports;Pakistani Sports".
 *
 * Order of the result: all sports channels (playlist order), then the Pakistani channels that are not sports channels (playlist order).
 */
object HomeMix {

    const val SPORTS = "Sports"
    const val PK_OTHER = "Other Pakistani Channels"
    private const val PK_PREFIX = "Pakistani "
    private const val UNDEFINED = "Undefined"

    /** [sports] = the sports playlist, [pakistan] = the Pakistan playlist (either may be empty, for example when its download failed). */
    fun merge(sports: List<Channel>, pakistan: List<Channel>): List<Channel> {
        // The Pakistani group text of every channel of the Pakistan playlist, to find the channels that are in both playlists
        val pkGroup = HashMap<String, String>(pakistan.size * 2 + 1)
        for (c in pakistan) pkGroup[c.url] = pakistaniGroup(c.group)

        val out = ArrayList<Channel>(sports.size + pakistan.size)
        val seen = HashSet<String>((sports.size + pakistan.size) * 2 + 1)

        for (c in sports) {
            if (!seen.add(c.url)) continue
            val pk = pkGroup[c.url]
            out.add(c.copy(group = if (pk == null) SPORTS else "$SPORTS;$pk"))
        }
        for (c in pakistan) {
            // false = already added: it is a sports channel too (its group has both parts), or the playlist listed it twice
            if (!seen.add(c.url)) continue
            out.add(c.copy(group = pakistaniGroup(c.group)))
        }
        return out
    }

    /** "News;Sports" -> "Pakistani News;Pakistani Sports". Blank parts and "Undefined" (any letter case) are skipped; nothing left -> PK_OTHER. */
    private fun pakistaniGroup(group: String?): String {
        if (group.isNullOrBlank()) return PK_OTHER
        val names = LinkedHashSet<String>()
        for (part in group.split(';')) {
            val p = part.trim()
            if (p.isEmpty() || p.equals(UNDEFINED, ignoreCase = true)) continue
            names.add(PK_PREFIX + p)
        }
        return if (names.isEmpty()) PK_OTHER else names.joinToString(";")
    }
}
