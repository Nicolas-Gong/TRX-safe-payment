package com.trxsafe.payment.wallet

import android.content.Context
import com.trxsafe.payment.security.TransactionValidator
import org.tron.trident.core.key.KeyPair
import org.tron.trident.proto.Chain
import org.tron.trident.utils.Base58Check
import org.tron.trident.crypto.Hash
import com.google.protobuf.ByteString

/**
 * 最小化热钱包管理器
 * 
 * 硬性约束：
 * 1. 禁止导出私钥
 * 2. 仅支持签名 TransferContract
 * 3. 禁止合约交易
 * 4. 禁止自定义交易
 * 5. 禁止任意 data
 */
class WalletManager(private val context: Context? = null) {
    
    private var keyStore: SecureKeyStore? = null
    private var currentKeyPair: KeyPair? = null
    private val validator = TransactionValidator()
    
    init {
        context?.let {
            keyStore = SecureKeyStore(it)
            // 尝试加载已存在的钱包
            loadWallet()
        }
    }
    
    /**
     * 创建新钱包
     * 本地生成私钥并加密存储
     * 
     * @return 钱包地址
     * @throws SecurityException 创建失败时抛出
     */
    @Throws(SecurityException::class)
    fun createWallet(): String {
        if (keyStore == null) {
            throw SecurityException("KeyStore 未初始化")
        }
        
        // 检查是否已存在钱包
        if (keyStore!!.hasWallet()) {
            throw SecurityException("钱包已存在，请先删除现有钱包")
        }
        
        try {
            // 生成密钥对
            val keyPair = KeyPair.generate()
            val address = keyPair.toBase58CheckAddress()
            val privateKeyHex = keyPair.toPrivateKey()
            
            // 加密存储私钥
            keyStore!!.savePrivateKey(privateKeyHex, address)
            
            // 保存到内存
            currentKeyPair = keyPair
            
            return address
            
        } catch (e: Exception) {
            throw SecurityException("创建钱包失败：${e.message}", e)
        }
    }
    
    /**
     * 导入钱包（从私钥）
     * 
     * @param privateKeyHex 私钥（16 进制字符串）
     * @return 钱包地址
     * @throws SecurityException 导入失败时抛出
     */
    @Throws(SecurityException::class)
    fun importWallet(privateKeyHex: String): String {
        if (keyStore == null) {
            throw SecurityException("KeyStore 未初始化")
        }
        
        // 验证私钥格式
        if (!keyStore!!.isValidPrivateKey(privateKeyHex)) {
            throw SecurityException("私钥格式错误，必须是 64 位 16 进制字符串")
        }
        
        // 检查是否已存在钱包
        if (keyStore!!.hasWallet()) {
            throw SecurityException("钱包已存在，请先删除现有钱包")
        }
        
        try {
            // 从私钥创建密钥对
            val keyPair = KeyPair(privateKeyHex)
            val address = keyPair.toBase58CheckAddress()
            
            // 加密存储私钥
            keyStore!!.savePrivateKey(privateKeyHex, address)
            
            // 保存到内存
            currentKeyPair = keyPair
            
            return address
            
        } catch (e: Exception) {
            throw SecurityException("导入钱包失败：${e.message}", e)
        }
    }
    
    /**
     * 导入观察钱包（仅地址）
     * 
     * @param address 钱包地址
     * @return 钱包地址
     * @throws SecurityException 导入失败时抛出
     */
    @Throws(SecurityException::class)
    fun importWatchWallet(address: String): String {
        if (keyStore == null) {
            throw SecurityException("KeyStore 未初始化")
        }
        
        // 验证地址格式
        if (!isValidAddress(address)) {
            throw SecurityException("地址格式错误")
        }
        
        // 检查是否已存在钱包
        if (keyStore!!.hasWallet()) {
            throw SecurityException("钱包已存在，请先删除现有钱包")
        }
        
        try {
            // 保存观察钱包
            keyStore!!.saveWatchWalletAddress(address)
            
            // 清除内存中的 KeyPair (如果是之前残留的)
            currentKeyPair = null
            
            return address
            
        } catch (e: Exception) {
            throw SecurityException("导入观察钱包失败：${e.message}", e)
        }
    }

    /**
     * 检查是否为观察钱包
     */
    fun isWatchOnly(): Boolean {
        return keyStore?.isWatchOnly() == true
    }

    /**
     * 加载钱包
     * 从加密存储中加载私钥到内存
     * 
     * @return true 表示加载成功
     */
    private fun loadWallet(): Boolean {
        if (keyStore == null) {
            return false
        }
        
        // 如果是观察钱包，不需要加载私钥
        if (keyStore!!.isWatchOnly()) {
            return true
        }
        
        return try {
            val privateKeyHex = keyStore!!.getPrivateKey()
            if (privateKeyHex != null) {
                currentKeyPair = KeyPair(privateKeyHex)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 获取钱包地址
     * 
     * @return 钱包地址，如果不存在返回 null
     */
    fun getAddress(): String? {
        return keyStore?.getWalletAddress() ?: currentKeyPair?.toBase58CheckAddress()
    }
    
    /**
     * 签名 TransferContract 交易
     * 
     * 硬性约束：
     * - 仅允许签名 TransferContract
     * - 禁止签名合约交易
     * - 禁止签名包含 data 的交易
     * - 观察钱包禁止签名
     * 
     * @param transaction 待签名的交易
     * @return 签名后的交易
     * @throws SecurityException 签名失败或违反约束时抛出
     */
    @Throws(SecurityException::class)
    fun signTransferContract(transaction: Chain.Transaction): Chain.Transaction {
        // 检查钱包是否存在
        if (!hasWallet()) {
            throw SecurityException("钱包不存在")
        }
        
        // 检查是否为观察钱包
        if (isWatchOnly()) {
            throw SecurityException("观察钱包无法签名，请使用生成二维码功能")
        }
        
        // 确保 KeyPair 已加载
        if (currentKeyPair == null) {
            if (!loadWallet() || currentKeyPair == null) {
                 throw SecurityException("私钥加载失败")
            }
        }
        
        // 硬性约束 1：验证交易类型
        validateTransactionType(transaction)
        
        // 硬性约束 2：验证没有 data
        validateNoData(transaction)
        
        // 硬性约束 3：验证没有合约调用
        validateNoContractCall(transaction)
        
        try {
            // 执行签名
            val rawData = transaction.rawData.toByteArray()
            val hash = Hash.sha256(rawData)
            val signature = currentKeyPair!!.sign(hash)
            
            // 构建签名后的交易
            return transaction.toBuilder()
                .addSignature(ByteString.copyFrom(signature))
                .build()
                
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            throw SecurityException("签名失败：${e.message}", e)
        }
    }
    
    /**
     * 验证交易类型
     * 硬性约束：仅允许 TransferContract
     */
    @Throws(SecurityException::class)
    private fun validateTransactionType(transaction: Chain.Transaction) {
        if (!transaction.hasRawData()) {
            throw SecurityException("交易缺少 RawData")
        }
        
        val rawData = transaction.rawData
        if (rawData.contractCount != 1) {
            throw SecurityException("仅允许包含一个合约")
        }
        
        val contract = rawData.getContract(0)
        if (contract.type != Chain.Transaction.Contract.ContractType.TransferContract) {
            throw SecurityException(
                "禁止签名此交易类型：${contract.type}，仅允许 TransferContract"
            )
        }
    }
    
    /**
     * 验证没有 data
     * 硬性约束：禁止任意 data
     */
    @Throws(SecurityException::class)
    private fun validateNoData(transaction: Chain.Transaction) {
        val rawData = transaction.rawData
        if (rawData.hasData() && rawData.data.size() > 0) {
            throw SecurityException(
                "禁止签名包含 data 的交易（${rawData.data.size()} 字节）"
            )
        }
    }
    
    /**
     * 验证没有合约调用
     * 硬性约束：禁止合约交易
     */
    @Throws(SecurityException::class)
    private fun validateNoContractCall(transaction: Chain.Transaction) {
        val rawData = transaction.rawData
        val contract = rawData.getContract(0)
        
        // 检查是否为合约调用类型
        val forbiddenTypes = listOf(
            Chain.Transaction.Contract.ContractType.TriggerSmartContract,
            Chain.Transaction.Contract.ContractType.CreateSmartContract
        )
        
        if (contract.type in forbiddenTypes) {
            throw SecurityException("禁止签名合约交易")
        }
    }
    
    /**
     * 删除钱包
     * 
     * @throws SecurityException 删除失败时抛出
     */
    @Throws(SecurityException::class)
    fun deleteWallet() {
        keyStore?.clearWallet()
        currentKeyPair = null
    }
    
    /**
     * 检查是否存在钱包
     * 
     * @return true 表示已存在钱包
     */
    fun hasWallet(): Boolean {
        return keyStore?.hasWallet() == true
    }
    
    /**
     * 验证地址格式
     * 
     * @param address TRON 地址字符串
     * @return true 表示格式正确
     */
    fun isValidAddress(address: String): Boolean {
        return try {
            // TRON 地址以 T 开头，长度为 34
            if (!address.startsWith("T") || address.length != 34) {
                return false
            }
            // 尝试解码 Base58
            Base58Check.base58ToBytes(address)
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 获取私钥用于备份
     * 
     * 安全警告：
     * 1. 必须在经过高强度生物识别验证后调用
     * 2. 调用后应立即在内存中清除
     * 3. 仅用于备份目的
     * 
     * @return 私钥（16 进制字符串）
     * @throws SecurityException 获取失败或钱包不存在时抛出
     */
    @Throws(SecurityException::class)
    fun getPrivateKeyForBackup(): String {
        if (!hasWallet()) {
            throw SecurityException("钱包不存在")
        }
        if (isWatchOnly()) {
            throw SecurityException("观察钱包无私钥")
        }
        
        return keyStore?.getPrivateKey() ?: throw SecurityException("密钥获取失败")
    }
    
    /**
     * 导出私钥
     * 硬性约束：禁止导出私钥
     * 
     * @deprecated 此方法已被禁用，始终抛出异常
     * @throws SecurityException 始终抛出
     */
    @Deprecated("禁止导出私钥", level = DeprecationLevel.ERROR)
    fun exportPrivateKey(): String {
        throw SecurityException("禁止导出私钥")
    }
    
    /**
     * 签名自定义交易
     * 硬性约束：禁止签名自定义交易
     * 
     * @deprecated 此方法已被禁用，始终抛出异常
     * @throws SecurityException 始终抛出
     */
    @Deprecated("禁止签名自定义交易", level = DeprecationLevel.ERROR)
    fun signCustomTransaction(transaction: Chain.Transaction): Chain.Transaction {
        throw SecurityException("禁止签名自定义交易，仅支持 TransferContract")
    }
    
    /**
     * 签名消息
     * 硬性约束：禁止签名任意消息
     * 
     * @deprecated 此方法已被禁用，始终抛出异常
     * @throws SecurityException 始终抛出
     */
    @Deprecated("禁止签名任意消息", level = DeprecationLevel.ERROR)
    fun signMessage(message: String): ByteArray {
        throw SecurityException("禁止签名任意消息")
    }
}

