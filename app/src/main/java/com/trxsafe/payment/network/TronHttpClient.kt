package com.trxsafe.payment.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.tron.trident.proto.Chain
import com.google.protobuf.ByteString
import org.tron.trident.proto.Contract
import org.tron.trident.utils.Base58Check
import org.tron.trident.utils.Numeric
import java.net.HttpURLConnection
import java.net.URL

/**
 * 分页结果数据类
 */
data class PaginatedResult(
    val data: List<JsonObject>, val nextUrl: String? = null, val hasMore: Boolean = false
)

/**
 * TRX 金额单位
 * 1 TRX = 1,000,000 SUN
 */
data class Sun(val value: Long) {
    val trx: Float get() = value / 1_000_000f
    override fun toString(): String = "$value SUN (${trx} TRX)"
    companion object {
        fun fromTrx(trx: Float): Sun = Sun((trx * 1_000_000).toLong())
    }
}

/**
 * TRON HTTP API 客户端
 * 用于替代 gRPC 的 HTTP 接口调用
 */
class TronHttpClient(private val baseUrl: String) {

    private val gson = Gson()

    private val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    private fun isValidBase58Address(address: String): Boolean {
        if (address.isEmpty() || address.length != 34 || !address.startsWith("T")) {
            return false
        }
        return address.all { it in BASE58_ALPHABET }
    }

    /**
     * 获取账户信息
     */
    suspend fun getAccount(address: String): JsonObject? = withContext(Dispatchers.IO) {
        callApiGet("v1/accounts/$address")
    }

    /**
     * 获取账户资源（带宽、能量、TRX余额）
     * @param address Base58 格式的地址
     * @return 包含资源的 JsonObject，失败返回 null
     */
    suspend fun getAccountResources(address: String): JsonObject? = withContext(Dispatchers.IO) {
        try {
            val url = if (baseUrl.endsWith("/")) {
                "${baseUrl}wallet/getaccountresource"
            } else {
                "$baseUrl/wallet/getaccountresource"
            }

            val requestBody = """{"address":"$address","visible":true}"""

            val json = callApiRaw(url, requestBody)
            android.util.Log.d("TronHttpClient", "getAccountResources response: $json")

            // 调试：打印所有字段
            if (json != null) {
                android.util.Log.d("TronHttpClient", "getAccountResources fields:")
                for (entry in json.entrySet()) {
                    android.util.Log.d("TronHttpClient", "  ${entry.key}: ${entry.value}")
                }
            }

            json
        } catch (e: Exception) {
            android.util.Log.e("TronHttpClient", "获取账户资源失败: ${e.message}")
            null
        }
    }

    /**
     * 预估转账消耗的带宽
     * 普通转账大约消耗 280 字节的带宽
     * @return 预估消耗的带宽点数
     */
    fun estimateTransferBandwidth(): Long {
        return 280L
    }

    /**
     * 计算带宽是否足够，不够时需要燃烧多少 TRX
     * @param availableBandwidth 可用带宽
     * @return 燃烧金额（单位：sun），如果带宽足够返回 0
     */
    fun calculateBandwidthBurn(availableBandwidth: Long): Long {
        val required = estimateTransferBandwidth()
        return if (availableBandwidth >= required) {
            0L
        } else {
            (required - availableBandwidth) * 1000L
        }
    }

    /**
     * 获取账户TRX余额
     * @param address Base58 格式的地址
     * @return 余额（单位：sun），失败返回 0
     */
    suspend fun getAccountBalance(address: String): Long = withContext(Dispatchers.IO) {
        try {
            val url = if (baseUrl.endsWith("/")) {
                "${baseUrl}wallet/getaccount"
            } else {
                "$baseUrl/wallet/getaccount"
            }

            val requestBody = """{"address":"$address","visible":true}"""

            val json = callApiRaw(url, requestBody)
            val balance = json?.get("balance")?.asLong ?: 0L
            android.util.Log.d("TronHttpClient", "账户余额: $balance sun (${balance / 1_000_000f} TRX)")
            balance
        } catch (e: Exception) {
            android.util.Log.e("TronHttpClient", "获取余额失败: ${e.message}")
            0L
        }
    }

    /**
     * 计算转账总消耗（带宽燃烧 + 转账金额）
     * @param availableBandwidth 可用带宽
     * @param transferAmount 转账金额（单位：sun）
     * @param accountBalance 账户余额（单位：sun）
     * @return 包含状态和消息的 Map
     */
    suspend fun calculateTransferCost(
        address: String,
        availableBandwidth: Long,
        transferAmount: Sun
    ): Map<String, Any> {
        val requiredBandwidth = estimateTransferBandwidth()
        val burnForBandwidth = calculateBandwidthBurn(availableBandwidth)
        val accountBalance = getAccountBalance(address)
        val totalNeeded = transferAmount.value + burnForBandwidth

        val canAfford = accountBalance >= totalNeeded

        android.util.Log.d("TronHttpClient", "费用计算详情:")
        android.util.Log.d("TronHttpClient", "  地址: $address")
        android.util.Log.d("TronHttpClient", "  可用带宽: $availableBandwidth")
        android.util.Log.d("TronHttpClient", "  所需带宽: $requiredBandwidth")
        android.util.Log.d("TronHttpClient", "  燃烧带宽: $burnForBandwidth SUN (${burnForBandwidth / 1_000_000f} TRX)")
        android.util.Log.d("TronHttpClient", "  转账金额: ${transferAmount.value} SUN (${transferAmount.value / 1_000_000f} TRX)")
        android.util.Log.d("TronHttpClient", "  账户余额: $accountBalance SUN (${accountBalance / 1_000_000f} TRX)")
        android.util.Log.d("TronHttpClient", "  总计需要: $totalNeeded SUN (${totalNeeded / 1_000_000f} TRX)")
        android.util.Log.d("TronHttpClient", "  是否能支付: $canAfford")

        return mapOf(
            "availableBandwidth" to availableBandwidth,
            "requiredBandwidth" to requiredBandwidth,
            "burnForBandwidth" to burnForBandwidth,
            "burnForBandwidthTRX" to (burnForBandwidth / 1_000_000.0),
            "transferAmount" to transferAmount.value,
            "transferAmountTRX" to (transferAmount.value / 1_000_000.0),
            "accountBalance" to accountBalance,
            "accountBalanceTRX" to (accountBalance / 1_000_000.0),
            "totalNeeded" to totalNeeded,
            "totalNeededTRX" to (totalNeeded / 1_000_000.0),
            "canAfford" to canAfford
        )
    }

    /**
     * 获取最新区块信息
     * 返回包含 blockID 和 number 的 JsonObject，如果获取失败返回 null
     */
    suspend fun getNowBlock(): JsonObject? = withContext(Dispatchers.IO) {
        try {
            val fullUrl = if (baseUrl.endsWith("/")) {
                "${baseUrl}wallet/getnowblock"
            } else {
                "$baseUrl/wallet/getnowblock"
            }

            android.util.Log.d("TronHttpClient", "获取最新区块: $fullUrl")

            val url = URL(fullUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "TronClient/1.0")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            val responseCode = conn.responseCode
            android.util.Log.d("TronHttpClient", "getNowBlock response code: $responseCode")

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = conn.inputStream.bufferedReader().readText()
                android.util.Log.d("TronHttpClient", "getNowBlock raw response: ${response.take(500)}")

                val json = gson.fromJson(response, JsonObject::class.java)

                val blockHeader = json.getAsJsonObject("block_header")
                val blockRawData = blockHeader?.getAsJsonObject("raw_data")

                val blockId = json.get("blockID")?.asString
                    ?: blockRawData?.get("hash")?.asString
                    ?: json.get("block_hash")?.asString

                val blockNumber = blockRawData?.get("number")?.asLong
                    ?: json.get("number")?.asLong

                android.util.Log.d("TronHttpClient", "解析结果 - blockId: $blockId, blockNumber: $blockNumber")

                if (blockId != null) {
                    val normalizedResponse = JsonObject()
                    normalizedResponse.addProperty("blockID", blockId)
                    if (blockNumber != null) {
                        normalizedResponse.addProperty("number", blockNumber)
                    } else {
                        normalizedResponse.addProperty("number", 0L)
                    }
                    if (blockRawData != null) {
                        normalizedResponse.add("raw_data", blockRawData)
                    }
                    val rawDataHex = blockHeader?.get("raw_data_hex")
                    if (rawDataHex != null) {
                        normalizedResponse.add("raw_data_hex", rawDataHex)
                    }

                    android.util.Log.d("TronHttpClient", "标准化区块响应: blockID=${normalizedResponse.get("blockID")}, number=${normalizedResponse.get("number")}")
                    normalizedResponse
                } else {
                    android.util.Log.e("TronHttpClient", "API响应中未找到blockID字段: $response")
                    null
                }
            } else {
                android.util.Log.e("TronHttpClient", "getNowBlock HTTP错误: $responseCode")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("TronHttpClient", "getNowBlock异常: ${e.message}", e)
            null
        }
    }

    /**
     * 创建转账交易（由节点设置TAPOS参数）
     * 调用 /wallet/createtransaction 让节点帮我们设置refBlockBytes和refBlockHash
     * @param fromAddress 发送方地址 (Base58格式)
     * @param toAddress 接收方地址 (Base58格式)
     * @param amountSun 转账金额（单位：SUN，1 TRX = 1,000,000 SUN）
     * @return TransactionResult.Success 包含交易JSON，TransactionResult.Error 包含错误信息
     */
    suspend fun createTransaction(
        fromAddress: String, toAddress: String, amountSun: Long
    ): TransactionResult = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d("TronHttpClient", "创建交易: from=$fromAddress, to=$toAddress, amount=$amountSun SUN")

            if (!isValidBase58Address(fromAddress)) {
                android.util.Log.e("TronHttpClient", "无效的发送方地址 (包含非法字符): $fromAddress")
                return@withContext TransactionResult.Error("无效的发送方地址")
            }

            if (!isValidBase58Address(toAddress)) {
                android.util.Log.e("TronHttpClient", "无效的接收方地址 (包含非法字符): $toAddress")
                return@withContext TransactionResult.Error("无效的接收方地址")
            }

            val request = JsonObject().apply {
                addProperty("owner_address", fromAddress)
                addProperty("to_address", toAddress)
                addProperty("amount", amountSun)
                addProperty("visible", true)
            }

            android.util.Log.d("TronHttpClient", "createTransaction request: ${gson.toJson(request)}")

            val response = callApi("wallet/createtransaction", gson.toJson(request))

            if (response != null) {
                android.util.Log.d("TronHttpClient", "createTransaction response: ${response.toString().take(300)}")

                val hasTxId = response.has("txID") && response.get("txID")?.asString?.isNotEmpty() == true
                val hasRawData = response.has("raw_data") && response.get("raw_data")?.isJsonObject == true
                val errorField = response.get("Error")?.asString

                if ((hasTxId && hasRawData) || response.get("result")?.asBoolean == true) {
                    android.util.Log.d("TronHttpClient", "节点创建交易成功")
                    return@withContext TransactionResult.Success(response)
                } else if (errorField != null) {
                    val userFriendlyMessage = parseErrorMessage(errorField)
                    android.util.Log.e("TronHttpClient", "节点创建交易失败: $errorField -> $userFriendlyMessage")
                    return@withContext TransactionResult.Error(userFriendlyMessage)
                } else {
                    android.util.Log.e("TronHttpClient", "节点创建交易失败: 响应格式异常")
                    return@withContext TransactionResult.Error("创建交易失败，请重试")
                }
            } else {
                android.util.Log.e("TronHttpClient", "createTransaction API返回null")
                return@withContext TransactionResult.Error("网络请求失败，请检查网络连接")
            }
        } catch (e: Exception) {
            android.util.Log.e("TronHttpClient", "createTransaction异常: ${e.message}", e)
            return@withContext TransactionResult.Error("网络异常，请重试")
        }
    }

    private fun parseErrorMessage(rawError: String): String {
        return when {
            rawError.contains("no OwnerAccount", ignoreCase = true) ||
            rawError.contains("OwnerAccount", ignoreCase = true) -> {
                "发送方账户未激活，请先向该账户转入至少1 TRX以激活账户"
            }
            rawError.contains("no ToAccount", ignoreCase = true) ||
            rawError.contains("ToAccount", ignoreCase = true) -> {
                "接收方账户不存在或未激活"
            }
            rawError.contains("balance", ignoreCase = true) &&
            rawError.contains("not sufficient", ignoreCase = true) -> {
                "余额不足，请确保账户有足够的TRX"
            }
            rawError.contains("contract validate", ignoreCase = true) -> {
                "交易验证失败，请检查地址和金额"
            }
            rawError.contains("invalid", ignoreCase = true) -> {
                "参数无效，请检查输入信息"
            }
            else -> rawError
        }
    }

    sealed class TransactionResult {
        data class Success(val transactionJson: JsonObject) : TransactionResult()
        data class Error(val message: String) : TransactionResult()
    }

    /**
     * 将JSON响应中的交易转换为Chain.Transaction proto对象
     * 用于本地签名
     * @param transactionJson 节点返回的交易JSON
     * @return Chain.Transaction对象，解析失败返回null
     */
    fun parseTransactionFromJson(transactionJson: JsonObject): Chain.Transaction? {
        return try {
            android.util.Log.d("TronHttpClient", "开始解析交易JSON")

            val rawDataJson = transactionJson.getAsJsonObject("raw_data")
                ?: throw Exception("raw_data不存在")

            android.util.Log.d("TronHttpClient", "raw_data: ${rawDataJson.toString().take(200)}")

            val refBlockBytesHex = rawDataJson.get("ref_block_bytes")?.asString
            val refBlockHashHex = rawDataJson.get("ref_block_hash")?.asString
            val timestamp = rawDataJson.get("timestamp")?.asLong ?: System.currentTimeMillis()
            val expiration = rawDataJson.get("expiration")?.asLong ?: (timestamp + 300_000)
            val feeLimit = rawDataJson.get("fee_limit")?.asLong ?: 10_000_000L

            android.util.Log.d("TronHttpClient", "提取的TAPOS参数:")
            android.util.Log.d("TronHttpClient", "  refBlockBytes: $refBlockBytesHex")
            android.util.Log.d("TronHttpClient", "  refBlockHash: $refBlockHashHex")
            android.util.Log.d("TronHttpClient", "  timestamp: $timestamp")
            android.util.Log.d("TronHttpClient", "  expiration: $expiration")
            android.util.Log.d("TronHttpClient", "  feeLimit: $feeLimit")

            val contracts = rawDataJson.getAsJsonArray("contract")
            if (contracts == null || contracts.size() == 0) {
                throw Exception("raw_data中没有contract")
            }

            val contractJson = contracts.get(0).asJsonObject
            android.util.Log.d("TronHttpClient", "contract: ${contractJson.toString().take(200)}")

            val transferContractJson = contractJson.getAsJsonObject("parameter")?.getAsJsonObject("value")
                ?: throw Exception("无法解析TransferContract")

            android.util.Log.d("TronHttpClient", "transferContract value: ${transferContractJson.toString().take(200)}")

            val ownerAddressHex = transferContractJson.get("owner_address")?.asString
            val toAddressHex = transferContractJson.get("to_address")?.asString
            val amount = transferContractJson.get("amount")?.asLong ?: 0L

            android.util.Log.d("TronHttpClient", "转账详情: owner=$ownerAddressHex, to=$toAddressHex, amount=$amount")

            if (ownerAddressHex == null || toAddressHex == null) {
                throw Exception("缺少地址信息")
            }

            val ownerAddressBytes = try {
                org.tron.trident.utils.Base58Check.base58ToBytes(ownerAddressHex)
            } catch (e: Exception) {
                org.tron.trident.utils.Numeric.hexStringToByteArray(ownerAddressHex)
            }
            val toAddressBytes = try {
                org.tron.trident.utils.Base58Check.base58ToBytes(toAddressHex)
            } catch (e: Exception) {
                org.tron.trident.utils.Numeric.hexStringToByteArray(toAddressHex)
            }

            val transferContract = Contract.TransferContract.newBuilder()
                .setOwnerAddress(ByteString.copyFrom(ownerAddressBytes))
                .setToAddress(ByteString.copyFrom(toAddressBytes))
                .setAmount(amount)
                .build()

            val contract = Chain.Transaction.Contract.newBuilder()
                .setType(Chain.Transaction.Contract.ContractType.TransferContract)
                .setParameter(com.google.protobuf.Any.pack(transferContract))
                .build()

            val rawDataBuilder = Chain.Transaction.raw.newBuilder()
                .addContract(contract)
                .setTimestamp(timestamp)
                .setExpiration(expiration)
                .setFeeLimit(feeLimit)

            refBlockBytesHex?.let { hex ->
                try {
                    val bytes = org.tron.trident.utils.Numeric.hexStringToByteArray(hex)
                    rawDataBuilder.setRefBlockBytes(ByteString.copyFrom(bytes))
                } catch (e: Exception) {
                    android.util.Log.w("TronHttpClient", "设置refBlockBytes失败: ${e.message}")
                }
            }

            refBlockHashHex?.let { hex ->
                try {
                    val bytes = org.tron.trident.utils.Numeric.hexStringToByteArray(hex)
                    rawDataBuilder.setRefBlockHash(ByteString.copyFrom(bytes))
                } catch (e: Exception) {
                    android.util.Log.w("TronHttpClient", "设置refBlockHash失败: ${e.message}")
                }
            }

            val transaction = Chain.Transaction.newBuilder()
                .setRawData(rawDataBuilder.build())
                .build()

            android.util.Log.d("TronHttpClient", "成功解析交易")
            android.util.Log.d("TronHttpClient", "  refBlockBytes size: ${transaction.rawData.refBlockBytes.size()}")
            android.util.Log.d("TronHttpClient", "  refBlockHash size: ${transaction.rawData.refBlockHash.size()}")

            transaction
        } catch (e: Exception) {
            android.util.Log.e("TronHttpClient", "解析交易失败: ${e.message}", e)
            null
        }
    }

    /**
     * 账户余额数据类
     */
    data class AccountBalances(
        val trxBalance: Long, val usdtBalance: Double
    )

    /**
     * 获取账户余额（TRX和USDT）
     */
    suspend fun getAccountBalances(address: String): AccountBalances = withContext(Dispatchers.IO) {
        try {
            val response = getAccount(address)
            if (response != null && response.has("data")) {
                val dataArray = response.getAsJsonArray("data")
                if (dataArray != null && dataArray.size() > 0) {
                    val accountData = dataArray.get(0).asJsonObject

                    // 获取TRX余额
                    val trxBalance = accountData.get("balance")?.let { balanceValue ->
                        when {
                            balanceValue.isJsonPrimitive && balanceValue.asJsonPrimitive.isNumber -> {
                                balanceValue.asLong
                            }

                            balanceValue.isJsonPrimitive && balanceValue.asJsonPrimitive.isString -> {
                                balanceValue.asString.toLongOrNull() ?: 0L
                            }

                            else -> 0L
                        }
                    } ?: 0L

                    // 获取USDT余额
                    val usdtBalance = accountData.get("trc20")?.let { trc20Element ->
                        parseUsdtBalance(trc20Element)
                    } ?: 0.0

                    return@withContext AccountBalances(trxBalance, usdtBalance)
                } else {
                    // 数据数组为空，说明地址没有账户数据，余额为0
                    return@withContext AccountBalances(0L, 0.0)
                }
            }
            AccountBalances(-1L, 0.0) // 查询失败
        } catch (e: Exception) {
            android.util.Log.e("TronHttpClient", "获取账户余额失败: ${e.message}", e)
            AccountBalances(-1L, 0.0)
        }
    }

    /**
     * 获取交易信息
     */
    suspend fun getTransactionById(txid: String): JsonObject? = withContext(Dispatchers.IO) {
        val request = JsonObject().apply {
            addProperty("value", txid)
        }
        callApi("wallet/gettransactionbyid", gson.toJson(request))
    }

    /**
     * 获取交易收据信息
     */
    suspend fun getTransactionInfoById(txid: String): JsonObject? = withContext(Dispatchers.IO) {
        val request = JsonObject().apply {
            addProperty("value", txid)
        }
        callApi("wallet/gettransactioninfobyid", gson.toJson(request))
    }

    /**
     * 广播交易 - 使用正确的 /wallet/broadcasttransaction 端点
     * 根据Python参考实现，需要发送完整的签名交易对象（包含signature字段）
     */
    suspend fun broadcastTransactionRaw(transaction: Chain.Transaction): String =
        withContext(Dispatchers.IO) {

            val txBytes = transaction.toByteArray()
            var txHex = org.tron.trident.utils.Numeric.toHexString(txBytes)

            if (txHex.startsWith("0x")) {
                txHex = txHex.substring(2)
            }

            val request = JsonObject().apply {
                addProperty("transaction", txHex)
            }

            val url = if (baseUrl.startsWith("http")) {
                "$baseUrl/wallet/broadcasthex"
            } else {
                "https://$baseUrl/wallet/broadcasthex"
            }

            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.doOutput = true

                val body = gson.toJson(request)

                conn.outputStream.use {
                    it.write(body.toByteArray(Charsets.UTF_8))
                }

                val response = conn.inputStream.bufferedReader().readText()

                val json = gson.fromJson(response, JsonObject::class.java)
                if (json["result"]?.asBoolean == true) {
                    val txid = org.tron.trident.utils.Numeric.toHexString(
                        org.tron.trident.crypto.Hash.sha256(txBytes)
                    )
                    txid
                } else {
                    throw Exception("广播失败: $response")
                }
            } finally {
                conn.disconnect()
            }
        }


    /**
     * 广播交易
     */
    suspend fun broadcastTransaction(transaction: Chain.Transaction): String =
        withContext(Dispatchers.IO) {
            try {
                val txBytes = transaction.toByteArray()
                var txHex = org.tron.trident.utils.Numeric.toHexString(txBytes)

                // 移除0x前缀（如果存在）
                if (txHex.startsWith("0x")) {
                    txHex = txHex.substring(2)
                }

                android.util.Log.d("TronHttpClient", "开始广播交易，txHex: ${txHex.take(50)}...")
                android.util.Log.d("TronHttpClient", "交易签名数量: ${transaction.signatureCount}")
                if (transaction.signatureCount > 0) {
                    val signature = transaction.getSignature(0)
                    android.util.Log.d("TronHttpClient", "签名长度: ${signature.size()}")
                    android.util.Log.d("TronHttpClient", "正在广播交易...")
                }

                val request = JsonObject().apply {
                addProperty("transaction", txHex)
                addProperty("visible", true)
            }
                android.util.Log.d("TronHttpClient", "请求数据: ${gson.toJson(request)}")
                val response = callApi("wallet/broadcasttransaction", gson.toJson(request))

                if (response != null) {
                    try {
                        android.util.Log.d("TronHttpClient", "收到API响应: ${response}")

                        val resultElement = response.get("result")
                        val result = try {
                            if (resultElement != null && resultElement.isJsonPrimitive && resultElement.asJsonPrimitive.isBoolean) {
                                resultElement.asBoolean
                            } else {
                                false
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("TronHttpClient", "解析result字段失败", e)
                            false
                        }

                        val codeElement = response.get("code")
                        val code = try {
                            if (codeElement != null && codeElement.isJsonPrimitive && codeElement.asJsonPrimitive.isString) {
                                codeElement.asString
                            } else {
                                null
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("TronHttpClient", "解析code字段失败", e)
                            null
                        }

                        android.util.Log.d("TronHttpClient", "result: $result, code: $code")

                        if (result == true) {
                            try {
                                val hash = org.tron.trident.crypto.Hash.sha256(txBytes)
                                if (hash == null) {
                                    throw Exception("交易哈希计算失败")
                                }
                                val txid = org.tron.trident.utils.Numeric.toHexString(hash)
                                if (txid.isNullOrEmpty()) {
                                    throw Exception("交易ID生成失败")
                                }
                                android.util.Log.d("TronHttpClient", "交易广播成功，txid: $txid")
                                txid
                            } catch (e: Exception) {
                                android.util.Log.e("TronHttpClient", "生成交易ID失败", e)
                                throw Exception("交易广播失败：生成交易ID时发生错误 - ${e.message}")
                            }
                        } else {
                            val errorElement = response.get("Error")
                            val error = try {
                                if (errorElement != null && errorElement.isJsonPrimitive && errorElement.asJsonPrimitive.isString) {
                                    errorElement.asString
                                } else {
                                    null
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("TronHttpClient", "解析Error字段失败", e)
                                null
                            }

                            val messageElement = response.get("message")
                            val message = try {
                                if (messageElement != null && messageElement.isJsonPrimitive && messageElement.asJsonPrimitive.isString) {
                                    messageElement.asString
                                } else {
                                    null
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("TronHttpClient", "解析message字段失败", e)
                                null
                            }

                            android.util.Log.d("TronHttpClient", "error: $error, message: $message")

                            val errorMessage = try {
                                when {
                                    error != null -> error
                                    message != null -> message
                                    code != null -> "错误码: $code"
                                    else -> "交易广播失败"
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("TronHttpClient", "构建错误消息失败", e)
                                "交易广播失败：未知错误"
                            }

                            android.util.Log.e("TronHttpClient", "交易广播失败: $errorMessage")
                            throw Exception(errorMessage)
                        }
                    } catch (e: NullPointerException) {
                        android.util.Log.e("TronHttpClient", "解析响应时发生空指针异常", e)
                        throw Exception("交易广播失败：服务器返回的数据格式异常")
                    } catch (e: Exception) {
                        android.util.Log.e("TronHttpClient", "处理响应时发生异常", e)
                        throw e
                    }
                } else {
                    android.util.Log.e("TronHttpClient", "API返回null响应")
                    throw Exception("广播交易失败：无响应")
                }
            } catch (e: Exception) {
                android.util.Log.e("TronHttpClient", "广播交易失败: ${e.message}", e)
                throw Exception("广播失败：${e.message}")
            }
        }

    /**
     * 获取账户的转出交易历史（支持分页）
     * @param address 钱包地址
     * @param limit 每页数量
     * @param nextUrl 可选的下一页URL，如果提供则使用此URL进行分页
     */
    suspend fun getOutgoingTransactions(
        address: String, limit: Int = 50, nextUrl: String? = null
    ): PaginatedResult = withContext(Dispatchers.IO) {
        var lastException: Exception? = null

        // 重试最多3次，每次间隔1秒
        for (attempt in 1..3) {
            try {
                val response = if (nextUrl != null) {
                    // 使用下一页URL
                    callApiGetUrl(nextUrl)
                } else {
                    // 首次请求 - 获取所有交易，然后在客户端过滤转出交易
                    val endpoint =
                        "v1/accounts/$address/transactions?limit=${limit * 2}&only_confirmed=true"
                    android.util.Log.d(
                        "TronHttpClient", "获取转出交易 (尝试 $attempt/3): $endpoint"
                    )
                    callApiGet(endpoint)
                }

                if (response != null && response.has("data")) {
                    val allTransactions = response.getAsJsonArray("data")
                    android.util.Log.d(
                        "TronHttpClient", "API返回 ${allTransactions.size()} 条交易记录"
                    )

                    // 过滤出转出交易（fromAddress 等于当前地址，且为TRX转账）
                    val outgoingTransactions =
                        allTransactions.mapNotNull { it.asJsonObject }.filter { txJson ->
                                try {
                                    val rawData = txJson.getAsJsonObject("raw_data")
                                    val contracts = rawData?.getAsJsonArray("contract")
                                    val contract = contracts?.get(0)?.asJsonObject
                                    val contractType = contract?.get("type")?.asString ?: ""

                                    // 只处理TRX原生转账交易
                                    if (contractType != "TransferContract") {
                                        return@filter false
                                    }

                                    val parameter = contract?.getAsJsonObject("parameter")
                                    val value = parameter?.getAsJsonObject("value")
                                    val ownerAddress = value?.get("owner_address")?.asString ?: ""

                                    // 判断是否为转出交易
                                    val isOutgoing = addressesMatch(ownerAddress, address)
                                    android.util.Log.d(
                                        "TronHttpClient",
                                        "交易 ${txJson.get("txID")?.asString} - owner: $ownerAddress, address: $address, isOutgoing: $isOutgoing"
                                    )
                                    isOutgoing
                                } catch (e: Exception) {
                                    android.util.Log.w(
                                        "TronHttpClient", "解析交易失败: ${e.message}", e
                                    )
                                    false
                                }
                            }

                    android.util.Log.d(
                        "TronHttpClient", "过滤后剩余 ${outgoingTransactions.size} 条转出交易"
                    )

                    // 解析下一页URL
                    val nextUrlResult = response.getAsJsonObject("meta")?.getAsJsonObject("links")
                        ?.get("next")?.asString

                    // 优先使用API返回的hasMore字段，如果没有则检查nextUrl是否存在
                    val apiHasMore = response.getAsJsonObject("meta")?.getAsJsonObject("links")
                        ?.get("hasMore")?.asBoolean

                    val hasMoreData = apiHasMore ?: (nextUrlResult != null)

                    android.util.Log.d(
                        "TronHttpClient",
                        "转出交易分页信息 - API hasMore: $apiHasMore, nextUrl存在: ${nextUrlResult != null}, 最终hasMore: $hasMoreData, filtered: ${outgoingTransactions.size}, limit: $limit"
                    )

                    return@withContext PaginatedResult(
                        data = outgoingTransactions.take(limit), // 限制返回数量
                        nextUrl = nextUrlResult, hasMore = hasMoreData
                    )
                }

                android.util.Log.w(
                    "TronHttpClient", "API响应为空或不包含data字段 (尝试 $attempt/3)"
                )
                // 如果这是最后一次尝试，返回空结果
                if (attempt == 3) {
                    return@withContext PaginatedResult(emptyList(), null, false)
                }

            } catch (e: Exception) {
                lastException = e
                android.util.Log.w(
                    "TronHttpClient", "获取转出交易失败 (尝试 $attempt/3): ${e.message}", e
                )

                // 如果不是最后一次尝试，等待1秒后重试
                if (attempt < 3) {
                    delay(1000)
                }
            }
        }

        // 所有重试都失败，返回空结果
        android.util.Log.e(
            "TronHttpClient",
            "获取转出交易失败，已重试3次，最后一次错误: ${lastException?.message}",
            lastException
        )
        PaginatedResult(emptyList(), null, false)
    }

    /**
     * 获取账户的转入交易历史（支持分页）
     * @param address 钱包地址
     * @param limit 每页数量
     * @param nextUrl 可选的下一页URL，如果提供则使用此URL进行分页
     */
    suspend fun getIncomingTransactions(
        address: String, limit: Int = 50, nextUrl: String? = null
    ): PaginatedResult = withContext(Dispatchers.IO) {
        var lastException: Exception? = null

        // 重试最多3次，每次间隔1秒
        for (attempt in 1..3) {
            try {
                val response = if (nextUrl != null) {
                    // 使用下一页URL
                    callApiGetUrl(nextUrl)
                } else {
                    // 首次请求 - 获取所有交易，然后在客户端过滤转入交易
                    val endpoint =
                        "v1/accounts/$address/transactions?limit=${limit * 2}&only_confirmed=true"
                    android.util.Log.d(
                        "TronHttpClient", "获取转入交易 (尝试 $attempt/3): $endpoint"
                    )
                    callApiGet(endpoint)
                }

                if (response != null && response.has("data")) {
                    val allTransactions = response.getAsJsonArray("data")
                    android.util.Log.d(
                        "TronHttpClient", "API返回 ${allTransactions.size()} 条交易记录"
                    )

                    // 过滤出转入交易（toAddress 等于当前地址，且为TRX转账）
                    val incomingTransactions =
                        allTransactions.mapNotNull { it.asJsonObject }.filter { txJson ->
                                try {
                                    val rawData = txJson.getAsJsonObject("raw_data")
                                    val contracts = rawData?.getAsJsonArray("contract")
                                    val contract = contracts?.get(0)?.asJsonObject
                                    val contractType = contract?.get("type")?.asString ?: ""

                                    // 只处理TRX转账交易
                                    if (contractType != "TransferContract") {
                                        return@filter false
                                    }

                                    val parameter = contract?.getAsJsonObject("parameter")
                                    val value = parameter?.getAsJsonObject("value")
                                    val toAddress = value?.get("to_address")?.asString ?: ""

                                    // 判断是否为转入交易
                                    val isIncoming = addressesMatch(toAddress, address)
                                    android.util.Log.d(
                                        "TronHttpClient",
                                        "交易 ${txJson.get("txID")?.asString} - to: $toAddress, address: $address, isIncoming: $isIncoming"
                                    )
                                    isIncoming
                                } catch (e: Exception) {
                                    android.util.Log.w(
                                        "TronHttpClient", "解析交易失败: ${e.message}", e
                                    )
                                    false
                                }
                            }

                    android.util.Log.d(
                        "TronHttpClient", "过滤后剩余 ${incomingTransactions.size} 条转入交易"
                    )

                    // 解析下一页URL
                    val nextUrlResult = response.getAsJsonObject("meta")?.getAsJsonObject("links")
                        ?.get("next")?.asString

                    // 优先使用API返回的hasMore字段，如果没有则检查nextUrl是否存在
                    val apiHasMore = response.getAsJsonObject("meta")?.getAsJsonObject("links")
                        ?.get("hasMore")?.asBoolean

                    val hasMoreData = apiHasMore ?: (nextUrlResult != null)

                    android.util.Log.d(
                        "TronHttpClient",
                        "转入交易分页信息 - API hasMore: $apiHasMore, nextUrl存在: ${nextUrlResult != null}, 最终hasMore: $hasMoreData, filtered: ${incomingTransactions.size}, limit: $limit"
                    )

                    return@withContext PaginatedResult(
                        data = incomingTransactions.take(limit), // 限制返回数量
                        nextUrl = nextUrlResult, hasMore = hasMoreData
                    )
                }

                android.util.Log.w(
                    "TronHttpClient", "API响应为空或不包含data字段 (尝试 $attempt/3)"
                )
                // 如果这是最后一次尝试，返回空结果
                if (attempt == 3) {
                    return@withContext PaginatedResult(emptyList(), null, false)
                }

            } catch (e: Exception) {
                lastException = e
                android.util.Log.w(
                    "TronHttpClient", "获取转入交易失败 (尝试 $attempt/3): ${e.message}", e
                )

                // 如果不是最后一次尝试，等待1秒后重试
                if (attempt < 3) {
                    delay(1000)
                }
            }
        }

        // 所有重试都失败，返回空结果
        android.util.Log.e(
            "TronHttpClient",
            "获取转入交易失败，已重试3次，最后一次错误: ${lastException?.message}",
            lastException
        )
        PaginatedResult(emptyList(), null, false)
    }

    /**
     * 地址匹配辅助方法
     */
    private fun addressesMatch(addr1: String, addr2: String): Boolean {
        if (addr1.isEmpty() || addr2.isEmpty()) return false

        // 直接字符串比较
        if (addr1 == addr2) return true

        // 如果其中一个是 hex 格式（41开头），尝试转换后比较
        if (addr1.startsWith("41") && addr1.length == 42) {
            try {
                val base58Addr1 = org.tron.trident.utils.Base58Check.bytesToBase58(
                    org.tron.trident.utils.Numeric.hexStringToByteArray(addr1)
                )
                if (base58Addr1 == addr2) return true
            } catch (e: Exception) {
                // 转换失败，继续
            }
        }

        if (addr2.startsWith("41") && addr2.length == 42) {
            try {
                val base58Addr2 = org.tron.trident.utils.Base58Check.bytesToBase58(
                    org.tron.trident.utils.Numeric.hexStringToByteArray(addr2)
                )
                if (base58Addr2 == addr1) return true
            } catch (e: Exception) {
                // 转换失败，继续
            }
        }

        return false
    }

    /**
     * 调用 TRON HTTP API (GET请求)
     */
    private fun callApiGet(endpoint: String): JsonObject? {
        val url = if (baseUrl.startsWith("http")) {
            "$baseUrl/$endpoint"
        } else {
            "https://$baseUrl/$endpoint"
        }

        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            // 获取响应
            if (connection.responseCode == 200) {
                connection.inputStream.bufferedReader().use { reader ->
                    val response = reader.readText()
                    gson.fromJson(response, JsonObject::class.java)
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 调用完整URL的GET请求（用于分页）
     */
    private fun callApiGetUrl(fullUrl: String): JsonObject? {
        val connection = URL(fullUrl).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            // 获取响应
            if (connection.responseCode == 200) {
                connection.inputStream.bufferedReader().use { reader ->
                    val response = reader.readText()
                    gson.fromJson(response, JsonObject::class.java)
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 解析USDT余额的辅助方法
     */
    private fun parseUsdtBalance(trc20Element: com.google.gson.JsonElement): Double {
        val usdtContract = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t"

        if (trc20Element.isJsonObject) {
            // 对象格式: {"contract_address": "balance"}
            val trc20Object = trc20Element.asJsonObject
            if (trc20Object.has(usdtContract)) {
                val balanceStr = trc20Object.get(usdtContract).asString
                val balanceLong = balanceStr.toLongOrNull() ?: 0L
                return balanceLong / 1_000_000.0
            }
        } else if (trc20Element.isJsonArray) {
            // 数组格式: 可能是 [{"contract_address": "balance"}, ...] 或 [["contract_address", "balance"], ...]
            val trc20Array = trc20Element.asJsonArray
            for (i in 0 until trc20Array.size()) {
                val tokenEntry = trc20Array.get(i)

                if (tokenEntry.isJsonObject) {
                    // 对象格式: {"contract_address": "balance"}
                    val tokenObj = tokenEntry.asJsonObject
                    val entries = tokenObj.entrySet()
                    for (entry in entries) {
                        if (entry.key == usdtContract) {
                            val balanceStr = entry.value.asString
                            val balanceLong = balanceStr.toLongOrNull() ?: 0L
                            return balanceLong / 1_000_000.0
                        }
                    }
                } else if (tokenEntry.isJsonArray) {
                    // 数组格式: ["contract_address", "balance"]
                    val tokenArray = tokenEntry.asJsonArray
                    if (tokenArray.size() >= 2) {
                        val contractAddress = tokenArray.get(0).asString
                        val balanceStr = tokenArray.get(1).asString

                        if (contractAddress == usdtContract) {
                            val balanceLong = balanceStr.toLongOrNull() ?: 0L
                            return balanceLong / 1_000_000.0
                        }
                    }
                }
            }
        }
        return 0.0
    }

    /**
     * 获取账户的USDT余额（向后兼容）
     */
    suspend fun getUsdtBalance(address: String): Double = withContext(Dispatchers.IO) {
        getAccountBalances(address).usdtBalance
    }

    /**
     * 获取TRX对USD的价格
     */
    suspend fun getTrxPrice(): Double = withContext(Dispatchers.IO) {
        try {
            // 使用CoinGecko API获取TRX价格
            val url =
                URL("https://api.coingecko.com/api/v3/simple/price?ids=tron&vs_currencies=usd")
            val connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = "GET"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            if (connection.responseCode == 200) {
                connection.inputStream.bufferedReader().use { reader ->
                    val response = reader.readText()
                    val jsonObject = gson.fromJson(response, JsonObject::class.java)
                    val tronData = jsonObject.getAsJsonObject("tron")
                    tronData?.get("usd")?.asDouble ?: 0.0
                }
            } else {
                0.0
            }
        } catch (e: Exception) {
            android.util.Log.w("TronHttpClient", "获取TRX价格失败: ${e.message}")
            0.0
        }
    }

    /**
     * 调用 TRON HTTP API (POST请求，发送纯hex字符串)
     */
    private fun callApiWithHex(endpoint: String, hexString: String): JsonObject? {
        val url = if (baseUrl.startsWith("http")) {
            "$baseUrl/$endpoint"
        } else {
            "https://$baseUrl/$endpoint"
        }

        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.doOutput = true

            // 发送请求（纯hex字符串）
            connection.outputStream.use { it.write(hexString.toByteArray(Charsets.UTF_8)) }

            // 获取响应
            if (connection.responseCode == 200) {
                connection.inputStream.bufferedReader().use { reader ->
                    val response = reader.readText()
                    gson.fromJson(response, JsonObject::class.java)
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 调用 TRON HTTP API (POST请求)
     */
    private fun callApi(endpoint: String, requestBody: String): JsonObject? {
        val url = if (baseUrl.startsWith("http")) {
            "$baseUrl/$endpoint"
        } else {
            "https://$baseUrl/$endpoint"
        }

        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.doOutput = true

            // 发送请求
            connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }

            // 获取响应
            if (connection.responseCode == 200) {
                connection.inputStream.bufferedReader().use { reader ->
                    val response = reader.readText()
                    gson.fromJson(response, JsonObject::class.java)
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 调用 TRON HTTP API (POST请求，原始 JSON body)
     * @param url 完整 URL
     * @param requestBody JSON 请求体
     * @return 解析后的 JsonObject，失败返回 null
     */
    private fun callApiRaw(url: String, requestBody: String): JsonObject? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.doOutput = true

            connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }

            if (connection.responseCode == 200) {
                connection.inputStream.bufferedReader().use { reader ->
                    val response = reader.readText()
                    gson.fromJson(response, JsonObject::class.java)
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
