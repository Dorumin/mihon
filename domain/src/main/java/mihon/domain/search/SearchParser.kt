package mihon.domain.search

/**
 * Turns tokens into a query tree. Field names are kept as written, so the same grammar
 * works for any searchable type; an unknown name is turned back into plain text when matched.
 */
class SearchParser(private val tokens: List<SearchLexer.Token>) {
    private var index = 0

    private fun peek(): SearchLexer.Token? = tokens.getOrNull(index)
    private fun advance(): SearchLexer.Token? = tokens.getOrNull(index++)

    fun parse(): QueryNode {
        if (tokens.isEmpty()) return AndNode(emptyList())
        return parseOr()
    }

    private fun parseOr(): QueryNode {
        val nodes = mutableListOf<QueryNode>()
        nodes.add(parseAnd())
        while (peek() is SearchLexer.Token.Or) {
            advance()
            nodes.add(parseAnd())
        }
        // "foo ||" is a typo, not a query that matches everything, so the empty branch is dropped
        val filteredNodes = nodes.filter { it !is EmptyQueryNode }
        if (filteredNodes.isEmpty()) return EmptyQueryNode
        return if (filteredNodes.size == 1) filteredNodes.first() else OrNode(filteredNodes)
    }

    private fun parseAnd(): QueryNode {
        val nodes = mutableListOf<QueryNode>()

        while (index < tokens.size && peek() !is SearchLexer.Token.Or &&
            peek() !is SearchLexer.Token.RParen
        ) {
            if (peek() is SearchLexer.Token.And) {
                advance()
            }
            nodes.add(parseTerm())
        }

        val filteredNodes = nodes.filter { it !is EmptyQueryNode }
        if (filteredNodes.isEmpty()) return EmptyQueryNode
        return if (filteredNodes.size == 1) filteredNodes.first() else AndNode(filteredNodes)
    }

    private fun parseTerm(): QueryNode {
        var negated = false

        while (peek() is SearchLexer.Token.Not) {
            advance()
            negated = !negated
        }

        if (peek() is SearchLexer.Token.LParen) {
            advance()

            val subTree = parseOr()

            if (peek() is SearchLexer.Token.RParen) {
                advance()
            }

            if (negated) {
                return NotNode(subTree)
            }
            return subTree
        }

        return when (val nextToken = advance()) {
            is SearchLexer.Token.General -> GeneralQueryNode(nextToken.value, negated)
            is SearchLexer.Token.Field -> FieldQueryNode(nextToken.field, nextToken.value, negated)
            is SearchLexer.Token.CompField -> {
                // an operator the lexer knows but the enum doesn't is dropped like any other unknown token
                val comparator = Comparator.fromString(nextToken.comparator) ?: return EmptyQueryNode
                ComparisonQueryNode(nextToken.field, nextToken.value, comparator, negated)
            }
            else -> EmptyQueryNode
        }
    }
}
