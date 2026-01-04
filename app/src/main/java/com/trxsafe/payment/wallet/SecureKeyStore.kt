package com.trxsafe.payment.wallet

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 钱包类型枚举
 */
enum class WalletType {
    PRIVATE_KEY,    // 私钥钱包
    WATCH_ONLY,     // 观察钱包
    HARDWARE        // 硬件钱包（预留）
}

/**
 * 钱包数据类
 */
data class WalletData(
    val id: String,
    val name: String,
    val address: String,
    val type: WalletType,
    val privateKeyEncrypted: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 安全密钥存储
 * 支持多钱包管理，使用 AES-GCM 加密存储私钥
 */
class SecureKeyStore(private val context: Context) {

    companion object {
        private const val KEYSTORE_ALIAS = "trx_safe_master_key"
        private const val PREFS_NAME = "secure_wallet_prefs"
        private const val KEY_WALLETS_DATA = "wallets_data"
        private const val KEY_CURRENT_WALLET_ID = "current_wallet_id"

        /**
         * AES-GCM 加密参数
         */
        private const val AES_MODE = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
    }
    
    private val masterKeyAlias by lazy {
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
    }

    private val encryptedPrefs by lazy {
        EncryptedSharedPreferences.create(
            PREFS_NAME,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val gson = Gson()

    // 兼容性：检查是否使用旧的单钱包存储格式
    private fun isLegacyFormat(): Boolean {
        return encryptedPrefs.contains("encrypted_private_key") ||
               encryptedPrefs.contains("wallet_address") ||
               encryptedPrefs.contains("is_watch_only")
    }

    // 迁移旧数据到新格式
    private fun migrateLegacyData() {
        if (!isLegacyFormat()) return

        try {
            val address = encryptedPrefs.getString("wallet_address", null)
            val privateKey = encryptedPrefs.getString("encrypted_private_key", null)
            val isWatchOnly = encryptedPrefs.getBoolean("is_watch_only", false)

            if (address != null) {
                val walletId = "legacy_wallet_${System.currentTimeMillis()}"
                val walletName = if (isWatchOnly) "观察钱包" else "默认钱包"
                val walletType = if (isWatchOnly) WalletType.WATCH_ONLY else WalletType.PRIVATE_KEY

                val walletData = WalletData(
                    id = walletId,
                    name = walletName,
                    address = address,
                    type = walletType,
                    privateKeyEncrypted = privateKey
                )

                val wallets = mutableListOf(walletData)
                saveWalletsData(wallets)
                setCurrentWalletId(walletId)

                // 清除旧数据
                encryptedPrefs.edit().apply {
                    remove("encrypted_private_key")
                    remove("wallet_address")
                    remove("is_watch_only")
                    remove("encryption_iv")
                    apply()
                }
            }
        } catch (e: Exception) {
            // 迁移失败，保持旧格式
        }
    }

    private fun getWalletsData(): MutableList<WalletData> {
        migrateLegacyData() // 确保已迁移
        val json = encryptedPrefs.getString(KEY_WALLETS_DATA, null)
        return if (json != null) {
            try {
                val type = object : TypeToken<MutableList<WalletData>>() {}.type
                gson.fromJson(json, type) ?: mutableListOf()
            } catch (e: Exception) {
                mutableListOf()
            }
        } else {
            mutableListOf()
        }
    }

    private fun saveWalletsData(wallets: List<WalletData>) {
        try {
            val json = gson.toJson(wallets)
            encryptedPrefs.edit().putString(KEY_WALLETS_DATA, json).apply()
        } catch (e: Exception) {
            throw SecurityException("保存钱包数据失败：${e.message}", e)
        }
    }

    private fun getCurrentWalletId(): String? {
        migrateLegacyData() // 确保已迁移
        return encryptedPrefs.getString(KEY_CURRENT_WALLET_ID, null)
    }

    private fun setCurrentWalletId(walletId: String?) {
        encryptedPrefs.edit().putString(KEY_CURRENT_WALLET_ID, walletId).apply()
    }

    /**
     * 创建新钱包
     */
    @Throws(SecurityException::class)
    fun createWallet(name: String, privateKeyHex: String, address: String): WalletData {
        val wallets = getWalletsData()
        val walletId = "wallet_${System.currentTimeMillis()}_${wallets.size}"

        val walletData = WalletData(
            id = walletId,
            name = name,
            address = address,
            type = WalletType.PRIVATE_KEY,
            privateKeyEncrypted = privateKeyHex
        )

        wallets.add(walletData)
        saveWalletsData(wallets)

        // 如果这是第一个钱包，设置为当前钱包
        if (getCurrentWalletId() == null) {
            setCurrentWalletId(walletId)
        }

        return walletData
    }

    /**
     * 导入观察钱包
     */
    @Throws(SecurityException::class)
    fun importWatchWallet(name: String, address: String): WalletData {
        val wallets = getWalletsData()
        val walletId = "watch_wallet_${System.currentTimeMillis()}_${wallets.size}"

        val walletData = WalletData(
            id = walletId,
            name = name,
            address = address,
            type = WalletType.WATCH_ONLY
        )

        wallets.add(walletData)
        saveWalletsData(wallets)

        // 如果这是第一个钱包，设置为当前钱包
        if (getCurrentWalletId() == null) {
            setCurrentWalletId(walletId)
        }

        return walletData
    }

    /**
     * 获取所有钱包
     */
    fun getAllWallets(): List<WalletData> {
        return getWalletsData()
    }

    /**
     * 获取当前钱包
     */
    fun getCurrentWallet(): WalletData? {
        val currentId = getCurrentWalletId()
        return getWalletsData().find { it.id == currentId }
    }

    /**
     * 设置当前钱包
     */
    fun setCurrentWallet(walletId: String) {
        val wallets = getWalletsData()
        if (wallets.any { it.id == walletId }) {
            setCurrentWalletId(walletId)
        }
    }

    /**
     * 删除钱包
     */
    @Throws(SecurityException::class)
    fun deleteWallet(walletId: String) {
        val wallets = getWalletsData()
        val updatedWallets = wallets.filter { it.id != walletId }

        if (updatedWallets.size != wallets.size) {
            saveWalletsData(updatedWallets)

            // 如果删除的是当前钱包，选择第一个可用的钱包
            val currentId = getCurrentWalletId()
            if (currentId == walletId) {
                val newCurrent = updatedWallets.firstOrNull()
                setCurrentWalletId(newCurrent?.id)
            }
        }
    }

    /**
     * 更新钱包信息
     */
    @Throws(SecurityException::class)
    fun updateWallet(walletId: String, newName: String? = null, newAddress: String? = null): WalletData {
        val wallets = getWalletsData()
        val walletIndex = wallets.indexOfFirst { it.id == walletId }

        if (walletIndex == -1) {
            throw SecurityException("钱包不存在")
        }

        val existingWallet = wallets[walletIndex]

        // 验证参数
        if (newName != null && newName.trim().isEmpty()) {
            throw SecurityException("钱包名称不能为空")
        }

        if (newAddress != null && existingWallet.type != WalletType.WATCH_ONLY) {
            throw SecurityException("只能修改观察钱包的地址")
        }

        // 创建更新后的钱包数据
        val updatedWallet = existingWallet.copy(
            name = newName ?: existingWallet.name,
            address = newAddress ?: existingWallet.address
        )

        // 更新钱包列表
        wallets[walletIndex] = updatedWallet
        saveWalletsData(wallets)

        return updatedWallet
    }

    /**
     * 根据ID获取钱包
     */
    fun getWalletById(walletId: String): WalletData? {
        return getWalletsData().find { it.id == walletId }
    }

    /**
     * 获取钱包私钥
     */
    fun getWalletPrivateKey(walletId: String): String? {
        val wallet = getWalletsData().find { it.id == walletId }
        return wallet?.privateKeyEncrypted
    }

    // ========== 兼容性方法 ==========

    /**
     * 保存私钥（加密存储）- 兼容旧API
     *
     * @param privateKeyHex 私钥（16 进制字符串）
     * @param address 钱包地址
     * @throws SecurityException 存储失败时抛出
     */
    @Throws(SecurityException::class)
    fun savePrivateKey(privateKeyHex: String, address: String) {
        // 如果还没有钱包数据，使用旧格式
        if (getWalletsData().isEmpty() && !isLegacyFormat()) {
            try {
                // 使用 EncryptedSharedPreferences 自动处理加密
                encryptedPrefs.edit().apply {
                    putString("encrypted_private_key", privateKeyHex)
                    putString("wallet_address", address)
                    putBoolean("is_watch_only", false)
                    apply()
                }
            } catch (e: Exception) {
                throw SecurityException("保存私钥失败：${e.message}", e)
            }
        } else {
            // 使用新格式
            createWallet("默认钱包", privateKeyHex, address)
        }
    }

    /**
     * 保存观察钱包地址（无私钥）- 兼容旧API
     *
     * @param address 钱包地址
     * @throws SecurityException 存储失败时抛出
     */
    @Throws(SecurityException::class)
    fun saveWatchWalletAddress(address: String) {
        // 如果还没有钱包数据，使用旧格式
        if (getWalletsData().isEmpty() && !isLegacyFormat()) {
            try {
                encryptedPrefs.edit().apply {
                    remove("encrypted_private_key") // 确保清除私钥
                    putString("wallet_address", address)
                    putBoolean("is_watch_only", true)
                    apply()
                }
            } catch (e: Exception) {
                throw SecurityException("保存观察钱包失败：${e.message}", e)
            }
        } else {
            // 使用新格式
            importWatchWallet("观察钱包", address)
        }
    }
    
    /**
     * 获取私钥（解密）- 兼容旧API
     *
     * @return 私钥（16 进制字符串），如果不存在或为观察钱包返回 null
     * @throws SecurityException 解密失败时抛出
     */
    @Throws(SecurityException::class)
    fun getPrivateKey(): String? {
        // 如果使用新格式，从当前钱包获取
        if (encryptedPrefs.contains(KEY_WALLETS_DATA)) {
            val currentWallet = getCurrentWallet()
            return currentWallet?.privateKeyEncrypted
        }
        // 兼容旧格式
        return try {
            encryptedPrefs.getString("encrypted_private_key", null)
        } catch (e: Exception) {
            throw SecurityException("获取私钥失败：${e.message}", e)
        }
    }

    /**
     * 获取钱包地址 - 兼容旧API
     *
     * @return 钱包地址，如果不存在返回 null
     */
    fun getWalletAddress(): String? {
        // 如果使用新格式，从当前钱包获取
        if (encryptedPrefs.contains(KEY_WALLETS_DATA)) {
            return getCurrentWallet()?.address
        }
        // 兼容旧格式
        return encryptedPrefs.getString("wallet_address", null)
    }

    /**
     * 检查是否为观察钱包 - 兼容旧API
     *
     * @return true 表示是观察钱包
     */
    fun isWatchOnly(): Boolean {
        // 如果使用新格式，从当前钱包获取
        if (encryptedPrefs.contains(KEY_WALLETS_DATA)) {
            return getCurrentWallet()?.type == WalletType.WATCH_ONLY
        }
        // 兼容旧格式
        return encryptedPrefs.getBoolean("is_watch_only", false)
    }

    /**
     * 检查是否存在钱包（无论是热钱包还是观察钱包）
     *
     * @return true 表示已存在钱包
     */
    fun hasWallet(): Boolean {
        // 检查新格式
        if (encryptedPrefs.contains(KEY_WALLETS_DATA)) {
            return getWalletsData().isNotEmpty()
        }
        // 检查旧格式
        return encryptedPrefs.contains("wallet_address")
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
     * @param privateKeyHex 私钥（16 进制字符串，可选 0x 前缀）
     * @return true 表示格式正确
     */
    fun isValidPrivateKey(privateKeyHex: String): Boolean {
        val cleanKey = privateKeyHex.removePrefix("0x")
        return cleanKey.matches(Regex("^[0-9a-fA-F]{64}$"))
    }
}
