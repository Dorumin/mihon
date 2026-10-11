package mihon.domain.library.model.search

/**
 * The fields a library search query can name, e.g. {@code title:} or {@code unread>10}.
 *
 * A field name that isn't listed here is treated as plain text by the matcher,
 * which is what makes typos like {@code titel:foo} search for "titel:foo" instead of failing.
 */
enum class MangaField(vararg val aliases: String, val fieldOnly: Boolean = false) {
    TITLE("title"),
    AUTHOR("author"),
    ARTIST("artist"),
    DESCRIPTION("description", "desc"),
    GENRE("genre", "tag"),
    SOURCE("source", "src"),
    NOTES("notes", "note"),
    LANGUAGE("language", "lang", fieldOnly = true),
    SOURCE_ID("source_id", "sourceid", "src_id", "srcid", fieldOnly = true),
    ;

    companion object {
        private val lookup = entries.flatMap { field ->
            field.aliases.map { it.lowercase() to field }
        }.toMap()

        fun fromString(value: String): MangaField? = lookup[value.lowercase()]
    }
}

enum class ComparisonField(vararg val aliases: String) {
    ID("id"),
    DATE_ADDED("added"),
    FETCH_INTERVAL("fetchinterval", "fi"),
    NEXT_UPDATE("nextupdate", "nu"),
    UNREAD("unread"),
    READ("read"),
    TOTAL("total"),
    ;

    companion object {
        private val lookup = entries.flatMap { field ->
            field.aliases.map { it.lowercase() to field }
        }.toMap()

        fun fromString(value: String): ComparisonField? = lookup[value.lowercase()]
    }
}
