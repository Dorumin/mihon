package mihon.feature.manga

import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.manga.ChapterList
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import mihon.domain.search.QueryNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.domain.chapter.model.Chapter

/**
 * The matcher is checked against a shelf built from the shapes real scanlations produce, because that
 * is where the interesting cases are: a prologue or extra that never got a number, upload dates a
 * source does not know, one group restarting the numbering at 1, fractional numbers, a blank scanlator
 * as opposed to a missing one. Each test names the entries that match, so a failure reads as "this
 * query picked up that chapter".
 */
@Execution(ExecutionMode.CONCURRENT)
class ChapterQueryNodeExtensionsTest {

    private val prologue = item(
        chapter = chapter(name = "Prologue"),
    )

    private val firstChapter = item(
        chapter = chapter(
            name = "Chapter 1",
            number = 1.0,
            uploadDate = "2019-09-21",
            scanlator = "Cool Manga & Scans",
            read = true,
            bookmark = true,
        ),
        downloadState = Download.State.DOWNLOADED,
    )

    private val secondChapter = item(
        chapter = chapter(
            name = "Chapter 2",
            number = 2.0,
            uploadDate = "2019-09-25",
            scanlator = "Cool Manga & Scans",
        ),
        downloadState = Download.State.QUEUE,
    )

    private val firstChapterHalf = item(
        chapter = chapter(
            name = "Chapter 1.5",
            number = 1.5,
            uploadDate = "2019-09-23",
            scanlator = "Cool Manga & Scans",
        ),
        downloadState = Download.State.DOWNLOADED,
    )

    // A second group that restarted numbering at 1, as Berserk's later batches do
    private val firstChapterAgain = item(
        chapter = chapter(
            name = "Chapter 1 (MangaPlus)",
            number = 1.0,
            uploadDate = "2024-02-01",
            scanlator = "MangaPlus",
            read = true,
        ),
    )

    // An extra whose scanlator field is blank rather than absent
    private val bonus = item(
        chapter = chapter(name = "Bonus", scanlator = "", bookmark = true),
    )

    private val shelf = listOf(
        prologue,
        firstChapter,
        secondChapter,
        firstChapterHalf,
        firstChapterAgain,
        bonus,
    )

    private val allNames = shelf.map { it.chapter.name }

    private fun matching(query: String): List<String> =
        shelf.filter { QueryNode.from(query).matches(it) }.map { it.chapter.name }

    private fun notMatching(query: String): List<String> =
        shelf.filter { !QueryNode.from(query).matches(it) }.map { it.chapter.name }

    @Test
    fun `an empty query keeps the whole list`() {
        matching("") shouldContainExactly allNames
        matching("   ") shouldContainExactly allNames
    }

    @Test
    fun `a bare term searches the name and the scanlator`() {
        matching("prologue") shouldContainExactly listOf("Prologue")
        matching("cool") shouldContainExactly listOf("Chapter 1", "Chapter 2", "Chapter 1.5")
        matching("manga") shouldContainExactly listOf("Chapter 1", "Chapter 2", "Chapter 1.5", "Chapter 1 (MangaPlus)")
    }

    @Test
    fun `several bare terms are ANDed`() {
        matching("chapter 1") shouldContainExactly listOf("Chapter 1", "Chapter 1.5", "Chapter 1 (MangaPlus)")
        notMatching("chapter 1") shouldContainExactly listOf("Prologue", "Chapter 2", "Bonus")
    }

    @Test
    fun `a named field searches only what it names`() {
        matching("scanlator:cool") shouldContainExactly listOf("Chapter 1", "Chapter 2", "Chapter 1.5")
        matching("group:mangaplus") shouldContainExactly listOf("Chapter 1 (MangaPlus)")
        // the value is a separate term, so this is "in the name" AND "anywhere"
        matching("name:chapter 1") shouldContainExactly listOf("Chapter 1", "Chapter 1.5", "Chapter 1 (MangaPlus)")
    }

    @Test
    fun `an empty field value asks for the field to be unset`() {
        // both the absent and the blank scanlator count, which is what a source leaves behind
        matching("scanlator:\"\"") shouldContainExactly listOf("Prologue", "Bonus")
        matching("name:\"\"") shouldBe emptyList<String>()
    }

    @Test
    fun `a field that is not a chapter field searches for that text instead`() {
        matching("titel:chapter") shouldBe emptyList<String>()
        matching("number:1") shouldBe emptyList<String>()
    }

    @Test
    fun `flags are asked for with their colon`() {
        matching("unread:true") shouldContainExactly listOf("Prologue", "Chapter 2", "Chapter 1.5", "Bonus")
        matching("unread:false") shouldContainExactly listOf("Chapter 1", "Chapter 1 (MangaPlus)")
        matching("unread:no") shouldContainExactly listOf("Chapter 1", "Chapter 1 (MangaPlus)")
        matching("bookmark:true") shouldContainExactly listOf("Chapter 1", "Bonus")
        matching("downloaded:true") shouldContainExactly listOf("Chapter 1", "Chapter 1.5")
    }

    @Test
    fun `a queued download is not a downloaded chapter`() {
        matching("downloaded:false") shouldContainExactly
            listOf("Prologue", "Chapter 2", "Chapter 1 (MangaPlus)", "Bonus")
        matching("downloaded:false scanlator:cool") shouldContainExactly listOf("Chapter 2")
    }

    @Test
    fun `a value that is not yes or no asks for the flag to be unset`() {
        // "unread:maybe" reads as "read", the same as a typo would
        matching("unread:maybe") shouldContainExactly listOf("Chapter 1", "Chapter 1 (MangaPlus)")
    }

    @Test
    fun `number ranges are exact and fractional`() {
        matching("number>=2") shouldContainExactly listOf("Chapter 2")
        matching("number>1 && number<2") shouldContainExactly listOf("Chapter 1.5")
        // two groups both numbered their first chapter 1, so naming it returns both
        matching("number>=1 && number<=1") shouldContainExactly listOf("Chapter 1", "Chapter 1 (MangaPlus)")
    }

    @Test
    fun `a range skips chapters that were never numbered`() {
        // the prologue and the bonus are neither below 1 nor at or above 1, so they answer no range
        matching("ch<=1") shouldContainExactly listOf("Chapter 1", "Chapter 1 (MangaPlus)")
        matching("-ch>=1") shouldBe emptyList<String>()
    }

    @Test
    fun `a date range skips chapters whose source gave no upload date`() {
        matching("date>=2024-01-01") shouldContainExactly listOf("Chapter 1 (MangaPlus)")
        matching("date<2024-01-01") shouldContainExactly listOf("Chapter 1", "Chapter 2", "Chapter 1.5")
        // negating a range does not bring the undated ones back
        matching("-date<2024-01-01") shouldContainExactly listOf("Chapter 1 (MangaPlus)")
    }

    @Test
    fun `the flag shorthand asks for the flag to be set`() {
        // a bare word is still plain text, so it finds nothing here; "unread:" means "unread:true"
        matching("unread") shouldBe emptyList<String>()
        matching("unread:") shouldContainExactly listOf("Prologue", "Chapter 2", "Chapter 1.5", "Bonus")
        matching("downloaded:") shouldContainExactly listOf("Chapter 1", "Chapter 1.5")
    }

    @Test
    fun `negation and grouping`() {
        matching("-unread:true") shouldContainExactly listOf("Chapter 1", "Chapter 1 (MangaPlus)")
        matching("prologue || bonus") shouldContainExactly listOf("Prologue", "Bonus")
        matching("scanlator:manga -scanlator:cool") shouldContainExactly listOf("Chapter 1 (MangaPlus)")
        matching("(chapter 1) -downloaded:true") shouldContainExactly listOf("Chapter 1 (MangaPlus)")
    }
}

private fun chapter(
    name: String,
    number: Double = -1.0,
    uploadDate: String? = null,
    scanlator: String? = null,
    read: Boolean = false,
    bookmark: Boolean = false,
): Chapter = Chapter.create().copy(
    name = name,
    chapterNumber = number,
    // -1 is how the model spells "the source did not give an upload date"
    dateUpload = uploadDate?.let {
        LocalDate.parse(it).atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds()
    } ?: -1L,
    scanlator = scanlator,
    read = read,
    bookmark = bookmark,
)

private fun item(
    chapter: Chapter,
    downloadState: Download.State = Download.State.NOT_DOWNLOADED,
): ChapterList.Item = ChapterList.Item(
    chapter = chapter,
    downloadState = downloadState,
    downloadProgress = 0,
)
