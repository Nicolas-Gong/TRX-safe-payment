package com.trxsafe.payment.wallet

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.reown.android.Core
import com.reown.android.CoreClient
import com.reown.android.relay.ConnectionType
import com.reown.appkit.client.AppKit
import com.reown.appkit.client.Modal
import com.reown.appkit.client.models.request.Request
import com.reown.appkit.client.models.request.SentRequestResult
import com.trxsafe.payment.security.SecurityConstraints
import com.trxsafe.payment.security.TransactionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * WalletConnect 管理器
 * 提供完整的 Reown AppKit WalletConnect 功能实现
 *
 * 主要功能:
 * - 钱包连接管理
 * - TRX 交易签名请求
 * - 连接状态监听
 * - 错误处理
 */
class WalletConnectManager(private val context: Context) {

    companion object {
        private const val TAG = "WalletConnectManager"

        // Reown Cloud 项目 ID (请替换为你自己的项目ID)
        private const val PROJECT_ID = "ffdaa24ed4d7b1a7b72572d793797e73"

        // 应用元数据
        private const val APP_NAME = "TRX_SAFE_PAYMENT"
        private const val APP_DESCRIPTION = "安全的 TRX 支付应用"
        private const val APP_URL = "https://trxsafe.com"
        private const val APP_ICON = "https://trxsafe.com/icon.png"
        private const val APP_REDIRECT = "kotlin-trxsafe-wc://request"
    }

    // 连接状态
    private val _connectionState = MutableLiveData<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: LiveData<ConnectionState> = _connectionState

    // 签名结果
    private val _signResult = MutableLiveData<SignResult>()
    val signResult: LiveData<SignResult> = _signResult

    // 协程作用域
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 初始化标志 (公开以便外部检查)
    @Volatile
    var isInitialized = false
        private set

    // 当前连接的账户地址
    private var currentAccountAddress: String? = null

    // 连接超时时间（毫秒）
    private val CONNECTION_TIMEOUT = 30000L

    // 请求超时时间（毫秒）
    private val REQUEST_TIMEOUT = 60000L

    // 连接超时任务
    private var connectionTimeoutJob: kotlinx.coroutines.Job? = null

    // 请求超时任务
    private var requestTimeoutJob: kotlinx.coroutines.Job? = null

    // 会话持久化键
    private val PREFS_NAME = "walletconnect_prefs"
    private val KEY_CONNECTED_ADDRESS = "connected_address"
    private val KEY_SESSION_EXPIRY = "session_expiry"

    /**
     * 初始化 WalletConnect
     * 注意: 必须在 Application 类中调用
     */
    fun initialize() {
        if (isInitialized) {
            Log.d(TAG, "WalletConnect 已初始化")
            return
        }

        try {
            Log.d(TAG, "开始初始化 Reown AppKit")

            // 获取 Application 实例
            val application = context.applicationContext as? Application
            if (application == null) {
                Log.e(TAG, "Context 不是 Application 实例")
                _connectionState.postValue(ConnectionState.Error("Context 必须是 Application"))
                return
            }

            // 配置应用元数据
            val appMetaData = Core.Model.AppMetaData(
                name = APP_NAME,
                description = APP_DESCRIPTION,
                url = APP_URL,
                icons = listOf(APP_ICON),
                redirect = APP_REDIRECT
            )

            // 初始化 CoreClient
            CoreClient.initialize(
                application = application,
                projectId = PROJECT_ID,
                metaData = appMetaData,
                connectionType = ConnectionType.AUTOMATIC,
                onError = { error ->
                    Log.e(TAG, "CoreClient 初始化错误: ${error.throwable.message}", error.throwable)
                    _connectionState.postValue(ConnectionState.Error("CoreClient 初始化失败: ${error.throwable.message}"))
                }
            )

            Log.d(TAG, "CoreClient 初始化完成")

            // 初始化 AppKit
            val initParams = Modal.Params.Init(core = CoreClient)

            AppKit.initialize(
                init = initParams,
                onSuccess = {
                    isInitialized = true
                    _connectionState.postValue(ConnectionState.Disconnected)
                    Log.d(TAG, "AppKit 初始化成功")
                    setupEventListeners()
                    restoreSession()
                },
                onError = { error: Modal.Model.Error ->
                    Log.e(TAG, "AppKit 初始化失败: ${error.throwable.message}", error.throwable)
                    _connectionState.postValue(ConnectionState.Error("初始化失败: ${error.throwable.message}"))
                }
            )

        } catch (e: Exception) {
            Log.e(TAG, "初始化异常: ${e.message}", e)
            _connectionState.postValue(ConnectionState.Error("初始化异常: ${e.message}"))
        }
    }

    /**
     * 设置事件监听器
     */
    private fun setupEventListeners() {
        try {
            // 监听会话状态
            val appKitModalDelegate = object : AppKit.ModalDelegate {
                override fun onSessionApproved(approvedSession: Modal.Model.ApprovedSession) {
                    Log.d(TAG, "会话已批准")
                    cancelConnectionTimeout()

                    try {
                        val account = AppKit.getAccount()
                        currentAccountAddress = account?.address

                        saveSession(currentAccountAddress)

                        _connectionState.postValue(ConnectionState.Connected)
                        Log.d(TAG, "连接成功 - 地址: $currentAccountAddress")
                    } catch (e: Exception) {
                        Log.e(TAG, "提取账户地址失败: ${e.message}", e)
                        _connectionState.postValue(ConnectionState.Error("获取账户失败"))
                    }
                }

                override fun onSessionRejected(rejectedSession: Modal.Model.RejectedSession) {
                    Log.d(TAG, "会话被拒绝: ${rejectedSession.reason}")
                    cancelConnectionTimeout()
                    currentAccountAddress = null
                    clearSession()
                    _connectionState.postValue(ConnectionState.Disconnected)
                }

                override fun onSessionUpdate(updatedSession: Modal.Model.UpdatedSession) {
                    Log.d(TAG, "会话已更新")
                }

                override fun onSessionExtend(session: Modal.Model.Session) {
                    Log.d(TAG, "会话已延长")
                    saveSession(currentAccountAddress)
                }

                override fun onSessionEvent(sessionEvent: Modal.Model.SessionEvent) {
                    Log.d(TAG, "会话事件: ${sessionEvent.name}")
                }

                override fun onSessionDelete(deletedSession: Modal.Model.DeletedSession) {
                    Log.d(TAG, "会话已删除")
                    cancelConnectionTimeout()
                    currentAccountAddress = null
                    clearSession()
                    _connectionState.postValue(ConnectionState.Disconnected)
                }

                override fun onSessionRequestResponse(response: Modal.Model.SessionRequestResponse) {
                    Log.d(TAG, "收到会话请求响应")
                    handleSessionResponse(response)
                }

                override fun onProposalExpired(proposal: Modal.Model.ExpiredProposal) {
                    Log.d(TAG, "提案已过期")
                    _connectionState.postValue(ConnectionState.Error("连接提案已过期"))
                }

                override fun onRequestExpired(request: Modal.Model.ExpiredRequest) {
                    Log.d(TAG, "请求已过期")
                    _signResult.postValue(SignResult.Error("签名请求已过期"))
                }

                override fun onConnectionStateChange(state: Modal.Model.ConnectionState) {
                    Log.d(TAG, "连接状态变化: ${state.isAvailable}")
                }

                override fun onError(error: Modal.Model.Error) {
                    Log.e(TAG, "AppKit 错误: ${error.throwable.message}", error.throwable)
                    handleError(error.throwable.message)
                }
            }

            AppKit.setDelegate(appKitModalDelegate)

        } catch (e: Exception) {
            Log.e(TAG, "设置监听器异常: ${e.message}", e)
        }
    }

    /**
     * 处理会话响应
     */
    private fun handleSessionResponse(response: Modal.Model.SessionRequestResponse) {
        cancelRequestTimeout()
        val result = response.result

        when (result) {
            is Modal.Model.JsonRpcResponse.JsonRpcResult -> {
                val signature = result.result.toString()
                _signResult.postValue(SignResult.Success(signature))
                Log.d(TAG, "签名成功: $signature")
            }
            is Modal.Model.JsonRpcResponse.JsonRpcError -> {
                val errorMessage = "签名失败 (代码: ${result.code}): ${result.message}"
                _signResult.postValue(SignResult.Error(errorMessage))
                Log.e(TAG, errorMessage)
            }
            else -> {
                Log.w(TAG, "未知的响应类型: ${result.javaClass.simpleName}")
                _signResult.postValue(SignResult.Error("未知的响应类型"))
            }
        }
    }

    /**
     * 连接钱包
     * 提示: 实际打开钱包选择需要在 UI 层完成
     * - Compose: 使用 navController.openAppKit()
     * - View: 使用 AppKitComponent
     */
    fun connectWallet() {
        if (!isInitialized) {
            _connectionState.postValue(ConnectionState.Error("WalletConnect 未初始化"))
            return
        }

        try {
            Log.d(TAG, "开始连接钱包")
            _connectionState.postValue(ConnectionState.Connecting)

            startConnectionTimeout()

            Log.d(TAG, "请在 UI 层调用打开 AppKit Modal 的方法")

        } catch (e: Exception) {
            Log.e(TAG, "连接钱包异常: ${e.message}", e)
            _connectionState.postValue(ConnectionState.Error("连接异常: ${e.message}"))
            cancelConnectionTimeout()
        }
    }

    /**
     * 启动连接超时计时器
     */
    private fun startConnectionTimeout() {
        cancelConnectionTimeout()
        connectionTimeoutJob = scope.launch {
            kotlinx.coroutines.delay(CONNECTION_TIMEOUT)
            if (_connectionState.value is ConnectionState.Connecting) {
                Log.w(TAG, "连接超时")
                _connectionState.postValue(ConnectionState.Error("连接超时，请重试"))
            }
        }
    }

    /**
     * 取消连接超时计时器
     */
    private fun cancelConnectionTimeout() {
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
    }

    /**
     * 启动请求超时计时器
     */
    private fun startRequestTimeout() {
        cancelRequestTimeout()
        requestTimeoutJob = scope.launch {
            kotlinx.coroutines.delay(REQUEST_TIMEOUT)
            val currentResult = _signResult.value
            if (currentResult == null || currentResult is SignResult.Pending) {
                Log.w(TAG, "请求超时")
                _signResult.postValue(SignResult.Error("请求超时，请重试"))
            }
        }
    }

    /**
     * 取消请求超时计时器
     */
    private fun cancelRequestTimeout() {
        requestTimeoutJob?.cancel()
        requestTimeoutJob = null
    }

    /**
     * 断开钱包连接
     */
    fun disconnect() {
        try {
            Log.d(TAG, "断开钱包连接")

            AppKit.disconnect(
                onSuccess = {
                    currentAccountAddress = null
                    _connectionState.postValue(ConnectionState.Disconnected)
                    Log.d(TAG, "断开连接成功")
                },
                onError = { error ->
                    Log.e(TAG, "断开连接失败: ${error.message}", error)
                    // 即使失败也清除状态
                    currentAccountAddress = null
                    _connectionState.postValue(ConnectionState.Disconnected)
                }
            )

        } catch (e: Exception) {
            Log.e(TAG, "断开连接异常: ${e.message}", e)
            currentAccountAddress = null
            _connectionState.postValue(ConnectionState.Disconnected)
        }
    }

    /**
     * 发送 TRX 转账签名请求
     */
    fun sendPaymentRequest(toAddress: String, amount: String, fromAddress: String) {
        if (!isInitialized) {
            _signResult.postValue(SignResult.Error("WalletConnect 未初始化"))
            return
        }

        if (currentAccountAddress == null) {
            _signResult.postValue(SignResult.Error("钱包未连接"))
            return
        }

        scope.launch {
            try {
                Log.d(TAG, "发送 TRX 转账请求 - 从: $fromAddress, 到: $toAddress, 金额: $amount")

                validatePaymentRequest(toAddress, amount, fromAddress)

                val transactionJson = createTronTransactionData(toAddress, amount, fromAddress)

                val request = Request(
                    method = "tron_signTransaction",
                    params = transactionJson
                )

                AppKit.request(
                    request = request,
                    onSuccess = { result: SentRequestResult ->
                        Log.d(TAG, "签名请求已发送,等待用户确认")
                        _signResult.postValue(SignResult.Pending)
                        startRequestTimeout()
                    },
                    onError = { error: Throwable ->
                        Log.e(TAG, "发送签名请求失败: ${error.message}", error)
                        cancelRequestTimeout()
                        _signResult.postValue(SignResult.Error("发送请求失败: ${error.message}"))
                    }
                )

            } catch (e: SecurityException) {
                Log.e(TAG, "支付请求安全检查失败: ${e.message}", e)
                _signResult.postValue(SignResult.Error("安全检查失败: ${e.message}"))
            } catch (e: Exception) {
                Log.e(TAG, "发送支付请求异常: ${e.message}", e)
                _signResult.postValue(SignResult.Error("支付请求异常: ${e.message}"))
            }
        }
    }

    /**
     * 验证支付请求的完整性和安全性
     */
    private fun validatePaymentRequest(toAddress: String, amount: String, fromAddress: String) {
        SecurityConstraints.checkTronAddress(toAddress)
        SecurityConstraints.checkTronAddress(fromAddress)

        val amountSun = convertTrxToSun(amount).toLongOrNull()
            ?: throw SecurityException("金额格式错误")

        SecurityConstraints.checkTransactionAmount(amountSun)
        SecurityConstraints.checkTransactionType(com.trxsafe.payment.security.TransactionType.TRANSFER_CONTRACT)

        if (fromAddress != currentAccountAddress) {
            throw SecurityException("发送地址与当前连接钱包不匹配")
        }
    }

    /**
     * 创建 TRON 交易数据 (JSON 字符串格式)
     */
    private fun createTronTransactionData(toAddress: String, amount: String, fromAddress: String): String {
        // 转换金额为 sun
        val amountSun = convertTrxToSun(amount).toLongOrNull()
            ?: throw SecurityException("金额转换失败")

        // === 安全检查: 验证交易数据 ===
        SecurityConstraints.validateTransactionData("TransferContract", amountSun)

        val json = JSONObject().apply {
            put("from", fromAddress)
            put("to", toAddress)
            put("amount", amountSun)
            put("token_id", 0) // 0 表示 TRX
            put("contract_type", "TransferContract") // 硬编码,只允许这个类型!
        }
        return json.toString()
    }

    /**
     * 将 TRX 金额转换为 sun 单位
     * 1 TRX = 1,000,000 SUN
     *
     * 根据 SecurityPolicy.REQUIRE_LONG_AMOUNT = true
     * 必须使用 Long 类型处理金额
     */
    private fun convertTrxToSun(trxAmount: String): String {
        return try {
            val trxValue = trxAmount.toDouble()
            // 必须转换为 Long 类型
            val sunValue = (trxValue * 1_000_000).toLong()
            sunValue.toString()
        } catch (e: Exception) {
            Log.e(TAG, "转换金额失败: $trxAmount", e)
            "0"
        }
    }

    /**
     * 处理错误
     */
    private fun handleError(message: String?) {
        val errorMsg = message ?: "未知错误"
        _connectionState.postValue(ConnectionState.Error(errorMsg))
        Log.e(TAG, errorMsg)
    }

    /**
     * 获取当前连接的账户地址
     */
    fun getConnectedAddress(): String? {
        return currentAccountAddress
    }

    /**
     * 检查是否已连接
     */
    fun isConnected(): Boolean {
        return currentAccountAddress != null
    }

    /**
     * 获取当前活动账户 (如果可用)
     */
    fun getActiveAccountInfo(): String? {
        return try {
            val account = AppKit.getAccount()
            account?.toString()
        } catch (e: Exception) {
            Log.e(TAG, "获取活动账户失败: ${e.message}", e)
            null
        }
    }

    /**
     * 获取选中的链信息 (如果可用)
     */
    fun getSelectedChainInfo(): String? {
        return try {
            val chain = AppKit.getSelectedChain()
            chain?.toString()
        } catch (e: Exception) {
            Log.e(TAG, "获取选中链失败: ${e.message}", e)
            null
        }
    }

    /**
     * 测试连接状态
     */
    fun testConnection(): String {
        return when {
            !isInitialized -> "WalletConnect 未初始化"
            !isConnected() -> "钱包未连接"
            else -> "连接正常 - 地址: $currentAccountAddress"
        }
    }

    /**
     * 获取连接状态详情
     */
    fun getConnectionStatus(): Map<String, Any> {
        return mapOf(
            "isInitialized" to isInitialized,
            "isConnected" to isConnected(),
            "connectedAddress" to (currentAccountAddress ?: "未连接"),
            "activeAccount" to (getActiveAccountInfo() ?: "无"),
            "selectedChain" to (getSelectedChainInfo() ?: "未选择")
        )
    }

    /**
     * 销毁资源
     */
    fun destroy() {
        try {
            cancelConnectionTimeout()
            cancelRequestTimeout()
            disconnect()
            currentAccountAddress = null
        } catch (e: Exception) {
            Log.e(TAG, "销毁资源异常: ${e.message}", e)
        }
    }

    /**
     * 保存会话信息到持久化存储
     */
    private fun saveSession(address: String?) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().apply {
                putString(KEY_CONNECTED_ADDRESS, address)
                putLong(KEY_SESSION_EXPIRY, System.currentTimeMillis() + 86400000)
                apply()
            }
            Log.d(TAG, "会话已保存: $address")
        } catch (e: Exception) {
            Log.e(TAG, "保存会话失败: ${e.message}", e)
        }
    }

    /**
     * 清除持久化的会话信息
     */
    private fun clearSession() {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
            Log.d(TAG, "会话已清除")
        } catch (e: Exception) {
            Log.e(TAG, "清除会话失败: ${e.message}", e)
        }
    }

    /**
     * 恢复会话
     */
    private fun restoreSession() {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedAddress = prefs.getString(KEY_CONNECTED_ADDRESS, null)
            val expiryTime = prefs.getLong(KEY_SESSION_EXPIRY, 0)

            if (savedAddress != null && expiryTime > System.currentTimeMillis()) {
                try {
                    val account = AppKit.getAccount()
                    if (account != null && account.address == savedAddress) {
                        currentAccountAddress = savedAddress
                        _connectionState.postValue(ConnectionState.Connected)
                        Log.d(TAG, "会话已恢复: $savedAddress")
                    } else {
                        clearSession()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "无法恢复会话: ${e.message}")
                    clearSession()
                }
            } else {
                clearSession()
            }
        } catch (e: Exception) {
            Log.e(TAG, "恢复会话异常: ${e.message}", e)
            clearSession()
        }
    }

    /**
     * 连接状态密封类
     */
    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    /**
     * 签名结果密封类
     */
    sealed class SignResult {
        object Pending : SignResult()
        data class Success(val signature: String) : SignResult()
        data class Error(val message: String) : SignResult()
    }
}