package com.trxsafe.payment.broadcast

import android.content.Context
import com.trxsafe.payment.settings.SettingsConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tron.trident.core.ApiWrapper
import org.tron.trident.proto.Chain
import org.tron.trident.proto.Contract
import java.text.SimpleDateFormat
import java.util.*

/**
 * 交易广播器
 * 
 * 功能：
 * - 广播前再次校验
 * - 广播到 TRON 网络
 * - 记录广播结果
 */
class TransactionBroadcaster(
    private val context: Context,
    private val apiWrapper: ApiWrapper
) {
    
    /**
     * 获取账户余额 (TRX)
     * 
     * @param address 账户地址
     * @return 余额 (SUN)
     */
    suspend fun getAccountBalance(address: String): Long = withContext(Dispatchers.IO) {
        try {
            val account = apiWrapper.getAccount(address)
            account.balance
        } catch (e: io.grpc.StatusRuntimeException) {
            // 节点连接问题
            -1L // 用 -1 表示查询失败，而不是 0（0 可能是真实余额）
        } catch (e: Exception) {
            -1L
        }
    }

    /**
     * 获取交易状态
     * 
     * @param txid 交易 ID
     * @return 交易状态信息
     */
    suspend fun getTransactionStatus(txid: String): TransactionStatusInfo = withContext(Dispatchers.IO) {
        try {
            val transaction = apiWrapper.getTransactionById(txid)
            if (transaction == null || transaction.rawData.timestamp == 0L) {
                return@withContext TransactionStatusInfo.NotFound
            }

            val info = apiWrapper.getTransactionInfoById(txid)
            if (info == null || info.id.isEmpty) {
                return@withContext TransactionStatusInfo.Pending
            }

            if (info.receipt.result == org.tron.trident.proto.Response.TransactionInfo.code.FAILED) {
                return@withContext TransactionStatusInfo.Failed(info.resMessage.toStringUtf8())
            }

            TransactionStatusInfo.Success(
                blockHeight = info.blockNumber,
                feeSun = info.fee,
                netUsage = info.receipt.netUsage,
                energyUsage = info.receipt.energyUsage
            )
        } catch (e: io.grpc.StatusRuntimeException) {
            val msg = when (e.status.code) {
                io.grpc.Status.Code.UNAVAILABLE -> "节点不可访问"
                io.grpc.Status.Code.DEADLINE_EXCEEDED -> "查询超时"
                else -> "网络错误: ${e.status.code}"
            }
            TransactionStatusInfo.Error(msg)
        } catch (e: Exception) {
            TransactionStatusInfo.Error(e.message ?: "查询失败")
        }
    }
    
    private val transactionRecorder = TransactionRecorder(context)
    
    /**
     * 广播交易
     * 
     * @param transaction 已签名的交易
     * @param config Settings 配置（用于校验金额）
     * @return 广播结果
     */
    suspend fun broadcast(
        transaction: Chain.Transaction,
        config: SettingsConfig
    ): BroadcastResult = withContext(Dispatchers.IO) {
        
        try {
            // 1. 广播前再次校验
            validateBeforeBroadcast(transaction, config)
            
            // 2. 广播交易
            val response = apiWrapper.broadcastTransaction(transaction)
            
            // 3. 处理响应
            if (response.result) {
                handleSuccess(transaction, config)
            } else {
                handleFailure(response.message.toStringUtf8()) // Ensure string format
            }
            
        } catch (e: BroadcastException) {
            BroadcastResult.Failure(e.message ?: "校验失败")
        } catch (e: io.grpc.StatusRuntimeException) {
            val friendlyMsg = when (e.status.code) {
                io.grpc.Status.Code.UNAVAILABLE -> "节点无法连接，请检查网络或更换节点"
                io.grpc.Status.Code.DEADLINE_EXCEEDED -> "连接节点超时"
                io.grpc.Status.Code.UNAUTHENTICATED -> "节点鉴权失败"
                else -> "节点错误 (${e.status.code}): ${e.status.description}"
            }
            BroadcastResult.Failure(friendlyMsg)
        } catch (e: java.net.ConnectException) {
            BroadcastResult.Failure("无法连接到节点，请检查网络设置")
        } catch (e: Exception) {
            BroadcastResult.Failure("发生未知错误：${e.message}")
        }
    }
    
    /**
     * 广播前校验
     * 
     * @param transaction 交易
     * @param config Settings 配置
     * @throws BroadcastException 校验失败时抛出
     */
    @Throws(BroadcastException::class)
    private fun validateBeforeBroadcast(
        transaction: Chain.Transaction,
        config: SettingsConfig
    ) {
        // 1. 检查交易是否已签名
        if (transaction.signatureCount == 0) {
            throw BroadcastException("交易未签名")
        }
        
        // 2. 检查交易类型
        val rawData = transaction.rawData
        if (rawData.contractCount != 1) {
            throw BroadcastException("交易包含多个合约")
        }
        
        val contract = rawData.getContract(0)
        if (contract.type != Chain.Transaction.Contract.ContractType.TransferContract) {
            throw BroadcastException("仅允许广播 TransferContract")
        }
        
        // 3. 解析 TransferContract
        val transferContract = Contract.TransferContract.parseFrom(contract.parameter.value)
        val actualAmount = transferContract.amount
        
        // 4. 关键校验：金额必须等于 pricePerUnitSun * multiplier
        val expectedAmount = config.pricePerUnitSun * config.multiplier
        if (actualAmount != expectedAmount) {
            throw BroadcastException(
                "金额校验失败：期望 $expectedAmount sun，实际 $actualAmount sun"
            )
        }
        
        // 5. 检查是否过期
        val currentTime = System.currentTimeMillis()
        if (rawData.expiration < currentTime) {
            throw BroadcastException("交易已过期")
        }
    }
    
    /**
     * 处理广播成功
     */
    private fun handleSuccess(
        transaction: Chain.Transaction,
        config: SettingsConfig
    ): BroadcastResult {
        // 计算 TXID
        val txid = calculateTxId(transaction)
        
        // 提取交易信息
        val transferContract = Contract.TransferContract.parseFrom(
            transaction.rawData.getContract(0).parameter.value
        )
        val fromAddress = org.tron.trident.utils.Base58Check.bytesToBase58(
            transferContract.ownerAddress.toByteArray()
        )
        val toAddress = org.tron.trident.utils.Base58Check.bytesToBase58(
            transferContract.toAddress.toByteArray()
        )
        val amountSun = transferContract.amount
        
        // 本地记录
        val record = TransactionRecord(
            txid = txid,
            fromAddress = fromAddress,
            toAddress = toAddress,
            amountSun = amountSun,
            timestamp = System.currentTimeMillis(),
            status = TransactionStatus.SUCCESS,
            memo = transaction.rawData.data.toStringUtf8()
        )
        transactionRecorder.saveRecord(record)
        
        return BroadcastResult.Success(
            txid = txid,
            message = "交易广播成功"
        )
    }
    
    /**
     * 处理广播失败
     */
    private fun handleFailure(errorMessage: String): BroadcastResult {
        // 解析错误信息
        val readableMessage = parseErrorMessage(errorMessage)
        
        return BroadcastResult.Failure(readableMessage)
    }
    
    /**
     * 计算交易 ID
     */
    private fun calculateTxId(transaction: Chain.Transaction): String {
        val txBytes = transaction.toByteArray()
        val hash = org.tron.trident.crypto.Hash.sha256(txBytes)
        return org.tron.trident.utils.Numeric.toHexString(hash)
    }
    
    /**
     * 解析错误信息
     * 将技术性错误转换为用户友好的提示
     */
    private fun parseErrorMessage(errorMessage: String): String {
        return when {
            errorMessage.contains("balance is not sufficient", ignoreCase = true) ->
                "余额不足"
            
            errorMessage.contains("account not exists", ignoreCase = true) ->
                "账户不存在"
            
            errorMessage.contains("expired", ignoreCase = true) ->
                "交易已过期"
            
            errorMessage.contains("duplicated", ignoreCase = true) ->
                "交易已提交，请勿重复广播"
            
            errorMessage.contains("validate signature error", ignoreCase = true) ->
                "签名验证失败"
            
            else -> "广播失败：$errorMessage"
        }
    }
    
    /**
     * 获取交易历史
     */
    fun getTransactionHistory(): List<TransactionRecord> {
        return transactionRecorder.getAllRecords()
    }

    /**
     * 获取入账交易（链上查询）
     * 
     * @param address 账户地址
     * @param limit 限制数量
     * @return 交易信息列表
     */
    suspend fun getIncomingTransactions(address: String, limit: Int = 10): List<TransactionRecord> = withContext(Dispatchers.IO) {
        try {
            // 注意：trident 的 apiWrapper.getTransactionsToThis 在某些节点可能不可用
            // 这里我们尝试获取，如果失败则返回空列表
            val transactions = apiWrapper.getTransactionsToThis(address, 0, limit)
            transactions.map { tx ->
                val txid = calculateTxId(tx)
                val rawData = tx.rawData
                val contract = rawData.getContract(0)
                val transfer = org.tron.trident.proto.Contract.TransferContract.parseFrom(contract.parameter.value)
                
                TransactionRecord(
                    txid = txid,
                    fromAddress = org.tron.trident.utils.Base58Check.bytesToBase58(transfer.ownerAddress.toByteArray()),
                    toAddress = org.tron.trident.utils.Base58Check.bytesToBase58(transfer.toAddress.toByteArray()),
                    amountSun = transfer.amount,
                    timestamp = rawData.timestamp,
                    status = TransactionStatus.SUCCESS,
                    memo = rawData.data.toStringUtf8()
                )
            }
        } catch (e: Exception) {
            // 降级处理：某些公共节点禁用了此 API，返回空列表
            emptyList()
        }
    }
}

/**
 * 广播结果
 */
sealed class BroadcastResult {
    /**
     * 广播成功
     */
    data class Success(
        val txid: String,
        val message: String = "交易广播成功"
    ) : BroadcastResult()
    
    /**
     * 广播失败
     */
    data class Failure(
        val message: String
    ) : BroadcastResult()
}

/**
 * 广播异常
 */
class BroadcastException(message: String) : Exception(message)

/**
 * 交易状态信息
 */
sealed class TransactionStatusInfo {
    object Pending : TransactionStatusInfo()
    object NotFound : TransactionStatusInfo()
    data class Success(
        val blockHeight: Long,
        val feeSun: Long,
        val netUsage: Long,
        val energyUsage: Long
    ) : TransactionStatusInfo()
    data class Failed(val reason: String) : TransactionStatusInfo()
    data class Error(val message: String) : TransactionStatusInfo()
}
