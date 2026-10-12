package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * A custom gesture detector that also implements an on long tap confirmed, because the built-in
 * one conflicts with the quick scale feature.
 */
open class GestureDetectorWithLongTap(
    context: Context,
    listener: Listener,
) : GestureDetector(context, listener) {

    private val handler = Handler(Looper.getMainLooper())

    /**
     * The listener this detector notifies. Held as a field so that [onTouchEvent] can call it
     * directly and not only build closures over it.
     */
    private val gestureListener = listener
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val longTapTime = ViewConfiguration.getLongPressTimeout().toLong()
    private val doubleTapTime = ViewConfiguration.getDoubleTapTimeout().toLong()

    private var downX = 0f
    private var downY = 0f
    private var lastUp = 0L
    private var lastDownEvent: MotionEvent? = null

    /**
     * Whether a tap on the given on down event lands on a zone that should be registered as soon
     * as the finger is lifted, instead of waiting for the double tap timeout. Set by the viewer,
     * the only one that knows where its tap zones are. When it returns false, taps keep the
     * built-in timing.
     */
    var isTapZone: ((MotionEvent) -> Boolean)? = null

    /**
     * Whether the touch in progress started on such a zone. Subclasses use it to skip the events
     * that would spend the same touch on a double tap.
     */
    var isTapZoneTouch = false

    /**
     * Time of the down event that was already spent on an immediate tap. The built-in detector
     * replays that tap later, passing along the very same down event, which is how the replay is
     * recognised. It is kept across touches rather than cleared, because the replay of an older
     * touch can be dispatched while a newer one is being handled.
     */
    private var immediateTapDownTime = 0L

    // The down point of the touch in progress, tracked apart from [downX]/[downY] because those
    // deliberately ignore the second tap of a double tap, which is a tap we do want to register.
    private var tapDownX = 0f
    private var tapDownY = 0f
    private var isPotentialTap = false
    private var longTapFired = false

    /**
     * Runnable to execute when a long tap is confirmed.
     */
    private val longTapFn = Runnable {
        longTapFired = true
        gestureListener.onLongTapConfirmed(lastDownEvent!!)
    }

    /**
     * Whether the given event is the down event of a touch that was already registered as an
     * immediate tap. Listeners use it to drop the single tap the built-in detector replays for it.
     */
    fun isHandledByImmediateTap(ev: MotionEvent): Boolean {
        return ev.eventTime == immediateTapDownTime
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastDownEvent?.recycle()
                lastDownEvent = MotionEvent.obtain(ev)

                isTapZoneTouch = isTapZone?.invoke(ev) == true
                isPotentialTap = true
                longTapFired = false
                tapDownX = ev.x
                tapDownY = ev.y

                // This is the key difference with the built-in detector. We have to ignore the
                // event if the last up and current down are too close in time (double tap).
                if (ev.downTime - lastUp > doubleTapTime) {
                    downX = ev.x
                    downY = ev.y
                    handler.postDelayed(longTapFn, longTapTime)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) {
                    handler.removeCallbacks(longTapFn)
                }
                if (abs(ev.x - tapDownX) > slop || abs(ev.y - tapDownY) > slop) {
                    isPotentialTap = false
                }
            }
            MotionEvent.ACTION_UP -> {
                lastUp = ev.eventTime
                handler.removeCallbacks(longTapFn)
                // A tap on a tap zone is registered the moment the finger is lifted, so the reader
                // does not wait the double tap timeout before turning the page. The listeners drop
                // the single tap the built-in detector replays for the same touch, see
                // [isHandledByImmediateTap].
                if (
                    isTapZoneTouch && isPotentialTap && !longTapFired &&
                        abs(ev.x - tapDownX) <= slop && abs(ev.y - tapDownY) <= slop
                ) {
                    val downEvent = lastDownEvent
                    if (downEvent != null) {
                        // The call comes first and only then is the touch recorded, because the
                        // listener asks [isHandledByImmediateTap] of the very event it is handed:
                        // recording it beforehand would make this call look like the replay it is
                        // meant to suppress, and the tap would be swallowed. The record is still
                        // taken before the event reaches the built-in detector below, so whatever
                        // it schedules from here on is covered.
                        gestureListener.onSingleTapConfirmed(downEvent)
                        immediateTapDownTime = downEvent.eventTime
                    }
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                handler.removeCallbacks(longTapFn)
                isPotentialTap = false
            }
        }
        return super.onTouchEvent(ev)
    }

    /**
     * Custom listener to also include a long tap confirmed
     */
    open class Listener : SimpleOnGestureListener() {
        /**
         * Notified when a long tap occurs with the initial on down [ev] that triggered it.
         */
        open fun onLongTapConfirmed(ev: MotionEvent) {
        }
    }
}
