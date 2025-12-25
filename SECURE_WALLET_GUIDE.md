# 最小化热钱包模块使用指南

## 📋 概述

最小化热钱包模块提供安全的私钥管理和交易签名功能，严格限制仅支持 TRX 普通转账。

---

## 🔒 硬性约束

### 1. **禁止导出私钥**
```kotlin
@Deprecated("禁止导出私钥", level = DeprecationLevel.ERROR)
fun exportPrivateKey(): String {
    throw SecurityException("禁止导出私钥")
}
```
编译时错误，无法调用此方法。

### 2. **仅支持签名 TransferContract**
- ✅ 允许：TRX 普通转账
- ❌ 禁止：合约交易
- ❌ 禁止：自定义交易
- ❌ 禁止：包含 data 的交易

### 3. **AES 加密存储**
使用 Android `EncryptedSharedPreferences` 自动加密私钥。

---

## 🏗️ 核心组件

### SecureKeyStore
**功能**：加密存储私钥

**方法**：
- `savePrivateKey(privateKeyHex, address)` - 保存私钥（加密）
- `getPrivateKey()` - 获取私钥（解密）
- `getWalletAddress()` - 获取地址
- `hasWallet()` - 检查是否存在钱包
- `clearWallet()` - 清除钱包

**加密方式**：
- 算法：AES-256-GCM
- 库：AndroidX Security Crypto
- 存储：EncryptedSharedPreferences

---

### WalletManager
**功能**：钱包管理和交易签名

**方法**：
- `createWallet()` - 创建新钱包
- `importWallet(privateKeyHex)` - 导入钱包
- `getAddress()` - 获取地址
- `signTransferContract(transaction)` - 签名交易
- `deleteWallet()` - 删除钱包
- `hasWallet()` - 检查是否存在钱包

---

## 🎯 使用示例

### 1. 创建钱包

```kotlin
val walletManager = WalletManager(context)

try {
    val address = walletManager.createWallet()
    println("钱包创建成功：$address")
} catch (e: SecurityException) {
    println("创建失败：${e.message}")
}
```

### 2. 导入钱包

```kotlin
val privateKeyHex = "1234567890abcdef..." // 64位16进制

try {
    val address = walletManager.importWallet(privateKeyHex)
    println("钱包导入成功：$address")
} catch (e: SecurityException) {
    println("导入失败：${e.message}")
}
```

### 3. 签名交易

```kotlin
// 构造交易
val transaction = transactionBuilder.buildTransferTransaction(
    fromAddress = walletManager.getAddress()!!,
    config = config
)

// 签名交易
try {
    val signedTransaction = walletManager.signTransferContract(transaction)
    println("签名成功")
    
    // 广播交易
    broadcastTransaction(signedTransaction)
    
} catch (e: SecurityException) {
    println("签名失败：${e.message}")
}
```

### 4. 删除钱包

```kotlin
try {
    walletManager.deleteWallet()
    println("钱包已删除")
} catch (e: SecurityException) {
    println("删除失败：${e.message}")
}
```

---

##🔄 完整流程示例

```kotlin
class WalletActivity : AppCompatActivity() {
    
    private lateinit var walletManager: WalletManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 初始化
        walletManager = WalletManager(this)
        
        // 检查是否已有钱包
        if (walletManager.hasWallet()) {
            val address = walletManager.getAddress()
            println("已有钱包：$address")
        } else {
            // 创建新钱包
            createNewWallet()
        }
    }
    
    private fun createNewWallet() {
        try {
            val address = walletManager.createWallet()
            showSuccess("钱包创建成功：$address")
        } catch (e: SecurityException) {
            showError("创建失败：${e.message}")
        }
    }
    
    private fun performTransfer(config: SettingsConfig) {
        lifecycleScope.launch {
            try {
                // 1. 构造交易
                val transaction = TransactionBuilder().buildTransferTransaction(
                    fromAddress = walletManager.getAddress()!!,
                    config = config
                )
                
                // 2. 风控检查
                val riskResult = RiskValidator().checkRisk(transaction, config)
                if (riskResult.level == RiskLevel.BLOCK) {
                    showError(riskResult.message)
                    return@launch
                }
                
                // 3. 显示确认对话框
                TransferConfirmDialog(
                    context = this@WalletActivity,
                    config = config,
                    fromAddress = walletManager.getAddress()!!,
                    onConfirmed = {
                        // 4. 签名交易
                        signAndBroadcast(transaction)
                    }
                ).show()
                
            } catch (e: Exception) {
                showError("交易失败：${e.message}")
            }
        }
    }
    
    private suspend fun signAndBroadcast(transaction: Chain.Transaction) {
        try {
            // 签名
            val signedTx = walletManager.signTransferContract(transaction)
            
            // 广播
            val result = broadcastTransaction(signedTx)
            showSuccess("交易成功")
            
        } catch (e: SecurityException) {
            showError("签名失败：${e.message}")
        }
    }
}
```

---

## 🚨 安全验证

### 签名前验证

```kotlin
private fun validateTransactionType(transaction: Chain.Transaction) {
    // 1. 检查交易类型
    if (contractType != TransferContract) {
        throw SecurityException("仅允许 TransferContract")
    }
}

private fun validateNoData(transaction: Chain.Transaction) {
    // 2. 检查 data 字段
    if (rawData.hasData() && rawData.data.size() > 0) {
        throw SecurityException("禁止签名包含 data 的交易")
    }
}

private fun validateNoContractCall(transaction: Chain.Transaction) {
    // 3. 检查合约调用
    val forbiddenTypes = listOf(
        TriggerSmartContract,
        CreateSmartContract
    )
    if (contractType in forbiddenTypes) {
        throw SecurityException("禁止签名合约交易")
    }
}
```

---

## ⚠️ 禁用的方法

```kotlin
// 尝试调用这些方法会导致编译错误

// 1. 导出私钥
@Deprecated("禁止导出私钥", level = DeprecationLevel.ERROR)
fun exportPrivateKey(): String

// 2. 签名自定义交易
@Deprecated("禁止签名自定义交易", level = DeprecationLevel.ERROR)
fun signCustomTransaction(transaction: Chain.Transaction): Chain.Transaction

// 3. 签名消息
@Deprecated("禁止签名任意消息", level = DeprecationLevel.ERROR)
fun signMessage(message: String): ByteArray
```

---

## 🔐 加密存储详解

### EncryptedSharedPreferences

```kotlin
val masterKey = MasterKey.Builder(context)
    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
    .build()

val encryptedPrefs = EncryptedSharedPreferences.create(
    context,
    "secure_wallet_prefs",
    masterKey,
    PrefKeyEncryptionScheme.AES256_SIV,
    PrefValueEncryptionScheme.AES256_GCM
)
```

**特性**：
- ✅ 密钥存储在 Android Keystore
- ✅ 自动加密/解密
- ✅ 硬件支持（如果可用）
- ✅ 设备锁定保护

---

## 📝 最佳实践

1. **初始化 Context**：
   ```kotlin
   // 正确
   val walletManager = WalletManager(context)
   
   // 错误（KeyStore 未初始化）
   val walletManager = WalletManager()
   ```

2. **异常处理**：
   ```kotlin
   try {
       walletManager.createWallet()
   } catch (e: SecurityException) {
       // 处理错误
   }
   ```

3. **生命周期管理**：
   ```kotlin
   override fun onDestroy() {
       // 不需要手动清理
       // 私钥保留在加密存储中
       super.onDestroy()
   }
   ```

---

**创建时间**: 2025-12-25  
**版本**: 1.0.0
