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
import kotlin.time.Instant

/** Matches a search query against a chapter of the opened entry. */
fun QueryNode.matches(item: ChapterList.Item): Boolean {
    return when (this) {
        is AndNode -> children.all { it.matches(item) }
        is OrNode -> children.any { it.matches(item) }
        is NotNode -> !child.matches(item)
        is EmptyQueryNode -> true
        is GeneralQueryNode -> matches(item)
        is FieldQueryNode -> matches(item)
        is ComparisonQueryNode -> matches(item)
    }
}

private fun chapterMatches(item: ChapterList.Item, text: String): Boolean =
    item.chapter.name.contains(text, ignoreCase = true) ||
        (item.chapter.scanlator?.contains(text, ignoreCase = true) ?: false)

// Asks whether a flag is wanted, so that "unread:true" and a bare "unread:" both mean unread.
private fun isRequested(value: String): Boolean =
    value.isEmpty() ||
        value.equals("true", ignoreCase = true) ||
        value.equals("yes", ignoreCase = true) ||
        value == "1"

private fun GeneralQueryNode.matches(item: ChapterList.Item): Boolean {
    val match = chapterMatches(item, value)
    return if (negated) !match else match
}

private fun FieldQueryNode.matches(item: ChapterList.Item): Boolean {
    val match = when (ChapterField.fromString(field)) {
        ChapterField.NAME ->
            if (value.isEmpty()) {
                item.chapter.name.isEmpty()
            } else {
                item.chapter.name.contains(value, ignoreCase = true)
            }

        ChapterField.SCANLATOR ->
            if (value.isEmpty()) {
                item.chapter.scanlator.isNullOrEmpty()
            } else {
                item.chapter.scanlator?.contains(value, ignoreCase = true) ?: false
            }

        ChapterField.UNREAD -> isRequested(value) == !item.chapter.read
        ChapterField.BOOKMARKED -> isRequested(value) == item.chapter.bookmark
        ChapterField.DOWNLOADED -> isRequested(value) == item.isDownloaded

        // Not a chapter field, so "titel:foo" is better off searching for that text than matching nothing
        null -> chapterMatches(item, "$field:$value")
    }
    return if (negated) !match else match
}

private fun ComparisonQueryNode.matches(item: ChapterList.Item): Boolean {
    // A comparison asks whether a chapter's own value falls in a range. Where the chapter has no
    // value, the answer is not "the lowest" or "the highest" but no answer at all, so such a chapter
    // is left out of the range and out of its negation alike, as a null is left out by a SQL engine.
    fun compareNumbers(number: Double): Boolean? {
        if (!item.chapter.isRecognizedNumber) return null
        val wanted = value.toDoubleOrNull() ?: return null
        return queryComparator.apply(number, wanted)
    }

    // -1 is how the model spells an upload date the source never gave
    fun compareDates(timestamp: Long): Boolean? {
        if (timestamp == -1L) return null
        val inputDate = runCatching { LocalDate.parse(value) }.getOrNull() ?: return null
        val uploadDate = Instant.fromEpochMilliseconds(timestamp)
            .toLocalDateTime(TimeZone.currentSystemDefault()).date
        return queryComparator.apply(uploadDate, inputDate)
    }

    val match = when (ChapterComparisonField.fromString(field)) {
        ChapterComparisonField.NUMBER -> compareNumbers(item.chapter.chapterNumber)
        ChapterComparisonField.DATE -> compareDates(item.chapter.dateUpload)

        // Not a chapter field, so "foo>bar" is better off searching for that text than matching nothing
        null -> chapterMatches(item, "$field${queryComparator.symbol}$value")
    }
    // a chapter with nothing to compare belongs to no range, not even the negation of one
    return match?.let { if (negated) !it else it } ?: false
}
