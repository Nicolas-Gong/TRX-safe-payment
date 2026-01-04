package com.trxsafe.payment.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.trxsafe.payment.TrxSafeApplication

/**
 * 基础 Activity 类
 * 负责统一处理 App 锁定逻辑
 */
abstract class BaseActivity : AppCompatActivity() {

    // 解锁后的缓冲时间（毫秒），防止立即重新锁定
    private val UNLOCK_BUFFER_TIME = 1000L
    private var lastUnlockTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()

        // 排除 LockActivity 本身，避免死循环
        if (this::class.java == LockActivity::class.java) {
            return
        }

        val app = TrxSafeApplication.getInstance(this)
        val appLockManager = app.appLockManager

        // 检查解锁缓冲时间，避免立即重新锁定
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastUnlockTime < UNLOCK_BUFFER_TIME) {
            return
        }

        // 检查是否需要锁定
        if (appLockManager.checkShouldLock()) {
            val intent = Intent(this, LockActivity::class.java)
            startActivity(intent)
        }
    }

    /**
     * 记录解锁时间，用于防止立即重新锁定
     */
    fun recordUnlockTime() {
        lastUnlockTime = System.currentTimeMillis()
    }
}
