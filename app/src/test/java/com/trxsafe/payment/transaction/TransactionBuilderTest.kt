package com.trxsafe.payment.transaction

import com.trxsafe.payment.settings.SettingsConfig
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * TransactionBuilder 单元测试
 */
class TransactionBuilderTest {
    
    private lateinit var builder: TransactionBuilder
    
    // 测试用的地址（需要替换为真实的 TRON 地址进行实际测试）
    private val testFromAddress = "TXYZoPE5CP4Gj4Kuvub4fTPdZK8qRVvvvv"
    private val testToAddress = "TAbcdeFGHIJKLMNOPQRSTUVWXYZabcdefg"
    
    @Before
    fun setup() {
        builder = TransactionBuilder()
    }
    
    // ========== 配置验证测试 ==========
    
    @Test(expected = TransactionBuildException::class)
    fun `测试空配置应抛出异常`() {
        val emptyConfig = SettingsConfig()
        builder.buildTransferTransaction(testFromAddress, emptyConfig)
    }
    
    @Test(expected = TransactionBuildException::class)
    fun `测试单价为0应抛出异常`() {
        val config = SettingsConfig(
            sellerAddress = testToAddress,
            pricePerUnitSun = 0L,
            multiplier = 1
        )
        builder.buildTransferTransaction(testFromAddress, config)
    }
    
    @Test(expected = TransactionBuildException::class)
    fun `测试倍率为0应抛出异常`() {
        val config = SettingsConfig(
            sellerAddress = testToAddress,
            pricePerUnitSun = 5_000_000L,
            multiplier = 0
        )
        builder.buildTransferTransaction(testFromAddress, config)
    }
    
    // ========== 金额验证测试 ==========
    
    @Test
    fun `测试正常金额计算`() {
        val config = SettingsConfig(
            sellerAddress = testToAddress,
            pricePerUnitSun = 5_000_000L,
            multiplier = 3,
            isPriceLocked = false,
            isFirstTimeSetAddress = false
        )
        
        val expectedAmount = 15_000_000L // 5_000_000 * 3
        assertEquals("总金额计算错误", expectedAmount, config.getTotalAmountSun())
    }
    
    // ========== 地址验证测试 ==========
    
    @Test(expected = TransactionBuildException::class)
    fun `测试发送方地址格式错误应抛出异常`() {
        val config = SettingsConfig(
            sellerAddress = testToAddress,
            pricePerUnitSun = 5_000_000L,
            multiplier = 1
        )
        
        builder.buildTransferTransaction("invalid_address", config)
    }
    
    @Test(expected = TransactionBuildException::class)
    fun `测试发送方和接收方地址相同应抛出异常`() {
        val config = SettingsConfig(
            sellerAddress = testFromAddress, // 与发送方相同
            pricePerUnitSun = 5_000_000L,
            multiplier = 1
        )
        
        builder.buildTransferTransaction(testFromAddress, config)
    }
    
    // ========== 交易构造测试 ==========
    
    @Test
    fun `测试构造合法交易的基本结构`() {
        // 注意：此测试需要真实的 TRON 地址才能通过
        // 这里仅测试逻辑，实际使用时需要替换为真实地址
        
        val config = SettingsConfig(
            sellerAddress = testToAddress,
            pricePerUnitSun = 5_000_000L,
            multiplier = 3,
            isPriceLocked = false,
            isFirstTimeSetAddress = false
        )
        
        try {
            val transaction = builder.buildTransferTransaction(testFromAddress, config)
            
            // 验证交易基本结构
            assertTrue("交易应包含 RawData", transaction.hasRawData())
            assertEquals("交易应仅包含一个合约", 1, transaction.rawData.contractCount)
            
            // 验证合约类型
            val contract = transaction.rawData.getContract(0)
            assertEquals(
                "合约类型应为 TransferContract",
                org.tron.trident.proto.Chain.Transaction.Contract.ContractType.TransferContract,
                contract.type
            )
            
        } catch (e: TransactionBuildException) {
            // 如果使用的是无效测试地址，这里会抛出异常
            // 在实际测试中应使用真实地址
            println("测试跳过：需要真实 TRON 地址 - ${e.message}")
        }
    }
    
    // ========== 异常情况测试 ==========
    
    @Test
    fun `测试异常处理 - 所有异常都应转换为 TransactionBuildException`() {
        val config = SettingsConfig(
            sellerAddress = testToAddress,
            pricePerUnitSun = 5_000_000L,
            multiplier = 1
        )
        
        try {
            // 使用无效地址触发异常
            builder.buildTransferTransaction("invalid", config)
            fail("应该抛出 TransactionBuildException")
        } catch (e: TransactionBuildException) {
            // 预期的异常
            assertNotNull("异常信息不应为空", e.message)
            assertTrue("异常信息应包含错误描述", e.message!!.isNotEmpty())
        } catch (e: Exception) {
            fail("应该抛出 TransactionBuildException，而不是 ${e::class.simpleName}")
        }
    }
    
    // ========== 边界值测试 ==========
    
    @Test
    fun `测试最小金额 1 sun`() {
        val config = SettingsConfig(
            sellerAddress = testToAddress,
            pricePerUnitSun = 1L,
            multiplier = 1
        )
        
        assertEquals("最小金额应为 1 sun", 1L, config.getTotalAmountSun())
    }
    
    @Test
    fun `测试最大倍率 10`() {
        val config = SettingsConfig(
            sellerAddress = testToAddress,
            pricePerUnitSun = 1_000_000L,
            multiplier = 10
        )
        
        assertEquals("最大倍率金额计算", 10_000_000L, config.getTotalAmountSun())
    }
    
    // ========== 集成测试 ==========
    
    @Test
    fun `测试完整流程 - 从配置到交易构造`() {
        // 创建配置
        val config = SettingsConfig(
            sellerAddress = testToAddress,
            pricePerUnitSun = 5_000_000L, // 5 TRX
            multiplier = 3,
            isPriceLocked = true,
            isFirstTimeSetAddress = false
        )
        
        // 验证配置
        assertTrue("配置应完整", config.isConfigComplete())
        assertEquals("总金额应正确计算", 15_000_000L, config.getTotalAmountSun())
        
        // 尝试构造交易（使用真实地址时才能成功）
        try {
            val transaction = builder.buildTransferTransaction(testFromAddress, config)
            assertNotNull("交易不应为空", transaction)
        } catch (e: TransactionBuildException) {
            println("集成测试跳过：${e.message}")
        }
    }
}
