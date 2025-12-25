package com.trxsafe.payment.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings 数据存储
 * 使用 SharedPreferences 存储配置
 */
class SettingsRepository(
    private val context: Context? = null
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
    }
    
    private val prefs: SharedPreferences? by lazy {
        context?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * 加载配置
     */
    suspend fun loadConfig(): SettingsConfig = withContext(Dispatchers.IO) {
        if (prefs == null) {
            return@withContext SettingsConfig()
        }
        
        SettingsConfig(
            sellerAddress = prefs!!.getString(KEY_SELLER_ADDRESS, "") ?: "",
            pricePerUnitSun = prefs!!.getLong(KEY_PRICE_PER_UNIT_SUN, 0L),
            multiplier = prefs!!.getInt(KEY_MULTIPLIER, 1),
            isPriceLocked = prefs!!.getBoolean(KEY_IS_PRICE_LOCKED, false),
            isFirstTimeSetAddress = prefs!!.getBoolean(KEY_IS_FIRST_TIME_SET_ADDRESS, true),
            isBiometricEnabled = prefs!!.getBoolean(KEY_IS_BIOMETRIC_ENABLED, false),
            nodeUrl = prefs!!.getString(KEY_NODE_URL, NodeConfig.MAINNET.grpcUrl) ?: NodeConfig.MAINNET.grpcUrl
        )
    }
    
    /**
     * 保存配置
     */
    suspend fun saveConfig(config: SettingsConfig) = withContext(Dispatchers.IO) {
        if (prefs == null) {
            throw IllegalStateException("Context 未初始化")
        }
        
        prefs!!.edit().apply {
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
        prefs?.edit()?.clear()?.apply()
    }
}
