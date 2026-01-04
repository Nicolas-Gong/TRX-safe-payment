package com.trxsafe.payment

import android.app.Application
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import androidx.lifecycle.ProcessLifecycleOwner
import com.trxsafe.payment.security.AppLockManager
import com.trxsafe.payment.security.SecurityConstraints
import com.trxsafe.payment.wallet.WalletConnectManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Application 类
 * 负责全局初始化和生命周期监听
 *
 * 启动性能优化:
 * - WalletConnectManager 采用完全延迟初始化,只在首次使用时才初始化
 * - 初始化在后台线程执行,避免阻塞主线程
 * - 避免在 Application.onCreate() 中执行任何耗时操作
 */
class TrxSafeApplication : Application(), LifecycleObserver {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile
    private var walletConnectManager: WalletConnectManager? = null

    @Volatile
    private var walletConnectInitializing = false

    lateinit var appLockManager: AppLockManager
        private set

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "TrxSafeApplication.onCreate() 开始")

        val startTime = System.currentTimeMillis()

        appLockManager = AppLockManager(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        executeSecurityChecksAsync()

        val endTime = System.currentTimeMillis()
        Log.d(TAG, "TrxSafeApplication.onCreate() 完成,耗时: ${endTime - startTime}ms")
    }

    /**
     * 获取 WalletConnectManager 实例
     * 采用完全延迟初始化策略，只在首次使用时才初始化
     * 初始化在后台线程执行，主线程完全不需要等待
     * 如果初始化尚未完成，返回 null，由调用方处理
     */
    fun getOrCreateWalletConnectManager(): WalletConnectManager? {
        val existing = walletConnectManager
        if (existing != null && existing.isInitialized) {
            return existing
        }

        if (walletConnectInitializing) {
            Log.d(TAG, "WalletConnectManager 正在后台初始化中...")
            return null
        }

        synchronized(this) {
            if (walletConnectManager != null && walletConnectManager!!.isInitialized) {
                return walletConnectManager!!
            }

            if (!walletConnectInitializing) {
                walletConnectInitializing = true
                Log.d(TAG, "开始在后台初始化 WalletConnectManager")

                Thread {
                    try {
                        val manager = WalletConnectManager(this)
                        manager.initialize()
                        walletConnectManager = manager
                        Log.d(TAG, "WalletConnectManager 后台初始化完成")
                    } catch (e: Exception) {
                        Log.e(TAG, "WalletConnectManager 初始化失败: ${e.message}", e)
                        walletConnectInitializing = false
                    }
                }.start()
            }

            return null
        }
    }

    /**
     * 获取 WalletConnectManager 实例，如果正在初始化则等待
     * 适用于必须同步获取的场景（如发起交易时）
     * 注意：此方法可能阻塞主线程，建议在后台线程使用或使用回调方式
     */
    fun getWalletConnectManagerSync(): WalletConnectManager {
        val existing = walletConnectManager
        if (existing != null && existing.isInitialized) {
            return existing
        }

        synchronized(this) {
            if (walletConnectManager != null && walletConnectManager!!.isInitialized) {
                return walletConnectManager!!
            }

            if (!walletConnectInitializing) {
                walletConnectInitializing = true
                Log.d(TAG, "开始同步初始化 WalletConnectManager (可能阻塞)")

                Thread {
                    try {
                        val manager = WalletConnectManager(this)
                        manager.initialize()
                        walletConnectManager = manager
                        Log.d(TAG, "WalletConnectManager 初始化完成")
                    } catch (e: Exception) {
                        Log.e(TAG, "WalletConnectManager 初始化失败: ${e.message}", e)
                        walletConnectInitializing = false
                    }
                }.start()

                while (walletConnectInitializing) {
                    Thread.sleep(10)
                }

                return walletConnectManager!!
            }

            while (walletConnectInitializing) {
                Thread.sleep(10)
            }

            return walletConnectManager!!
        }
    }

    /**
     * 异步执行安全检查
     * 安全检查应该快速执行,但为了不影响启动速度,放在协程中执行
     */
    private fun executeSecurityChecksAsync() {
        applicationScope.launch {
            val startTime = System.currentTimeMillis()
            try {
                performSecurityChecks()
                Log.d(TAG, "安全检查完成,耗时: ${System.currentTimeMillis() - startTime}ms")
            } catch (e: SecurityException) {
                Log.e(TAG, "安全检查失败: ${e.message}")
                throw e
            }
        }
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
            // 1. 检查是否禁用了 WalletConnect (如果已启用则跳过)
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
        private const val TAG = "TrxSafeApplication"

        /**
         * 获取 Application 实例
         */
        fun getInstance(context: android.content.Context): TrxSafeApplication {
            return context.applicationContext as TrxSafeApplication
        }
    }
}