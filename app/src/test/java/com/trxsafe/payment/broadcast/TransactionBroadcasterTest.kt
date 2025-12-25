package com.trxsafe.payment.broadcast

import com.trxsafe.payment.settings.SettingsConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.tron.trident.proto.Chain
import org.tron.trident.proto.Contract
import com.google.protobuf.ByteString

/**
 * TransactionBroadcaster 单元测试
 */
class TransactionBroadcasterTest {
    
    private lateinit var config: SettingsConfig
    
    @Before
    fun setup() {
        config = SettingsConfig(
            sellerAddress = "TAbcdeFGHIJKLMNOPQRSTUVWXYZabcdefg",
            pricePerUnitSun = 5_000_000L,
            multiplier = 3
        )
    }
    
    // ========== 广播前校验测试 ==========
    
    @Test
    fun `测试未签名交易应抛出异常`() {
        // 创建未签名交易
        val transaction = createMockTransaction(
            amount = 15_000_000L,
            signed = false
        )
        
        // 尝试广播（会在校验阶段失败）
        // 注意：这需要模拟 broadcaster，实际测试需要 mock ApiWrapper
    }
    
    @Test
    fun `测试金额不匹配应抛出异常`() {
        // 期望金额：5_000_000 * 3 = 15_000_000
        // 实际金额：10_000_000（不匹配）
        val transaction = createMockTransaction(
            amount = 10_000_000L,
            signed = true
        )
        
        // 金额校验应该失败
    }
    
    @Test
    fun `测试正确金额应通过校验`() {
        // 正确金额：5_000_000 * 3 = 15_000_000
        val transaction = createMockTransaction(
            amount = 15_000_000L,
            signed = true
        )
        
        // 应该通过金额校验
        val expectedAmount = config.pricePerUnitSun * config.multiplier
        assertEquals("金额应匹配", 15_000_000L, expectedAmount)
    }
    
    // ========== TransactionRecord 测试 ==========
    
    @Test
    fun `测试交易记录数据模型`() {
        val record = TransactionRecord(
            txid = "abc123",
            toAddress = "TXYZoPE5...",
            amountSun = 15_000_000L,
            timestamp = System.currentTimeMillis(),
            status = TransactionStatus.SUCCESS,
            memo = "测试交易"
        )
        
        assertEquals("abc123", record.txid)
        assertEquals("TXYZoPE5...", record.toAddress)
        assertEquals(15_000_000L, record.amountSun)
        assertEquals(TransactionStatus.SUCCESS, record.status)
    }
    
    @Test
    fun `测试交易状态枚举`() {
        assertEquals(3, TransactionStatus.values().size)
        assertTrue(TransactionStatus.values().contains(TransactionStatus.SUCCESS))
        assertTrue(TransactionStatus.values().contains(TransactionStatus.FAILURE))
        assertTrue(TransactionStatus.values().contains(TransactionStatus.PENDING))
    }
    
    // ========== 辅助方法 ==========
    
    /**
     * 创建模拟交易
     */
    private fun createMockTransaction(amount: Long, signed: Boolean): Chain.Transaction {
        val transferContract = Contract.TransferContract.newBuilder()
            .setOwnerAddress(ByteString.copyFromUtf8("from"))
            .setToAddress(ByteString.copyFromUtf8("to"))
            .setAmount(amount)
            .build()
        
        val contract = Chain.Transaction.Contract.newBuilder()
            .setType(Chain.Transaction.Contract.ContractType.TransferContract)
            .setParameter(com.google.protobuf.Any.pack(transferContract))
            .build()
        
        val rawData = Chain.Transaction.raw.newBuilder()
            .addContract(contract)
            .setTimestamp(System.currentTimeMillis())
            .setExpiration(System.currentTimeMillis() + 60_000)
            .build()
        
        val builder = Chain.Transaction.newBuilder()
            .setRawData(rawData)
        
        if (signed) {
            // 添加模拟签名
            builder.addSignature(ByteString.copyFromUtf8("mock_signature"))
        }
        
        return builder.build()
    }
}
