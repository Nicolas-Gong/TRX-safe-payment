package com.trxsafe.payment.security

/**
 * 交易类型枚举
 * 安全约束：仅允许 TRX 普通转账
 */
enum class TransactionType {
    /**
     * TRX 普通转账 - 唯一允许的交易类型
     */
    TRANSFER_CONTRACT,
    
    /**
     * 禁止的交易类型（用于明确拒绝）
     */
    TRIGGER_SMART_CONTRACT,  // 禁止：智能合约调用
    TRC20_TRANSFER,          // 禁止：TRC20 代币转账
    APPROVE,                 // 禁止：授权操作
    TRANSFER_FROM,           // 禁止：代理转账
    CREATE_CONTRACT,         // 禁止：创建合约
    FREEZE_BALANCE,          // 禁止：冻结余额
    UNFREEZE_BALANCE,        // 禁止：解冻余额
    UNKNOWN                  // 禁止：未知类型
}

/**
 * 安全策略配置
 * 硬性约束：不可修改的安全规则
 */
object SecurityPolicy {
    
    /**
     * 允许的交易类型列表
     * 硬性约束：仅允许 TRANSFER_CONTRACT（TRX 普通转账）
     */
    val ALLOWED_TRANSACTION_TYPES = setOf(
        TransactionType.TRANSFER_CONTRACT
    )
    
    /**
     * 明确禁止的交易类型列表
     * 硬性约束：任何智能合约相关操作都被禁止
     */
    val FORBIDDEN_TRANSACTION_TYPES = setOf(
        TransactionType.TRIGGER_SMART_CONTRACT,
        TransactionType.TRC20_TRANSFER,
        TransactionType.APPROVE,
        TransactionType.TRANSFER_FROM,
        TransactionType.CREATE_CONTRACT,
        TransactionType.FREEZE_BALANCE,
        TransactionType.UNFREEZE_BALANCE,
        TransactionType.UNKNOWN
    )
    
    /**
     * TRX 最小转账金额（单位：sun）
     * 1 TRX = 1,000,000 sun
     */
    const val MIN_TRANSFER_AMOUNT_SUN: Long = 1L
    
    /**
     * TRX 最大转账金额（单位：sun）
     * 默认：1,000,000 TRX = 1,000,000,000,000 sun
     */
    const val MAX_TRANSFER_AMOUNT_SUN: Long = 1_000_000_000_000L
    
    /**
     * 是否允许 WalletConnect
     * 硬性约束：禁止 WalletConnect
     */
    const val ALLOW_WALLET_CONNECT = false
    
    /**
     * 是否允许 DApp 浏览器
     * 硬性约束：禁止 DApp 浏览器
     */
    const val ALLOW_DAPP_BROWSER = false
    
    /**
     * 是否允许智能合约交互
     * 硬性约束：禁止任何智能合约交互
     */
    const val ALLOW_SMART_CONTRACT = false
    
    /**
     * 金额类型检查：必须使用 long 类型
     * 硬性约束：禁止使用 float 或 double 处理金额
     */
    const val REQUIRE_LONG_AMOUNT = true
    
    /**
     * 异常处理策略
     * 硬性约束：任意异常必须直接拒绝签名
     */
    const val REJECT_ON_ANY_EXCEPTION = true
}
