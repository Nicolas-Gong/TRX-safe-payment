package com.trxsafe.payment.security

/**
 * 安全约束检查器
 * 在应用启动时强制执行硬性安全策略
 */
object SecurityConstraints {

    private var walletConnectEnabled = false

    /**
     * 设置 WalletConnect 的启用状态
     */
    fun setWalletConnectEnabled(enabled: Boolean) {
        walletConnectEnabled = enabled
        android.util.Log.d("SecurityConstraints", "WalletConnect enabled: $enabled")
    }

    /**
     * 检查 WalletConnect 是否已启用
     */
    fun isWalletConnectEnabled(): Boolean {
        return walletConnectEnabled
    }

    /**
     * 确保 WalletConnect 已被禁用
     *
     * 注意: WalletConnect 可以安全地用于 TRX 转账签名
     * 只要只处理 TRANSFER_CONTRACT 类型的交易,就不会违反安全约束
     */
    fun ensureWalletConnectDisabled() {
        if (walletConnectEnabled) {
            throw SecurityException("Security Violation: WalletConnect must be disabled")
        }
    }

    /**
     * 确保禁用了 DApp 浏览器
     */
    fun ensureDAppBrowserDisabled() {
        if (SecurityPolicy.ALLOW_DAPP_BROWSER) {
            throw SecurityException("Security Violation: DApp Browser must be disabled")
        }
    }

    /**
     * 确保仅允许 TRX 转账
     */
    fun ensureOnlyTrxTransfer() {
        // 由 SecurityPolicy.ALLOWED_TRANSACTION_TYPES 保证
        if (SecurityPolicy.ALLOWED_TRANSACTION_TYPES.size != 1 ||
            TransactionType.TRANSFER_CONTRACT !in SecurityPolicy.ALLOWED_TRANSACTION_TYPES) {
            throw SecurityException("Security Violation: Only TRX transfers (TRANSFER_CONTRACT) are allowed")
        }
    }

    /**
     * 确保禁用了智能合约交互
     */
    fun ensureSmartContractDisabled() {
        if (SecurityPolicy.ALLOW_SMART_CONTRACT) {
            throw SecurityException("Security Violation: Smart Contract interaction must be disabled")
        }
    }

    /**
     * 确保禁用了 TRC20 交互
     */
    fun ensureTrc20Disabled() {
        // TRC20 是智能合约的一部分,由 ALLOW_SMART_CONTRACT 检查
        if (SecurityPolicy.ALLOW_SMART_CONTRACT) {
            throw SecurityException("Security Violation: TRC20 must be disabled")
        }

        // 额外检查: TRC20_TRANSFER 必须在禁止列表中
        if (TransactionType.TRC20_TRANSFER !in SecurityPolicy.FORBIDDEN_TRANSACTION_TYPES) {
            throw SecurityException("Security Violation: TRC20_TRANSFER must be in forbidden list")
        }
    }

    /**
     * 检查是否为有效且受允许的交易类型
     * @throws SecurityException 如果交易类型不被允许
     */
    fun checkTransactionType(type: TransactionType) {
        // 检查是否在允许列表中
        if (type !in SecurityPolicy.ALLOWED_TRANSACTION_TYPES) {
            throw SecurityException(
                "Security Violation: Transaction type $type is forbidden. " +
                        "Only TRANSFER_CONTRACT (TRX transfers) are allowed."
            )
        }

        // 检查是否在禁止列表中 (双重保险)
        if (type in SecurityPolicy.FORBIDDEN_TRANSACTION_TYPES) {
            throw SecurityException(
                "Security Violation: Transaction type $type is explicitly forbidden."
            )
        }
    }

    /**
     * 检查交易金额是否在允许范围内
     * @param amountSun 金额 (单位: sun)
     * @throws SecurityException 如果金额不在允许范围内
     */
    fun checkTransactionAmount(amountSun: Long) {
        if (amountSun < SecurityPolicy.MIN_TRANSFER_AMOUNT_SUN) {
            throw SecurityException(
                "Security Violation: Transfer amount $amountSun sun is below minimum " +
                        "${SecurityPolicy.MIN_TRANSFER_AMOUNT_SUN} sun"
            )
        }

        if (amountSun > SecurityPolicy.MAX_TRANSFER_AMOUNT_SUN) {
            throw SecurityException(
                "Security Violation: Transfer amount $amountSun sun exceeds maximum " +
                        "${SecurityPolicy.MAX_TRANSFER_AMOUNT_SUN} sun"
            )
        }
    }

    /**
     * 验证 TRON 交易数据的安全性
     * @param contractType 合约类型
     * @param amountSun 金额 (单位: sun)
     * @throws SecurityException 如果交易不符合安全策略
     */
    fun validateTransactionData(contractType: String, amountSun: Long) {
        // 1. 检查合约类型
        if (contractType != "TransferContract") {
            throw SecurityException(
                "Security Violation: Contract type '$contractType' is forbidden. " +
                        "Only 'TransferContract' is allowed."
            )
        }

        // 2. 检查金额
        checkTransactionAmount(amountSun)

        // 3. 记录日志
        android.util.Log.d(
            "SecurityConstraints",
            "Transaction validated: type=$contractType, amount=$amountSun sun"
        )
    }

    /**
     * 检查地址格式
     * @param address TRON 地址
     * @throws SecurityException 如果地址格式无效
     */
    fun checkTronAddress(address: String) {
        // TRON 地址必须以 T 开头,长度为 34
        if (!address.startsWith("T") || address.length != 34) {
            throw SecurityException(
                "Security Violation: Invalid TRON address format: $address"
            )
        }
    }
}

/*
 * ===== WalletConnect 集成安全检查清单 =====
 *
 * 在 WalletConnectManager 中实现以下安全检查:
 *
 * 1. 创建交易前验证:
 *
 * fun sendPaymentRequest(toAddress: String, amount: String, fromAddress: String) {
 *     // 检查地址格式
 *     SecurityConstraints.checkTronAddress(toAddress)
 *     SecurityConstraints.checkTronAddress(fromAddress)
 *
 *     // 检查交易类型
 *     SecurityConstraints.checkTransactionType(TransactionType.TRANSFER_CONTRACT)
 *
 *     // 转换并检查金额
 *     val amountSun = convertTrxToSun(amount).toLong()
 *     SecurityConstraints.checkTransactionAmount(amountSun)
 *
 *     // 创建交易
 *     val transactionData = createTronTransactionData(toAddress, amount, fromAddress)
 *
 *     // ...发送签名请求
 * }
 *
 *
 * 2. 创建交易数据时验证:
 *
 * private fun createTronTransactionData(...): String {
 *     val amountSun = convertTrxToSun(amount).toLong()
 *
 *     // 验证交易数据
 *     SecurityConstraints.validateTransactionData("TransferContract", amountSun)
 *
 *     val json = JSONObject().apply {
 *         put("from", fromAddress)
 *         put("to", toAddress)
 *         put("amount", amountSun)
 *         put("token_id", 0)
 *         put("contract_type", "TransferContract") // 只能是这个!
 *     }
 *     return json.toString()
 * }
 *
 *
 * 3. 异常处理策略:
 *
 * 根据 SecurityPolicy.REJECT_ON_ANY_EXCEPTION = true
 * 任何异常都必须拒绝签名请求:
 *
 * try {
 *     // 执行签名操作
 * } catch (e: Exception) {
 *     Log.e(TAG, "签名失败: ${e.message}", e)
 *     _signResult.postValue(SignResult.Error("签名失败: ${e.message}"))
 *     // 不要重试,直接拒绝
 * }
 *
 *
 * 4. 金额类型检查:
 *
 * 根据 SecurityPolicy.REQUIRE_LONG_AMOUNT = true
 * 必须使用 Long 类型处理金额:
 *
 * private fun convertTrxToSun(trxAmount: String): String {
 *     val trxValue = trxAmount.toDouble()
 *     val sunValue = (trxValue * 1_000_000).toLong() // 必须转换为 Long
 *     return sunValue.toString()
 * }
 */