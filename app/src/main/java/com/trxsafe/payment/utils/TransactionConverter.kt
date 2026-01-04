package com.trxsafe.payment.utils

import com.google.gson.JsonObject
import com.trxsafe.payment.broadcast.TransactionRecord
import com.trxsafe.payment.broadcast.TransactionStatus
import org.tron.trident.utils.Base58Check
import org.tron.trident.utils.Numeric

object TransactionConverter {

    fun convertHexToBase58(hexAddress: String): String {
        return try {
            if (hexAddress.startsWith("41") && hexAddress.length == 42) {
                val base58 = Base58Check.bytesToBase58(Numeric.hexStringToByteArray(hexAddress))
                if (base58.isNotEmpty()) base58 else hexAddress
            } else {
                hexAddress
            }
        } catch (e: Exception) {
            hexAddress
        }
    }

    fun convertBlockchainTransactionToRecord(
        txJson: JsonObject,
        myAddress: String,
        isIncomingOverride: Boolean? = null
    ): TransactionRecord {
        return when {
            txJson.has("txID") && txJson.has("raw_data") -> {
                convertTronGridTransactionToRecord(txJson, myAddress, isIncomingOverride)
            }
            txJson.has("txID") && txJson.has("rawData") -> {
                convertRpcTransactionToRecord(txJson, myAddress, isIncomingOverride)
            }
            else -> {
                convertGenericTransactionToRecord(txJson, myAddress, isIncomingOverride)
            }
        }
    }

    private fun convertTronGridTransactionToRecord(
        txJson: JsonObject,
        myAddress: String,
        isIncomingOverride: Boolean?
    ): TransactionRecord {
        val txid = txJson.get("txID")?.asString ?: ""
        val timestamp = txJson.get("block_timestamp")?.asLong ?: System.currentTimeMillis()
        val blockHeight = txJson.get("blockNumber")?.asLong ?: 0L

        val rawData = txJson.getAsJsonObject("raw_data")
        val contracts = rawData?.getAsJsonArray("contract")
        val contract = contracts?.get(0)?.asJsonObject
        val parameter = contract?.getAsJsonObject("parameter")
        val value = parameter?.getAsJsonObject("value")

        var amount = 0L
        var fromAddr = ""
        var toAddr = ""

        if (value != null) {
            amount = value.get("amount")?.asLong ?: 0L
            val originalFromAddr = value.get("owner_address")?.asString ?: ""
            val originalToAddr = value.get("to_address")?.asString ?: ""

            fromAddr = convertHexToBase58(originalFromAddr)
            toAddr = convertHexToBase58(originalToAddr)
        }

        val ret = txJson.getAsJsonArray("ret")?.get(0)?.asJsonObject
        val status = if (ret?.get("contractRet")?.asString == "SUCCESS") {
            TransactionStatus.SUCCESS
        } else {
            TransactionStatus.FAILURE
        }

        val totalFee = try {
            val retFee = ret?.get("fee")?.asNumber?.toLong() ?: 0L
            if (retFee > 0) retFee else {
                txJson.get("fee")?.asNumber?.toLong() ?: 0L
            }
        } catch (e: Exception) {
            txJson.get("fee")?.asNumber?.toLong() ?: 0L
        }

        val netUsage = txJson.get("net_usage")?.asNumber?.toInt() ?: 0
        val energyUsage = txJson.get("energy_usage")?.asNumber?.toInt() ?: 0
        val netFee = txJson.get("net_fee")?.asNumber?.toLong() ?: 0L
        val energyFee = txJson.get("energy_fee")?.asNumber?.toLong() ?: 0L
        val feeSun = if (totalFee > 0) totalFee else (netFee + energyFee)

        return TransactionRecord(
            txid = txid,
            fromAddress = fromAddr,
            toAddress = toAddr,
            amountSun = amount,
            feeSun = feeSun,
            netUsage = netUsage.toLong(),
            energyUsage = energyUsage.toLong(),
            blockHeight = blockHeight,
            timestamp = timestamp,
            status = status,
            memo = ""
        )
    }

    private fun convertRpcTransactionToRecord(
        txJson: JsonObject,
        myAddress: String,
        isIncomingOverride: Boolean?
    ): TransactionRecord {
        val rawData = txJson.getAsJsonObject("raw_data")
        val contract = rawData?.getAsJsonArray("contract")?.get(0)?.asJsonObject
        val parameter = contract?.getAsJsonObject("parameter")
        val value = parameter?.getAsJsonObject("value")

        val amount = value?.get("amount")?.asLong ?: 0L
        val ownerAddress = value?.get("owner_address")?.asString ?: ""
        val toAddress = value?.get("to_address")?.asString ?: ""

        val fromAddr = convertHexToBase58(ownerAddress)
        val toAddr = convertHexToBase58(toAddress)

        val timestamp = rawData?.get("timestamp")?.asLong ?: System.currentTimeMillis()
        val txid = txJson.get("txID")?.asString ?: ""

        return TransactionRecord(
            txid = txid,
            fromAddress = fromAddr,
            toAddress = toAddr,
            amountSun = amount,
            timestamp = timestamp,
            status = TransactionStatus.SUCCESS,
            feeSun = 0L,
            blockHeight = 0L,
            netUsage = 0,
            energyUsage = 0,
            memo = ""
        )
    }

    private fun convertGenericTransactionToRecord(
        txJson: JsonObject,
        myAddress: String,
        isIncomingOverride: Boolean?
    ): TransactionRecord {
        val txid = txJson.get("txID")?.asString ?: txJson.get("hash")?.asString ?: ""
        val timestamp = txJson.get("timestamp")?.asLong
            ?: txJson.get("block_timestamp")?.asLong
            ?: System.currentTimeMillis()

        var fromAddr = txJson.get("ownerAddress")?.asString ?: txJson.get("from")?.asString ?: ""
        var toAddr = txJson.get("toAddress")?.asString ?: txJson.get("to")?.asString ?: ""
        val amount = txJson.get("amount")?.asLong ?: 0L

        fromAddr = convertHexToBase58(fromAddr)
        toAddr = convertHexToBase58(toAddr)

        return TransactionRecord(
            txid = txid,
            fromAddress = fromAddr,
            toAddress = toAddr,
            amountSun = amount,
            timestamp = timestamp,
            status = TransactionStatus.SUCCESS,
            feeSun = 0L,
            blockHeight = 0L,
            netUsage = 0,
            energyUsage = 0,
            memo = ""
        )
    }
}
