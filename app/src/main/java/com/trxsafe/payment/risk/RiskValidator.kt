package com.trxsafe.payment.risk

import com.google.protobuf.ByteString
import com.trxsafe.payment.settings.SettingsConfig
import org.tron.trident.proto.Chain
import org.tron.trident.proto.Contract

/**
 * 风险等级枚举
 */
enum class RiskLevel {
    /**
     * 放行 - 交易安全，可以继续
     */
    PASS,
    
    /**
     * 警告 - 存在风险，但允许继续（需要提示用户）
     */
    WARN,
    
    /**
     * 阻止 - 高风险或不符合规则，必须阻止
     */
    BLOCK
}

/**
 * 风险校验结果
 * 
 * @property level 风险等级
 * @property message 提示信息
 * @property requiresConfirmation 是否需要用户二次确认
 */
data class RiskCheckResult(
    val level: RiskLevel,
    val message: String,
    val requiresConfirmation: Boolean = false
) {
    companion object {
        /**
         * 创建放行结果
         */
        fun pass(message: String = "风控检查通过"): RiskCheckResult {
            return RiskCheckResult(RiskLevel.PASS, message, false)
        }
        
        /**
         * 创建警告结果
         */
        fun warn(message: String, requiresConfirmation: Boolean = false): RiskCheckResult {
            return RiskCheckResult(RiskLevel.WARN, message, requiresConfirmation)
        }
        
        /**
         * 创建阻止结果
         */
        fun block(message: String): RiskCheckResult {
            return RiskCheckResult(RiskLevel.BLOCK, message, false)
        }
    }
}

/**
 * 交易风控校验器
 * 
 * 白名单规则（全部满足才放行）：
 * - 交易类型 == TransferContract
 * - token == TRX
 * - data 为空
 * - amount > 0
 * - amount == pricePerUnitSun * multiplier
 * 
 * 额外风控：
 * - 单价 < 0.001 TRX (1000 sun)：风险提示
 * - 单价 > 1 TRX (1_000_000 sun)：高风险提示
 * - 单价 > 10 TRX (10_000_000 sun)：必须二次确认
 */
class RiskValidator {
    
    companion object {
        /**
         * 单价风险阈值 - 低于此值为异常低价（sun）
         * 0.001 TRX = 1000 sun
         */
        private const val PRICE_LOW_RISK_THRESHOLD = 1000L
        
        /**
         * 单价风险阈值 - 高于此值为高价（sun）
         * 1 TRX = 1_000_000 sun
         */
        private const val PRICE_HIGH_RISK_THRESHOLD = 1_000_000L
        
        /**
         * 单价风险阈值 - 高于此值必须二次确认（sun）
         * 10 TRX = 10_000_000 sun
         */
        private const val PRICE_CONFIRMATION_THRESHOLD = 10_000_000L
    }
    
    /**
     * 执行完整的风控检查
     * 
     * @param transaction 待检查的交易
     * @param config Settings 配置
     * @param isWhitelisted 收款地址是否在白名单中
     * @return 风控检查结果
     */
    fun checkRisk(
        transaction: Chain.Transaction,
        config: SettingsConfig,
        isWhitelisted: Boolean = false
    ): RiskCheckResult {
        
        // 1. 白名单规则检查（基本协议规则，任一不通过直接 BLOCK）
        val protocolResult = checkWhitelist(transaction, config)
        if (protocolResult.level == RiskLevel.BLOCK) {
            return protocolResult
        }
        
        // 2. 价格风险检查
        val priceRiskResult = checkPriceRisk(config.pricePerUnitSun)
        if (priceRiskResult.level == RiskLevel.BLOCK) {
            return priceRiskResult
        }
        
        // 3. 业务白名单检查 (Address Book Whitelist)
        if (!isWhitelisted) {
            // 如果地址不在白名单，风险等级自动提升至 WARN
            return RiskCheckResult.warn("风险提示：该地址不在您的白名单地址簿中，请仔细核对！", requiresConfirmation = true)
        }
        
        // 4. 重合检查 (如果价格有警告且不在白名单)
        if (priceRiskResult.level == RiskLevel.WARN) {
             return priceRiskResult
        }
        
        // 5. 所有检查通过
        return RiskCheckResult.pass("风控检查通过")
    }
    
    /**
     * 基本协议规则检查
     * 全部满足才通过，任一不满足则阻止 (BLOCK)
     * 内部使用 TransactionValidator 进行硬性安全约束检查
     * 
     * @param transaction 交易对象
     * @param config Settings 配置
     * @return 检查结果
     */
    private fun checkWhitelist(
        transaction: Chain.Transaction,
        config: SettingsConfig
    ): RiskCheckResult {
        return try {
            val validator = com.trxsafe.payment.transaction.TransactionValidator()
            // 提取 from 地址用于验证
            val fromAddress = org.tron.trident.utils.Base58Check.bytesToBase58(
                Contract.TransferContract.parseFrom(
                    transaction.rawData.getContract(0).parameter.value
                ).ownerAddress.toByteArray()
            )
            
            validator.validateTransactionWithConfig(transaction, config, fromAddress)
            RiskCheckResult.pass("协议白名单检查通过")
        } catch (e: Exception) {
            RiskCheckResult.block("协议违规：${e.message}")
        }
    }
    
    /**
     * 价格风险检查
     * 
     * @param priceSun 单价（sun）
     * @return 检查结果
     */
    private fun checkPriceRisk(priceSun: Long): RiskCheckResult {
        
        // 风险 1：单价异常低（< 0.001 TRX）
        if (priceSun < PRICE_LOW_RISK_THRESHOLD) {
            val priceTrx = com.trxsafe.payment.utils.AmountUtils.sunToTrx(priceSun)
            return RiskCheckResult.warn(
                "风险提示：单价异常低（$priceTrx TRX），请确认是否正确",
                requiresConfirmation = false
            )
        }
        
        // 风险 2：单价超过 10 TRX（必须二次确认）
        if (priceSun > PRICE_CONFIRMATION_THRESHOLD) {
            val priceTrx = com.trxsafe.payment.utils.AmountUtils.sunToTrx(priceSun)
            return RiskCheckResult.warn(
                "高风险警告：单价过高（$priceTrx TRX），需要二次确认",
                requiresConfirmation = true
            )
        }
        
        // 风险 3：单价较高（> 1 TRX）
        if (priceSun > PRICE_HIGH_RISK_THRESHOLD) {
            val priceTrx = com.trxsafe.payment.utils.AmountUtils.sunToTrx(priceSun)
            return RiskCheckResult.warn(
                "高风险提示：单价较高（$priceTrx TRX），请仔细核对",
                requiresConfirmation = false
            )
        }
        
        // 价格正常
        return RiskCheckResult.pass()
    }
    
    /**
     * 仅检查白名单规则
     * 
     * @param transaction 交易对象
     * @param config Settings 配置
     * @return 检查结果
     */
    fun checkWhitelistOnly(
        transaction: Chain.Transaction,
        config: SettingsConfig
    ): RiskCheckResult {
        return checkWhitelist(transaction, config)
    }
    
    /**
     * 仅检查价格风险
     * 
     * @param priceSun 单价（sun）
     * @return 检查结果
     */
    fun checkPriceRiskOnly(priceSun: Long): RiskCheckResult {
        return checkPriceRisk(priceSun)
    }
    
    /**
     * 检查交易是否需要用户确认
     * 
     * @param transaction 交易对象
     * @param config Settings 配置
     * @return true 表示需要用户确认
     */
    fun requiresUserConfirmation(
        transaction: Chain.Transaction,
        config: SettingsConfig
    ): Boolean {
        val result = checkRisk(transaction, config)
        return result.requiresConfirmation
    }
}
