package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import ca.mpreg.imagedecoder.ImageDecoder
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import okio.BufferedSource
import kotlin.math.abs

/**
 * Pixels for the pages around the one being read, kept so that turning to a page that was already
 * decoded costs no decoding at all.
 *
 * For a page that is already downloaded, the decode is the largest part of what a turn waits for:
 * the file is read and processed in the holder, and the image is then decoded again to be drawn.
 * Once the pixels exist they are cheap to keep, and keeping them is what makes decoding ahead of the
 * turn worth doing — a page whose pixels are already here is shown as soon as it is asked for.
 *
 * Everything here is counted in decoded pixels rather than in bytes on disk, because what is being
 * bounded is memory held. A page that is tightly compressed can be very large once decoded, and
 * sizing the cache by the file would let one such page decide the whole budget.
 */
/**
 * The most a single page's pixels may take. A page bigger than this is still decoded and shown —
 * that costs nothing extra, the pixels were made anyway — but it is not kept, so one very large
 * page cannot be worth more than the cache is allowed to be in total.
 */
private const val MAX_PAGE_PIXELS = 32L * 1024 * 1024

/**
 * The most the whole cache may take. This is the one number that decides how much of a chapter the
 * reader can look at without re-decoding, and it is paid in memory held, not on disk.
 */
private const val MAX_CACHED_PIXELS = 64L * 1024 * 1024

/**
 * How a page the reader no longer holds is ranked, so it is dropped before any page that is still
 * in reach.
 */
private const val FAR = 1 shl 20

/**
 * A page in the cache, by where it is rather than by the object standing for it. A chapter that is
 * looked at again after being released builds brand new page objects for the same pages, and the
 * pixels for those pages are still the pixels for those pages.
 */
private data class PageLocation(val chapterId: Long?, val index: Int)

/**
 * Decodes a page's image to pixels in one pass, with the decoder that the strip and coil paths use,
 * so the formats they read (AVIF, HEIF, ...) are read here too.
 *
 * The source is left whole for whoever else wants it. Throws when the image cannot be decoded this
 * way; the caller then hands the file to the view instead, which is the older path.
 */
fun decodeToBitmap(source: BufferedSource): Bitmap {
    val (frame, isHdr) = source.peek().inputStream().let { input ->
        ImageDecoder.open(input).use { it.decodeNext() to it.isHdr }
    }
    return frame.use {
        val config = if (isHdr) Bitmap.Config.RGBA_F16 else Bitmap.Config.ARGB_8888
        val bitmap = createBitmap(frame.width, frame.height, config)
        frame.image.rewind()
        bitmap.copyPixelsFromBuffer(frame.image)
        bitmap
    }
}

class PageBitmapCache {

    private val entries = LinkedHashMap<PageLocation, Bitmap>()

    /**
     * What the cache is holding, in decoded pixels, which is what the limits are counted in.
     */
    fun heldBytes(): Long = entries.values.sumOf { it.allocationByteCount.toLong() }

    /**
     * The pixels for the given page, if they are here. Null when they are not, or when whatever drew
     * them has already let go of them.
     */
    fun find(page: ReaderPage): Bitmap? {
        val cached = entries[keyOf(page)] ?: return null
        if (cached.isRecycled) {
            drop(page)
            return null
        }
        return cached
    }

    /**
     * Keeps the pixels for a page, answering false when they are not kept because a single page is
     * larger than the cache is allowed to be.
     */
    fun store(page: ReaderPage, bitmap: Bitmap): Boolean {
        if (bitmap.allocationByteCount > MAX_PAGE_PIXELS) {
            return false
        }
        entries[keyOf(page)] = bitmap
        return true
    }

    fun drop(page: ReaderPage) {
        entries.remove(keyOf(page))
    }

    fun clear() {
        entries.clear()
    }

    /**
     * Drops the pages farthest from the reading position until the whole cache fits, so the cache
     * follows the reader rather than remembering everything the reader has looked at. A page the
     * reader no longer holds is taken as far away, which is what lets go of the pages of a chapter
     * that has been scrolled past.
     *
     * [order] is the pages in reading order, [current] the one being read.
     */
    fun trimTo(order: List<ReaderPage>, current: ReaderPage?) {
        val rank = HashMap<PageLocation, Int>()
        var position = 0
        order.forEach { page ->
            rank.putIfAbsent(keyOf(page), position)
            position += 1
        }
        // Where the reader is. When it is not on a page, or not on one that is ranked, everything
        // held is equally far and the pages are let go in the order they were put here.
        val from = current?.let { rank[keyOf(it)] } ?: FAR

        while (heldBytes() > MAX_CACHED_PIXELS) {
            var farthest: PageLocation? = null
            var farthestDistance = 0
            entries.keys.forEach { key ->
                val distance = rank[key]?.let { abs(it - from) } ?: FAR
                if (distance > farthestDistance) {
                    farthestDistance = distance
                    farthest = key
                }
            }
            // Everything held is at the reading position, so there is nothing here to let go of
            if (farthest == null) break
            entries.remove(farthest)
        }
    }

    private fun keyOf(page: ReaderPage): PageLocation = PageLocation(page.chapter.chapter.id, page.index)
}
