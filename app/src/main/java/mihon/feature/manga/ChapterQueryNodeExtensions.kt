package mihon.feature.manga

import eu.kanade.tachiyomi.ui.manga.ChapterList
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import mihon.domain.chapter.model.search.ChapterComparisonField
import mihon.domain.chapter.model.search.ChapterField
import mihon.domain.search.AndNode
import mihon.domain.search.ComparisonQueryNode
import mihon.domain.search.EmptyQueryNode
import mihon.domain.search.FieldQueryNode
import mihon.domain.search.GeneralQueryNode
import mihon.domain.search.NotNode
import mihon.domain.search.OrNode
import mihon.domain.search.QueryNode
import tachiyomi.domain.chapter.model.Chapter
import kotlin.time.Instant

/** Matches a search query against a chapter of the opened entry. */
fun QueryNode.matches(item: ChapterList.Item): Boolean = matches(item.chapter, item.isDownloaded)

/**
 * Matches a search query against a chapter on its own, for callers that hold chapters without a
 * row around them. The download state is asked for instead of read off a row, as a chapter alone
 * does not know whether it was downloaded.
 */
fun QueryNode.matches(chapter: Chapter, isDownloaded: Boolean): Boolean {
    return when (this) {
        is AndNode -> children.all { it.matches(chapter, isDownloaded) }
        is OrNode -> children.any { it.matches(chapter, isDownloaded) }
        is NotNode -> !child.matches(chapter, isDownloaded)
        is EmptyQueryNode -> true
        is GeneralQueryNode -> matches(chapter)
        is FieldQueryNode -> matches(chapter, isDownloaded)
        is ComparisonQueryNode -> matches(chapter)
    }
}

private fun chapterMatches(chapter: Chapter, text: String): Boolean =
    chapter.name.contains(text, ignoreCase = true) ||
        (chapter.scanlator?.contains(text, ignoreCase = true) ?: false)

// Asks whether a flag is wanted, so that "unread:true" and a bare "unread:" both mean unread.
private fun isRequested(value: String): Boolean =
    value.isEmpty() ||
        value.equals("true", ignoreCase = true) ||
        value.equals("yes", ignoreCase = true) ||
        value == "1"

private fun GeneralQueryNode.matches(chapter: Chapter): Boolean {
    val match = chapterMatches(chapter, value)
    return if (negated) !match else match
}

private fun FieldQueryNode.matches(chapter: Chapter, isDownloaded: Boolean): Boolean {
    val match = when (ChapterField.fromString(field)) {
        ChapterField.NAME ->
            if (value.isEmpty()) {
                chapter.name.isEmpty()
            } else {
                chapter.name.contains(value, ignoreCase = true)
            }

        ChapterField.SCANLATOR ->
            if (value.isEmpty()) {
                chapter.scanlator.isNullOrEmpty()
            } else {
                chapter.scanlator?.contains(value, ignoreCase = true) ?: false
            }

        ChapterField.UNREAD -> isRequested(value) == !chapter.read
        ChapterField.BOOKMARKED -> isRequested(value) == chapter.bookmark
        ChapterField.DOWNLOADED -> isRequested(value) == isDownloaded

        // Not a chapter field, so "titel:foo" is better off searching for that text than matching nothing
        null -> chapterMatches(chapter, "$field:$value")
    }
    return if (negated) !match else match
}

private fun ComparisonQueryNode.matches(chapter: Chapter): Boolean {
    // A comparison asks whether a chapter's own value falls in a range. Where the chapter has no
    // value, the answer is not "the lowest" or "the highest" but no answer at all, so such a chapter
    // is left out of the range and out of its negation alike, as a null is left out by a SQL engine.
    fun compareNumbers(number: Double): Boolean? {
        if (!chapter.isRecognizedNumber) return null
        val wanted = value.toDoubleOrNull() ?: return null
        return queryComparator.apply(number, wanted)
    }

    // A chapter the source never gave an upload date for has no date to compare, so it belongs to no range
    fun compareDates(uploadTime: Instant?): Boolean? {
        val uploadDate = uploadTime?.toLocalDateTime(TimeZone.currentSystemDefault())?.date ?: return null
        val inputDate = runCatching { LocalDate.parse(value) }.getOrNull() ?: return null
        return queryComparator.apply(uploadDate, inputDate)
    }

    val match = when (ChapterComparisonField.fromString(field)) {
        ChapterComparisonField.NUMBER -> compareNumbers(chapter.chapterNumber)
        ChapterComparisonField.DATE -> compareDates(chapter.dateUpload)

        // Not a chapter field, so "foo>bar" is better off searching for that text than matching nothing
        null -> chapterMatches(chapter, "$field${queryComparator.symbol}$value")
    }
    // a chapter with nothing to compare belongs to no range, not even the negation of one
    return match?.let { if (negated) !it else it } ?: false
}
