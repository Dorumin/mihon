package eu.kanade.tachiyomi.ui.reader.viewer

import android.os.SystemClock
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import logcat.LogPriority
import logcat.logcat

/**
 * Measurements of how long a page waits to get pixels on screen, meant to be read from logcat on a
 * device while the reader is being used. Every line is tagged and prefixed `rdx` so they can be
 * picked out with one token, and they are written at INFO because a release build drops anything
 * below it.
 *
 * This is a dead end for behaviour: delete this file and the calls into it and the reader works the
 * same. Nothing here is used to decide anything the reader does.
 */
/**
 * Whether the lines are written at all. Flip it to false to leave the reader untouched, with nothing
 * in it changed.
 */
private const val METRICS_ENABLED = true

/**
 * What the lines are tagged with, which is what a log viewer filters on.
 */
private const val METRICS_TAG = "rdx"

/**
 * A timestamp to measure against. The same clock the rest of the reader uses, which only ever moves
 * forward and costs no allocation.
 */
fun metricsTime(): Long = SystemClock.uptimeMillis()

/**
 * Milliseconds elapsed since the given [since] timestamp.
 */
fun metricsSince(since: Long): Long = metricsTime() - since

/**
 * What identifies a page in a log line. The chapter id and index, not the page object, because a
 * chapter that is looked at again after being released gets brand new page objects for the same
 * pages.
 */
fun metricsPage(page: ReaderPage): String {
    return "${page.chapter.chapter.id}/${page.index}"
}

/**
 * One measurement line: `rdx p=<chapterId>/<index> act=<action> <detail>`.
 * [detail] is a space separated list of `key=value` fields, left to the caller so a line can carry
 * whatever was worth recording at the point that measured it.
 */
fun metrics(action: String, page: ReaderPage?, detail: String) {
    if (!METRICS_ENABLED) return
    logcat(METRICS_TAG, LogPriority.INFO) {
        val subject = if (page != null) "p=${metricsPage(page)} " else ""
        "$METRICS_TAG $subject act=$action $detail"
    }
}
