package com.trxsafe.payment.transaction

import com.google.protobuf.ByteString
import com.trxsafe.payment.settings.SettingsConfig
import com.trxsafe.payment.validation.AddressValidator
import org.tron.trident.proto.Chain
import org.tron.trident.proto.Contract
import org.tron.trident.utils.Base58Check

class TransactionBuilder {
    
    @Throws(TransactionBuildException::class)
    suspend fun buildTransferTransaction(
        fromAddress: String,
        config: SettingsConfig,
        httpClient: com.trxsafe.payment.network.TronHttpClient?
    ): Chain.Transaction {

        validateConfig(config)

        val totalAmountSun = config.getTotalAmountSun()

        validateTransactionParams(
            fromAddress = fromAddress,
            toAddress = config.sellerAddress,
            amountSun = totalAmountSun,
            config = config
        )

        return if (httpClient != null) {
            buildTransferContractTransactionWithRefBlockHttp(
                fromAddress = fromAddress,
                toAddress = config.sellerAddress,
                amountSun = totalAmountSun,
                httpClient = httpClient
            )
        } else {
            buildTransferContractTransaction(
                fromAddress = fromAddress,
                toAddress = config.sellerAddress,
                amountSun = totalAmountSun
            )
        }
    }
    
    @Throws(TransactionBuildException::class)
    private fun validateConfig(config: SettingsConfig) {
        if (!config.isConfigComplete()) {
            throw TransactionBuildException("Settings 配置不完整")
        }
        
        if (config.sellerAddress.isEmpty()) {
            throw TransactionBuildException("收款地址不能为空")
        }
        
        if (config.pricePerUnitSun <= 0) {
            throw TransactionBuildException("单价必须大于 0")
        }
        
        if (config.multiplier <= 0) {
            throw TransactionBuildException("倍率必须大于 0")
        }
    }
    
    @Throws(TransactionBuildException::class)
    private fun validateTransactionParams(
        fromAddress: String,
        toAddress: String,
        amountSun: Long,
        config: SettingsConfig
    ) {
        if (amountSun <= 0) {
            throw TransactionBuildException("转账金额必须大于 0，当前值：$amountSun sun")
        }
        
        val expectedAmount = config.pricePerUnitSun * config.multiplier
        if (amountSun != expectedAmount) {
            throw TransactionBuildException("转账金额不匹配")
        }
        
        if (toAddress != config.sellerAddress) {
            throw TransactionBuildException("接收地址不匹配")
        }
        
        if (!AddressValidator.isValidTronAddress(fromAddress)) {
            throw TransactionBuildException("发送方地址格式错误：$fromAddress")
        }
        
        if (!AddressValidator.isValidTronAddress(toAddress)) {
            throw TransactionBuildException("接收方地址格式错误：$toAddress")
        }
        
        if (fromAddress == toAddress) {
            throw TransactionBuildException("发送方和接收方地址不能相同")
        }
    }
    
    @Throws(TransactionBuildException::class)
    private suspend fun buildTransferContractTransactionWithRefBlockHttp(
        fromAddress: String,
        toAddress: String,
        amountSun: Long,
        httpClient: com.trxsafe.payment.network.TronHttpClient
    ): Chain.Transaction {
        android.util.Log.d("TransactionBuilder", "使用在线签名流程：让节点创建交易")

        val result = httpClient.createTransaction(fromAddress, toAddress, amountSun)

        val transactionJson = when (result) {
            is com.trxsafe.payment.network.TronHttpClient.TransactionResult.Success -> {
                result.transactionJson
            }
            is com.trxsafe.payment.network.TronHttpClient.TransactionResult.Error -> {
                throw TransactionBuildException(result.message)
            }
        }

        val transaction = httpClient.parseTransactionFromJson(transactionJson)
            ?: throw TransactionBuildException("解析节点返回的交易失败")

        android.util.Log.d("TransactionBuilder", "在线签名流程：交易创建成功")
        android.util.Log.d("TransactionBuilder", "refBlockBytes: ${transaction.rawData.refBlockBytes.size()} bytes")
        android.util.Log.d("TransactionBuilder", "refBlockHash: ${transaction.rawData.refBlockHash.size()} bytes")

        return transaction
    }

    @Throws(TransactionBuildException::class)
    private fun buildTransferContractTransaction(
        fromAddress: String,
        toAddress: String,
        amountSun: Long
    ): Chain.Transaction {
        try {
            val fromAddressBytes = ByteString.copyFrom(Base58Check.base58ToBytes(fromAddress))
            val toAddressBytes = ByteString.copyFrom(Base58Check.base58ToBytes(toAddress))
            
            val transferContract = Contract.TransferContract.newBuilder()
                .setOwnerAddress(fromAddressBytes)
                .setToAddress(toAddressBytes)
                .setAmount(amountSun)
                .build()
            
            validateTransferContract(transferContract)
            
            val contract = Chain.Transaction.Contract.newBuilder()
                .setType(Chain.Transaction.Contract.ContractType.TransferContract)
                .setParameter(com.google.protobuf.Any.pack(transferContract))
                .build()
            
            val currentTime = System.currentTimeMillis()
            val rawData = Chain.Transaction.raw.newBuilder()
                .addContract(contract)
                .setTimestamp(currentTime)
                .setExpiration(currentTime + 60_000)
                .setFeeLimit(10_000_000)
                .setData(ByteString.empty())
                .build()
            
            val transaction = Chain.Transaction.newBuilder()
                .setRawData(rawData)
                .build()
            
            validateBuiltTransaction(transaction)
            
            return transaction
            
        } catch (e: TransactionBuildException) {
            throw e
        } catch (e: Exception) {
            throw TransactionBuildException("构造交易失败：${e.message}", e)
        }
    }
    
    @Throws(TransactionBuildException::class)
    private fun validateTransferContract(transferContract: Contract.TransferContract) {
        if (transferContract.ownerAddress.isEmpty) {
            throw TransactionBuildException("TransferContract 缺少发送方地址")
        }
        
        if (transferContract.toAddress.isEmpty) {
            throw TransactionBuildException("TransferContract 缺少接收方地址")
        }
        
        if (transferContract.amount <= 0) {
            throw TransactionBuildException("TransferContract 金额无效")
        }
    }
    
    @Throws(TransactionBuildException::class)
    private fun validateBuiltTransaction(transaction: Chain.Transaction) {
        if (!transaction.hasRawData()) {
            throw TransactionBuildException("交易缺少 RawData")
        }
        
        val rawData = transaction.rawData
        
        if (rawData.contractCount != 1) {
            throw TransactionBuildException("交易必须仅包含一个合约，当前数量：${rawData.contractCount}")
        }
        
        val contract = rawData.getContract(0)
        
        if (contract.type != Chain.Transaction.Contract.ContractType.TransferContract) {
            throw TransactionBuildException("交易类型必须是 TransferContract")
        }
    }
    
}

class TransactionBuildException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
