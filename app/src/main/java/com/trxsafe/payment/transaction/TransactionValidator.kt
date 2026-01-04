package com.trxsafe.payment.transaction

import com.trxsafe.payment.settings.SettingsConfig
import org.tron.trident.proto.Chain
import org.tron.trident.proto.Contract

/**
 * 交易校验器（扩展版）
 * 
 * 用于在构造交易后、签名前进行最终校验
 * 确保交易完全符合安全约束
 */
class TransactionValidator {
    
    /**
     * 验证交易是否符合 Settings 配置
     * 
     * @param transaction 待验证的交易
     * @param config Settings 配置
     * @param fromAddress 发送方地址
     * @return 验证结果
     * @throws TransactionValidationException 验证失败时抛出
     */
    @Throws(TransactionValidationException::class)
    fun validateTransactionWithConfig(
        transaction: Chain.Transaction,
        config: SettingsConfig,
        fromAddress: String
    ): ValidationResult {
        
        try {
            // 1. 基础验证
            validateBasicStructure(transaction)
            
            // 2. 验证交易类型
            validateTransactionType(transaction)
            
            // 3. 验证 TransferContract 内容
            validateTransferContractContent(transaction, config, fromAddress)
            
            // 4. 验证没有多余字段
            validateNoExtraFields(transaction)
            
            return ValidationResult.Success("交易验证通过")
            
        } catch (e: TransactionValidationException) {
            throw e
        } catch (e: Exception) {
            throw TransactionValidationException(
                "交易验证失败：${e.message}",
                e
            )
        }
    }
    
    /**
     * 验证交易基础结构
     */
    @Throws(TransactionValidationException::class)
    private fun validateBasicStructure(transaction: Chain.Transaction) {
        if (!transaction.hasRawData()) {
            throw TransactionValidationException("交易缺少 RawData")
        }
        
        val rawData = transaction.rawData
        
        if (rawData.contractCount == 0) {
            throw TransactionValidationException("交易没有合约")
        }
        
        if (rawData.contractCount > 1) {
            throw TransactionValidationException(
                "交易包含多个合约（${rawData.contractCount}），仅允许一个"
            )
        }
    }
    
    /**
     * 验证交易类型
     */
    @Throws(TransactionValidationException::class)
    private fun validateTransactionType(transaction: Chain.Transaction) {
        val contract = transaction.rawData.getContract(0)
        
        // 硬性约束：仅允许 TransferContract
        if (contract.type != Chain.Transaction.Contract.ContractType.TransferContract) {
            throw TransactionValidationException(
                "禁止的交易类型：${contract.type}，仅允许 TransferContract"
            )
        }
    }
    
    /**
     * 验证 TransferContract 内容
     */
    @Throws(TransactionValidationException::class)
    private fun validateTransferContractContent(
        transaction: Chain.Transaction,
        config: SettingsConfig,
        fromAddress: String
    ) {
        val contract = transaction.rawData.getContract(0)
        val transferContract = Contract.TransferContract.parseFrom(contract.parameter.value)
        
        // 验证发送方地址
        val actualFromAddress = org.tron.trident.utils.Base58Check.bytesToBase58(
            transferContract.ownerAddress.toByteArray()
        )
        if (actualFromAddress != fromAddress) {
            throw TransactionValidationException(
                "发送方地址不匹配：期望 $fromAddress，实际 $actualFromAddress"
            )
        }
        
        // 硬性约束：接收方地址必须等于 sellerAddress
        val actualToAddress = org.tron.trident.utils.Base58Check.bytesToBase58(
            transferContract.toAddress.toByteArray()
        )
        if (actualToAddress != config.sellerAddress) {
            throw TransactionValidationException(
                "接收方地址不匹配：期望 ${config.sellerAddress}，实际 $actualToAddress"
            )
        }
        
        // 硬性约束：金额必须等于 pricePerUnitSun * multiplier
        val expectedAmount = config.pricePerUnitSun * config.multiplier
        val actualAmount = transferContract.amount
        if (actualAmount != expectedAmount) {
            throw TransactionValidationException(
                "转账金额不匹配：期望 $expectedAmount sun，实际 $actualAmount sun"
            )
        }
        
        // 硬性约束：金额必须大于 0
        if (actualAmount <= 0) {
            throw TransactionValidationException(
                "转账金额必须大于 0，当前值：$actualAmount sun"
            )
        }
    }
    
    /**
     * 验证没有多余字段
     */
    @Throws(TransactionValidationException::class)
    private fun validateNoExtraFields(transaction: Chain.Transaction) {
        val rawData = transaction.rawData
        
        // 硬性约束：不包含 data 字段
        // RawData 中不应该有 data 字段
        if (!rawData.data.isEmpty) {
            throw TransactionValidationException(
                "交易包含 data 字段，已拒绝"
            )
        }
        
        // 检查是否有脚本字段
        if (!rawData.scripts.isEmpty) {
            throw TransactionValidationException(
                "交易包含 scripts 字段，已拒绝"
            )
        }
    }
}

/**
 * 交易验证异常
 */
class TransactionValidationException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * 验证结果
 */
sealed class ValidationResult {
    /**
     * 验证成功
     */
    data class Success(val message: String) : ValidationResult()
    
    /**
     * 验证失败
     */
    data class Failure(val message: String) : ValidationResult()
}
