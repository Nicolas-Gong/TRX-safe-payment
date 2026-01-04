package com.trxsafe.payment.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.content.Intent
import com.trxsafe.payment.databinding.ActivityLockBinding
import com.trxsafe.payment.security.BiometricAuthManager
import com.trxsafe.payment.TrxSafeApplication
import com.trxsafe.payment.utils.setDebouncedClick

/**
 * 锁定界面
 * 当 App 超时或手动锁定时显示，要求生物识别解锁
 */
class LockActivity : BaseActivity() {

    private lateinit var binding: ActivityLockBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnUnlock.setDebouncedClick(debounceDelayMs = 1000) {
            performUnlock()
        }

        // 自动尝试解锁
        performUnlock()
    }

    private fun performUnlock() {
        val authManager = BiometricAuthManager(this)
        val app = TrxSafeApplication.getInstance(this)
        val appLockManager = app.appLockManager

        authManager.authenticate(
            title = "解锁应用",
            subtitle = "验证您的身份以进入",
            onSuccess = {
                appLockManager.unlock()
                // 记录解锁时间，防止立即重新锁定
                recordUnlockTime()
                Toast.makeText(this, "解锁成功", Toast.LENGTH_SHORT).show()
                finish() // 回到之前的界面
            },
            onError = { error ->
                Toast.makeText(this, "解锁失败：$error", Toast.LENGTH_SHORT).show()
            }
        )
    }

    override fun onBackPressed() {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.addCategory(Intent.CATEGORY_HOME)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
    }
}

// NOTE: Also need to handle Intent in onBackPressed or just leave empty to prevent exit back to app
