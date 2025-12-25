package com.trxsafe.payment.broadcast

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 交易状态
 */
enum class TransactionStatus {
    /**
     * 成功
     */
    SUCCESS,
    
    /**
     * 失败
     */
    FAILURE,
    
    /**
     * 待确认
     */
    PENDING
}

/**
 * 交易记录
 */
data class TransactionRecord(
    /**
     * 交易 ID
     */
    val txid: String,
    
    /**
     * 发送地址
     */
    val fromAddress: String = "",
    
    /**
     * 接收地址
     */
    val toAddress: String,
    
    /**
     * 转账金额（sun）
     */
    val amountSun: Long,
    
    /**
     * 消耗费用（sun）
     */
    val feeSun: Long = 0,
    
    /**
     * 带宽消耗
     */
    val netUsage: Long = 0,
    
    /**
     * 能量消耗
     */
    val energyUsage: Long = 0,
    
    /**
     * 区块高度
     */
    val blockHeight: Long = 0,

    /**
     * 时间戳（毫秒）
     */
    val timestamp: Long,
    
    /**
     * 交易状态
     */
    val status: TransactionStatus,
    
    /**
     * 错误信息
     */
    val errorMessage: String? = null,

    /**
     * 备注
     */
    val memo: String = ""
)

/**
 * 交易记录器
 * 用于本地存储交易历史
 */
class TransactionRecorder(context: Context) {
    
    companion object {
        private const val PREFS_NAME = "transaction_records"
        private const val KEY_RECORDS = "records"
        private const val MAX_RECORDS = 100 // 最多保留 100 条记录
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )
    
    private val gson = Gson()
    
    /**
     * 保存交易记录
     * 
     * @param record 交易记录
     */
    fun saveRecord(record: TransactionRecord) {
        val records = getAllRecords().toMutableList()
        
        // 添加新记录到列表开头
        records.add(0, record)
        
        // 限制记录数量
        if (records.size > MAX_RECORDS) {
            records.subList(MAX_RECORDS, records.size).clear()
        }
        
        // 保存到 SharedPreferences
        val json = gson.toJson(records)
        prefs.edit().putString(KEY_RECORDS, json).apply()
    }

    /**
     * 更新交易记录
     * 
     * @param txid 交易 ID
     * @param updater 更新逻辑
     */
    fun updateRecord(txid: String, updater: (TransactionRecord) -> TransactionRecord) {
        val records = getAllRecords().toMutableList()
        val index = records.indexOfFirst { it.txid == txid }
        if (index != -1) {
            records[index] = updater(records[index])
            val json = gson.toJson(records)
            prefs.edit().putString(KEY_RECORDS, json).apply()
        }
    }
    
    /**
     * 获取所有交易记录
     * 
     * @return 交易记录列表（按时间倒序）
     */
    fun getAllRecords(): List<TransactionRecord> {
        val json = prefs.getString(KEY_RECORDS, null) ?: return emptyList()
        
        return try {
            val type = object : TypeToken<List<TransactionRecord>>() {}.type
            gson.fromJson<List<TransactionRecord>>(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * 根据 TXID 获取记录
     * 
     * @param txid 交易 ID
     * @return 交易记录，如果不存在返回 null
     */
    fun getRecordByTxId(txid: String): TransactionRecord? {
        return getAllRecords().find { it.txid == txid }
    }
    
    /**
     * 清除所有记录
     */
    fun clearAllRecords() {
        prefs.edit().clear().apply()
    }
    
    /**
     * 获取成功的交易数量
     */
    fun getSuccessCount(): Int {
        return getAllRecords().count { it.status == TransactionStatus.SUCCESS }
    }
    
    /**
     * 获取今日交易记录
     */
    fun getTodayRecords(): List<TransactionRecord> {
        val todayStart = getTodayStartTimestamp()
        return getAllRecords().filter { it.timestamp >= todayStart }
    }
    
    /**
     * 获取今日开始时间戳
     */
    private fun getTodayStartTimestamp(): Long {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
}
