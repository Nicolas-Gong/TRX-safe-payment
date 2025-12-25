package com.trxsafe.payment.qrcode

/**
 * 二维码分片助手
 * 用于处理大数据量交易的分片编码和解码
 */
object QRSplitter {
    
    private const val PREFIX = "trxsafe:v1"
    private const val MAX_CHUNK_SIZE = 400 // 每个二维码携带的数据量上限
    
    /**
     * 将数据分片
     */
    fun split(data: String): List<String> {
        if (data.length <= MAX_CHUNK_SIZE) {
            return listOf(data) // 如果数据量小，不分片（保持原始格式以兼容旧版）
        }
        
        val chunks = data.chunked(MAX_CHUNK_SIZE)
        val total = chunks.size
        
        return chunks.mapIndexed { index, payload ->
            "$PREFIX:$index:$total:$payload"
        }
    }
    
    /**
     * 判断是否是分片数据
     */
    fun isMultipart(qrString: String): Boolean {
        return qrString.startsWith(PREFIX)
    }
    
    /**
     * 解析分片元数据
     * @return Triple(index, total, payload)
     */
    fun parsePart(qrString: String): Triple<Int, Int, String>? {
        if (!isMultipart(qrString)) return null
        
        val parts = qrString.split(":", limit = 5)
        if (parts.size < 5) return null
        
        return try {
            val index = parts[2].toInt()
            val total = parts[3].toInt()
            val payload = parts[4]
            Triple(index, total, payload)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 分片管理器，用于在多轮扫码中收集数据
     */
    class collector(val total: Int) {
        private val parts = mutableMapOf<Int, String>()
        
        fun addPart(index: Int, payload: String): Boolean {
            parts[index] = payload
            return parts.size == total
        }
        
        fun getCompletedData(): String? {
            if (parts.size != total) return null
            
            return (0 until total).joinToString("") { parts[it] ?: "" }
        }
        
        fun getProgress(): Int = (parts.size * 100) / total
        
        fun getMissingIndices(): List<Int> {
            return (0 until total).filter { !parts.containsKey(it) }
        }
    }
}
