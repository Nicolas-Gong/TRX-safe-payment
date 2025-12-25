package com.trxsafe.payment.security

import org.tron.trident.proto.Chain
import org.tron.trident.proto.Contract

/**
 * 交易验证器
 * 硬性约束：仅允许 TRX 普通转账（TransferContract）
 */
class TransactionValidator {
    
    /**
     * 验证交易类型是否合法
     * 
     * @param transaction TRON 交易对象
     * @return 验证结果
     * @throws SecurityException 当交易类型不合法时抛出异常
     */
    @Throws(SecurityException::class)
    fun validateTransaction(transaction: Chain.Transaction): ValidationResult {
        try {
            // 检查交易是否为空
            if (transaction == null) {
                throw SecurityException("交易对象为空")
            }
            
            // 获取交易中的合约列表
            val contracts = transaction.rawData.contractList
            
            // 检查是否存在合约
            if (contracts.isEmpty()) {
                throw SecurityException("交易中没有合约")
            }
            
            // 硬性约束：仅允许一个合约
            if (contracts.size > 1) {
                throw SecurityException("禁止批量交易，仅允许单个转账操作")
            }
            
            // 获取合约类型
            val contract = contracts[0]
            val contractType = contract.type
            
            // 硬性约束：仅允许 TransferContract（TRX 普通转账）
            if (contractType != Chain.Transaction.Contract.ContractType.TransferContract) {
                throw SecurityException("禁止的交易类型：${contractType.name}，仅允许 TRX 普通转账")
            }
            
            // 验证转账合约的具体内容
            validateTransferContract(contract)
            
            return ValidationResult.success("交易验证通过")
            
        } catch (e: Exception) {
            // 硬性约束：任意异常必须直接拒绝
            if (SecurityPolicy.REJECT_ON_ANY_EXCEPTION) {
                throw SecurityException("交易验证失败：${e.message}", e)
            }
            return ValidationResult.failure("交易验证失败：${e.message}")
        }
    }
    
    /**
     * 验证 TransferContract 的具体内容
     * 
     * @param contract 合约对象
     * @throws SecurityException 验证失败时抛出异常
     */
    @Throws(SecurityException::class)
    private fun validateTransferContract(contract: Chain.Transaction.Contract) {
        try {
            // 解析 TransferContract
            val transferContract = Contract.TransferContract.parseFrom(contract.parameter.value)
            
            // 验证发送方地址
            if (transferContract.ownerAddress.isEmpty) {
                throw SecurityException("发送方地址为空")
            }
            
            // 验证接收方地址
            if (transferContract.toAddress.isEmpty) {
                throw SecurityException("接收方地址为空")
            }
            
            // 验证转账金额
            val amount = transferContract.amount
            validateAmount(amount)
            
        } catch (e: Exception) {
            throw SecurityException("TransferContract 验证失败：${e.message}", e)
        }
    }
    
    /**
     * 验证转账金额
     * 硬性约束：金额必须使用 long 类型（sun），禁止 float/double
     * 
     * @param amount 转账金额（单位：sun）
     * @throws SecurityException 金额不合法时抛出异常
     */
    @Throws(SecurityException::class)
    fun validateAmount(amount: Long) {
        // 检查金额是否为正数
        if (amount <= 0) {
            throw SecurityException("转账金额必须大于 0")
        }
        
        // 检查最小金额
        if (amount < SecurityPolicy.MIN_TRANSFER_AMOUNT_SUN) {
            throw SecurityException("转账金额低于最小值：${SecurityPolicy.MIN_TRANSFER_AMOUNT_SUN} sun")
        }
        
        // 检查最大金额
        if (amount > SecurityPolicy.MAX_TRANSFER_AMOUNT_SUN) {
            throw SecurityException("转账金额超过最大值：${SecurityPolicy.MAX_TRANSFER_AMOUNT_SUN} sun")
        }
    }
    
    /**
     * 检查是否为智能合约调用
     * 硬性约束：禁止任何智能合约调用
     * 
     * @param contractType 合约类型
     * @return true 表示是智能合约调用
     */
    fun isSmartContractCall(contractType: Chain.Transaction.Contract.ContractType): Boolean {
        return contractType == Chain.Transaction.Contract.ContractType.TriggerSmartContract ||
               contractType == Chain.Transaction.Contract.ContractType.CreateSmartContract
    }
    
    /**
     * 检查地址是否为智能合约地址
     * 
     * @param address 地址字符串
     * @return true 表示可能是智能合约地址
     */
    fun isContractAddress(address: String): Boolean {
        // TRON 智能合约地址通常以 41 开头（Base58 编码后以 T 开头）
        // 这里可以添加更多检查逻辑
        return false // 简化处理，后续可增强
    }
}

/**
 * 验证结果类
 */
data class ValidationResult(
    val isValid: Boolean,
    val message: String,
    val errorCode: Int = 0
) {
    companion object {
        fun success(message: String = "验证成功") = ValidationResult(true, message)
        fun failure(message: String, errorCode: Int = -1) = ValidationResult(false, message, errorCode)
    }
}
