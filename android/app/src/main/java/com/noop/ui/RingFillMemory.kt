package com.noop.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf

// MARK: - Ring fill memory
//
// The frame each ring last stood at, held above the lists that recycle it. A ring whose host leaves
// composition — a lazy item scrolled off, the hero row collapsing into its pinned twin — comes back
// where it left, so a fill runs once per value rather than once per composition.

/** The frame a ring starts its fill from: [fraction] of its arc, and the [value] shown under it. */
data class RingFillStart(val fraction: Float, val value: Float)

/** Where each keyed ring stands. Read by [GlowRing] through [LocalRingFillMemory]. */
@Stable
class RingFillMemory {
    private val frames = HashMap<String, RingFillStart>()

    /** Where the ring keyed [key] starts. An unseen ring starts empty, so a first sight still fills in. */
    fun startFor(key: String): RingFillStart = frames[key] ?: EMPTY

    /** Bank where the ring keyed [key] stands — its settled frame, or the one it is disposed on. */
    fun record(key: String, fraction: Float, value: Float) {
        frames[key] = RingFillStart(fraction, value)
    }

    private companion object {
        /** An unfilled ring: an empty arc under a zero. */
        val EMPTY = RingFillStart(0f, 0f)
    }
}

/** One memory per process — it has to outlive every screen and list item that draws a ring. */
val LocalRingFillMemory = staticCompositionLocalOf { RingFillMemory() }
