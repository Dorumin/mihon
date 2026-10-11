package mihon.domain.chapter.model.search

/**
 * The fields a chapter search query can name inside an entry, e.g. {@code scanlator:} or {@code number>=10}.
 *
 * A field name that isn't listed here is treated as plain text by the matcher.
 *
 * A value is a single word; quote it to span more than one, as {@code name:"chapter 1"} does.
 */
enum class ChapterField(vararg val aliases: String) {
    NAME("name", "title"),
    SCANLATOR("scanlator", "group", "scanlation", "gl"),
    // Boolean fields. An empty or truthy value asks for the flag to be set, e.g. "unread:true".
    UNREAD("unread"),
    BOOKMARKED("bookmarked", "bookmark"),
    DOWNLOADED("downloaded", "download"),
    ;

    companion object {
        private val lookup = entries.flatMap { field ->
            field.aliases.map { it.lowercase() to field }
        }.toMap()

        fun fromString(value: String): ChapterField? = lookup[value.lowercase()]
    }
}

enum class ChapterComparisonField(vararg val aliases: String) {
    NUMBER("number", "num", "ch", "chapter_number"),
    // Compared against "yyyy-MM-dd" values, like every other date field in a query.
    DATE("date", "uploaded", "upload"),
    ;

    companion object {
        private val lookup = entries.flatMap { field ->
            field.aliases.map { it.lowercase() to field }
        }.toMap()

        fun fromString(value: String): ChapterComparisonField? = lookup[value.lowercase()]
    }
}
