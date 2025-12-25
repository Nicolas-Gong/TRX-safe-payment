package com.trxsafe.payment.qrcode

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import android.util.Base64

/**
 * 未签名交易 QR 数据
 * 主 App 生成，冷钱包扫描
 */
data class UnsignedTransactionQR(
    /**
     * 协议版本
     */
    @SerializedName("v")
    val version: String = "1.0",
    
    /**
     * 交易类型（固定为 "transfer"）
     */
    @SerializedName("type")
    val type: String = "transfer",
    
    /**
     * 发送方地址
     */
    @SerializedName("from")
    val fromAddress: String,
    
    /**
     * 接收方地址
     */
    @SerializedName("to")
    val toAddress: String,
    
    /**
     * 转账金额（sun）
     */
    @SerializedName("amount")
    val amountSun: Long,
    
    /**
     * 引用区块哈希（Base64 编码）
     */
    @SerializedName("refBlock")
    val refBlockHash: String,
    
    /**
     * 引用区块高度
     */
    @SerializedName("refBlockHeight")
    val refBlockHeight: Long,
    
    /**
     * 过期时间（毫秒时间戳）
     */
    @SerializedName("expiration")
    val expiration: Long,
    
    /**
     * 时间戳（毫秒）
     */
    @SerializedName("timestamp")
    val timestamp: Long,
    
    /**
     * 原始交易数据（Base64 编码）
     * 用于签名
     */
    @SerializedName("rawData")
    val rawDataBase64: String
)

/**
 * 已签名交易 QR 数据
 * 冷钱包生成，主 App 扫描
 */
data class SignedTransactionQR(
    /**
     * 协议版本
     */
    @SerializedName("v")
    val version: String = "1.0",
    
    /**
     * 交易类型
     */
    @SerializedName("type")
    val type: String = "transfer",
    
    /**
     * 接收方地址（用于校验）
     */
    @SerializedName("to")
    val toAddress: String,
    
    /**
     * 转账金额（sun，用于校验）
     */
    @SerializedName("amount")
    val amountSun: Long,
    
    /**
     * 签名数据（Base64 编码）
     */
    @SerializedName("signature")
    val signatureBase64: String,
    
    /**
     * 完整的签名后交易（Base64 编码）
     */
    @SerializedName("signedTx")
    val signedTransactionBase64: String
)

/**
 * QR Code 编解码器
 */
object QRCodec {
    
    private val gson = Gson()
    
    /**
     * 编码未签名交易为 QR 字符串
     * 
     * @param data 未签名交易数据
     * @return QR 字符串
     */
    fun encodeUnsignedTransaction(data: UnsignedTransactionQR): String {
        return gson.toJson(data)
    }
    
    /**
     * 解码未签名交易 QR 字符串
     * 
     * @param qrString QR 字符串
     * @return 未签名交易数据
     * @throws IllegalArgumentException 解码失败时抛出
     */
    @Throws(IllegalArgumentException::class)
    fun decodeUnsignedTransaction(qrString: String): UnsignedTransactionQR {
        return try {
            gson.fromJson(qrString, UnsignedTransactionQR::class.java)
        } catch (e: Exception) {
            throw IllegalArgumentException("解码失败：${e.message}", e)
        }
    }
    
    /**
     * 编码已签名交易为 QR 字符串
     * 
     * @param data 已签名交易数据
     * @return QR 字符串
     */
    fun encodeSignedTransaction(data: SignedTransactionQR): String {
        return gson.toJson(data)
    }
    
    /**
     * 解码已签名交易 QR 字符串
     * 
     * @param qrString QR 字符串
     * @return 已签名交易数据
     * @throws IllegalArgumentException 解码失败时抛出
     */
    @Throws(IllegalArgumentException::class)
    fun decodeSignedTransaction(qrString: String): SignedTransactionQR {
        return try {
            gson.fromJson(qrString, SignedTransactionQR::class.java)
        } catch (e: Exception) {
            throw IllegalArgumentException("解码失败：${e.message}", e)
        }
    }
    
    /**
     * 验证已签名交易数据
     * 
     * @param originalUnsigned 原始未签名交易
     * @param signed 已签名交易
     * @return true 表示验证通过
     */
    fun validateSignedTransaction(
        originalUnsigned: UnsignedTransactionQR,
        signed: SignedTransactionQR
    ): Boolean {
        // 验证接收地址
        if (originalUnsigned.toAddress != signed.toAddress) {
            return false
        }
        
        // 验证金额
        if (originalUnsigned.amountSun != signed.amountSun) {
            return false
        }
        
        // 验证类型
        if (signed.type != "transfer") {
            return false
        }
        
        return true
    }
    
    /**
     * ByteArray 转 Base64 字符串
     */
    fun bytesToBase64(bytes: ByteArray): String {
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
    
    /**
     * Base64 字符串转 ByteArray
     */
    fun base64ToBytes(base64: String): ByteArray {
        return Base64.decode(base64, Base64.NO_WRAP)
    }
}
