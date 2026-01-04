package com.trxsafe.payment.service

import android.content.Context
import com.trxsafe.payment.broadcast.BroadcastResult
import com.trxsafe.payment.broadcast.TransactionBroadcaster
import com.trxsafe.payment.network.TronHttpClient
import com.trxsafe.payment.settings.SettingsConfig
import com.trxsafe.payment.settings.SettingsRepository
import com.trxsafe.payment.transaction.TransactionBuilder
import com.trxsafe.payment.validation.AddressValidator
import com.trxsafe.payment.wallet.WalletManager
import org.tron.trident.proto.Chain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TransferService(private val context: Context) {

    private val walletManager = WalletManager(context)
    private val settingsRepository = SettingsRepository.getInstance(context)
    private var httpClient: TronHttpClient? = null

    data class TransferParams(
        val toAddress: String,
        val amountTrx: Double,
        val burnTrx: Double = 0.0,
        val onProgress: (TransferProgress) -> Unit = {},
        val onSuccess: (String) -> Unit = {},
        val onError: (String) -> Unit = {}
    )

    sealed class TransferProgress {
        object Validating : TransferProgress()
        data class Building(val step: Int, val total: Int) : TransferProgress()
        object Signing : TransferProgress()
        object Broadcasting : TransferProgress()
        data class Completed(val txid: String) : TransferProgress()
    }

    suspend fun executeTransfer(params: TransferParams): Result<String> = withContext(Dispatchers.IO) {
        try {
            withContext(Dispatchers.Main) {
                params.onProgress(TransferProgress.Validating)
            }

            val fromAddress = walletManager.getAddress()
                ?: return@withContext Result.failure(IllegalStateException("未找到钱包地址"))

            AddressValidator.validateAndNormalize(params.toAddress)
                .onFailure { return@withContext Result.failure(it) }

            if (params.amountTrx <= 0) {
                return@withContext Result.failure(IllegalArgumentException("转账金额必须大于0"))
            }

            withContext(Dispatchers.Main) {
                params.onProgress(TransferProgress.Building(1, 3))
            }

            val config = settingsRepository.loadConfig()
            val amountSun = (params.amountTrx * 1_000_000).toLong()

            val transferConfig = SettingsConfig(
                sellerAddress = params.toAddress,
                pricePerUnitSun = amountSun,
                multiplier = 1,
                nodeUrl = config.nodeUrl
            )

            val transactionBuilder = TransactionBuilder()
            val httpClient = getOrCreateHttpClient(config.nodeUrl)

            withContext(Dispatchers.Main) {
                params.onProgress(TransferProgress.Building(2, 3))
            }

            val unsignedTransaction = transactionBuilder.buildTransferTransaction(
                fromAddress = fromAddress,
                config = transferConfig,
                httpClient = httpClient
            )

            withContext(Dispatchers.Main) {
                params.onProgress(TransferProgress.Signing)
            }

            val signedTransaction = walletManager.signTransferContract(unsignedTransaction)

            withContext(Dispatchers.Main) {
                params.onProgress(TransferProgress.Broadcasting)
            }

            val broadcaster = TransactionBroadcaster(context, null, httpClient)
            val result = broadcaster.broadcast(signedTransaction, config)

            when (result) {
                is BroadcastResult.Success -> {
                    withContext(Dispatchers.Main) {
                        params.onProgress(TransferProgress.Completed(result.txid))
                        params.onSuccess(result.txid)
                    }
                    Result.success(result.txid)
                }
                is BroadcastResult.Failure -> {
                    withContext(Dispatchers.Main) {
                        params.onError(result.message)
                    }
                    Result.failure(Exception(result.message))
                }
            }

        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                params.onError(e.message ?: "转账失败")
            }
            Result.failure(e)
        }
    }

    private fun getOrCreateHttpClient(nodeUrl: String): TronHttpClient {
        return httpClient ?: TronHttpClient(nodeUrl).also { httpClient = it }
    }

    suspend fun buildTransaction(
        toAddress: String,
        amountTrx: Double
    ): Result<Chain.Transaction> = withContext(Dispatchers.IO) {
        try {
            val fromAddress = walletManager.getAddress()
                ?: return@withContext Result.failure(IllegalStateException("未找到钱包地址"))

            AddressValidator.validateAndNormalize(toAddress)
                .onFailure { return@withContext Result.failure(it) }

            if (amountTrx <= 0) {
                return@withContext Result.failure(IllegalArgumentException("转账金额必须大于0"))
            }

            val config = settingsRepository.loadConfig()
            val amountSun = (amountTrx * 1_000_000).toLong()

            val transferConfig = SettingsConfig(
                sellerAddress = toAddress,
                pricePerUnitSun = amountSun,
                multiplier = 1,
                nodeUrl = config.nodeUrl
            )

            val transactionBuilder = TransactionBuilder()
            val httpClient = getOrCreateHttpClient(config.nodeUrl)

            val transaction = transactionBuilder.buildTransferTransaction(
                fromAddress = fromAddress,
                config = transferConfig,
                httpClient = httpClient
            )

            Result.success(transaction)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
