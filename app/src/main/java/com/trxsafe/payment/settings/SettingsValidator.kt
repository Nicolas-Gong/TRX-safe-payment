package com.trxsafe.payment.settings

import com.trxsafe.payment.wallet.WalletManager
import org.tron.trident.utils.Base58Check

/**
 * 设置配置验证器
 * 负责验证所有设置项的合法性
 */
class SettingsValidator(
    private val walletManager: WalletManager = WalletManager()
) {
    
    companion object {
        /**
         * 单价最小值（sun）
         */
        const val MIN_PRICE_SUN = 1000L
        
        /**
         * 单价最大值（sun）
         */
        const val MAX_PRICE_SUN = 10_000_000L
        
        /**
         * 单价警告阈值（10 TRX = 10_000_000 sun）
         */
        const val PRICE_WARNING_THRESHOLD_SUN = 10_000_000L
        
        /**
         * 倍率最小值
         */
        const val MIN_MULTIPLIER = 1
        
        /**
         * 倍率最大值
         */
        const val MAX_MULTIPLIER = 10
    }
    
    /**
     * 验证收款地址
     * 
     * @param address TRON 地址字符串
     * @return 验证结果
     */
    fun validateSellerAddress(address: String): SettingsValidationResult {
        // 检查是否为空
        if (address.isEmpty()) {
            return SettingsValidationResult.Error(
                message = "收款地址不能为空",
                field = SettingsField.SELLER_ADDRESS
            )
        }
        
        // 验证地址格式
        if (!walletManager.isValidAddress(address)) {
            return SettingsValidationResult.Error(
                message = "地址格式不正确，TRON 地址应以 T 开头，长度为 34 位",
                field = SettingsField.SELLER_ADDRESS
            )
        }
        
        // 检查是否为合约地址
        if (isContractAddress(address)) {
            return SettingsValidationResult.Error(
                message = "禁止使用合约地址作为收款地址",
                field = SettingsField.SELLER_ADDRESS
            )
        }
        
        return SettingsValidationResult.Success
    }
    
    /**
     * 验证单价
     * 
     * @param priceSun 单价（单位：sun）
     * @return 验证结果
     */
    fun validatePrice(priceSun: Long): SettingsValidationResult {
        // 检查是否为正数
        if (priceSun <= 0) {
            return SettingsValidationResult.Error(
                message = "单价必须大于 0",
                field = SettingsField.PRICE_PER_UNIT
            )
        }
        
        // 检查最小值
        if (priceSun < MIN_PRICE_SUN) {
            return SettingsValidationResult.Error(
                message = "单价不能低于 ${MIN_PRICE_SUN} sun",
                field = SettingsField.PRICE_PER_UNIT
            )
        }
        
        // 检查最大值
        if (priceSun > MAX_PRICE_SUN) {
            return SettingsValidationResult.Error(
                message = "单价不能超过 ${MAX_PRICE_SUN} sun",
                field = SettingsField.PRICE_PER_UNIT
            )
        }
        
        // 警告：单价超过 10 TRX
        if (priceSun > PRICE_WARNING_THRESHOLD_SUN) {
            val trxAmount = com.trxsafe.payment.utils.AmountUtils.sunToTrx(priceSun)
            return SettingsValidationResult.Warning(
                message = "单价较高（${trxAmount} TRX），请确认是否正确设置",
                requiresConfirmation = true
            )
        }
        
        return SettingsValidationResult.Success
    }
    
    /**
     * 验证倍率
     * 
     * @param multiplier 倍率
     * @return 验证结果
     */
    fun validateMultiplier(multiplier: Int): SettingsValidationResult {
        // 检查最小值
        if (multiplier < MIN_MULTIPLIER) {
            return SettingsValidationResult.Error(
                message = "倍率不能小于 ${MIN_MULTIPLIER}",
                field = SettingsField.MULTIPLIER
            )
        }
        
        // 检查最大值
        if (multiplier > MAX_MULTIPLIER) {
            return SettingsValidationResult.Error(
                message = "倍率不能大于 ${MAX_MULTIPLIER}",
                field = SettingsField.MULTIPLIER
            )
        }
        
        return SettingsValidationResult.Success
    }
    
    /**
     * 验证完整配置
     * 
     * @param config 配置对象
     * @return 验证结果
     */
    fun validateConfig(config: SettingsConfig): SettingsValidationResult {
        // 验证地址
        val addressResult = validateSellerAddress(config.sellerAddress)
        if (addressResult !is SettingsValidationResult.Success) {
            return addressResult
        }
        
        // 验证单价
        val priceResult = validatePrice(config.pricePerUnitSun)
        if (priceResult !is SettingsValidationResult.Success) {
            return priceResult
        }
        
        // 验证倍率
        val multiplierResult = validateMultiplier(config.multiplier)
        if (multiplierResult !is SettingsValidationResult.Success) {
            return multiplierResult
        }
        
        return SettingsValidationResult.Success
    }
    
    /**
     * 检查地址是否为合约地址
     * 
     * @param address TRON 地址
     * @return true 表示是合约地址
     */
    private fun isContractAddress(address: String): Boolean {
        try {
            // 解码 Base58 地址
            val decoded = Base58Check.base58ToBytes(address)
            
            // TRON 地址第一个字节：
            // - 0x41: 普通地址
            // - 0x5a: 合约地址（TVM）
            if (decoded.isNotEmpty() && decoded[0] == 0x5a.toByte()) {
                return true
            }
            
            // 注意：这只是基本检查
            // 更准确的方法是查询链上数据判断账户类型
            // 但这里为了简化，只做前缀检查
            
            return false
        } catch (e: Exception) {
            // 解码失败，认为不是合约地址（应该在地址格式验证阶段被拦截）
            return false
        }
    }
    
    /**
     * 验证单价字符串输入
     * 
     * @param priceStr 单价字符串（TRX 格式）
     * @return 验证结果，如果成功则包含转换后的 sun 值
     */
    fun validatePriceInput(priceStr: String): Pair<SettingsValidationResult, Long?> {
        return try {
            // 使用 AmountUtils 转换
            val priceSun = com.trxsafe.payment.utils.AmountUtils.trxToSun(priceStr)
            val result = validatePrice(priceSun)
            Pair(result, priceSun)
        } catch (e: IllegalArgumentException) {
            Pair(
                SettingsValidationResult.Error(
                    message = "单价格式错误：${e.message}",
                    field = SettingsField.PRICE_PER_UNIT
                ),
                null
            )
        }
    }
    
    /**
     * 验证倍率字符串输入
     * 
     * @param multiplierStr 倍率字符串
     * @return 验证结果，如果成功则包含转换后的倍率值
     */
    fun validateMultiplierInput(multiplierStr: String): Pair<SettingsValidationResult, Int?> {
        return try {
            val multiplier = multiplierStr.toIntOrNull()
            if (multiplier == null) {
                return Pair(
                    SettingsValidationResult.Error(
                        message = "倍率必须是整数",
                        field = SettingsField.MULTIPLIER
                    ),
                    null
                )
            }
            val result = validateMultiplier(multiplier)
            Pair(result, multiplier)
        } catch (e: Exception) {
            Pair(
                SettingsValidationResult.Error(
                    message = "倍率格式错误",
                    field = SettingsField.MULTIPLIER
                ),
                null
            )
        }
    }
}
