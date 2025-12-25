package com.trxsafe.payment

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import androidx.lifecycle.ProcessLifecycleOwner
import com.trxsafe.payment.security.AppLockManager
import com.trxsafe.payment.security.SecurityConstraints

/**
 * Application 类
 * 负责全局初始化和生命周期监听
 */
class TrxSafeApplication : Application(), LifecycleObserver {
    
    lateinit var appLockManager: AppLockManager
        private set
    
    override fun onCreate() {
        super.onCreate()
        
        // 初始化 AppLockManager
        appLockManager = AppLockManager(this)
        
        // 注册生命周期监听
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        
        // 执行安全约束检查
        performSecurityChecks()
    }
    
    /**
     * App 进入前台
     */
    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    fun onAppForegrounded() {
        // App 回到前台时，检查是否需要锁定
        // 实际的锁定逻辑在 MainActivity 中处理
    }
    
    /**
     * App 进入后台
     */
    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    fun onAppBackgrounded() {
        // 记录进入后台的时间
        appLockManager.onAppPaused()
    }
    
    /**
     * 执行安全约束检查
     */
    private fun performSecurityChecks() {
        try {
            // 1. 检查是否禁用了 WalletConnect
            SecurityConstraints.ensureWalletConnectDisabled()
            
            // 2. 检查是否禁用了 DApp 浏览器
            SecurityConstraints.ensureDAppBrowserDisabled()
            
            // 3. 检查是否仅允许 TRX 转账
            SecurityConstraints.ensureOnlyTrxTransfer()
            
            // 4. 检查是否禁用了智能合约
            SecurityConstraints.ensureSmartContractDisabled()
            
            // 5. 检查是否禁用了 TRC20
            SecurityConstraints.ensureTrc20Disabled()
            
        } catch (e: SecurityException) {
            // 如果安全检查失败，记录错误并终止应用
            android.util.Log.e("TrxSafeApplication", "Security check failed: ${e.message}")
            throw e
        }
    }
    
    companion object {
        /**
         * 获取 Application 实例
         */
        fun getInstance(context: android.content.Context): TrxSafeApplication {
            return context.applicationContext as TrxSafeApplication
        }
    }
}
