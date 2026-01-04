package com.trxsafe.payment.validation

import org.tron.trident.utils.Base58Check

object AddressValidator {

    private val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    fun isValidTronAddress(address: String): Boolean {
        return try {
            if (!address.startsWith("T") || address.length != 34) {
                return false
            }
            Base58Check.base58ToBytes(address)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun validateAndNormalize(address: String): Result<String> {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("地址不能为空"))
        }
        if (!isValidTronAddress(trimmed)) {
            return Result.failure(IllegalArgumentException("地址格式无效"))
        }
        return Result.success(trimmed)
    }

    fun isWatchOnlyAddress(address: String): Boolean {
        return isValidTronAddress(address)
    }

    fun normalizeAddress(address: String): String {
        return address.trim()
    }
}
