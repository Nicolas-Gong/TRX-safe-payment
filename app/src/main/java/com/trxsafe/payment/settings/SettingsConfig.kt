package com.trxsafe.payment.settings

/**
 * 设置配置数据模型
 * 用于卖能量功能的配置管理
 */
data class SettingsConfig(
    /**
     * 卖能量收款地址
     * - 必须是有效的 TRON Base58 地址
     * - 禁止合约地址
     */
    val sellerAddress: String = "",
    
    /**
     * 单价（单位：sun）
     * - 范围：1000 ～ 10_000_000 sun
     * - > 10 TRX (10_000_000 sun) 需要警告
     */
    val pricePerUnitSun: Long = 0L,
    
    /**
     * 倍率
     * - 范围：1 ～ 10
     */
    val multiplier: Int = 1,
    
    /**
     * 单价是否已锁定
     * - 保存后自动锁定
     * - 需要手动解锁才能修改
     */
    val isPriceLocked: Boolean = false,
    
    /**
     * 是否首次设置地址
     * - 用于判断是否需要二次确认
     */
    val isFirstTimeSetAddress: Boolean = true,
    
    /**
     * 是否启用生物识别
     */
    val isBiometricEnabled: Boolean = false,
    
    /**
     * 当前选中的节点 URL
     */
    val nodeUrl: String = NodeConfig.MAINNET.grpcUrl
) {
    /**
     * 计算最终金额（单位：sun）
     * totalAmountSun = pricePerUnitSun * multiplier
     */
    fun getTotalAmountSun(): Long {
        return pricePerUnitSun * multiplier
    }
    
    /**
     * 检查配置是否完整
     */
    fun isConfigComplete(): Boolean {
        return sellerAddress.isNotEmpty() && 
               pricePerUnitSun > 0 && 
               multiplier > 0
    }
    
    /**
     * 获取总金额的 TRX 表示（用于显示）
     */
    fun getTotalAmountTrx(): String {
        val totalSun = getTotalAmountSun()
        return com.trxsafe.payment.utils.AmountUtils.sunToTrx(totalSun)
    }
}

/**
 * 设置配置的验证结果
 */
sealed class SettingsValidationResult {
    /**
     * 验证成功
     */
    object Success : SettingsValidationResult()
    
    /**
     * 验证失败
     * @param message 错误信息
     * @param field 出错的字段名
     */
    data class Error(
        val message: String,
        val field: SettingsField
    ) : SettingsValidationResult()
    
    /**
     * 需要警告（但可以继续）
     * @param message 警告信息
     * @param requiresConfirmation 是否需要用户确认
     */
    data class Warning(
        val message: String,
        val requiresConfirmation: Boolean = true
    ) : SettingsValidationResult()
}

/**
 * 设置字段枚举
 */
enum class SettingsField {
    SELLER_ADDRESS,
    PRICE_PER_UNIT,
    MULTIPLIER
}
