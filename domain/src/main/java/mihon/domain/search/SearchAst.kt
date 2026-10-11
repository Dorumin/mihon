package mihon.domain.search

/**
 * A parsed search query, kept independent of what it is being searched.
 *
 * Field names are only tokenized here; resolving them to the fields of a concrete type
 * (a library entry, a chapter, ...) is up to whoever matches the query against it,
 * so every searchable type can bring its own vocabulary (see [MangaField], [ChapterField]).
 */
enum class Comparator(val symbol: String) {
    GTE(">="),
    LTE("<="),
    GT(">"),
    LT("<"),
    EQ("="),
    ;

    fun <T : Comparable<T>> apply(a: T, b: T): Boolean = when (this) {
        GTE -> a >= b
        LTE -> a <= b
        GT -> a > b
        LT -> a < b
        EQ -> a == b
    }

    companion object {
        private val lookup = entries.associateBy { it.symbol }

        fun fromString(value: String): Comparator? = lookup[value]
    }
}

sealed interface QueryNode {
    companion object {
        fun from(query: String): QueryNode {
            val tokens = SearchLexer.tokenize(query)
            return SearchParser(tokens).parse()
        }
    }
}

data class AndNode(val children: List<QueryNode>) : QueryNode

data class OrNode(val children: List<QueryNode>) : QueryNode

data class NotNode(val child: QueryNode) : QueryNode

object EmptyQueryNode : QueryNode

data class GeneralQueryNode(val value: String, val negated: Boolean) : QueryNode

data class FieldQueryNode(val field: String, val value: String, val negated: Boolean) : QueryNode

data class ComparisonQueryNode(
    val field: String,
    val value: String,
    val queryComparator: Comparator,
    val negated: Boolean,
) : QueryNode
