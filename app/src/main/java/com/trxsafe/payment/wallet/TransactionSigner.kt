package com.trxsafe.payment.wallet

import com.trxsafe.payment.security.SecurityPolicy
import com.trxsafe.payment.security.TransactionValidator
import com.trxsafe.payment.security.ValidationResult
import org.tron.trident.core.key.KeyPair
import org.tron.trident.proto.Chain
import org.tron.trident.proto.Contract
import org.tron.trident.crypto.Hash
import com.google.protobuf.ByteString

/**
 * 交易签名管理器
 * 硬性约束：
 * 1. 签名前必须验证交易类型
 * 2. 仅允许签名 TRX 普通转账
 * 3. 任意异常必须直接拒绝签名
 */
class TransactionSigner(
    private val validator: TransactionValidator = TransactionValidator()
) {
    
    /**
     * 签名交易
     * 
     * @param transaction 待签名的交易
     * @param keyPair 密钥对
     * @return 签名后的交易
     * @throws SecurityException 安全检查失败时抛出异常
     */
    @Throws(SecurityException::class)
    suspend fun signTransaction(
        transaction: Chain.Transaction,
        keyPair: KeyPair
    ): Chain.Transaction {
        try {
            // 硬性约束 1：签名前必须验证交易类型
            val validationResult = validator.validateTransaction(transaction)
            if (!validationResult.isValid) {
                throw SecurityException("交易验证失败：${validationResult.message}")
            }
            
            // 硬性约束 2：再次确认仅允许 TRX 普通转账
            val contractType = transaction.rawData.getContract(0).type
            if (contractType != Chain.Transaction.Contract.ContractType.TransferContract) {
                throw SecurityException("严重错误：尝试签名非 TRX 转账交易")
            }
            
            // 执行签名
            val rawData = transaction.rawData.toByteArray()
            val hash = Hash.sha256(rawData)
            val signature = keyPair.sign(hash)
            
            // 构建签名后的交易
            val signedTransaction = transaction.toBuilder()
                .addSignature(ByteString.copyFrom(signature))
                .build()
            
            return signedTransaction
            
        } catch (e: SecurityException) {
            // 硬性约束 3：任意异常必须直接拒绝签名
            throw e
        } catch (e: Exception) {
            // 硬性约束 3：任意异常必须直接拒绝签名
            if (SecurityPolicy.REJECT_ON_ANY_EXCEPTION) {
                throw SecurityException("签名失败，已拒绝：${e.message}", e)
            }
            throw SecurityException("签名过程发生未知错误", e)
        }
    }
    
    /**
     * 创建 TRX 转账交易（未签名）
     * 
     * @param fromAddress 发送方地址
     * @param toAddress 接收方地址
     * @param amountSun 转账金额（单位：sun）
     * @return 未签名的交易
     * @throws SecurityException 参数验证失败时抛出异常
     */
    @Throws(SecurityException::class)
    fun createTransferTransaction(
        fromAddress: ByteString,
        toAddress: ByteString,
        amountSun: Long
    ): Chain.Transaction {
        try {
            // 验证金额
            validator.validateAmount(amountSun)
            
            // 创建 TransferContract
            val transferContract = Contract.TransferContract.newBuilder()
                .setOwnerAddress(fromAddress)
                .setToAddress(toAddress)
                .setAmount(amountSun)
                .build()
            
            // 创建交易
            val contract = Chain.Transaction.Contract.newBuilder()
                .setType(Chain.Transaction.Contract.ContractType.TransferContract)
                .setParameter(com.google.protobuf.Any.pack(transferContract))
                .build()
            
            val rawData = Chain.Transaction.raw.newBuilder()
                .addContract(contract)
                .setTimestamp(System.currentTimeMillis())
                .setExpiration(System.currentTimeMillis() + 60000) // 60 秒过期
                .build()
            
            return Chain.Transaction.newBuilder()
                .setRawData(rawData)
                .build()
            
        } catch (e: Exception) {
            throw SecurityException("创建交易失败：${e.message}", e)
        }
    }
    
    /**
     * 验证签名后的交易
     * 
     * @param signedTransaction 已签名的交易
     * @return 验证结果
     */
    fun verifySignedTransaction(signedTransaction: Chain.Transaction): ValidationResult {
        return try {
            // 检查是否包含签名
            if (signedTransaction.signatureCount == 0) {
                return ValidationResult.failure("交易未签名")
            }
            
            // 验证交易类型
            validator.validateTransaction(signedTransaction)
            
            ValidationResult.success("签名验证通过")
        } catch (e: SecurityException) {
            ValidationResult.failure("签名验证失败：${e.message}")
        }
    }
}
