package com.trxsafe.payment.wallet

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 安全密钥存储
 * 使用 AES-GCM 加密存储私钥
 */
class SecureKeyStore(private val context: Context) {
    
    companion object {
        private const val KEYSTORE_ALIAS = "trx_safe_master_key"
        private const val PREFS_NAME = "secure_wallet_prefs"
        private const val KEY_ENCRYPTED_PRIVATE_KEY = "encrypted_private_key"
        private const val KEY_IV = "encryption_iv"
        private const val KEY_WALLET_ADDRESS = "wallet_address"
        private const val KEY_IS_WATCH_ONLY = "is_watch_only"
        
        /**
         * AES-GCM 加密参数
         */
        private const val AES_MODE = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
    }
    
    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }
    
    private val encryptedPrefs by lazy {
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    
    /**
     * 保存私钥（加密存储）
     * 
     * @param privateKeyHex 私钥（16 进制字符串）
     * @param address 钱包地址
     * @throws SecurityException 存储失败时抛出
     */
    @Throws(SecurityException::class)
    fun savePrivateKey(privateKeyHex: String, address: String) {
        try {
            // 使用 EncryptedSharedPreferences 自动处理加密
            encryptedPrefs.edit().apply {
                putString(KEY_ENCRYPTED_PRIVATE_KEY, privateKeyHex)
                putString(KEY_WALLET_ADDRESS, address)
                putBoolean(KEY_IS_WATCH_ONLY, false)
                apply()
            }
        } catch (e: Exception) {
            throw SecurityException("保存私钥失败：${e.message}", e)
        }
    }

    /**
     * 保存观察钱包地址（无私钥）
     *
     * @param address 钱包地址
     * @throws SecurityException 存储失败时抛出
     */
    @Throws(SecurityException::class)
    fun saveWatchWalletAddress(address: String) {
        try {
            encryptedPrefs.edit().apply {
                remove(KEY_ENCRYPTED_PRIVATE_KEY) // 确保清除私钥
                putString(KEY_WALLET_ADDRESS, address)
                putBoolean(KEY_IS_WATCH_ONLY, true)
                apply()
            }
        } catch (e: Exception) {
            throw SecurityException("保存观察钱包失败：${e.message}", e)
        }
    }
    
    /**
     * 获取私钥（解密）
     * 
     * @return 私钥（16 进制字符串），如果不存在或为观察钱包返回 null
     * @throws SecurityException 解密失败时抛出
     */
    @Throws(SecurityException::class)
    fun getPrivateKey(): String? {
        return try {
            encryptedPrefs.getString(KEY_ENCRYPTED_PRIVATE_KEY, null)
        } catch (e: Exception) {
            throw SecurityException("获取私钥失败：${e.message}", e)
        }
    }
    
    /**
     * 获取钱包地址
     * 
     * @return 钱包地址，如果不存在返回 null
     */
    fun getWalletAddress(): String? {
        return encryptedPrefs.getString(KEY_WALLET_ADDRESS, null)
    }
    
    /**
     * 检查是否为观察钱包
     * 
     * @return true 表示是观察钱包
     */
    fun isWatchOnly(): Boolean {
        return encryptedPrefs.getBoolean(KEY_IS_WATCH_ONLY, false)
    }
    
    /**
     * 检查是否存在钱包（无论是热钱包还是观察钱包）
     * 
     * @return true 表示已存在钱包
     */
    fun hasWallet(): Boolean {
        // 只要有地址就算有钱包
        return encryptedPrefs.contains(KEY_WALLET_ADDRESS)
    }
    
    /**
     * 清除钱包数据
     * 
     * @throws SecurityException 清除失败时抛出
     */
    @Throws(SecurityException::class)
    fun clearWallet() {
        try {
            encryptedPrefs.edit().clear().apply()
        } catch (e: Exception) {
            throw SecurityException("清除钱包失败：${e.message}", e)
        }
    }
    
    /**
     * 验证私钥格式
     * 
     * @param privateKeyHex 私钥（16 进制字符串）
     * @return true 表示格式正确
     */
    fun isValidPrivateKey(privateKeyHex: String): Boolean {
        // TRON 私钥是 64 位 16 进制字符串
        return privateKeyHex.matches(Regex("^[0-9a-fA-F]{64}$"))
    }
}
