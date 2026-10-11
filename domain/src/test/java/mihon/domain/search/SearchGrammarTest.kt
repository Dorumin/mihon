package mihon.domain.search

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * The grammar is tested through [QueryNode.from], the entry point every caller uses, and the tree is
 * compared as text so that a failure reads as "this query parsed to this shape" rather than as a stack
 * of node assertions.
 */
@Execution(ExecutionMode.CONCURRENT)
class SearchGrammarTest {

    @Test
    fun `an empty query parses to a node that matches everything`() {
        parse("") shouldBe "EMPTY"
        parse("   ") shouldBe "EMPTY"
    }

    @Test
    fun `several bare terms are ANDed`() {
        parse("foo") shouldBe "G:foo"
        parse("foo bar") shouldBe "(G:foo AND G:bar)"
        parse("foo,bar") shouldBe "(G:foo AND G:bar)"
    }

    @Test
    fun `explicit and and or operators`() {
        parse("foo && bar") shouldBe "(G:foo AND G:bar)"
        parse("foo || bar") shouldBe "(G:foo OR G:bar)"
    }

    @Test
    fun `or binds looser than and`() {
        parse("a || b c") shouldBe "(G:a OR (G:b AND G:c))"
    }

    @Test
    fun `parentheses group`() {
        parse("(a b) c") shouldBe "((G:a AND G:b) AND G:c)"
    }

    @Test
    fun `a leading dash negates the following term`() {
        parse("-foo") shouldBe "NOT(G:foo)"
        parse("-title:foo") shouldBe "NOT(F:title=foo)"
        parse("--foo") shouldBe "G:foo"
    }

    @Test
    fun `quoted values stay one term`() {
        parse("\"hello world\"") shouldBe "G:hello world"
        parse("title:\"foo bar\"") shouldBe "F:title=foo bar"
        // unquoted, a field value is one word and what follows is another term
        parse("name:chapter 1") shouldBe "(F:name=chapter AND G:1)"
    }

    @Test
    fun `a field may have no value, which is how a flag is asked for`() {
        parse("unread:") shouldBe "F:unread="
        parse("scanlator:\"\"") shouldBe "F:scanlator="
    }

    @Test
    fun `field names are kept as written, resolving them is up to the matcher`() {
        parse("titel:foo") shouldBe "F:titel=foo"
        parse("foo>=bar") shouldBe "C:foo>=bar"
    }

    @Test
    fun `a comparison value may contain hyphens, so dates parse as written`() {
        parse("date>=2024-01-01") shouldBe "C:date>=2024-01-01"
        parse("date<=2024-01-01") shouldBe "C:date<=2024-01-01"
    }

    @Test
    fun `a comparison is written flush, spaces around the operator make it plain text`() {
        // left strict on purpose: allowing the gap would make "date 2024" ambiguous, so a query typed
        // with spaces reads as three unrelated terms until that turns out to annoy people in practice
        parse("date >= 2024-01-01") shouldBe "(G:date AND G:>= AND G:2024-01-01)"
    }

    @Test
    fun `comparators map from their symbol and compare values`() {
        Comparator.fromString(">=") shouldBe Comparator.GTE
        Comparator.fromString("!") shouldBe null
        Comparator.GTE.apply(3, 3) shouldBe true
        Comparator.GT.apply(3, 3) shouldBe false
    }

    @Test
    fun `a hyphen only negates at the start of a term, inside a word it stays part of it`() {
        parse("non-standard") shouldBe "G:non-standard"
        parse("-non-standard") shouldBe "NOT(G:non-standard)"
    }

    @Test
    fun `a dash with a space after it negates nothing and stays text`() {
        parse("- foo") shouldBe "(G:- AND G:foo)"
    }

    @Test
    fun `a dangling or is dropped, it does not turn the query into a match all`() {
        parse("foo ||") shouldBe "G:foo"
        parse("|| foo") shouldBe "G:foo"
        parse("a || ()") shouldBe "G:a"
        parse("||") shouldBe "EMPTY"
    }

    @Test
    fun `an unbalanced parenthesis is ignored and the group still parses`() {
        parse("a)") shouldBe "G:a"
        parse(")") shouldBe "EMPTY"
    }

    private fun parse(query: String): String = describe(QueryNode.from(query))
}

private fun describe(node: QueryNode): String = when (node) {
    is AndNode -> describeChildren(node.children, "AND")
    is OrNode -> describeChildren(node.children, "OR")
    is NotNode -> "NOT(${describe(node.child)})"
    is EmptyQueryNode -> "EMPTY"
    is GeneralQueryNode -> describeTerm("G:${node.value}", node.negated)
    is FieldQueryNode -> describeTerm("F:${node.field}=${node.value}", node.negated)
    is ComparisonQueryNode ->
        describeTerm("C:${node.field}${node.queryComparator.symbol}${node.value}", node.negated)
}

private fun describeChildren(children: List<QueryNode>, operator: String): String = when (children.size) {
    0 -> "EMPTY"
    1 -> describe(children.first())
    else -> "(${children.map { describe(it) }.joinToString(" $operator ")})"
}

private fun describeTerm(text: String, negated: Boolean): String =
    if (negated) "NOT($text)" else text
