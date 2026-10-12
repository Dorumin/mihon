package eu.kanade.tachiyomi.ui.reader.model

data class ViewerChapters(
    val currChapter: ReaderChapter,
    val prevChapter: ReaderChapter?,
    val nextChapter: ReaderChapter?,
    /**
     * Chapters the source does not have between the current one and its neighbours. A hole made by
     * the reader's own filters is not missing from the manga, so it is not counted here and the
     * reader needs no screen of its own to browse past it.
     */
    val prevMissingCount: Int = 0,
    val nextMissingCount: Int = 0,
) {

    fun ref() {
        currChapter.ref()
        prevChapter?.ref()
        nextChapter?.ref()
    }

    fun unref() {
        currChapter.unref()
        prevChapter?.unref()
        nextChapter?.unref()
    }
}
