package com.trxsafe.payment.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings 数据存储
 * 使用 SharedPreferences 存储配置
 */
class SettingsRepository private constructor(
    private val context: Context
) {
    companion object {
        private const val PREF_NAME = "settings_config"
        private const val KEY_SELLER_ADDRESS = "seller_address"
        private const val KEY_PRICE_PER_UNIT_SUN = "price_per_unit_sun"
        private const val KEY_MULTIPLIER = "multiplier"
        private const val KEY_IS_PRICE_LOCKED = "is_price_locked"
        private const val KEY_IS_FIRST_TIME_SET_ADDRESS = "is_first_time_set_address"
        private const val KEY_IS_BIOMETRIC_ENABLED = "is_biometric_enabled"
        private const val KEY_NODE_URL = "node_url"

        @Volatile
        private var INSTANCE: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return INSTANCE ?: synchronized(this) {
                val instance = SettingsRepository(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
    
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * 加载配置
     */
    suspend fun loadConfig(): SettingsConfig = withContext(Dispatchers.IO) {
        val storedNodeUrl = prefs.getString(KEY_NODE_URL, null)
        val nodeUrl = migrateNodeUrl(storedNodeUrl)

        SettingsConfig(
            sellerAddress = prefs.getString(KEY_SELLER_ADDRESS, "") ?: "",
            pricePerUnitSun = prefs.getLong(KEY_PRICE_PER_UNIT_SUN, 0L),
            multiplier = prefs.getInt(KEY_MULTIPLIER, 1),
            isPriceLocked = prefs.getBoolean(KEY_IS_PRICE_LOCKED, false),
            isFirstTimeSetAddress = prefs.getBoolean(KEY_IS_FIRST_TIME_SET_ADDRESS, true),
            isBiometricEnabled = prefs.getBoolean(KEY_IS_BIOMETRIC_ENABLED, false),
            nodeUrl = nodeUrl
        )
    }

    /**
     * 同步加载配置（非协程环境使用）
     */
    fun getConfigSync(): SettingsConfig {
        val storedNodeUrl = prefs.getString(KEY_NODE_URL, null)
        val nodeUrl = migrateNodeUrl(storedNodeUrl)

        return SettingsConfig(
            sellerAddress = prefs.getString(KEY_SELLER_ADDRESS, "") ?: "",
            pricePerUnitSun = prefs.getLong(KEY_PRICE_PER_UNIT_SUN, 0L),
            multiplier = prefs.getInt(KEY_MULTIPLIER, 1),
            isPriceLocked = prefs.getBoolean(KEY_IS_PRICE_LOCKED, false),
            isFirstTimeSetAddress = prefs.getBoolean(KEY_IS_FIRST_TIME_SET_ADDRESS, true),
            isBiometricEnabled = prefs.getBoolean(KEY_IS_BIOMETRIC_ENABLED, false),
            nodeUrl = nodeUrl
        )
    }
    
    /**
     * 迁移旧的gRPC节点URL到HTTP格式
     */
    private fun migrateNodeUrl(storedUrl: String?): String {
        return when {
            storedUrl == null -> NodeConfig.MAINNET.httpUrl
            storedUrl.contains("grpc.trongrid.io") -> "https://api.trongrid.io"
            storedUrl.contains("grpc.eu.trongrid.io") -> "https://api.trongrid.io" // 回退到主节点
            storedUrl.contains("grpc.asia.trongrid.io") -> "https://api.trongrid.io" // 回退到主节点
            storedUrl.contains("grpc.shasta.trongrid.io") -> "https://shasta.trongrid.io"
            storedUrl.contains(":50051") -> storedUrl.replace(":50051", "").replace("grpc.", "api.") // 转换gRPC到HTTP
            !storedUrl.startsWith("http") -> "https://$storedUrl" // 确保HTTPS
            else -> storedUrl
        }
    }
    
    /**
     * 保存配置
     */
    suspend fun saveConfig(config: SettingsConfig) = withContext(Dispatchers.IO) {
        prefs.edit().apply {
            putString(KEY_SELLER_ADDRESS, config.sellerAddress)
            putLong(KEY_PRICE_PER_UNIT_SUN, config.pricePerUnitSun)
            putInt(KEY_MULTIPLIER, config.multiplier)
            putBoolean(KEY_IS_PRICE_LOCKED, config.isPriceLocked)
            putBoolean(KEY_IS_FIRST_TIME_SET_ADDRESS, config.isFirstTimeSetAddress)
            putBoolean(KEY_IS_BIOMETRIC_ENABLED, config.isBiometricEnabled)
            putString(KEY_NODE_URL, config.nodeUrl)
        }.apply()
    }
    
    /**
     * 清空配置
     */
    suspend fun clearConfig() = withContext(Dispatchers.IO) {
        prefs.edit().clear().apply()
    }
}
