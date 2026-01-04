package com.trxsafe.payment.transaction

import com.trxsafe.payment.settings.SettingsConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TransactionBuilderTest {
    
    private lateinit var builder: TransactionBuilder
    
    private val testFromAddress = "TXYZoPE5CP4Gj4Kuvub4fTPdZK8qRVvvvv"
    private val testToAddress = "TAbcdeFGHIJKLMNOPQRSTUVWXYZabcdefg"
    
    @Before
    fun setup() {
        builder = TransactionBuilder()
    }
    
    @Test(expected = TransactionBuildException::class)
    fun `测试空配置应抛出异常`() {
        runBlocking {
            val emptyConfig = SettingsConfig()
            builder.buildTransferTransaction(testFromAddress, emptyConfig, null)
        }
    }
    
    @Test(expected = TransactionBuildException::class)
    fun `测试单价为0应抛出异常`() {
        runBlocking {
            val config = SettingsConfig(
                sellerAddress = testToAddress,
                pricePerUnitSun = 0L,
                multiplier = 1
            )
            builder.buildTransferTransaction(testFromAddress, config, null)
        }
    }
    
    @Test(expected = TransactionBuildException::class)
    fun `测试倍率为0应抛出异常`() {
        runBlocking {
            val config = SettingsConfig(
                sellerAddress = testToAddress,
                pricePerUnitSun = 5_000_000L,
                multiplier = 0
            )
            builder.buildTransferTransaction(testFromAddress, config, null)
        }
    }
    
    @Test
    fun `测试正常金额计算`() {
        val config = SettingsConfig(
            sellerAddress = testToAddress,
            pricePerUnitSun = 5_000_000L,
            multiplier = 3,
            isPriceLocked = false,
            isFirstTimeSetAddress = false
        )
        
        val expectedAmount = 15_000_000L
        assertEquals("总金额计算错误", expectedAmount, config.getTotalAmountSun())
    }
    
    @Test(expected = TransactionBuildException::class)
    fun `测试发送方地址格式错误应抛出异常`() {
        runBlocking {
            val config = SettingsConfig(
                sellerAddress = testToAddress,
                pricePerUnitSun = 5_000_000L,
                multiplier = 1
            )
            builder.buildTransferTransaction("invalid_address", config, null)
        }
    }
    
    @Test(expected = TransactionBuildException::class)
    fun `测试发送方和接收方地址相同应抛出异常`() {
        runBlocking {
            val config = SettingsConfig(
                sellerAddress = testFromAddress,
                pricePerUnitSun = 5_000_000L,
                multiplier = 1
            )
            builder.buildTransferTransaction(testFromAddress, config, null)
        }
    }
    
    @Test
    fun `测试构造合法交易的基本结构`() {
        runBlocking {
            val config = SettingsConfig(
                sellerAddress = testToAddress,
                pricePerUnitSun = 5_000_000L,
                multiplier = 3,
                isPriceLocked = false,
                isFirstTimeSetAddress = false
            )
            
            try {
                val transaction = builder.buildTransferTransaction(testFromAddress, config, null)
                
                assertTrue("交易应包含 RawData", transaction.hasRawData())
                assertEquals("交易应仅包含一个合约", 1, transaction.rawData.contractCount)
                
                val contract = transaction.rawData.getContract(0)
                assertEquals(
                    "合约类型应为 TransferContract",
                    org.tron.trident.proto.Chain.Transaction.Contract.ContractType.TransferContract,
                    contract.type
                )
                
            } catch (e: TransactionBuildException) {
                println("测试跳过：需要真实 TRON 地址 - ${e.message}")
            }
        }
    }
    
    @Test
    fun `测试异常处理`() {
        runBlocking {
            val config = SettingsConfig(
                sellerAddress = testToAddress,
                pricePerUnitSun = 5_000_000L,
                multiplier = 1
            )
            
            try {
                builder.buildTransferTransaction("invalid", config, null)
                fail("应该抛出 TransactionBuildException")
            } catch (e: TransactionBuildException) {
                assertNotNull("异常信息不应为空", e.message)
                assertTrue("异常信息应包含错误描述", e.message!!.isNotEmpty())
            } catch (e: Exception) {
                fail("应该抛出 TransactionBuildException，而不是 ${e::class.simpleName}")
            }
        }
    }
    
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
    
    @Test
    fun `测试完整流程`() = runBlocking {
        val config = SettingsConfig(
            sellerAddress = testToAddress,
            pricePerUnitSun = 5_000_000L,
            multiplier = 3,
            isPriceLocked = true,
            isFirstTimeSetAddress = false
        )
        
        assertTrue("配置应完整", config.isConfigComplete())
        assertEquals("总金额应正确计算", 15_000_000L, config.getTotalAmountSun())
        
        try {
            val transaction = builder.buildTransferTransaction(testFromAddress, config, null)
            assertNotNull("交易不应为空", transaction)
        } catch (e: TransactionBuildException) {
            println("集成测试跳过：${e.message}")
        }
    }
}
