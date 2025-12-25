package com.trxsafe.payment.wallet

import org.tron.trident.proto.Chain

/**
 * 硬件钱包提供商接口
 */
interface HardwareWalletProvider {
    /**
     * 提供商名称（例如 "Ledger", "Trezor"）
     */
    val providerName: String
    
    /**
     * 获取支持的连接类型
     */
    val connectionType: ConnectionType
    
    /**
     * 连接设备
     */
    suspend fun connect(): Result<Unit>
    
    /**
     * 获取设备上的地址
     */
    suspend fun getAddress(path: String = "m/44'/195'/0'/0/0"): Result<String>
    
    /**
     * 对交易进行签名
     */
    suspend fun signTransaction(transaction: Chain.Transaction, path: String = "m/44'/195'/0'/0/0"): Result<Chain.Transaction>
    
    enum class ConnectionType {
        USB,
        BLUETOOTH,
        QR_CODE
    }
}
