package com.freetv.app

/** One horizontal row of the Home screen. `total` = how many channels the category has in all (items may be a shorter preview). */
data class Row(
    val id: String,
    val title: String,
    val items: List<Channel>,
    val total: Int
)

/**
 * What the vertical Home list shows: on top either the shop banners (Banner, from your cPanel) or, when there are none,
 * the hero banner (Hero); then, only while you have switched it on, the live match card (Live, Phase 42); then the rows.
 */
sealed interface HomeItem {
    data class Hero(val channel: Channel) : HomeItem
    data class Banner(val banners: List<AdBanner>) : HomeItem
    data class Live(val card: LiveCard) : HomeItem
    data class RowItem(val row: Row) : HomeItem
}
