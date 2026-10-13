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
    private suspend fun setImage() {
        progressIndicator?.setProgress(0)

        val streamFn = page.stream ?: return

        try {
            val config = Config(
                zoomDuration = viewer.config.doubleTapAnimDuration,
                minimumScaleType = viewer.config.imageScaleType,
                cropBorders = viewer.config.imageCropBorders,
                zoomStartPosition = viewer.config.imageZoomType,
                landscapeZoom = viewer.config.landscapeZoom,
            )

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
            val decodedAt = metricsTime()
            val bitmap =
                if (isAnimated) {
                    null
                } else {
                    withContext(Dispatchers.Default) {
                        try {
                            decodeToBitmap(source)
                        } catch (e: Throwable) {
                            logcat(LogPriority.ERROR, e)
                            null
                        }
                    }
                }
            val decoded = metricsSince(decodedAt)

            withContext(Dispatchers.Main) {
                if (bitmap != null) {
                    setImage(bitmap, config)
                    if (!isSpreadHalf) {
                        viewer.bitmapCache.store(page, bitmap)
                    }
                } else {
                    setImage(source, isAnimated, config)
                }
                removeErrorLayout()
            }

            // Up to the page being accepted, not to every tile of it being drawn
            metrics(
                "page",
                item,
                "read=$read process=$processed decode=$decoded total=${metricsSince(started)} bytes=$bytes",
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
