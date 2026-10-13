package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import androidx.core.view.isVisible
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.databinding.ReaderErrorBinding
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.BIG_DECODE_BYTES
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.reader.viewer.decodeToBitmap
import eu.kanade.tachiyomi.ui.reader.viewer.metrics
import eu.kanade.tachiyomi.ui.reader.viewer.metricsSince
import eu.kanade.tachiyomi.ui.reader.viewer.metricsTime
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR

/**
 * View of the ViewPager that contains a page of a chapter.
 */
@SuppressLint("ViewConstructor")
class PagerPageHolder(
    readerThemedContext: Context,
    val viewer: PagerViewer,
    val page: ReaderPage,
) : ReaderPageImageView(readerThemedContext), ViewPagerAdapter.PositionableView {

    /**
     * Item that identifies this view. Needed by the adapter to not recreate views.
     */
    override val item
        get() = page

    /**
     * Loading progress bar to indicate the current progress.
     */
    private var progressIndicator: ReaderProgressIndicator? = null // = ReaderProgressIndicator(readerThemedContext)

    /**
     * Error layout to show when the image fails to load.
     */
    private var errorLayout: ReaderErrorBinding? = null

    private val scope = MainScope()

    /**
     * Job for loading the page and processing changes to the page's status.
     */
    private var loadJob: Job? = null

    /**
     * Job for the colour of the margins, kept apart from [loadJob] so it cannot stand in the way
     * of the first pixels.
     */
    private var backgroundJob: Job? = null

    /**
     * The page's file, kept only while the page is on screen from prepared pixels. It is what
     * [promoteToFullDetail] hands to the view when the reader stops on the page, and it is let go only
     * when the view is detached: a page that is put back to its prepared pixels when the reader moves
     * on is brought up to the file again the next time they stop on it, and this is the only copy of
     * it the holder has. At most the pages on screen hold one.
     */
    private var preparedSource: BufferedSource? = null

    /**
     * Whether this page is on screen from pixels that came back from the cache rather than from a file
     * this holder has. Such a page wants the file exactly the way a prepared one does — it is on screen
     * from pixels sized for the screen, and the reader wants the file's own detail once they stop on
     * it — but the cache hit is what skipped reading the file, so it is not here. This is what says the
     * promotion has to read it back at that moment, rather than reach for a source this holder never
     * had. A page that was pushed off screen and is looked at again is always one of these: its holder
     * was detached, which is where the file it was prepared from is let go.
     */
    private var fromCache = false

    /**
     * Job for reading back the file of a page whose pixels came from the cache, kept as a job so it can
     * be let go when the reader moves on before the file is here — the same cancellation a queued
     * promotion gets.
     */
    private var promotionJob: Job? = null

    /**
     * Whether that job was stopped on purpose, because the reader moved on. A stopped read and a file
     * that cannot be read look the same to the coroutine that was doing one, and only the second is
     * worth saying something about.
     */
    private var promotionCancelled = false

    /**
     * When this holder was made, measured against when the image is drawn. A fresh one for a page
     * that was already on screen is a re-decode, which is what the log is for.
     */
    private val createdAt = metricsTime()

    init {
        metrics("holder", page, "")
        loadJob = scope.launch { loadPageAndProcessStatus() }
        // A double tap on a tap zone turns pages, so it must not be spent on zooming the image
        isDoubleTapZoomSuppressed = { event ->
            viewer.config.tapZonesImmediate && viewer.isTapZone(event)
        }
    }

    /**
     * Called when this view is detached from the window. Unsubscribes any active subscription.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        loadJob?.cancel()
        loadJob = null
        // Otherwise a page that was only looked at for a moment keeps a full bitmap alive
        backgroundJob?.cancel()
        backgroundJob = null
        // And the file it was prepared from, which is the larger of the two
        preparedSource = null
        // Including one that was still being read back for a page that is off screen now
        promotionJob?.let { job ->
            promotionCancelled = true
            job.cancel()
        }
        promotionJob = null
    }

    private fun initProgressIndicator() {
        if (progressIndicator == null) {
            progressIndicator = ReaderProgressIndicator(context)
            addView(progressIndicator)
        }
    }

    /**
     * Loads the page and processes changes to the page's status.
     *
     * Returns immediately if the page has no PageLoader.
     * Otherwise, this function does not return. It will continue to process status changes until
     * the Job is cancelled.
     */
    private suspend fun loadPageAndProcessStatus() {
        val loader = page.chapter.pageLoader ?: return

        supervisorScope {
            launch(Dispatchers.IO) {
                loader.loadPage(page)
            }
            page.statusFlow.collectLatest { state ->
                when (state) {
                    Page.State.Queue -> setQueued()
                    Page.State.LoadPage -> setLoading()
                    Page.State.DownloadImage -> {
                        setDownloading()
                        page.progressFlow.collectLatest { value ->
                            progressIndicator?.setProgress(value)
                        }
                    }
                    Page.State.Ready -> setImage()
                    is Page.State.Error -> setError(state.error)
                }
            }
        }
    }

    /**
     * Called when the page is queued.
     */
    private fun setQueued() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is loading.
     */
    private fun setLoading() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is downloading.
     */
    private fun setDownloading() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is ready.
     */
    /**
     * How the view is set up for a page. Read twice for a page that is prepared and then shown: the
     * prepared pixels and the file that replaces them are the same page in the same view, and a
     * second reading of the settings is cheaper than carrying the first one's `Config` along.
     */
    private fun imageConfig(): Config = Config(
        zoomDuration = viewer.config.doubleTapAnimDuration,
        minimumScaleType = viewer.config.imageScaleType,
        cropBorders = viewer.config.imageCropBorders,
        zoomStartPosition = viewer.config.imageZoomType,
        landscapeZoom = viewer.config.landscapeZoom,
    )

    private suspend fun setImage() {
        progressIndicator?.setProgress(0)

        val streamFn = page.stream ?: return

        try {
            val config = imageConfig()

            // Pixels for this page may already be decoded, by this holder or by an earlier one for a
            // page that was pushed off screen and is being looked at again. A half of a spread is
            // left out: it stands where the page it was made from stands, and shows the other half.
            val isSpreadHalf = page is InsertPage
            val cached =
                if (isSpreadHalf) {
                    null
                } else {
                    withContext(Dispatchers.Main) { viewer.bitmapCache.find(page) }
                }
            if (cached != null) {
                // The page is on screen from pixels that were made elsewhere, so it has no file of its
                // own to bring up to detail when the reader stops on it. That is what [fromCache] says,
                // and what makes the promotion read the file back at that moment.
                fromCache = true
                metrics("hit", page, "bytes=${cached.allocationByteCount}")
                withContext(Dispatchers.Main) {
                    setImage(cached, config)
                    removeErrorLayout()
                }
                // Same rule as the path below: with a fixed theme there is nothing to sample, and
                // a colour set here would be painted over the reader's. Pixels are kept only for
                // pages that are not animated, so there is no animated case to skip here.
                if (viewer.config.automaticBackground) {
                    showBackgroundFor(cached, null)
                }
                return
            }

            val started = metricsTime()
            val buffer = withContext(Dispatchers.IO) { streamFn().use { Buffer().readFrom(it) } }
            val bytes = buffer.size
            val read = metricsSince(started)

            // Splitting and merging the image are CPU work over the bytes that were just read
            val processedAt = metricsTime()
            val (source, isAnimated) = withContext(Dispatchers.Default) {
                val source = process(item, buffer)
                val isAnimated = ImageUtil.isAnimatedAndSupported(source)
                Pair(source, isAnimated)
            }
            val processed = metricsSince(processedAt)

            // The image is decoded here rather than by the view at layout time, which keeps the whole
            // of it off the main thread and, for a page that is only being given lead time, off the
            // turn. A page that cannot be decoded this way keeps the older path.
            // Only for a page that is not animated. A still decode takes the first frame, which is
            // all the user would ever see of a moving page, and the pixels kept for it are the same
            // first frame the animated view would throw away. Animated pages keep the file path,
            // where the animated view is the one holding the frames.
            // Only for a page away from the reading position. A page the reader is on is being asked
            // for now, so it has no lead time to spend and nothing to prepare ahead of the turn; it
            // takes the file path below, where the view tiles it as it lays out and keeps the file's
            // own detail under zoom. A page that is only being given lead time has several turns
            // before it is asked for, and is shown as soon as it is — and when the reader does stop
            // on one, [promoteToFullDetail] hands the view the file instead, so this rule only ever
            // holds for a page the reader jumped to without a holder ahead of the turn.
            val isLeadPage = !viewer.isCurrent(page)
            val decodedAt = metricsTime()
            val bitmap =
                if (isAnimated || !isLeadPage) {
                    null
                } else {
                    // The window's bound is on how much work runs at once, and memory is a different
                    // bound. A page whose file is over [BIG_DECODE_BYTES] inflates to a frame of a few
                    // hundred MB, and the app's heap is 512MB, so five of those at once is an
                    // allocation that cannot be made at all rather than a slow turn. The file's size is
                    // a proxy for the frame, not the frame: only the decoder knows the real size, and by
                    // then the frame is already allocated, so this decides how many such pages are
                    // decoded at a time, never whether one is.
                    val lane =
                        if (bytes > BIG_DECODE_BYTES) {
                            viewer.bigDecodeDispatcher
                        } else {
                            viewer.decodeDispatcher
                        }
                    withContext(lane) {
                        try {
                            decodeToBitmap(source, viewer.screenWidth, viewer.screenHeight)
                        } catch (e: Throwable) {
                            logcat(LogPriority.ERROR, e)
                            null
                        }
                    }
                }
            val decoded = metricsSince(decodedAt)

            var kept = false
            withContext(Dispatchers.Main) {
                if (bitmap != null) {
                    setImage(bitmap, config)
                    // The page is on screen from pixels sized for it, so the file is kept for the
                    // time it becomes the page being read (see [promoteToFullDetail])
                    preparedSource = source
                    if (!isSpreadHalf) {
                        kept = viewer.bitmapCache.store(page, bitmap)
                    }
                    // The rule above is read before the decode, not after it, so a page that became
                    // the one the reader is on while its pixels were still being prepared has no
                    // promotion coming to it from the turn — that moment has already passed. The
                    // promotion is asked for here as well, and it answers for itself when this is not
                    // the page being read.
                    promoteToFullDetail()
                } else {
                    setImage(source, isAnimated, config)
                }
                removeErrorLayout()
            }

            // The pixels the page ended up with, where they were made here rather than drawn from the
            // file: the bitmap is already in hand, so its size and its cost are free to say. `bytes=`
            // above is the file, `pixels=` is what is held in memory, and `kept=` is whether it is
            // in the cache for the next time this page is asked for.
            val pixels =
                if (bitmap == null) {
                    ""
                } else {
                    " dim=${bitmap.width}x${bitmap.height} pixels=${bitmap.allocationByteCount}"
                }
            // A half of a spread is neither looked up nor kept, so it has nothing to answer here
            val keptField =
                if (bitmap == null || isSpreadHalf) {
                    ""
                } else {
                    " kept=$kept"
                }

            // Up to the page being accepted, not to every tile of it being drawn
            metrics(
                "page",
                item,
                "read=$read process=$processed decode=$decoded total=${metricsSince(started)} bytes=$bytes$pixels$keptField",
            )

            // Only when the setting asks for it. A fixed theme colour is painted by the reader behind
            // the pages, and a sampled colour here would cover it and the setting would do nothing.
            if (!isAnimated && viewer.config.automaticBackground) {
                // Pixels already decoded are sampled directly; only a page that had to fall back to
                // the view's own decoding is sampled from the file again.
                showBackgroundFor(bitmap, if (bitmap != null) null else source.peek())
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            withContext(Dispatchers.Main) {
                setError(e)
            }
        }
    }

    /**
     * Called when this page becomes the one the reader is on, and it was on screen from prepared
     * pixels. Those pixels are all a prepared page will ever show: they are sized to the screen, so
     * zooming into them shows the pixels rather than more detail. Once the reader stops on the page
     * there is no more lead time to spend, so the file is handed to the view instead — the
     * subsampling view tiles it as it lays out and keeps the file's own detail at every zoom. The
     * swap is what the reader pays at the stop, not at the turn, and it is why a page that was only
     * passed through keeps its prepared pixels.
     *
     * Nothing is swapped when the reader has already moved on, or when this page was never prepared.
     * A page whose pixels came back from the cache is prepared in exactly the same way and wants the
     * same thing; it is only that its file was never read, which is what the cache hit skipped, so for
     * that one the file is read back here first. The prepared pixels stay in the cache either way:
     * scrolling back over this page is answered by them again, and it promotes again.
     */
    fun promoteToFullDetail() {
        // Read first, so the method is safe to call at either time a page can become the one being
        // read: a page that is not current keeps its file and is promoted when the reader reaches it.
        if (!viewer.isCurrent(page)) {
            return
        }
        val source = preparedSource
        if (source != null) {
            startPromotion(source)
            return
        }
        // No file in hand. Only a page that is on screen from cached pixels still wants one — a page
        // that took the file path is showing the file already, and a page that was prepared has one
        // above. The read is a job rather than this call, because the turn is on the UI thread and a
        // page's file is tens of MB to read and split.
        if (!fromCache || promotionJob != null) {
            return
        }
        val streamFn = page.stream ?: return
        promotionCancelled = false
        promotionJob = scope.launch {
            try {
                // The same two hops the miss path runs, so a page that was split or rotated to fit is
                // brought up to the half of it it is showing, not to the whole spread
                val buffer = withContext(Dispatchers.IO) { streamFn().use { Buffer().readFrom(it) } }
                val file = withContext(Dispatchers.Default) { process(item, buffer) }
                withContext(Dispatchers.Main) {
                    promotionJob = null
                    // Held for as long as the page is on screen, like a prepared page's is: the file is
                    // here for the next time the reader stops on it, and let go when the view is detached
                    preparedSource = file
                    // The reader may have moved on while the file was being read, and a page they left
                    // keeps its prepared pixels
                    if (viewer.isCurrent(page)) {
                        startPromotion(file)
                    }
                }
            } catch (e: Throwable) {
                promotionJob = null
                // A read that was stopped because the reader moved on is not a failure; one that could
                // not be read at all is, and is the only reason a page keeps its prepared pixels
                if (!promotionCancelled) {
                    logcat(LogPriority.ERROR, e)
                }
            }
        }
    }

    /**
     * Hands the view the file and says so. Split out because the file arrives at two moments: the one
     * a prepared page already has, and the one a page whose pixels came back from the cache has to
     * read back first.
     */
    private fun startPromotion(source: BufferedSource) {
        val from = viewer.bitmapCache.find(page)?.let { "from=${it.width}x${it.height}" } ?: ""
        promoteToFullDetail(source, imageConfig())
        metrics("promote", page, from)
    }

    /**
     * Called when this page stops being the one the reader is on, and it was brought up to the file's
     * own detail. The tiles over the file are the expensive thing a page holds here — for the measured
     * page, a pyramid over a 7000×5000 PNG, many times the prepared bitmap — and the reader only wants
     * that detail on the page they stopped on, so a page they have left goes back to the prepared
     * pixels. It is a swap of two views the holder already has, so it costs no decoding, and the page
     * can be brought up to the file again when the reader comes back to it.
     *
     * Nothing happens for a page that was never promoted. A page whose promotion is still loading has
     * nothing to swap back — the prepared view is still the page — so for that one the load is let go
     * instead, which is what keeps a fast scroll from queueing a full decode per page. A page whose
     * file is still being read back for it is let go the same way, before the read is spent.
     */
    fun demoteToPrepared() {
        // A file the reader is no longer asking for is not worth reading, and this is the moment they
        // stopped asking
        promotionJob?.let { job ->
            promotionCancelled = true
            job.cancel()
        }
        promotionJob = null
        if (demoteToCached()) {
            metrics("demote", page, "")
        } else if (cancelPromotion()) {
            metrics("cancel", page, "")
        }
    }

    /**
     * Works out the colour of the margins, kept apart from the page so it cannot stand in front of
     * the first pixels. Pixels that are already decoded are sampled by reading nine of them; a page
     * that had to fall back to the view's own decoding is sampled from the file, as it was before.
     *
     * Only called when the setting asks for sampled margins. A fixed theme colour belongs to the
     * reader behind the pages, and a background set here is drawn over it.
     */
    private fun showBackgroundFor(bitmap: Bitmap?, backgroundSource: BufferedSource?) {
        if (bitmap == null && backgroundSource == null) {
            return
        }
        backgroundJob = scope.launch {
            try {
                val drawable = withContext(Dispatchers.Default) {
                    if (bitmap != null) {
                        ImageUtil.chooseBackground(context, bitmap)
                    } else if (backgroundSource != null) {
                        ImageUtil.chooseBackground(context, backgroundSource.inputStream())
                    } else {
                        error("Nothing to sample the margins from")
                    }
                }
                withContext(Dispatchers.Main) {
                    pageBackground = drawable
                    applyPageBackground()
                    logPageBackground()
                }
            } catch (e: Throwable) {
                // A missing margin colour is not a missing page
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    /**
     * Says what colour the page area ended up with, so the letterbox can be read off the log.
     * `color=none` is a holder that paints nothing of its own, which is what a fixed theme should
     * look like: the reader's colour behind it is what the user sees. Two lines for one page, the
     * second one carrying a colour, is the margins repainting after the page appeared.
     */
    private fun logPageBackground() {
        val painted = pageBackground
        val color = when (painted) {
            is ColorDrawable -> painted.color.toString(16)
            else -> "none"
        }
        metrics("bg", page, "color=$color")
    }

    private fun process(page: ReaderPage, imageSource: BufferedSource): BufferedSource {
        if (viewer.config.dualPageRotateToFit) {
            return rotateDualPage(imageSource)
        }

        if (!viewer.config.dualPageSplit) {
            return imageSource
        }

        if (page is InsertPage) {
            return splitInHalf(imageSource)
        }

        val isDoublePage = ImageUtil.isWideImage(imageSource)
        if (!isDoublePage) {
            return imageSource
        }

        onPageSplit(page)

        return splitInHalf(imageSource)
    }

    private fun rotateDualPage(imageSource: BufferedSource): BufferedSource {
        val isDoublePage = ImageUtil.isWideImage(imageSource)
        return if (isDoublePage) {
            val rotation = if (viewer.config.dualPageRotateToFitInvert) -90f else 90f
            ImageUtil.rotateImage(imageSource, rotation)
        } else {
            imageSource
        }
    }

    private fun splitInHalf(imageSource: BufferedSource): BufferedSource {
        var side = when {
            viewer is L2RPagerViewer && page is InsertPage -> ImageUtil.Side.RIGHT
            viewer !is L2RPagerViewer && page is InsertPage -> ImageUtil.Side.LEFT
            viewer is L2RPagerViewer && page !is InsertPage -> ImageUtil.Side.LEFT
            viewer !is L2RPagerViewer && page !is InsertPage -> ImageUtil.Side.RIGHT
            else -> error("We should choose a side!")
        }

        if (viewer.config.dualPageInvert) {
            side = when (side) {
                ImageUtil.Side.RIGHT -> ImageUtil.Side.LEFT
                ImageUtil.Side.LEFT -> ImageUtil.Side.RIGHT
            }
        }

        return ImageUtil.splitInHalf(imageSource, side)
    }

    private fun onPageSplit(page: ReaderPage) {
        val newPage = InsertPage(page)
        viewer.onPageSplit(page, newPage)
    }

    /**
     * Called when the page has an error.
     */
    private fun setError(error: Throwable?) {
        progressIndicator?.hide()
        showErrorLayout(error)
    }

    override fun onImageLoaded() {
        super.onImageLoaded()
        logPageBackground()
        metrics("shown", item, "total=${metricsSince(createdAt)}")
        progressIndicator?.hide()
    }

    /**
     * Called when an image fails to decode.
     */
    override fun onImageLoadError(error: Throwable?) {
        super.onImageLoadError(error)
        setError(error)
    }

    /**
     * Called when an image is zoomed in/out.
     */
    override fun onScaleChanged(newScale: Float) {
        super.onScaleChanged(newScale)
        viewer.activity.hideMenu()
    }

    private fun showErrorLayout(error: Throwable?): ReaderErrorBinding {
        if (errorLayout == null) {
            errorLayout = ReaderErrorBinding.inflate(LayoutInflater.from(context), this, true)
            errorLayout?.actionRetry?.viewer = viewer
            errorLayout?.actionRetry?.setOnClickListener {
                page.chapter.pageLoader?.retryPage(page)
            }
        }

        val imageUrl = page.imageUrl
        errorLayout?.actionOpenInWebView?.isVisible = imageUrl != null
        if (imageUrl != null) {
            if (imageUrl.startsWith("http", true)) {
                errorLayout?.actionOpenInWebView?.viewer = viewer
                errorLayout?.actionOpenInWebView?.setOnClickListener {
                    val sourceId = viewer.activity.viewModel.manga?.source

                    val intent = WebViewActivity.newIntent(context, imageUrl, sourceId)
                    context.startActivity(intent)
                }
            }
        }

        errorLayout?.errorMessage?.text = with(context) { error?.formattedMessage }
            ?: context.stringResource(MR.strings.decode_image_error)

        errorLayout?.root?.isVisible = true
        return errorLayout!!
    }

    /**
     * Removes the decode error layout from the holder, if found.
     */
    private fun removeErrorLayout() {
        errorLayout?.root?.isVisible = false
        errorLayout = null
    }
}
