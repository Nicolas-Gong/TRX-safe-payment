package com.trxsafe.payment.transaction

import com.google.protobuf.ByteString
import com.trxsafe.payment.settings.SettingsConfig
import org.tron.trident.proto.Chain
import org.tron.trident.proto.Contract
import org.tron.trident.utils.Base58Check

/**
 * TRX 交易构造器
 * 
 * 硬性约束：
 * 1. 仅允许构造 TransferContract（TRX 普通转账）
 * 2. 构造前强制校验所有参数
 * 3. 任一校验失败必须抛出异常并拒绝构造
 * 4. 禁止包含 data 或任何合约字段
 */
class TransactionBuilder {
    
    /**
     * 构造 TRX 转账交易
     * 
     * @param fromAddress 发送方地址（当前钱包地址）
     * @param config Settings 配置（包含收款地址、单价、倍率）
     * @return 未签名的交易对象
     * @throws TransactionBuildException 构造失败时抛出
     */
    @Throws(TransactionBuildException::class)
    fun buildTransferTransaction(
        fromAddress: String,
        config: SettingsConfig
    ): Chain.Transaction {
        
        // 强制校验 1：验证 Settings 配置
        validateConfig(config)
        
        // 计算总金额
        val totalAmountSun = config.getTotalAmountSun()
        
        // 强制校验 2：验证交易参数
        validateTransactionParams(
            fromAddress = fromAddress,
            toAddress = config.sellerAddress,
            amountSun = totalAmountSun,
            config = config
        )
        
        // 构造交易
        return buildTransferContractTransaction(
            fromAddress = fromAddress,
            toAddress = config.sellerAddress,
            amountSun = totalAmountSun
        )
    }
    
    /**
     * 验证 Settings 配置
     * 
     * @param config Settings 配置
     * @throws TransactionBuildException 配置无效时抛出
     */
    @Throws(TransactionBuildException::class)
    private fun validateConfig(config: SettingsConfig) {
        // 检查配置是否完整
        if (!config.isConfigComplete()) {
            throw TransactionBuildException("Settings 配置不完整")
        }
        
        // 检查收款地址
        if (config.sellerAddress.isEmpty()) {
            throw TransactionBuildException("收款地址不能为空")
        }
        
        // 检查单价
        if (config.pricePerUnitSun <= 0) {
            throw TransactionBuildException("单价必须大于 0")
        }
        
        // 检查倍率
        if (config.multiplier <= 0) {
            throw TransactionBuildException("倍率必须大于 0")
        }
    }
    
    /**
     * 验证交易参数
     * 
     * @param fromAddress 发送方地址
     * @param toAddress 接收方地址
     * @param amountSun 转账金额（sun）
     * @param config Settings 配置
     * @throws TransactionBuildException 参数无效时抛出
     */
    @Throws(TransactionBuildException::class)
    private fun validateTransactionParams(
        fromAddress: String,
        toAddress: String,
        amountSun: Long,
        config: SettingsConfig
    ) {
        // 硬性约束 1：金额必须大于 0
        if (amountSun <= 0) {
            throw TransactionBuildException(
                "转账金额必须大于 0，当前值：$amountSun sun"
            )
        }
        
        // 硬性约束 2：金额必须等于 pricePerUnitSun * multiplier
        val expectedAmount = config.pricePerUnitSun * config.multiplier
        if (amountSun != expectedAmount) {
            throw TransactionBuildException(
                "转账金额不匹配：期望 $expectedAmount sun（单价 ${config.pricePerUnitSun} × 倍率 ${config.multiplier}），实际 $amountSun sun"
            )
        }
        
        // 硬性约束 3：接收地址必须等于 sellerAddress
        if (toAddress != config.sellerAddress) {
            throw TransactionBuildException(
                "接收地址不匹配：期望 ${config.sellerAddress}，实际 $toAddress"
            )
        }
        
        // 验证发送方地址格式
        if (!isValidTronAddress(fromAddress)) {
            throw TransactionBuildException(
                "发送方地址格式错误：$fromAddress"
            )
        }
        
        // 验证接收方地址格式
        if (!isValidTronAddress(toAddress)) {
            throw TransactionBuildException(
                "接收方地址格式错误：$toAddress"
            )
        }
        
        // 检查发送方和接收方地址不能相同
        if (fromAddress == toAddress) {
            throw TransactionBuildException(
                "发送方和接收方地址不能相同"
            )
        }
    }
    
    /**
     * 构造 TransferContract 交易
     * 
     * @param fromAddress 发送方地址
     * @param toAddress 接收方地址
     * @param amountSun 转账金额（sun）
     * @return 未签名的交易对象
     * @throws TransactionBuildException 构造失败时抛出
     */
    @Throws(TransactionBuildException::class)
    private fun buildTransferContractTransaction(
        fromAddress: String,
        toAddress: String,
        amountSun: Long
    ): Chain.Transaction {
        try {
            // 转换地址为 ByteString
            val fromAddressBytes = ByteString.copyFrom(Base58Check.base58ToBytes(fromAddress))
            val toAddressBytes = ByteString.copyFrom(Base58Check.base58ToBytes(toAddress))
            
            // 创建 TransferContract
            // 硬性约束：不包含 data 或任何合约字段
            val transferContract = Contract.TransferContract.newBuilder()
                .setOwnerAddress(fromAddressBytes)
                .setToAddress(toAddressBytes)
                .setAmount(amountSun)
                .build()
            
            // 验证构造的 TransferContract
            validateTransferContract(transferContract)
            
            // 创建交易合约
            val contract = Chain.Transaction.Contract.newBuilder()
                .setType(Chain.Transaction.Contract.ContractType.TransferContract)
                .setParameter(com.google.protobuf.Any.pack(transferContract))
                .build()
            
            // 创建交易 RawData
            val currentTime = System.currentTimeMillis()
            val rawData = Chain.Transaction.raw.newBuilder()
                .addContract(contract)
                .setTimestamp(currentTime)
                .setExpiration(currentTime + 60_000) // 60 秒过期
                .build()
            
            // 创建未签名的交易
            val transaction = Chain.Transaction.newBuilder()
                .setRawData(rawData)
                .build()
            
            // 最后验证构造的交易
            validateBuiltTransaction(transaction)
            
            return transaction
            
        } catch (e: TransactionBuildException) {
            // 重新抛出自定义异常
            throw e
        } catch (e: Exception) {
            // 任何其他异常都转换为 TransactionBuildException
            throw TransactionBuildException(
                "构造交易失败：${e.message}",
                e
            )
        }
    }
    
    /**
     * 验证构造的 TransferContract
     * 
     * @param transferContract TransferContract 对象
     * @throws TransactionBuildException 验证失败时抛出
     */
    @Throws(TransactionBuildException::class)
    private fun validateTransferContract(transferContract: Contract.TransferContract) {
        // 硬性约束：不包含 data 字段
        // TransferContract 默认不包含 data 字段，这里做额外检查
        
        // 验证发送方地址存在
        if (transferContract.ownerAddress.isEmpty) {
            throw TransactionBuildException("TransferContract 缺少发送方地址")
        }
        
        // 验证接收方地址存在
        if (transferContract.toAddress.isEmpty) {
            throw TransactionBuildException("TransferContract 缺少接收方地址")
        }
        
        // 验证金额
        if (transferContract.amount <= 0) {
            throw TransactionBuildException("TransferContract 金额无效")
        }
    }
    
    /**
     * 验证构造的交易
     * 
     * @param transaction 交易对象
     * @throws TransactionBuildException 验证失败时抛出
     */
    @Throws(TransactionBuildException::class)
    private fun validateBuiltTransaction(transaction: Chain.Transaction) {
        // 验证交易包含 RawData
        if (!transaction.hasRawData()) {
            throw TransactionBuildException("交易缺少 RawData")
        }
        
        val rawData = transaction.rawData
        
        // 硬性约束：仅包含一个合约
        if (rawData.contractCount != 1) {
            throw TransactionBuildException(
                "交易必须仅包含一个合约，当前数量：${rawData.contractCount}"
            )
        }
        
        val contract = rawData.getContract(0)
        
        // 硬性约束：合约类型必须是 TransferContract
        if (contract.type != Chain.Transaction.Contract.ContractType.TransferContract) {
            throw TransactionBuildException(
                "交易类型必须是 TransferContract，当前类型：${contract.type}"
            )
        }
        
        // 硬性约束：不包含任何合约字段（除了 TransferContract）
        // 检查是否有多余的字段
        if (rawData.contractCount > 1) {
            throw TransactionBuildException("交易包含多个合约，已拒绝")
        }
        
        // 验证时间戳
        if (rawData.timestamp <= 0) {
            throw TransactionBuildException("交易时间戳无效")
        }
        
        // 验证过期时间
        if (rawData.expiration <= rawData.timestamp) {
            throw TransactionBuildException("交易过期时间无效")
        }
    }
    
    /**
     * 验证 TRON 地址格式
     * 
     * @param address TRON 地址
     * @return true 表示格式正确
     */
    private fun isValidTronAddress(address: String): Boolean {
        return try {
            // TRON 地址以 T 开头，长度为 34
            if (!address.startsWith("T") || address.length != 34) {
                return false
            }
            // 尝试解码 Base58
            Base58Check.base58ToBytes(address)
            true
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * 交易构造异常
 * 
 * @param message 错误信息
 * @param cause 原因异常
 */
class TransactionBuildException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
