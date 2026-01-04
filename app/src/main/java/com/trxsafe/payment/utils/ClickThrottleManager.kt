package com.trxsafe.payment.utils

import android.view.View
import android.widget.Button
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object ClickThrottleManager {

    const val DEFAULT_THROTTLE_DELAY_MS = 1000L

    private val throttleDelays = ConcurrentHashMap<View, Long>()
    private val enabledStates = ConcurrentHashMap<View, Boolean>()
    private val originalTexts = ConcurrentHashMap<View, String>()  // 保存按钮原始文字
    private val clickLocks = ConcurrentHashMap<View, AtomicBoolean>()

    fun setThrottleDelay(view: View, delayMs: Long) {
        throttleDelays[view] = delayMs
    }

    fun getThrottleDelay(view: View): Long {
        return throttleDelays[view] ?: DEFAULT_THROTTLE_DELAY_MS
    }

    fun isClickLocked(view: View): Boolean {
        return clickLocks[view]?.get() ?: true
    }

    fun hasLockRecord(view: View): Boolean {
        return clickLocks.containsKey(view)
    }

    fun lockClick(view: View) {
        clickLocks.getOrPut(view) { AtomicBoolean(true) }.set(false)
        saveEnabledState(view)
        view.isEnabled = false
        
        // 保存按钮原始文字
        when (view) {
            is Button -> {
                originalTexts[view] = view.text.toString()
            }
            is MaterialButton -> {
                originalTexts[view] = view.text.toString()
            }
        }
    }

    fun unlockClick(view: View) {
        clickLocks.getOrPut(view) { AtomicBoolean(true) }.set(true)
        restoreEnabledState(view)
    }

    fun resetClick(view: View) {
        unlockClick(view)
        clearLoadingState(view)
    }

    private fun saveEnabledState(view: View) {
        enabledStates[view] = view.isEnabled
    }

    private fun restoreEnabledState(view: View) {
        val originalState = enabledStates[view] ?: true
        view.isEnabled = originalState
        enabledStates.remove(view)
    }

    private fun clearLoadingState(view: View) {
        when (view) {
            is Button -> {
                val originalText = originalTexts[view]
                if (originalText != null) {
                    view.text = originalText
                    originalTexts.remove(view)
                } else {
                    val tagText = view.tag as? String
                    if (tagText != null) {
                        view.text = tagText
                    }
                }
            }
            is MaterialButton -> {
                val originalText = originalTexts[view]
                if (originalText != null) {
                    view.text = originalText
                    originalTexts.remove(view)
                } else {
                    val tagText = view.tag as? String
                    if (tagText != null) {
                        view.text = tagText
                    }
                }
            }
        }
    }

    fun applyLoadingState(view: View, loadingText: String = "处理中...") {
        when (view) {
            is Button -> {
                view.tag = view.text.toString()
                view.text = loadingText
            }
            is MaterialButton -> {
                view.tag = view.text.toString()
                view.text = loadingText
            }
        }
    }

    fun clearThrottle(view: View) {
        throttleDelays.remove(view)
        clickLocks.remove(view)
        enabledStates.remove(view)
        originalTexts.remove(view)
    }

    fun clearAllThrottles() {
        throttleDelays.clear()
        clickLocks.clear()
        enabledStates.clear()
        originalTexts.clear()
    }
}

fun View.setThrottledClick(
    throttleDelayMs: Long = ClickThrottleManager.DEFAULT_THROTTLE_DELAY_MS,
    loadingText: String = "处理中...",
    onClick: (View) -> Unit
) {
    this.setOnClickListener { view ->
        if (!ClickThrottleManager.isClickLocked(view)) {
            return@setOnClickListener
        }

        val currentTime = System.currentTimeMillis()
        val lastClickTime = throttleDelays[view] ?: 0L

        if (currentTime - lastClickTime < throttleDelayMs) {
            return@setOnClickListener
        }

        throttleDelays[view] = currentTime
        ClickThrottleManager.lockClick(view)
        ClickThrottleManager.applyLoadingState(view, loadingText)

        try {
            onClick(view)
        } catch (e: Exception) {
            ClickThrottleManager.resetClick(view)
            throw e
        }
    }
}

private val throttleDelays = ConcurrentHashMap<View, Long>()

fun View.setOnSafeClickListener(
    throttleDelayMs: Long = ClickThrottleManager.DEFAULT_THROTTLE_DELAY_MS,
    onSafeClick: (View) -> Unit
) {
    this.setOnClickListener { view ->
        val currentTime = System.currentTimeMillis()
        val lastClickTime = throttleDelays[view] ?: 0L

        if (currentTime - lastClickTime < throttleDelayMs) {
            return@setOnClickListener
        }

        throttleDelays[view] = currentTime
        onSafeClick(view)
    }
}

fun View.applyLoadingState(loadingText: String = "处理中...") {
    ClickThrottleManager.applyLoadingState(this, loadingText)
}

fun View.resetState() {
    ClickThrottleManager.resetClick(this)
}

fun View.lockState() {
    ClickThrottleManager.lockClick(this)
}

fun View.unlockState() {
    ClickThrottleManager.unlockClick(this)
}

fun View.setClickLocked(locked: Boolean) {
    if (locked) {
        ClickThrottleManager.lockClick(this)
    } else {
        ClickThrottleManager.unlockClick(this)
    }
}
