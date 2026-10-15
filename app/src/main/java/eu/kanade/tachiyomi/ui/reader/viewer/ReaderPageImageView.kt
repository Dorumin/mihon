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
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
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
     * What the still view up here was given, named for the log: the pixels a page was prepared at and
     * the file's own detail are not the same cost, and a holder that leaves the window with either of
     * them up is one whose pixels nothing let go of. Set where the image is set, read only for the log.
     */
    private var imageFrom = "none"

    /**
     * When a load this view started, so the load's own cost can be said at whichever end answers it.
     *
     * The view's own decoding is the other way a page gets pixels — the file path, where the
     * subsampling view reads and tiles the file as it lays out — and until now its cost was only
     * inferable from the holder's `shown` line, which is why the two paths could not be compared
     * against each other. Set only where a file is handed over: a page set from pixels that were made
     * already has no load here to time, and says nothing rather than a zero that would read as a fast
     * decode.
     */
    private var loadStartedAt = 0L

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
     * Which page this view was made for, set by the holder for the same reason as
     * [isDoubleTapZoomSuppressed]: the view is not given one, and a zoom line that carries no page
     * cannot be read against the holder's lines, because two pages of the same size report the same
     * fit. Only the zoom log needs it, and only for reading a log back, so it stays here rather than
     * becoming part of what a view knows.
     */
    var zoomPage: ReaderPage? = null

    /**
     * Which of the two views this one is drawing with, for the zoom log: the page's own prepared
     * pixels, or the file's own detail swapped in over them. The two have different fits — one is the
     * viewport, the other the file — and a reader's gesture reaches whichever is on screen, so a line
     * that does not say which cannot be compared against the other.
     */
    private var zoomSubject = "page"

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
        // What was let go here, read before anything is dropped. This is the only place that says a
        // page's image was let go at all, and the holder's detach line is what says a page left the
        // window; the two are read together to count how many images are alive at once. The webtoon
        // adapter calls this for every holder it recycles; the pager path calls it only for a page
        // whose image is over a file and is not being read any more, and leaves a prepared or
        // promoted page alone because its pixels belong to the cache — see [imageReleasableOnDetach].
        // A detached page with no line here is one whose pixels only the collector will free.
        val held = heldImageView()
        val detail = detailView != null
        val preview = previewView != null
        // A release that answers true here is a view let go while its image was still being read,
        // which is the use-after-free case rather than a free
        val loading = imageLoadRunning()
        metrics("release", zoomPage, "view=$held loading=$loading detail=$detail preview=$preview")
        isImageShown = false
        stripRequest?.dispose()
        stripRequest = null
        // A promotion that was still loading is dropped, not left waiting for a page that is gone.
        // Out of the tree only, and not recycled: `recycle()` gives back the file and the pixels, and
        // a load that has started is a native read out of the one and a native write into the other.
        // Either half is what makes recycling mid-load a use-after-free rather than an error; the task
        // holds the view, so both are given back when it is done, and closing the file here is only
        // ever a free ahead of that work — which is what a page that was mid-load when the reader
        // moved on crashes on.
        detailView?.let {
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
        imageFrom = "none"
    }

    /**
     * What kind of image view is up here and what it was given, named for the holder's detach line:
     * the holder is what knows the page, this is what knows what it holds. A holder that answers
     * anything other than "none" as it leaves the window, and whose line says it could not be let go,
     * is a page whose pixels only the collector will free.
     */
    fun heldImageView(): String = when (pageView) {
        is SubsamplingScaleImageView -> imageFrom
        is AppCompatImageView -> "anim"
        is WebtoonStripImageView -> "strips"
        null -> "none"
        else -> "other"
    }

    /**
     * Whether an image this view is drawing is still being read, which is what says whether the view
     * can be let go at all. `recycle()` gives back the file and the pixels, and the read is a native
     * write into those pixels: recycling a view whose load has started is a use-after-free rather than
     * an error, which is why the places that drop a view mid-load take it out of the tree only.
     *
     * A page that failed to decode answers true as well — a view that never became ready has no load
     * left in it, but nothing can tell the two apart from here.
     */
    fun imageLoadRunning(): Boolean {
        // A second view that exists at all is one that was made to load a file beside the page
        if (detailView != null) return true
        val view = pageView ?: return false
        return when (view) {
            is SubsamplingScaleImageView -> !view.isReady()
            else -> false
        }
    }

    /**
     * Whether the image this view is up is this view's alone to give back, which is what says whether
     * a holder may call [recycle] as it leaves the window. Two answers, both about whose hands the
     * pixels are in:
     *
     * - a view that is on screen from prepared pixels is drawing a bitmap the page cache holds, and
     *   a page that was brought up to the file keeps that view aside in [previewView], whose bitmap
     *   is the cache's too. Recycling either gives pixels back that are not this holder's to give,
     *   and the cache's own check for a recycled bitmap then drops the page — which is what scrolling
     *   back over a page that was pushed off screen depends on.
     * - a view whose image is still being read cannot be touched at all, for the reason in
     *   [imageLoadRunning].
     *
     * Asked where it is asked rather than carried from an earlier reading: what a holder stops as it
     * detaches is what can change the answer.
     */
    fun imageReleasableOnDetach(): Boolean {
        if (previewView != null) return false
        if (imageFrom != "file") return false
        return !imageLoadRunning()
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
        // Whether the taps of a double tap reached this view at all, and where they landed. A lost
        // double tap is told from a refused one here: a refused one reaches the image and logs a run
        // of `act=zoom` that ends back at its own `fit=`, and a lost one logs no such run, so the
        // pair of events is what separates the two. A move is left out — it fires every frame of a
        // pan and says nothing about whether a tap was seen.
        val suppressed = isDoubleTapZoomSuppressed?.invoke(ev) == true
        // Only the events that say a finger went down or left: a move fires every frame of a pan, and
        // the masked action never reports a second finger down, so `fingers=` is what tells a tap from
        // the start of a pinch.
        if (
            ev.actionMasked == MotionEvent.ACTION_DOWN ||
                ev.actionMasked == MotionEvent.ACTION_UP ||
                ev.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            // What the view that would receive this touch says of itself, which is what the library
            // checks before it will accept a double tap: it aborts the touch when the image is not
            // ready, and it only starts a quick scale when zoom and quick scale are both on. A view
            // that answers a tap and no other does is told apart here.
            val target = pageView as? SubsamplingScaleImageView
            metrics(
                "tap",
                zoomPage,
                "who=$zoomSubject action=${touchActionName(ev.actionMasked)} fingers=${ev.pointerCount} at=${ev.x.toInt()}x${ev.y.toInt()} sup=$suppressed" +
                    if (target != null) {
                        " ready=${target.isReady()} zoom=${target.isZoomEnabled()} quick=${target.isQuickScaleEnabled()}"
                    } else {
                        " none"
                    },
            )
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (suppressed) {
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
     * What a touch event was, named for the log. A double tap is two of these, and the pair is what
     * says whether the gesture was lost or refused, so the events are read by hand. Note that the
     * masked action never reports a second finger down — that is only in the raw action — which is
     * what `fingers=` is for: a one that stays at 1 is a tap, a two that reaches 2 is a pinch.
     */
    private fun touchActionName(action: Int) = when (action) {
        MotionEvent.ACTION_DOWN -> "down"
        MotionEvent.ACTION_UP -> "up"
        MotionEvent.ACTION_CANCEL -> "cancel"
        else -> "$action"
    }

    /**
     * What started a scale change, named for the log rather than left as the library's number: a
     * report of zoom events is read by hand, and which gesture was refused is the whole question. The
     * library reports a double tap as 4 because its own gesture detector answers the double tap and
     * then animates, so a double tap never reaches the touch path that would report 2 — which is why
     * a run of pinches shows up as no lines at all, and an absence cannot be told from a refusal.
     */
    private fun zoomOriginName(origin: Int) = when (origin) {
        SubsamplingScaleImageView.ORIGIN_DOUBLE_TAP_ZOOM -> "doubletap"
        SubsamplingScaleImageView.ORIGIN_TOUCH -> "pinch"
        SubsamplingScaleImageView.ORIGIN_FLING -> "fling"
        SubsamplingScaleImageView.ORIGIN_ANIM -> "anim"
        else -> "$origin"
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
                    // The limits as the view holds them at the moment the reader asked, which is what
                    // decides whether the gesture was allowed at all: `minScale()` is the fit for the
                    // source this view holds, and `maxScale` is the ceiling written when the image
                    // reported ready. A double tap whose target is at or below the current scale
                    // divided by 0.9 is answered by zooming *out*, so a page that looks like it will
                    // not zoom at all reads as this line repeating with `to=` unchanged.
                    metrics("zoom", zoomPage, "who=$zoomSubject to=$newScale fit=$minScale max=$maxScale by=${zoomOriginName(origin)}")
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
        // The ceiling is a multiple of the fit for the source this view holds, not of the scale the
        // view happens to be at: the library's `maxScale` is a plain absolute it never derives from
        // the file, and its own floor is the fit — the scale at which the source just fills the
        // viewport — which is what `minScale()` answers. A view set up for the first time is at that
        // fit when this runs, so the two readings agree; a view that is set up again while it still
        // holds a zoom from an earlier visit would otherwise take a multiple of that zoom, and each
        // visit would compound it.
        val fit = minScale
        metrics("zoomset", zoomPage, "who=$zoomSubject fit=$fit max=${fit * MAX_ZOOM_SCALE} tap=${fit * 2}")
        maxScale = fit * MAX_ZOOM_SCALE
        setDoubleTapZoomScale(fit * 2)

        when (config?.zoomStartPosition) {
            ZoomStartPosition.LEFT -> setScaleAndCenter(fit, PointF(0F, 0F))
            ZoomStartPosition.RIGHT -> setScaleAndCenter(fit, PointF(sWidth.toFloat(), 0F))
            // The middle of the source, not the middle of the screen as the view reads it now: for a
            // view that is being set up again the second is wherever the reader left it
            ZoomStartPosition.CENTER -> setScaleAndCenter(fit, PointF(sWidth / 2f, sHeight / 2f))
            null -> {}
        }
    }

    /**
     * Moves the reader's zoom and pan from the view that is being replaced to the one that is taking
     * over, so a page that was zoomed while its detail loaded keeps the reader's position when the
     * detail arrives rather than dropping back to the whole page.
     *
     * The two scales are not the same number and cannot be copied: each is screen pixels per source
     * pixel, so the same on-screen size is the preview's scale against the preview's own pixels and
     * the new view's scale against the file's. The factor between them is the ratio of the two fits,
     * and a fit is what the library calls `minScale()` — the scale at which each source just fits the
     * viewport — which is also the value the zoom log prints.
     *
     * The center is stored in source coordinates, so it is moved through the screen translation the
     * two views share rather than copied.
     *
     * The result is clamped to the new view's own limits, which is what keeps a position that was
     * legal on the preview legal here. A zoom that is still animating when the load finishes is not
     * readable to its end — the library keeps the animation it started private — so this is the
     * reader's position at the moment of the swap, and the clamp is what keeps it legal. The
     * transition is not continued on the new view: it starts at the position above, already settled.
     */
    private fun SubsamplingScaleImageView.carryPositionFrom(preview: SubsamplingScaleImageView) {
        val previewCenter = preview.center ?: return
        val previewFit = preview.minScale
        val fit = minScale
        if (previewFit <= 0F || fit <= 0F) return
        // A reader that has not zoomed is already at the fit of the new view, so there is nothing to
        // move and no position to overwrite
        if (preview.scale <= previewFit) return
        val target = (preview.scale * (fit / previewFit)).coerceIn(fit, maxScale)
        val translateX = preview.width / 2f - preview.scale * previewCenter.x
        val translateY = preview.height / 2f - preview.scale * previewCenter.y
        setScaleAndCenter(target, PointF((width / 2f - translateX) / target, (height / 2f - translateY) / target))
    }

    /**
     * Puts a page back at the position it shows at when the reader first comes to it, for the reader
     * has moved on from the page.
     *
     * A holder keeps its view across the turns the reader makes past it, so a zoom and a pan the reader
     * set on a page stay on that view until something else writes them, and the next time the reader
     * comes back they arrive at a page that is already zoomed somewhere. That position is in the units
     * of whichever of the two views was last drawn — the file's pixels or the prepared ones — and the
     * view that is up now is the other one, which is what makes a previous visit's zoom read further
     * in or panned to the other side. The position is not carried back out of the file's view into the
     * prepared one, as it is carried forward at [promoteToFullDetail]: the reader has left the page, so
     * there is no one to keep it for. The ceiling is written again from the fit rather than left at
     * what the last visit set from a scale that was already zoomed, which is what stops it compounding
     * across visits.
     *
     * [RESET_ZOOM_ON_LEAVE] is what this is asked for under; with it off the state is left as it was
     * and a page keeps its reader's zoom across the turn, which is the behaviour the drift above is
     * measured against.
     */
    fun resetZoomFor(config: Config) {
        if (!RESET_ZOOM_ON_LEAVE) return
        val view = pageView as? SubsamplingScaleImageView ?: return
        // A view that has not measured its source has no fit to write a ceiling from, and nothing of
        // the reader's that it could let go
        if (!view.isReady()) return
        view.resetScaleAndCenter()
        view.setupZoom(config)
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
        // Cleared here rather than left from the last file this view was given, so a view that is
        // reused for a page set from pixels cannot answer with the earlier page's load
        loadStartedAt = 0L
        setOnImageEventListener(
            object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                override fun onReady() {
                    // What the library's own reading and tiling cost, for a page that came from a file.
                    // A page set from pixels that were made already has no load here to time, so it says
                    // nothing rather than a zero that would read as a fast decode. This is the cost the
                    // pixels path spends in its holder instead, and the two are what a reader who finds
                    // one path slower than the other on small files asks against.
                    if (loadStartedAt > 0L) {
                        metrics("drawn", zoomPage, "view=$imageFrom load=${metricsSince(loadStartedAt)}")
                    }
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
                imageFrom = "pixels"
                isVisible = true
            }
            is BitmapDrawable -> {
                setImage(ImageSource.bitmap(data.bitmap))
                imageFrom = "pixels"
                isVisible = true
            }
            is BufferedSource -> {
                // The load is timed from here, because the library reads the file when the view is
                // first drawn and this is as early as the file is in the view
                loadStartedAt = metricsTime()
                setImage(ImageSource.inputStream(data.inputStream()))
                imageFrom = "file"
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
     *
     * [onLoadEnd] is called when the load this starts is over, however it ended — swapped in, failed, or
     * left behind by a reader that moved on before it was drawn again. It is the view that knows that
     * moment and the holder that owns what the load was holding a place for, so the view is told and
     * calls it; the gate is not named here, because this view is built for covers and strips too, where
     * there is no gate to hold a place. Answers whether a load was started, so a caller that asked for a
     * place before this call can let go of one that was never used.
     */
    fun promoteToFullDetail(source: BufferedSource, config: Config, onLoadEnd: (() -> Unit)? = null): Boolean {
        if (pageView !is SubsamplingScaleImageView) return false
        // A page that already has a detail view loading has one. A second would leave the first out of
        // every field, so it would never be swapped in, never be removed from the tree and never be
        // recycled — a whole tile pyramid held for nothing.
        if (detailView != null) return false

        val detail = newSubsamplingImageView()
        detailView = detail
        detail.applyImageConfig(config)
        detail.setOnImageEventListener(
            object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                override fun onReady() {
                    // The load is over, however it went, so the place it was holding is let go first:
                    // by the time the fields below are read, `detailView` is already its null, and a
                    // load that was left behind by a reader that moved on answers here with nothing
                    // left to swap.
                    onLoadEnd?.let { it() }
                    val loaded = detailView ?: return
                    val preview = pageView as? SubsamplingScaleImageView ?: return
                    // The swap happened: the file is on screen and what was is taken away in the same
                    // moment. The page id is the holder's, handed over for the zoom log, so a line that
                    // has none is one whose holder never set it — a view made outside a page holder.
                    // `load=` is what the reading of the file cost, which is the thing a reader pays
                    // when they stop on a page that was prepared ahead: the turn that made this page the
                    // one being read does not wait for it, so the load is what the stop waits for.
                    metrics("swap", zoomPage, "load=${metricsSince(loadStartedAt)}")
                    // From here the gestures reach the file's own view, whose fit is the file's, not the
                    // viewport's, and whose ceiling was just written from that fit.
                    zoomSubject = "detail"
                    loaded.setupZoom(config)
                    // The ceiling has to exist before the position is moved over: the move is clamped
                    // to it, and a ceiling of the library's default of two would put the reader's
                    // position outside the range the new view allows.
                    loaded.carryPositionFrom(preview)
                    loaded.isVisible = true
                    // The prepared view is taken out of the tree but kept, not recycled: it is what
                    // this page goes back to when the reader moves on, and its pixels are the cache's
                    // anyway. `previewView` being set is what says the page is on the file's detail.
                    removeView(preview)
                    previewView = preview
                    pageView = loaded
                    detailView = null
                    imageFrom = "file"
                    this@ReaderPageImageView.onImageLoaded()
                }

                override fun onImageLoadError(e: Exception) {
                    // The load is over, and this is the only end it answers at, so the place comes first
                    onLoadEnd?.let { it() }
                    // The file could not be read again. Without this line the promotion is silent: the
                    // preview stays up and nothing says a swap was tried and failed, which is how a page
                    // that never gets its detail looks the same as one that was never promoted.
                    // A failed promotion still paid its reading of the file, and `load=` is what says
                    // so: a page that never gets its detail is only told apart from one that was never
                    // promoted by a line like this beside the `promote` the holder wrote.
                    metrics(
                        "swapfail",
                        null,
                        "err=${e::class.simpleName} msg=${e.message} load=${metricsSince(loadStartedAt)}",
                    )
                    // A page that cannot be read from the file again keeps what it was showing
                    detailView?.let {
                        removeView(it)
                        it.recycle()
                    }
                    detailView = null
                }
            },
        )
        // The loading view goes behind the preview rather than over it. It paints nothing until it has
        // tiles, so the preview is what the reader sees either way — but a view that is on top is what
        // receives the touches, and the library answers a touch on a view that is not ready by
        // consuming it before its double-tap detector is ever fed, so a view left over its preview
        // would swallow every gesture for the whole length of the load: a page that is still loading
        // takes no zoom, no pan and no fling, and only a single tap still turns pages because that
        // one has its own detector.
        // The order is set before the view is added: `View` sorts its children as a child is inserted,
        // and setting the order afterwards only invalidates without re-sorting.
        detail.setZ(-1f)
        addView(detail, MATCH_PARENT, MATCH_PARENT)
        // The view is left drawable rather than hidden: the library reads the file when it is first
        // drawn, and a view that is never drawn is never read, so an invisible one would sit out its
        // whole load and answer neither listener. It paints nothing until it has tiles, which is what
        // keeps the preview on screen for the length of the load and makes the swap at `onReady` the
        // first thing the reader sees change.
        // The source is read through `peek()`, which leaves the one the holder kept whole: a page that
        // is put back to its prepared pixels is promoted again the next time the reader stops on it,
        // and a source the first promotion consumed would answer the second with nothing at all.
        // The load is timed from here rather than from where the place at the gate was asked for: the
        // wait for one is the holder's `gate=`, and this is the part that was the library's own reading
        loadStartedAt = metricsTime()
        detail.setImage(ImageSource.inputStream(source.peek().inputStream()))
        // A load was started, so whatever was held for it before this call is still held: the listeners
        // above are what let it go, at whichever of the two ends this load answers.
        return true
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
        imageFrom = "pixels"
        // The gestures reach the prepared pixels again, whose fit is the viewport's
        zoomSubject = "page"
        return true
    }

    /**
     * Lets go of a page's detail that is still loading, for the reader has moved on from the page it
     * was for.
     *
     * A promotion that has not swapped yet has no prepared view kept aside — the preview is still the
     * page — so there is nothing to swap back and the only thing to do is stop the load. Removing the
     * view from the tree is what stops the rest of it: the library reads a file when it is first drawn,
     * so a view that is not drawn has no decode left to start. What has started is left to finish, and
     * the view is not recycled because that closes the file the started read is still copying out of —
     * the task holds the view, so the file is given back when it is done. This is what keeps a fast
     * scroll from queueing a full decode per page, which is what runs the app out of heap.
     *
     * Answers whether there was one to let go of. A load that had been drawn answers one of the two
     * listeners above when it finishes, and that is where the caller lets go of whatever it was holding
     * for the load; a load that never was drawn answers neither, so the caller has to let go of it here
     * rather than wait for an answer that will not come.
     */
    fun cancelPromotion(): Boolean {
        val detail = detailView ?: return false
        removeView(detail)
        // Not recycled: the tile-init task this view started is still reading the file and writing its
        // tiles into pixels, and `recycle()` gives back both — a native write into freed pixels is a
        // use-after-free, not an error. The task holds the view, so the file and the tiles are given
        // back when it finishes.
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

/**
 * Whether the reader's zoom and pan are set back to the page's own when the reader moves on from it,
 * which is what [ReaderPageImageView.resetZoomFor] does.
 *
 * Off, a page keeps whatever position the reader left on it for as long as its holder is on screen, and
 * that position is in the units of the view that was drawn last, so it reads differently on the view
 * that is up when the reader comes back. On, each visit starts from the page as it fills the screen and
 * the position is only ever the reader's current one.
 */
private const val RESET_ZOOM_ON_LEAVE = true
