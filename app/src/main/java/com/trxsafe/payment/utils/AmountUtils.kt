package com.trxsafe.payment.utils

/**
 * 金额工具类
 * 硬性约束：所有金额使用 long 类型（sun），禁止 float/double
 * 
 * 单位说明：
 * - 1 TRX = 1,000,000 sun（微单位）
 * - 所有内部计算使用 sun（long 类型）
 * - 显示时转换为 TRX（保留 6 位小数）
 */
object AmountUtils {
    
    /**
     * TRX 与 sun 的转换比例
     * 1 TRX = 1,000,000 sun
     */
    const val SUN_PER_TRX: Long = 1_000_000L
    
    /**
     * 将 TRX 转换为 sun
     * 硬性约束：输入必须是字符串，避免浮点数精度问题
     * 
     * @param trxAmount TRX 金额字符串（例如："1.5"）
     * @return sun 金额（long 类型）
     * @throws IllegalArgumentException 输入格式错误时抛出
     */
    @Throws(IllegalArgumentException::class)
    fun trxToSun(trxAmount: String): Long {
        try {
            // 移除空格
            val cleanAmount = trxAmount.trim()
            
            // 检查是否为空
            if (cleanAmount.isEmpty()) {
                throw IllegalArgumentException("金额不能为空")
            }
            
            // 检查是否包含非法字符
            if (!cleanAmount.matches(Regex("^\\d+(\\.\\d{1,6})?$"))) {
                throw IllegalArgumentException("金额格式错误，仅支持数字和小数点，小数位最多 6 位")
            }
            
            // 分割整数和小数部分
            val parts = cleanAmount.split(".")
            val integerPart = parts[0].toLongOrNull() ?: 0L
            val decimalPart = if (parts.size > 1) parts[1] else "0"
            
            // 补齐小数部分到 6 位
            val paddedDecimal = decimalPart.padEnd(6, '0')
            
            // 计算总的 sun 数量
            val sunAmount = integerPart * SUN_PER_TRX + paddedDecimal.toLong()
            
            // 检查是否溢出
            if (sunAmount < 0) {
                throw IllegalArgumentException("金额溢出")
            }
            
            return sunAmount
            
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("金额格式错误：${e.message}", e)
        }
    }
    
    /**
     * 将 sun 转换为 TRX 字符串
     *
     * @param sunAmount sun 金额（long 类型）
     * @param decimals 保留小数位数（默认 6 位）
     * @param trimTrailingZeros 是否去除尾部零（默认 true）
     * @return TRX 金额字符串
     */
    fun sunToTrx(sunAmount: Long, decimals: Int = 6, trimTrailingZeros: Boolean = true): String {
        if (sunAmount < 0) {
            throw IllegalArgumentException("金额不能为负数")
        }

        // 计算整数部分和小数部分
        val integerPart = sunAmount / SUN_PER_TRX
        val decimalPart = sunAmount % SUN_PER_TRX

        // 格式化小数部分
        val decimalStr = decimalPart.toString().padStart(6, '0')
        val trimmedDecimal = decimalStr.substring(0, decimals.coerceAtMost(6))
            .let { if (trimTrailingZeros) it.trimEnd('0') else it }

        // 返回格式化后的字符串
        return if (trimmedDecimal.isEmpty() || (trimTrailingZeros && trimmedDecimal.all { it == '0' })) {
            integerPart.toString()
        } else {
            "$integerPart.$trimmedDecimal"
        }
    }
    
    /**
     * 格式化显示金额（带单位）
     * 
     * @param sunAmount sun 金额
     * @return 格式化字符串（例如："1.5 TRX"）
     */
    fun formatAmount(sunAmount: Long): String {
        return "${sunToTrx(sunAmount)} TRX"
    }
    
    /**
     * 格式化显示金额（带千位分隔符）
     * 
     * @param sunAmount sun 金额
     * @return 格式化字符串（例如："1,234.56 TRX"）
     */
    fun formatAmountWithSeparator(sunAmount: Long): String {
        val trxAmount = sunToTrx(sunAmount)
        val parts = trxAmount.split(".")
        
        // 整数部分添加千位分隔符
        val integerWithSeparator = parts[0].reversed()
            .chunked(3)
            .joinToString(",")
            .reversed()
        
        // 组合整数和小数部分
        val formatted = if (parts.size > 1) {
            "$integerWithSeparator.${parts[1]}"
        } else {
            integerWithSeparator
        }
        
        return "$formatted TRX"
    }
    
    /**
     * 验证金额字符串格式
     * 
     * @param amountStr 金额字符串
     * @return true 表示格式正确
     */
    fun isValidAmount(amountStr: String): Boolean {
        return try {
            trxToSun(amountStr)
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 比较两个金额
     * 
     * @param amount1 金额 1（sun）
     * @param amount2 金额 2（sun）
     * @return -1: amount1 < amount2, 0: amount1 == amount2, 1: amount1 > amount2
     */
    fun compareAmounts(amount1: Long, amount2: Long): Int {
        return amount1.compareTo(amount2)
    }
}
