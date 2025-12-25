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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        
        // 排除 LockActivity 本身，避免死循环
        if (this is LockActivity) {
            return
        }

        val app = TrxSafeApplication.getInstance(this)
        val appLockManager = app.appLockManager
        
        // 检查是否需要锁定
        if (appLockManager.checkShouldLock()) {
            val intent = Intent(this, LockActivity::class.java)
            // 清除之前的栈，确保解锁后回到主界面或者保持当前？
            // 通常是覆盖当前，所以不需要 FLAG_ACTIVITY_CLEAR_TOP
            startActivity(intent)
        }
    }
}
