package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import androidx.annotation.ColorInt
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.blue
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.green
import androidx.core.graphics.red
import androidx.core.graphics.set
import ca.mpreg.imagedecoder.ImageDecoder
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.metrics
import eu.kanade.tachiyomi.ui.reader.viewer.metricsSince
import eu.kanade.tachiyomi.ui.reader.viewer.metricsTime
import okio.BufferedSource
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

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
 *
 * Whether any page is prepared at all is a reader setting, read in PagerConfig and asked about by the
 * page holder; with it off nothing is looked up and nothing is stored here, and every page hands its
 * file to its view instead.
 */
/**
 * What a page's pixels are sized to, rather than a size they are refused at. A page is decoded at
 * whatever resolution it is asked for and, if that is larger than this, scaled down to about this
 * before it is kept, so the window of pages held here is decided by this number and by the one
 * below together.
 *
 * It is the whole cache divided by the pages that can be on view at once (the one being read plus
 * the reader's lead either way), so a page at this size is one that fits without pushing another
 * out. A page that is already smaller is kept as it is.
 */
private const val MAX_PAGE_PIXELS = 6L * 1024 * 1024

/**
 * The most the whole cache may take. This is the one number that decides how much of a chapter the
 * reader can look at without re-decoding, and it is paid in memory held, not on disk.
 */
private const val MAX_CACHED_PIXELS = 64L * 1024 * 1024

/**
 * How big a band of a page's own rows is while the page is being scaled down, in bytes. It is a
 * working buffer, so all it decides is the peak above the frame itself: a band is read once and
 * thrown away. It is kept well under the size of a page's frame so that the frame, one band and the
 * sized page are what is held at the top, rather than the frame and the page twice.
 */
private const val BAND_BYTES = 8L * 1024 * 1024

/**
 * A file this big or bigger is one whose pixels at the file's own resolution are a few hundred MB —
 * the measured page was a 13MB file over a 140MB frame — and the app's heap is 512MB, so a handful of
 * them decoding at once is already past it. It is a file-size proxy for the frame, not the frame
 * itself: a page that compresses very well can be over this and still be huge once decoded, and only
 * the decoder knows that, by which point the frame is already allocated. What it decides is how many
 * such pages are decoded at a time, not whether one is decoded at all.
 */
const val BIG_DECODE_BYTES = 4L * 1024 * 1024

/**
 * How much detail a page prepared ahead is asked to hold beyond the screen it is on. One is the screen
 * itself, which is all a page that is only being given lead time is asked for: the reader's double
 * tap zooms 2×, so a page prepared at this size shows its pixels rather than more detail once it is
 * zoomed into. That is the trade for keeping the window large, and it is a trade only a page that is
 * still being given lead time makes: when the reader stops on a prepared page, the holder hands the
 * view the file it came from (`PagerPageHolder.promoteToFullDetail`), where the subsampling view
 * keeps real detail at every zoom. So this number decides how fast a page is ready and how much of a
 * chapter fits in the window, not what a page the reader stopped on shows.
 */
private const val ZOOM_OVER_VIEW = 1

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
 * Debug: whether the pixels a page is kept in are colour or grey. This is a way to see what the cache
 * is actually holding — which pages it reached, and how small they came out — on screen, at a glance,
 * rather than read off `dim=` and `pixels=`. It changes only what the pixels are: the size they are
 * sized to, the window and the log all stay as they are, so a page under this flag is the same page
 * at the same place, without colour.
 *
 * It is not a smaller page. There is no grey format to keep the pixels in: what `Bitmap.Config`
 * offers is ALPHA_8, ARGB_4444, ARGB_8888, HARDWARE, RGBA_1010102, RGBA_F16 and RGB_565, and the
 * grey formats are only in the deprecated ones. So grey is written into the same four bytes a pixel
 * already costs and `pixels=` does not move. The format that would halve the cost is RGB_565, which
 * is a change to what every reader sees rather than a debug one.
 *
 * The conversion walks the pixels one at a time, so it is a debug view rather than a fast one: it
 * shows up in `decode=` for every page that takes the pixels path.
 *
 * The grey alone is not enough to see what this path is for. A page is sized to the screen it is on,
 * which is 1:1 with that same screen, so at the reading zoom there is nothing to notice — the drop
 * only shows when the page is zoomed into. [GRAYSCALE_SIZE] is what makes it visible at a glance.
 */
private const val CACHE_GRAYSCALE = false

/**
 * Debug: how much of the size a page is really asked for the grey page is made at. It is read only
 * under [CACHE_GRAYSCALE], and unlike that flag it does change the numbers: `dim=`, `pixels=` and
 * what the cache holds all go down with the picture, so a session under the two flags together is
 * not the session the reader would have. It is there to answer "how far off the file's own detail
 * am I looking at", which the grey on a page sized to the screen cannot.
 */
private const val GRAYSCALE_SIZE = 0.25

/**
 * Decodes a page's image to pixels in one pass, with the decoder that the strip and coil paths use,
 * so the formats they read (AVIF, HEIF, ...) are read here too.
 *
 * The pixels are sized to what a page is asked to show: the smaller of the view itself, and
 * [MAX_PAGE_PIXELS]. A page that is already no bigger than either is kept as it is.
 * The image is inflated at its own resolution either way — a PNG is unfiltered over all of its
 * pixels whatever is done afterwards — so this is what buys memory, the collector's work and the
 * view's own drawing, not the time to read the file.
 *
 * The source is left whole for whoever else wants it. Throws when the image cannot be decoded this
 * way; the caller then hands the file to the view instead, which is the older path.
 */
fun decodeToBitmap(source: BufferedSource, viewWidth: Int, viewHeight: Int): Bitmap {
    // The two things this pass costs, told apart because they are asked about differently. The inflate
    // is the decoder's own work over the file's pixels, so it is the part a page's resolution decides.
    // The sizing below is over the pixels the page is *kept* at, which is about the same count for every
    // page on one screen whatever the file was — a page is sized to the screen it is on, and the screen
    // does not change with the file. So a single total cannot tell a page that was slow because its
    // file was big from one that was slow because the sizing was, and the two are worth different
    // things: the inflate is already native, and the sizing is what a change here can still act on.
    val inflatedAt = metricsTime()
    val (frame, isHdr) = source.peek().inputStream().let { input ->
        ImageDecoder.open(input).use { it.decodeNext() to it.isHdr }
    }
    val inflated = metricsSince(inflatedAt)
    return frame.use {
        val config = if (isHdr) Bitmap.Config.RGBA_F16 else Bitmap.Config.ARGB_8888
        val bytesPerPixel = if (isHdr) 8 else 4
        val srcWidth = frame.width
        val srcHeight = frame.height
        // Two ways a page is too big to keep as it came out: more than the view wants with room to
        // zoom, and more than one page is allowed to be worth. Whichever is smaller decides, and
        // neither asks for a page to be made larger than it already is.
        val fit = min(
            viewWidth.toDouble() * ZOOM_OVER_VIEW / srcWidth,
            viewHeight.toDouble() * ZOOM_OVER_VIEW / srcHeight,
        )
        val budget = sqrt(MAX_PAGE_PIXELS.toDouble() / bytesPerPixel / (srcWidth.toDouble() * srcHeight))
        val fitScale = min(min(fit, budget), 1.0)
        // What the page is actually made at, once the debug pair is taken into account. A page sized
        // to the screen is 1:1 with the screen it is on, so the detail it dropped is not something
        // the eye can see at the reading zoom; under the debug flag the page is made smaller still,
        // and everything below — and the line that reports it — is about the page that is shown.
        val shownAt = if (CACHE_GRAYSCALE) fitScale * GRAYSCALE_SIZE else fitScale

        val sizingAt = metricsTime()
        val sized = if (shownAt >= 1.0) {
            // Nothing to leave out: the page is kept at the size it came out at, so the frame's
            // pixels are copied into it once
            val full = createBitmap(srcWidth, srcHeight, config)
            frame.image.rewind()
            full.copyPixelsFromBuffer(frame.image)
            full
        } else {
            // The page is never made at its own size in Java. The decoder's own frame buffer is the
            // only copy of it at native resolution, and the sized bitmap is filled band by band out
            // of that — the same reason the coil decoder's `decodeStrips` works band by band. What is
            // held at the peak is the frame, one band of its rows, and the sized page, rather than
            // the frame and the page twice: for the measured 5000×7000 page that is the difference
            // between a 140MB allocation that the app's heap has no room for and one it does.
            val scaledWidth = (srcWidth * shownAt).toInt().coerceAtLeast(1)
            val scaledHeight = (srcHeight * shownAt).toInt().coerceAtLeast(1)
            val sizedBitmap = createBitmap(scaledWidth, scaledHeight, config)
            val rowBytes = srcWidth * bytesPerPixel
            val bandRows = (BAND_BYTES / rowBytes).toInt().coerceAtLeast(1).coerceAtMost(srcHeight)
            val band = createBitmap(srcWidth, bandRows, config)
            // The band is scaled by Skia rather than walked here, which is the call the coil decoder's
            // `decodeStrips` makes. Only the band is ever handed to it, so the scale stays inside the
            // memory bound above: what is held at the peak is still the frame, one band, and the sized
            // page. Filtering is a change of its own beside the speed: the walk this replaces took one
            // pixel per output pixel, so a downscale threw whole rows and columns of the page away, which
            // what makes thin lines on a page break or vanish at the reading zoom.
            val across = scaledWidth.toFloat() / srcWidth
            frame.image.rewind()
            var srcTop = 0
            while (srcTop < srcHeight) {
                val rows = min(bandRows, srcHeight - srcTop)
                if (rows != band.height) {
                    band.reconfigure(srcWidth, rows, config)
                }
                band.copyPixelsFromBuffer(
                    frame.image.duplicate().apply {
                        position(srcTop * rowBytes)
                        limit((srcTop + rows) * rowBytes)
                    },
                )
                // The output rows this band answers, which is the mapping the walk used read from the
                // other side: an output row takes its source row in reading order, so a band of source
                // rows answers exactly the output rows between those two bounds. Each band is scaled to
                // its own share rather than to the page's scale, which is what makes the shares add up
                // to `scaledHeight` exactly — one scale for the whole page rounded per band, and a page
                // of many bands would then leave a row unfilled or draw one twice.
                val outTop = srcTop * scaledHeight / srcHeight
                val outRows = ((srcTop + rows) * scaledHeight / srcHeight) - outTop
                val matrix = Matrix().apply { setScale(across, outRows.toFloat() / rows) }
                val scaledBand = Bitmap.createBitmap(band, 0, 0, srcWidth, rows, matrix, true)
                sizedBitmap.applyCanvas {
                    drawBitmap(scaledBand, 0F, outTop.toFloat(), null)
                }
                scaledBand.recycle()
                srcTop += rows
            }
            band.recycle()
            sizedBitmap
        }
        val sizedMs = metricsSince(sizingAt)
        // What this pass cost and what it was asked for. `src=` is the frame the decoder inflated,
        // `dim=` is what is kept, and the two timings are the halves above: `inflate=` is the
        // decoder's work over the file, `size=` is the sizing over what is kept — a copy for a page that
        // was kept as it came out, the band loop and the native scale for one that was not. The two are
        // read against `dim=` rather than against `src=`: the sizing is over the pixels that are kept, so
        // its cost is what `dim=` counts, and a page whose `size=` is large at a small `dim=` is a
        // page the sizing is being asked to do more of than it needs. A page that was kept at its own
        // size says so with `scale=1` rather than by leaving the line out, which is what makes the
        // absence of a line mean only "this page did not take the pixels path". No page id here: this
        // is the one place that knows the file's dimensions, and the line is read against the holder's
        // for the same page by what surrounds it.
        metrics(
            "fit",
            null,
            "src=${srcWidth}x${srcHeight} dim=${sized.width}x${sized.height} scale=${shownAt} inflate=$inflated size=$sizedMs",
        )
        // The debug flag asks for grey, written into the pixels this pass just made
        if (CACHE_GRAYSCALE) toGrayscale(sized) else sized
    }
}

/**
 * The pixels a page is kept as, when the debug flag asks for grey. The page is walked once and each
 * pixel replaced by its own brightness, in place: these are pixels this pass made and nothing else
 * has drawn them yet.
 */
private fun toGrayscale(bitmap: Bitmap): Bitmap {
    var y = 0
    while (y < bitmap.height) {
        var x = 0
        while (x < bitmap.width) {
            bitmap.set(x, y, bitmap[x, y].asGray())
            x += 1
        }
        y += 1
    }
    return bitmap
}

/**
 * The grey a colour pixel is kept as, as a colour: its brightness in all three channels, with the
 * page left opaque. The weights are the ones the eye gives to each channel — a page read as the plain
 * average of its channels sits too dark.
 */
private fun @receiver:ColorInt Int.asGray(): Int {
    val shade = (red * 299 + green * 587 + blue * 114) / 1000
    return Color.rgb(shade, shade, shade)
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
     * Keeps the pixels for a page, answering false when they are not kept. A page is asked for at a
     * size that fits, so nothing is expected to hit this; it is here so that a page which came by
     * some other way cannot make the cache worth more than it is allowed to be.
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
