package com.trxsafe.payment.security

import android.content.Context
import android.content.SharedPreferences

/**
 * App 锁定管理器
 * 负责管理 App 锁定状态和超时设置
 */
class AppLockManager(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "app_lock_prefs",
        Context.MODE_PRIVATE
    )
    
    companion object {
        private const val KEY_LOCK_ENABLED = "lock_enabled"
        private const val KEY_LOCK_TIMEOUT = "lock_timeout"
        private const val KEY_LAST_PAUSE_TIME = "last_pause_time"
        private const val KEY_IS_LOCKED = "is_locked"
        
        // 默认超时时间（毫秒）
        const val DEFAULT_TIMEOUT_MS = 60_000L // 1 分钟
        const val TIMEOUT_30_SECONDS = 30_000L
        const val TIMEOUT_1_MINUTE = 60_000L
        const val TIMEOUT_5_MINUTES = 300_000L
        const val TIMEOUT_IMMEDIATELY = 0L
    }
    
    /**
     * 是否启用 App 锁
     */
    var isLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCK_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_LOCK_ENABLED, value).apply()
    
    /**
     * 锁定超时时间（毫秒）
     */
    var lockTimeout: Long
        get() = prefs.getLong(KEY_LOCK_TIMEOUT, DEFAULT_TIMEOUT_MS)
        set(value) = prefs.edit().putLong(KEY_LOCK_TIMEOUT, value).apply()
    
    /**
     * 上次进入后台的时间
     */
    private var lastPauseTime: Long
        get() = prefs.getLong(KEY_LAST_PAUSE_TIME, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_PAUSE_TIME, value).apply()
    
    /**
     * 当前是否已锁定
     */
    var isLocked: Boolean
        get() = prefs.getBoolean(KEY_IS_LOCKED, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_LOCKED, value).apply()
    
    /**
     * 记录 App 进入后台
     */
    fun onAppPaused() {
        if (isLockEnabled) {
            lastPauseTime = System.currentTimeMillis()
        }
    }
    
    /**
     * 检查是否需要锁定
     * @return true 表示需要锁定
     */
    fun checkShouldLock(): Boolean {
        if (!isLockEnabled) {
            return false
        }
        
        // 如果已经锁定，保持锁定状态
        if (isLocked) {
            return true
        }
        
        val currentTime = System.currentTimeMillis()
        val elapsedTime = currentTime - lastPauseTime
        
        // 如果超过超时时间，需要锁定
        if (elapsedTime >= lockTimeout) {
            isLocked = true
            return true
        }
        
        return false
    }
    
    /**
     * 解锁 App
     */
    fun unlock() {
        isLocked = false
        lastPauseTime = 0L
    }
    
    /**
     * 立即锁定 App
     */
    fun lock() {
        isLocked = true
    }
    
    /**
     * 重置超时计时器
     */
    fun resetTimeout() {
        lastPauseTime = System.currentTimeMillis()
    }
    
    /**
     * 获取超时时间的可读文本
     */
    fun getTimeoutText(): String {
        return when (lockTimeout) {
            TIMEOUT_IMMEDIATELY -> "立即锁定"
            TIMEOUT_30_SECONDS -> "30 秒"
            TIMEOUT_1_MINUTE -> "1 分钟"
            TIMEOUT_5_MINUTES -> "5 分钟"
            else -> "${lockTimeout / 1000} 秒"
        }
    }
}
