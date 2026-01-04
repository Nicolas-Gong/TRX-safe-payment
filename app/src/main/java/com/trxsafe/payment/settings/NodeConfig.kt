package com.trxsafe.payment.settings

/**
 * 节点配置
 * 包含多个官方和社区维护的TRON节点以提高连接稳定性
 */
data class NodeConfig(
    val name: String,
    val httpUrl: String,
    val isDefault: Boolean = false,
    val isCustom: Boolean = false
) {
    // 为了兼容性保留grpcUrl属性，但返回httpUrl
    val grpcUrl: String
        get() = httpUrl
    companion object {
        // 主网节点 - 使用可靠的TRON HTTP API节点
        val MAINNET_TRONGRID = NodeConfig("主网 (TronGrid)", "https://api.trongrid.io", isDefault = true)
        val MAINNET_NILE = NodeConfig("主网 (Nile)", "https://nile.trongrid.io")

        // 测试网节点
        val NILE_TESTNET = NodeConfig("Nile 测试网", "https://nile.trongrid.io")
        val SHASTA_TESTNET = NodeConfig("Shasta 测试网", "https://shasta.trongrid.io")

        // 获取所有可用节点 - 按优先级排序
        fun getAllDefaults() = listOf(
            MAINNET_TRONGRID,
            MAINNET_NILE,
            NILE_TESTNET,
            SHASTA_TESTNET
        )

        // 获取主网节点列表 - 核心节点
        fun getMainnetNodes() = listOf(
            MAINNET_TRONGRID,
            MAINNET_NILE
        )

        // 获取自动切换节点列表 - 按优先级排序
        fun getFailoverNodes() = listOf(
            MAINNET_TRONGRID,   // TronGrid (推荐)
            MAINNET_NILE        // Nile (备用)
        )

        // 默认主网节点
        val MAINNET = MAINNET_TRONGRID
    }
}
