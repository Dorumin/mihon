package eu.kanade.tachiyomi.ui.reader.viewer

import kotlinx.coroutines.CompletableDeferred

/**
 * Who may be decoding a page right now, and who gets a free place next.
 *
 * The pages themselves stay with their holders: a decode produces the pixels of one page, which go to
 * that page's view, so a pool of workers would have to carry them back. What is owned here is the
 * order the free places are handed out, which a bounded dispatcher cannot express —
 * `Dispatchers.Default.limitedParallelism(n)` is a fair queue, so the page whose holder was built
 * first gets a place first. The pager builds holders left to right, so opening a chapter on its
 * last-read page puts the page the reader is about to turn to at the *end* of that queue, and a turn
 * that lands on a page that has not started is the black screen the reader sees.
 *
 * The order is asked for again each time places are handed out, rather than fixed when a page joins
 * the queue, because the reader's position moves while pages are still waiting: a page that was
 * ordered from six pages away and is then jumped to should be let through ahead of a page that asked
 * from two away and has since been left behind.
 *
 * Capacity is counted here, in pages, and split into two allowances because memory and work are not
 * the same number: a page whose file is over [BIG_DECODE_BYTES] inflates to a frame of a few hundred
 * MB and only a couple of those fit the app's heap. The next step for this is one allowance counted
 * in bytes rather than two counted in pages; the ordering below does not change with it.
 *
 * Touched from the UI thread only, like the page cache: a holder reaches the gate from its own
 * coroutine before that coroutine hops to another thread to do the decode, and gives the place back
 * from the same block it took it in.
 */
class DecodeGate(val smallCapacity: Int, val bigCapacity: Int) {

    private val waiting = ArrayList<DecodeSlot>()
    private var inUseSmall = 0
    private var inUseBig = 0

    /**
     * How many pages there are that could ask, and how many of them have said whether they want to.
     *
     * The first wave is held until they have all said, because asking is what decides the order and
     * the pages ask one at a time. A page reaches the gate only once its file has been read, so the
     * first one to finish its read would otherwise take a place on an empty gate, and that is the
     * leftmost page of the window rather than the near one. Every holder says one way or the other —
     * a page that is animated, or is the one being read, or has no file, says it wants nothing — so
     * the wave settles on the pages that will ask, and a chapter whose pages are all animated settles
     * without ever admitting one.
     *
     * After anything has been let through there is nothing left to wait for: on a later turn the pages
     * that were already prepared have nothing to ask, and the ones that are still waiting are exactly
     * the ones that are, so the rule is the same one with an empty queue beside it.
     */
    private var registered = 0
    private var resolved = 0
    private var batching = true

    /**
     * Says that a page exists and could want a place, before it has read its file. Called as the page
     * is built, so the count is complete before any of the reads it starts has finished.
     */
    fun register() {
        registered += 1
    }

    /**
     * Says that a page wants no place. It still counts as having said so, which is what lets a wave
     * settle when some of its pages never ask.
     */
    fun withdraw() {
        resolved += 1
    }

    /**
     * Takes a place, or waits for one. [rankOf] is asked whenever the gate is deciding, and answers
     * lower for a page that is wanted sooner; it is a question rather than a number because what is
     * near the reading position changes while a page is still waiting — a page that asked from six
     * pages away and is then jumped to should be let through ahead of a page that asked from two
     * away and has since been left behind, and a number fixed at ask-time cannot say that. What the
     * rank *means* is still the viewer's question (`PagerViewer.decodePriority`) — the gate calls the
     * thing it was given and still knows nothing about pages, reading order or where the reader is.
     * [big] is whether the work is one whose frame is a few hundred MB, which is the second
     * allowance.
     *
     * [wave] says whether this is a page saying what it wants. A page's own detail load is not one:
     * it is started by a view that is already on the page, by a holder that has long finished asking,
     * so it takes a place on the same allowance in the same order and is not counted against the wave.
     * Counting it would settle the first wave before the pages that had not asked yet had, and they
     * would take their places by arrival rather than by rank — which is the ordering this whole file
     * exists to keep.
     *
     * The place is given back with [give], and the pair has to be used from a `try`/`finally`: a
     * decode that is cancelled because the reader moved on has to let go of its place, and one that
     * was cancelled before it had one has to leave the queue.
     */
    suspend fun take(rankOf: () -> Int, big: Boolean, wave: Boolean = true): DecodeSlot {
        if (wave) {
            resolved += 1
        }
        val slot = DecodeSlot(rankOf, big)
        waiting.add(slot)
        admit()
        try {
            // The page waits for its place here. The signal is one-shot rather than a permit: the
            // gate has already decided who may go, and this is only the thing that page waits on
            // until it does. A `Semaphore(0)` cannot stand in for it — kotlinx asks its constructor
            // for at least one permit and throws "Semaphore should have at least 1 permit, but had
            // 0", which is what every page beside the one being read came up as.
            slot.signal.await()
        } catch (e: Throwable) {
            // Stopped on the way in. It has still said what it wants, so the wave count stands; it is
            // the queue entry that must go, or it would hold a place that was never taken.
            if (slot.taken) {
                give(slot)
            } else {
                waiting.remove(slot)
            }
            throw e
        }
        return slot
    }

    /**
     * Lets go of a place, and hands it to whoever wants it next.
     */
    fun give(slot: DecodeSlot) {
        if (slot.big) {
            inUseBig -= 1
        } else {
            inUseSmall -= 1
        }
        admit()
    }

    /**
     * Hands the free places out, best-ranked first — asking each page what it wants now rather than
     * remembering what it said when it queued, which is the whole point of the slot carrying a
     * question. A page that is no longer wanted is not pulled out here: it keeps its place in the
     * queue and is answered with the worst rank, and it leaves when its holder is detached or
     * cancelled, which is where its place is let go.
     */
    private fun admit() {
        if (batching) {
            if (resolved < registered) {
                return
            }
            batching = false
        }
        // Granting a page a place only ever consumes capacity, so no page further down the order
        // becomes admissible because of an earlier grant: one pass in rank order is enough. The order
        // is rebuilt here each time, which is what re-ranks the queue; at most the pages in the
        // window are waiting, so the order is a scan rather than a heap, and `sortedBy` asks each
        // page once rather than once per comparison.
        waiting.sortedBy { it.rankOf() }.forEach { slot ->
            val free =
                if (slot.big) {
                    inUseBig < bigCapacity
                } else {
                    inUseSmall < smallCapacity
                }
            if (free) {
                if (slot.big) {
                    inUseBig += 1
                } else {
                    inUseSmall += 1
                }
                slot.taken = true
                slot.signal.complete(Unit)
                waiting.remove(slot)
            }
        }
    }
}

/**
 * A page waiting at the gate, and the place it is waiting for. It is what a holder gets back from
 * [DecodeGate.take] and hands to [DecodeGate.give], so the pair names the same page's place without
 * the gate having to look it up again, and it is where a cancelled decode finds out whether it had
 * one to let go of or only a queue entry to leave.
 *
 * It carries [rankOf], the question of how wanted the page is, rather than the number it answered:
 * the gate asks it every time it is deciding, so a page that has been jumped to or jumped past is
 * ordered by where it stands now rather than where it stood when it queued.
 */
class DecodeSlot(val rankOf: () -> Int, val big: Boolean) {
    // A place of its own, which is what the holder waits on. The gate grants one by completing it,
    // so a page that was not picked stays suspended rather than being moved anywhere.
    val signal = CompletableDeferred<Unit>()
    var taken = false
}
