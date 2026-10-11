package mihon.feature.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import mihon.domain.library.model.search.ComparisonField
import mihon.domain.library.model.search.MangaField
import mihon.domain.search.AndNode
import mihon.domain.search.ComparisonQueryNode
import mihon.domain.search.EmptyQueryNode
import mihon.domain.search.FieldQueryNode
import mihon.domain.search.GeneralQueryNode
import mihon.domain.search.NotNode
import mihon.domain.search.OrNode
import mihon.domain.search.QueryNode
import tachiyomi.source.local.LocalSource
import kotlin.math.abs
import kotlin.time.Instant

fun QueryNode.matches(item: LibraryItem): Boolean {
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

private fun GeneralQueryNode.matches(item: LibraryItem): Boolean {
    val match = libraryMatches(item, value)
    return if (negated) !match else match
}

// Every field a bare term is matched against, kept as a `when` so that each field is handled explicitly
private fun libraryMatches(item: LibraryItem, text: String): Boolean {
    val manga = item.libraryManga.manga

    return MangaField.entries.any { field ->
        if (field.fieldOnly) return@any false

        when (field) {
            MangaField.TITLE -> manga.title.contains(text, ignoreCase = true)
            MangaField.AUTHOR -> manga.author?.contains(text, ignoreCase = true) ?: false
            MangaField.ARTIST -> manga.artist?.contains(text, ignoreCase = true) ?: false
            MangaField.DESCRIPTION -> manga.description?.contains(text, ignoreCase = true) ?: false
            MangaField.GENRE -> manga.genre?.any { it.contains(text, ignoreCase = true) } ?: false
            MangaField.SOURCE -> {
                item.sourceName.contains(text, ignoreCase = true) ||
                    (text.equals("local", ignoreCase = true) && manga.source == LocalSource.ID)
            }
            MangaField.NOTES -> manga.notes.contains(text, ignoreCase = true)

            // field-only queries; unreachable; added here to make `when` exhaustive
            MangaField.LANGUAGE, MangaField.SOURCE_ID -> error("How did we get here?")
        }
    }
}

private fun FieldQueryNode.matches(item: LibraryItem): Boolean {
    val manga = item.libraryManga.manga

    // Names are resolved here, so that a name this type doesn't have is searched as text
    val requestedField = MangaField.fromString(field)
    val match = when (requestedField) {
        MangaField.GENRE -> {
            if (value.isEmpty()) {
                manga.genre.isNullOrEmpty()
            } else {
                manga.genre?.any { it.contains(value, ignoreCase = true) } ?: false
            }
        }

        MangaField.SOURCE -> {
            if (value.isEmpty()) {
                item.sourceName.isEmpty()
            } else {
                item.sourceName.contains(value, ignoreCase = true) ||
                    (value.equals("local", ignoreCase = true) && manga.source == LocalSource.ID)
            }
        }

        MangaField.SOURCE_ID -> {
            value.toLongOrNull()?.let { it == manga.source } ?: false
        }

        null -> libraryMatches(item, "$field:$value")

        else -> {
            val text = when (requestedField) {
                MangaField.TITLE -> manga.title
                MangaField.AUTHOR -> manga.author
                MangaField.ARTIST -> manga.artist
                MangaField.DESCRIPTION -> manga.description
                MangaField.NOTES -> manga.notes
                MangaField.LANGUAGE -> item.sourceLanguage

                // unreachable; added here to make `when` exhaustive
                MangaField.GENRE, MangaField.SOURCE, MangaField.SOURCE_ID -> error("How did we get here?")
            }

            if (value.isEmpty()) {
                text.isNullOrEmpty()
            } else {
                text?.contains(value, ignoreCase = true) ?: false
            }
        }
    }

    return if (negated) !match else match
}

private fun ComparisonQueryNode.matches(item: LibraryItem): Boolean {
    val manga = item.libraryManga.manga

    fun compareDates(timestamp: Long, value: String): Boolean? {
        val inputDate = runCatching { LocalDate.parse(value) }.getOrNull() ?: return null
        val mangaDate = Instant.fromEpochMilliseconds(timestamp).toLocalDateTime(TimeZone.currentSystemDefault()).date
        return queryComparator.apply(mangaDate, inputDate)
    }

    val requestedField = ComparisonField.fromString(field)
    val match = when (requestedField) {
        ComparisonField.ID -> value.toLongOrNull()?.let { queryComparator.apply(manga.id, it) }

        ComparisonField.DATE_ADDED -> manga.favoriteAt?.let { compareDates(it, value) }

        ComparisonField.FETCH_INTERVAL -> value.toIntOrNull()
            ?.let { queryComparator.apply(abs(manga.fetchInterval), it) }

        ComparisonField.NEXT_UPDATE -> compareDates(manga.nextUpdate, value)

        ComparisonField.UNREAD -> {
            value.toLongOrNull()?.let {
                queryComparator.apply(item.unreadCount, it)
            }
        }

        ComparisonField.READ -> {
            value.toLongOrNull()?.let {
                queryComparator.apply(item.libraryManga.readCount, it)
            }
        }

        ComparisonField.TOTAL -> {
            value.toLongOrNull()?.let {
                queryComparator.apply(item.libraryManga.totalChapters, it)
            }
        }

        null -> libraryMatches(item, "$field${queryComparator.symbol}$value")
    }

    // a manga with nothing to compare belongs to no range, not even the negation of one
    return match?.let { if (negated) !it else it } ?: false
}
