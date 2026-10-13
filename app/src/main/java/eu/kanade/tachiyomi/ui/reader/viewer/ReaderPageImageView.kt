package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import androidx.annotation.AttrRes
import androidx.annotation.CallSuper
import androidx.annotation.StyleRes
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.os.postDelayed
import androidx.core.view.isVisible
import coil3.asDrawable
import coil3.dispose
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.ViewSizeResolver
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.EASE_IN_OUT_QUAD
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.EASE_OUT_QUAD
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
import com.github.chrisbanes.photoview.PhotoView
import eu.kanade.tachiyomi.data.coil.ImageDecoder
import eu.kanade.tachiyomi.data.coil.cropBorders
import eu.kanade.tachiyomi.data.coil.webtoonStrips
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonStripImageView
import eu.kanade.tachiyomi.util.system.animatorDurationScale
import eu.kanade.tachiyomi.util.view.isVisibleOnScreen
import okio.BufferedSource

/**
 * A wrapper view for showing page image.
 *
 * Animated image will be drawn by [PhotoView] while [SubsamplingScaleImageView] will take non-animated image.
 *
 * @param isWebtoon if true, [WebtoonStripImageView] will be used instead of [SubsamplingScaleImageView]
 * and [AppCompatImageView] will be used instead of [PhotoView]
 */
open class ReaderPageImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttrs: Int = 0,
    @StyleRes defStyleRes: Int = 0,
    private val isWebtoon: Boolean = false,
) : FrameLayout(context, attrs, defStyleAttrs, defStyleRes) {

    private var pageView: View? = null

    /**
     * A second still view, drawn over the first, that a page is brought up to the file's own detail
     * in.
     *
     * A subsampling view drops whatever it was drawing the moment it is handed a new image, so
     * promoting a page in the view that is drawing it shows the page as nothing for as long as the
     * new source loads. Loading beside it and swapping the two over when it is ready is what keeps the
     * page on screen. It exists only while that load runs, and is let go when it is swapped in, when
     * it fails, and in [recycle].
     */
    private var detailView: SubsamplingScaleImageView? = null

    /**
     * The view a promoted page was showing before it was brought up to the file, kept aside while the
     * file's own detail is on screen so the page can be put back when the reader moves on.
     *
     * It is not recycled at the swap because its pixels are the prepared ones the reader wants again
     * the moment they stop on this page, and it is the cheap half: a bitmap the page cache already
     * holds, against the tile pyramid over the file, which is what letting go of the detail view is
     * for. Non-null is what says a page is currently showing the file, so it is also the flag for
     * whether there is anything to put back.
     */
    private var previewView: SubsamplingScaleImageView? = null

    private var stripRequest: Disposable? = null

    private var config: Config? = null

    var onImageLoaded: (() -> Unit)? = null
    var onImageLoadError: ((Throwable?) -> Unit)? = null
    var onScaleChanged: ((newScale: Float) -> Unit)? = null
    var onViewClicked: (() -> Unit)? = null

    /**
     * For automatic background. Will be set as background color when [onImageLoaded] is called.
     */
    var pageBackground: Drawable? = null

    /**
     * Whether an image is drawn here. The viewers ask it when an item becomes the current one, to
     * know whether the user would have seen an empty page.
     */
    var isImageShown = false

    /**
     * Whether a touch on the given on down event landed where the reader wants a tap, in which
     * case a double tap must not zoom the image. Set by the page holder, the only one that knows
     * where its tap zones are.
     */
    var isDoubleTapZoomSuppressed: ((MotionEvent) -> Boolean)? = null

    /**
     * Whether zoom is turned off for the touch in progress, to be restored when it ends.
     */
    private var isZoomSuppressed = false

    @CallSuper
    open fun onImageLoaded() {
        onImageLoaded?.invoke()
        isImageShown = true
        applyPageBackground()
    }

    /**
     * Paints the page background behind the image. [onImageLoaded] does it for the common case,
     * where the background is already known by the time the image is drawn; a holder that worked it
     * out afterwards calls this once it is, so the margins repaint after the page appeared.
     */
    fun applyPageBackground() {
        background = pageBackground
    }

    @CallSuper
    open fun onImageLoadError(error: Throwable?) {
        onImageLoadError?.invoke(error)
    }

    @CallSuper
    open fun onScaleChanged(newScale: Float) {
        onScaleChanged?.invoke(newScale)
    }

    @CallSuper
    open fun onViewClicked() {
        onViewClicked?.invoke()
    }

    open fun onPageSelected(forward: Boolean) {
        with(pageView as? SubsamplingScaleImageView) {
            if (this == null) return
            if (isReady) {
                landscapeZoom(forward)
            } else {
                setOnImageEventListener(
                    object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                        override fun onReady() {
                            setupZoom(config)
                            landscapeZoom(forward)
                            this@ReaderPageImageView.onImageLoaded()
                        }

                        override fun onImageLoadError(e: Exception) {
                            onImageLoadError(e)
                        }
                    },
                )
            }
        }
    }

    private fun SubsamplingScaleImageView.landscapeZoom(forward: Boolean) {
        if (
            config != null &&
            config!!.landscapeZoom &&
            config!!.minimumScaleType == SCALE_TYPE_CENTER_INSIDE &&
            sWidth > sHeight &&
            scale == minScale
        ) {
            handler?.postDelayed(500) {
                val point = when (config!!.zoomStartPosition) {
                    ZoomStartPosition.LEFT -> if (forward) PointF(0F, 0F) else PointF(sWidth.toFloat(), 0F)
                    ZoomStartPosition.RIGHT -> if (forward) PointF(sWidth.toFloat(), 0F) else PointF(0F, 0F)
                    ZoomStartPosition.CENTER -> center
                }

                val targetScale = height.toFloat() / sHeight.toFloat()
                animateScaleAndCenter(targetScale, point)!!
                    .withDuration(500)
                    .withEasing(EASE_IN_OUT_QUAD)
                    .withInterruptible(true)
                    .start()
            }
        }
    }

    fun setImage(drawable: Drawable, config: Config) {
        this.config = config
        if (drawable is Animatable) {
            prepareAnimatedImageView()
            setAnimatedImage(drawable, config)
        } else {
            prepareNonAnimatedImageView()
            setNonAnimatedImage(drawable, config)
        }
    }

    /**
     * Shows pixels that were decoded already. The image is handed the pixels rather than the file, so
     * nothing is decoded a second time to draw it; what is given up is the library's own decoding,
     * which can draw a page in pieces and at more detail than the pixels hold once zoomed into.
     *
     * Only for a page that is not animated, which is the only kind the reader keeps pixels for.
     */
    fun setImage(bitmap: Bitmap, config: Config) {
        this.config = config
        prepareNonAnimatedImageView()
        setNonAnimatedImage(bitmap, config)
    }

    fun setImage(source: BufferedSource, isAnimated: Boolean, config: Config) {
        this.config = config
        if (isAnimated) {
            prepareAnimatedImageView()
            setAnimatedImage(source, config)
        } else if (isWebtoon) {
            prepareStripImageView()
            setStripImage(source, config)
        } else {
            prepareNonAnimatedImageView()
            setNonAnimatedImage(source, config)
        }
    }

    fun recycle() {
        isImageShown = false
        stripRequest?.dispose()
        stripRequest = null
        // A promotion that was still loading is dropped, not left waiting for a page that is gone
        detailView?.let {
            it.recycle()
            removeView(it)
        }
        detailView = null
        // And the prepared view that was kept out of the tree while a promoted page was on screen.
        // It is only ever out of the tree, so there is nothing to remove here.
        previewView?.let {
            it.recycle()
        }
        previewView = null
        pageView?.let {
            when (it) {
                is SubsamplingScaleImageView -> it.recycle()
                is AppCompatImageView -> it.dispose()
                is WebtoonStripImageView -> it.recycle()
            }
            it.isVisible = false
        }
    }

    /**
     * Check if the image can be panned to the left
     */
    fun canPanLeft(): Boolean = canPan { it.left }

    /**
     * Check if the image can be panned to the right
     */
    fun canPanRight(): Boolean = canPan { it.right }

    /**
     * Check whether the image can be panned.
     * @param fn a function that returns the direction to check for
     */
    private fun canPan(fn: (RectF) -> Float): Boolean {
        (pageView as? SubsamplingScaleImageView)?.let { view ->
            RectF().let {
                view.getPanRemaining(it)
                return fn(it) > 1
            }
        }
        return false
    }

    /**
     * Pans the image to the left by a screen's width worth.
     */
    fun panLeft() {
        pan { center, view -> center.also { it.x -= view.width / view.scale } }
    }

    /**
     * Pans the image to the right by a screen's width worth.
     */
    fun panRight() {
        pan { center, view -> center.also { it.x += view.width / view.scale } }
    }

    /**
     * Pans the image.
     * @param fn a function that computes the new center of the image
     */
    private fun pan(fn: (PointF, SubsamplingScaleImageView) -> PointF) {
        (pageView as? SubsamplingScaleImageView)?.let { view ->

            val target = fn(view.center ?: return, view)
            view.animateCenter(target)!!
                .withEasing(EASE_OUT_QUAD)
                .withDuration(250)
                .withInterruptible(true)
                .start()
        }
    }

    /**
     * Turns zoom off on the image for the whole of a touch that started on a tap zone, so fast
     * tapping there only turns pages and never zooms. The flag has to be set before the event is
     * passed on, because the image reads it while handling that very same event.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (isDoubleTapZoomSuppressed?.invoke(ev) == true) {
                    (pageView as? SubsamplingScaleImageView)?.let { view ->
                        view.setZoomEnabled(false)
                        isZoomSuppressed = true
                    }
                }
            }
            // A second finger means it's not a tap, so there's nothing left to suppress
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isZoomSuppressed) {
                    (pageView as? SubsamplingScaleImageView)?.let { view ->
                        view.setZoomEnabled(true)
                    }
                    isZoomSuppressed = false
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * A view that draws a still page, with the touch behaviour the reader expects of one. Made twice:
     * for the page itself, and for the file's own detail beside it (see
     * [promoteToFullDetail]) — the second one is zoomed and panned by the reader just like the first,
     * so it is the same view, not a lighter one.
     */
    private fun newSubsamplingImageView() = SubsamplingScaleImageView(context).apply {
        setDoubleTapZoomStyle(SubsamplingScaleImageView.ZOOM_FOCUS_CENTER)
        setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_INSIDE)
        setMinimumTileDpi(180)
        setOnStateChangedListener(
            object : SubsamplingScaleImageView.OnStateChangedListener {
                override fun onScaleChanged(newScale: Float, origin: Int) {
                    this@ReaderPageImageView.onScaleChanged(newScale)
                }

                override fun onCenterChanged(newCenter: PointF?, origin: Int) {
                    // Not used
                }
            },
        )
        setOnClickListener { this@ReaderPageImageView.onViewClicked() }
    }

    private fun prepareNonAnimatedImageView() {
        if (pageView is SubsamplingScaleImageView) return
        removeView(pageView)

        pageView = newSubsamplingImageView()
        addView(pageView, MATCH_PARENT, MATCH_PARENT)
    }

    private fun SubsamplingScaleImageView.setupZoom(config: Config?) {
        // 5x zoom
        maxScale = scale * MAX_ZOOM_SCALE
        setDoubleTapZoomScale(scale * 2)

        when (config?.zoomStartPosition) {
            ZoomStartPosition.LEFT -> setScaleAndCenter(scale, PointF(0F, 0F))
            ZoomStartPosition.RIGHT -> setScaleAndCenter(scale, PointF(sWidth.toFloat(), 0F))
            ZoomStartPosition.CENTER -> setScaleAndCenter(scale, center)
            null -> {}
        }
    }

    /**
     * The zoom, scale and crop settings of a still page, applied to whichever view is drawing it.
     * The listener for "the image is here" is not part of this: the two callers answer it differently,
     * one by showing a page and one by swapping a page over.
     */
    private fun SubsamplingScaleImageView.applyImageConfig(config: Config) {
        setDoubleTapZoomDuration(config.zoomDuration.getSystemScaledDuration())
        setMinimumScaleType(config.minimumScaleType)
        setMinimumDpi(1) // Just so that very small image will be fit for initial load
        setCropBorders(config.cropBorders)
    }

    private fun setNonAnimatedImage(
        data: Any,
        config: Config,
    ) = (pageView as? SubsamplingScaleImageView)?.apply {
        applyImageConfig(config)
        setOnImageEventListener(
            object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                override fun onReady() {
                    setupZoom(config)
                    if (isVisibleOnScreen()) landscapeZoom(true)
                    this@ReaderPageImageView.onImageLoaded()
                }

                override fun onImageLoadError(e: Exception) {
                    this@ReaderPageImageView.onImageLoadError(e)
                }
            },
        )

        when (data) {
            is Bitmap -> {
                setImage(ImageSource.bitmap(data))
                isVisible = true
            }
            is BitmapDrawable -> {
                setImage(ImageSource.bitmap(data.bitmap))
                isVisible = true
            }
            is BufferedSource -> {
                setImage(ImageSource.inputStream(data.inputStream()))
                isVisible = true
            }
            else -> {
                throw IllegalArgumentException("Not implemented for class ${data::class.simpleName}")
            }
        }
    }

    /**
     * Brings the page up to the file's own detail without a black window. The view that is drawing it
     * is left alone: a second view is added over it and given the file, and the two are swapped over
     * when it is ready — the page the reader stopped on keeps what it had until the file is on screen.
     * The second view paints nothing until it has tiles, so nothing the reader can see changes before
     * the swap.
     *
     * Only a page already drawn by a still view can be promoted; anything else has nothing prepared to
     * bring up. A page that cannot be read from the file again keeps what it was showing.
     */
    fun promoteToFullDetail(source: BufferedSource, config: Config) {
        if (pageView !is SubsamplingScaleImageView) return
        // A page that already has a detail view loading has one. A second would leave the first out of
        // every field, so it would never be swapped in, never be removed from the tree and never be
        // recycled — a whole tile pyramid held for nothing.
        if (detailView != null) return

        val detail = newSubsamplingImageView()
        detailView = detail
        detail.applyImageConfig(config)
        detail.setOnImageEventListener(
            object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                override fun onReady() {
                    val loaded = detailView ?: return
                    val preview = pageView as? SubsamplingScaleImageView ?: return
                    // The swap happened: the file is on screen and what was is taken away in the same
                    // moment. No page id — the image view does not know the page — so this line is read
                    // against the holder's `act=promote` for the same page by what surrounds it, and the
                    // holder logs `act=shown` for the page right after it.
                    metrics("swap", null, "")
                    loaded.setupZoom(config)
                    loaded.isVisible = true
                    // The prepared view is taken out of the tree but kept, not recycled: it is what
                    // this page goes back to when the reader moves on, and its pixels are the cache's
                    // anyway. `previewView` being set is what says the page is on the file's detail.
                    removeView(preview)
                    previewView = preview
                    pageView = loaded
                    detailView = null
                    this@ReaderPageImageView.onImageLoaded()
                }

                override fun onImageLoadError(e: Exception) {
                    // The file could not be read again. Without this line the promotion is silent: the
                    // preview stays up and nothing says a swap was tried and failed, which is how a page
                    // that never gets its detail looks the same as one that was never promoted.
                    metrics("swapfail", null, "err=${e::class.simpleName} msg=${e.message}")
                    // A page that cannot be read from the file again keeps what it was showing
                    detailView?.let {
                        removeView(it)
                        it.recycle()
                    }
                    detailView = null
                }
            },
        )
        addView(detail, MATCH_PARENT, MATCH_PARENT)
        // The view is left drawable rather than hidden: the library reads the file when it is first
        // drawn, and a view that is never drawn is never read, so an invisible one would sit out its
        // whole load and answer neither listener. It paints nothing until it has tiles, which is what
        // keeps the preview on screen for the length of the load and makes the swap at `onReady` the
        // first thing the reader sees change.
        // The source is read through `peek()`, which leaves the one the holder kept whole: a page that
        // is put back to its prepared pixels is promoted again the next time the reader stops on it,
        // and a source the first promotion consumed would answer the second with nothing at all.
        detail.setImage(ImageSource.inputStream(source.peek().inputStream()))
    }

    /**
     * Puts a page back to what it was prepared at, for the reader has moved on from it.
     *
     * The mirror of [promoteToFullDetail], and the thing that keeps the reader's position affordable:
     * the tiles over the file are the expensive part here — for the measured page, a pyramid over a
     * 7000×5000 PNG — and only the page the reader stopped on wants that detail. The prepared view is
     * put back rather than rebuilt, so this is a swap and no decoding.
     *
     * Answers whether anything was put back, so a holder that was never promoted says nothing.
     */
    fun demoteToCached(): Boolean {
        val preview = previewView ?: return false
        val detail = pageView ?: return false

        removeView(detail)
        (detail as? SubsamplingScaleImageView)?.recycle()
        addView(preview, MATCH_PARENT, MATCH_PARENT)
        preview.isVisible = true
        pageView = preview
        previewView = null
        return true
    }

    /**
     * Lets go of a page's detail that is still loading, for the reader has moved on from the page it
     * was for.
     *
     * A promotion that has not swapped yet has no prepared view kept aside — the preview is still the
     * page — so there is nothing to swap back and the only thing to do is stop the load. Removing the
     * view from the tree is what stops it: the library reads a file when it is first drawn, so a view
     * that is not drawn has no decode left in it. This is what keeps a fast scroll from queueing a
     * full decode per page, which is what runs the app out of heap.
     *
     * Answers whether there was one to let go of.
     */
    fun cancelPromotion(): Boolean {
        val detail = detailView ?: return false
        removeView(detail)
        detail.recycle()
        detailView = null
        return true
    }

    private fun prepareStripImageView() {
        if (pageView is WebtoonStripImageView) return
        removeView(pageView)

        pageView = WebtoonStripImageView(context)
        addView(pageView, MATCH_PARENT, WRAP_CONTENT)
    }

    private fun setStripImage(data: BufferedSource, config: Config) {
        val view = pageView as? WebtoonStripImageView ?: return
        view.onReload = { requestStrips(view, data, config, isReload = true) }
        requestStrips(view, data, config, isReload = false)
    }

    private fun requestStrips(view: WebtoonStripImageView, data: BufferedSource, config: Config, isReload: Boolean) {
        // A recycled view is reused for another page, where a request still running for the old one must not land
        stripRequest?.dispose()
        stripRequest = ImageRequest.Builder(context)
            // Peeking leaves the data whole for decoding again after the strips were freed off screen
            .data(data.peek())
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .target(
                onSuccess = { result ->
                    view.setImage(result as ImageDecoder.StripsImage)
                    if (!isReload) {
                        view.isVisible = true
                        onImageLoaded()
                    }
                },
            )
            .listener(
                onError = { _, result ->
                    onImageLoadError(result.throwable)
                },
            )
            .size(ViewSizeResolver(this))
            .precision(Precision.INEXACT)
            .cropBorders(config.cropBorders)
            .webtoonStrips(true)
            .crossfade(false)
            .build()
            .let(context.imageLoader::enqueue)
    }

    private fun prepareAnimatedImageView() {
        if (pageView is AppCompatImageView) return
        removeView(pageView)

        pageView = if (isWebtoon) {
            AppCompatImageView(context)
        } else {
            PhotoView(context)
        }.apply {
            adjustViewBounds = true

            if (this is PhotoView) {
                setScaleLevels(1F, 2F, MAX_ZOOM_SCALE)
                // Force 2 scale levels on double tap
                setOnDoubleTapListener(
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onDoubleTap(e: MotionEvent): Boolean {
                            if (this@ReaderPageImageView.isDoubleTapZoomSuppressed?.invoke(e) == true) {
                                return true
                            }
                            if (scale > 1F) {
                                setScale(1F, e.x, e.y, true)
                            } else {
                                setScale(2F, e.x, e.y, true)
                            }
                            return true
                        }

                        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                            this@ReaderPageImageView.onViewClicked()
                            return super.onSingleTapConfirmed(e)
                        }
                    },
                )
                setOnScaleChangeListener { _, _, _ ->
                    this@ReaderPageImageView.onScaleChanged(scale)
                }
            }
        }
        addView(pageView, MATCH_PARENT, MATCH_PARENT)
    }

    private fun setAnimatedImage(
        data: Any,
        config: Config,
    ) = (pageView as? AppCompatImageView)?.apply {
        if (this is PhotoView) {
            setZoomTransitionDuration(config.zoomDuration.getSystemScaledDuration())
        }

        val request = ImageRequest.Builder(context)
            .data(data)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .target(
                onSuccess = { result ->
                    val drawable = result.asDrawable(context.resources)
                    setImageDrawable(drawable)
                    (drawable as? Animatable)?.start()
                    isVisible = true
                    this@ReaderPageImageView.onImageLoaded()
                },
            )
            .listener(
                onError = { _, result ->
                    onImageLoadError(result.throwable)
                },
            )
            .crossfade(false)
            .build()
        context.imageLoader.enqueue(request)
    }

    private fun Int.getSystemScaledDuration(): Int {
        return (this * context.animatorDurationScale).toInt().coerceAtLeast(1)
    }

    /**
     * All of the config except [zoomDuration] will only be used for non-animated image.
     */
    data class Config(
        val zoomDuration: Int,
        val minimumScaleType: Int = SCALE_TYPE_CENTER_INSIDE,
        val cropBorders: Boolean = false,
        val zoomStartPosition: ZoomStartPosition = ZoomStartPosition.CENTER,
        val landscapeZoom: Boolean = false,
    )

    enum class ZoomStartPosition {
        LEFT,
        CENTER,
        RIGHT,
    }
}

private const val MAX_ZOOM_SCALE = 5F
