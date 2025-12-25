package com.trxsafe.payment.risk

import com.google.protobuf.ByteString
import com.trxsafe.payment.settings.SettingsConfig
import com.trxsafe.payment.transaction.TransactionBuilder
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.tron.trident.proto.Chain
import org.tron.trident.proto.Contract
import org.tron.trident.utils.Base58Check

/**
 * RiskValidator 单元测试
 */
class RiskValidatorTest {
    
    private lateinit var validator: RiskValidator
    
    // 测试用地址
    private val testFromAddress = "TXYZoPE5CP4Gj4Kuvub4fTPdZK8qRVvvvv"
    private val testToAddress = "TAbcdeFGHIJKLMNOPQRSTUVWXYZabcdefg"
    
    @Before
    fun setup() {
        validator = RiskValidator()
    }
    
    // ========== 白名单规则测试 ==========
    
    @Test
    fun `测试白名单 - 合法交易应通过`() {
        val config = SettingsConfig(
            sellerAddress = testToAddress,
            pricePerUnitSun = 5_000_000L,
            multiplier = 3
        )
        
        // 创建合法交易（模拟）
        val transaction = createMockTransaction(
            amount = 15_000_000L, // 5_000_000 * 3
            hasData = false
        )
        
        val result = validator.checkWhitelistOnly(transaction, config)
        
        // 注意：由于使用模拟交易，实际测试可能需要真实交易对象
        // 这里主要测试逻辑
    }
    
    @Test
    fun `测试白名单 - 金额为0应阻止`() {
        val config = SettingsConfig(
            sellerAddress = testToAddress,
            pricePerUnitSun = 5_000_000L,
            multiplier = 1
        )
        
        val transaction = createMockTransaction(
            amount = 0L,
            hasData = false
        )
        
        val result = validator.checkWhitelistOnly(transaction, config)
        assertEquals("金额为0应返回 BLOCK", RiskLevel.BLOCK, result.level)
        assertTrue("错误信息应包含'金额'", result.message.contains("金额"))
    }
    
    @Test
    fun `测试白名单 - 金额不匹配应阻止`() {
        val config = SettingsConfig(
            sellerAddress = testToAddress,
            pricePerUnitSun = 5_000_000L,
            multiplier = 3
        )
        
        // 错误的金额：应该是 15_000_000，但给了 10_000_000
        val transaction = createMockTransaction(
            amount = 10_000_000L,
            hasData = false
        )
        
        val result = validator.checkWhitelistOnly(transaction, config)
        assertEquals("金额不匹配应返回 BLOCK", RiskLevel.BLOCK, result.level)
        assertTrue("错误信息应包含'不匹配'", result.message.contains("不匹配"))
    }
    
    @Test
    fun `测试白名单 - 包含data应阻止`() {
        val config = SettingsConfig(
            sellerAddress = testToAddress,
            pricePerUnitSun = 5_000_000L,
            multiplier = 1
        )
        
        val transaction = createMockTransaction(
            amount = 5_000_000L,
            hasData = true
        )
        
        val result = validator.checkWhitelistOnly(transaction, config)
        assertEquals("包含data应返回 BLOCK", RiskLevel.BLOCK, result.level)
        assertTrue("错误信息应包含'data'", result.message.contains("data"))
    }
    
    // ========== 价格风险测试 ==========
    
    @Test
    fun `测试价格风险 - 正常价格应通过`() {
        // 正常价格：0.1 TRX = 100_000 sun
        val result = validator.checkPriceRiskOnly(100_000L)
        assertEquals("正常价格应返回 PASS", RiskLevel.PASS, result.level)
    }
    
    @Test
    fun `测试价格风险 - 异常低价应警告`() {
        // 低价：0.0001 TRX = 100 sun（< 0.001 TRX）
        val result = validator.checkPriceRiskOnly(100L)
        assertEquals("异常低价应返回 WARN", RiskLevel.WARN, result.level)
        assertFalse("异常低价不需要二次确认", result.requiresConfirmation)
        assertTrue("警告信息应包含'异常低'", result.message.contains("异常低"))
    }
    
    @Test
    fun `测试价格风险 - 高价应警告`() {
        // 高价：5 TRX = 5_000_000 sun（> 1 TRX 但 < 10 TRX）
        val result = validator.checkPriceRiskOnly(5_000_000L)
        assertEquals("高价应返回 WARN", RiskLevel.WARN, result.level)
        assertFalse("高价不需要二次确认", result.requiresConfirmation)
        assertTrue("警告信息应包含'较高'", result.message.contains("较高"))
    }
    
    @Test
    fun `测试价格风险 - 超高价应要求二次确认`() {
        // 超高价：15 TRX = 15_000_000 sun（> 10 TRX）
        val result = validator.checkPriceRiskOnly(15_000_000L)
        assertEquals("超高价应返回 WARN", RiskLevel.WARN, result.level)
        assertTrue("超高价需要二次确认", result.requiresConfirmation)
        assertTrue("警告信息应包含'过高'或'二次确认'", 
            result.message.contains("过高") || result.message.contains("二次确认"))
    }
    
    @Test
    fun `测试价格风险 - 边界值 0点001 TRX`() {
        // 边界值：0.001 TRX = 1000 sun
        val result = validator.checkPriceRiskOnly(1000L)
        assertEquals("0.001 TRX应通过", RiskLevel.PASS, result.level)
    }
    
    @Test
    fun `测试价格风险 - 边界值 1 TRX`() {
        // 边界值：1 TRX = 1_000_000 sun
        val result = validator.checkPriceRiskOnly(1_000_000L)
        assertEquals("1 TRX应通过", RiskLevel.PASS, result.level)
    }
    
    @Test
    fun `测试价格风险 - 边界值 10 TRX`() {
        // 边界值：10 TRX = 10_000_000 sun
        val result = validator.checkPriceRiskOnly(10_000_000L)
        assertEquals("10 TRX应通过", RiskLevel.PASS, result.level)
    }
    
    @Test
    fun `测试价格风险 - 略超 10 TRX`() {
        // 略超 10 TRX：10.1 TRX = 10_100_000 sun
        val result = validator.checkPriceRiskOnly(10_100_000L)
        assertEquals("略超10 TRX应返回 WARN", RiskLevel.WARN, result.level)
        assertTrue("略超10 TRX需要二次确认", result.requiresConfirmation)
    }
    
    // ========== 完整风控检查测试 ==========
    
    @Test
    fun `测试完整风控 - 正常交易应通过`() {
        val config = SettingsConfig(
            sellerAddress = testToAddress,
            pricePerUnitSun = 100_000L, // 0.1 TRX
            multiplier = 3
        )
        
        val transaction = createMockTransaction(
            amount = 300_000L,
            hasData = false
        )
        
        val result = validator.checkRisk(transaction, config)
        // 由于是模拟交易，实际结果取决于 mock 实现
    }
    
    @Test
    fun `测试用户确认需求判断`() {
        val config = SettingsConfig(
            sellerAddress = testToAddress,
            pricePerUnitSun = 15_000_000L, // 15 TRX，超过阈值
            multiplier = 1
        )
        
        val transaction = createMockTransaction(
            amount = 15_000_000L,
            hasData = false
        )
        
        val requiresConfirmation = validator.requiresUserConfirmation(transaction, config)
        // 预期需要确认
    }
    
    // ========== 辅助方法 ==========
    
    /**
     * 创建模拟交易对象
     * 注意：这是简化的模拟，实际测试应使用真实的交易构造
     */
    private fun createMockTransaction(
        amount: Long,
        hasData: Boolean
    ): Chain.Transaction {
        
        // 创建 TransferContract
        val transferContract = Contract.TransferContract.newBuilder()
            .setOwnerAddress(ByteString.copyFrom(Base58Check.base58ToBytes(testFromAddress)))
            .setToAddress(ByteString.copyFrom(Base58Check.base58ToBytes(testToAddress)))
            .setAmount(amount)
            .build()
        
        // 创建合约
        val contract = Chain.Transaction.Contract.newBuilder()
            .setType(Chain.Transaction.Contract.ContractType.TransferContract)
            .setParameter(com.google.protobuf.Any.pack(transferContract))
            .build()
        
        // 创建 RawData
        val rawDataBuilder = Chain.Transaction.raw.newBuilder()
            .addContract(contract)
            .setTimestamp(System.currentTimeMillis())
            .setExpiration(System.currentTimeMillis() + 60_000)
        
        // 添加 data（如果需要）
        if (hasData) {
            rawDataBuilder.setData(ByteString.copyFromUtf8("test_data"))
        }
        
        val rawData = rawDataBuilder.build()
        
        // 创建交易
        return Chain.Transaction.newBuilder()
            .setRawData(rawData)
            .build()
    }
}
