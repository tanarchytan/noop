package com.noop.ui.whoop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember

// MARK: - Customise selection
//
// The working state behind WhoopCustomizeSheet: an ordered enabled list plus the untouched remainder.
// Display-only, it decides WHICH items render and in WHAT order, never what a value is.

/**
 * The editor's working selection over the registry [all]. [enabled] is the ordered visible set and
 * [available] the remainder in [all]'s own order; nothing is persisted until the caller saves.
 */
@Stable
class CustomizeSelection<T>(private val all: List<T>, initial: List<T>) {

    private val order = mutableStateListOf<T>().also { list ->
        initial.forEach { if (it in all && it !in list) list.add(it) }
    }

    /** The visible items, in display order. */
    val enabled: List<T> get() = order

    /** Everything not enabled, in the canonical order [all] was given in. */
    val available: List<T> get() = all.filter { it !in order }

    /** At least one item must stay visible — an empty dashboard reads as a bug, not a choice. */
    val canSave: Boolean get() = order.isNotEmpty()

    /** Show [item], appended after the ones already shown. */
    fun add(item: T) {
        if (item in all && item !in order) order.add(item)
    }

    /** Hide [item]; it returns to [available] at its canonical position. */
    fun remove(item: T) {
        order.remove(item)
    }

    /** Move the enabled item at [from] to [to]; out-of-range indices do nothing. */
    fun move(from: Int, to: Int) {
        if (from in order.indices && to in order.indices) order.add(to, order.removeAt(from))
    }

    /** Replace the selection with [defaults], dropping anything not in [all]. */
    fun reset(defaults: List<T>) {
        order.clear()
        defaults.forEach { if (it in all && it !in order) order.add(it) }
    }
}

/** Remember a [CustomizeSelection]; rebuilt when the registry or the saved selection changes. */
@Composable
fun <T> rememberCustomizeSelection(all: List<T>, initial: List<T>): CustomizeSelection<T> =
    remember(all, initial) { CustomizeSelection(all, initial) }
