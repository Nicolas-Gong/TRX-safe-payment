package com.trxsafe.payment.utils

import android.view.View
import java.util.concurrent.ConcurrentHashMap

object DebouncedClickManager {

    const val DEFAULT_DEBOUNCE_DELAY_MS = 300L

    private val lastClickTimes = ConcurrentHashMap<View, Long>()
    private val debounceDelays = ConcurrentHashMap<View, Long>()

    fun setDebounceDelay(view: View, delayMs: Long) {
        debounceDelays[view] = delayMs
    }

    fun getDebounceDelay(view: View): Long {
        return debounceDelays[view] ?: DEFAULT_DEBOUNCE_DELAY_MS
    }

    fun isDebounced(view: View): Boolean {
        val currentTime = System.currentTimeMillis()
        val lastClickTime = lastClickTimes[view] ?: 0L
        return currentTime - lastClickTime < getDebounceDelay(view)
    }

    fun canClick(view: View): Boolean {
        return !isDebounced(view)
    }

    fun recordClick(view: View) {
        lastClickTimes[view] = System.currentTimeMillis()
    }

    fun clearDebounce(view: View) {
        lastClickTimes.remove(view)
        debounceDelays.remove(view)
    }

    fun clearAllDebounces() {
        lastClickTimes.clear()
        debounceDelays.clear()
    }
}

fun View.setDebouncedClick(
    debounceDelayMs: Long = DebouncedClickManager.DEFAULT_DEBOUNCE_DELAY_MS,
    onClick: (View) -> Unit
) {
    this.setOnClickListener { view ->
        if (!DebouncedClickManager.canClick(view)) {
            return@setOnClickListener
        }

        DebouncedClickManager.recordClick(view)
        onClick(view)
    }
}

fun View.setDebouncedClickWithResult(
    debounceDelayMs: Long = DebouncedClickManager.DEFAULT_DEBOUNCE_DELAY_MS,
    onClick: (View) -> Boolean
) {
    this.setOnClickListener { view ->
        if (!DebouncedClickManager.canClick(view)) {
            return@setOnClickListener
        }

        DebouncedClickManager.recordClick(view)
        val shouldProceed = onClick(view)
        if (!shouldProceed) {
            DebouncedClickManager.clearDebounce(view)
        }
    }
}

fun View.isDebounced(): Boolean {
    return DebouncedClickManager.isDebounced(this)
}

fun View.clearDebounce() {
    DebouncedClickManager.clearDebounce(this)
}
