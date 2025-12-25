package com.trxsafe.payment.settings

/**
 * 节点配置
 */
data class NodeConfig(
    val name: String,
    val grpcUrl: String,
    val isDefault: Boolean = false,
    val isCustom: Boolean = false
) {
    companion object {
        val MAINNET = NodeConfig("主网 (TronGrid)", "grpc.trongrid.io:50051", isDefault = true)
        val NILE = NodeConfig("Nile 测试网", "47.252.19.181:50051")
        val SHASTA = NodeConfig("Shasta 测试网", "grpc.shasta.trongrid.io:50051")
        
        fun getAllDefaults() = listOf(MAINNET, NILE, SHASTA)
    }
}
