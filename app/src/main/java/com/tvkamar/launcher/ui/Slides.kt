package com.tvkamar.launcher.ui

import android.text.format.DateUtils
import androidx.compose.ui.graphics.ImageBitmap
import com.tvkamar.launcher.TvApp
import com.tvkamar.launcher.WatchedItem

/** What the backdrop shows: the focused watched title or app. */
data class Slide(
    val key: String,
    val app: TvApp,
    val title: String,
    /** Title artwork; null shows the app's own artwork. */
    val art: ImageBitmap?,
    val backdrop: ImageBitmap,
)

fun WatchedItem.toSlide() = Slide("w.$key", app, title, art, backdrop)

fun TvApp.toSlide() = Slide("a.$packageName", this, label, null, backdrop)

/** "Just now", "5 minutes ago", ... */
fun watchedAgo(item: WatchedItem, now: Long = System.currentTimeMillis()): String =
    if (now - item.watchedAt < DateUtils.MINUTE_IN_MILLIS) "Just now"
    else DateUtils.getRelativeTimeSpanString(item.watchedAt, now, DateUtils.MINUTE_IN_MILLIS).toString()
