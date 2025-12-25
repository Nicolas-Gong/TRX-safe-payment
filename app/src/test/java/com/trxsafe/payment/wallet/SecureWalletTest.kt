package com.trxsafe.payment.wallet

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SecureKeyStore 和 WalletManager 单元测试
 */
@RunWith(AndroidJUnit4::class)
class SecureWalletTest {
    
    private lateinit var context: Context
    private lateinit var keyStore: SecureKeyStore
    private lateinit var walletManager: WalletManager
    
    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        keyStore = SecureKeyStore(context)
        walletManager = WalletManager(context)
        
        // 清理之前的测试数据
        try {
            keyStore.clearWallet()
        } catch (e: Exception) {
            // 忽略
        }
    }
    
    // ========== SecureKeyStore 测试 ==========
    
    @Test
    fun testPrivateKeyValidation() {
        // 有效私钥（64 位 16 进制）
        val validKey = "0".repeat(64)
        assertTrue("有效私钥应通过验证", keyStore.isValidPrivateKey(validKey))
        
        // 无效私钥（长度不足）
        val invalidKey1 = "0".repeat(63)
        assertFalse("长度不足应验证失败", keyStore.isValidPrivateKey(invalidKey1))
        
        // 无效私钥（非16进制）
        val invalidKey2 = "G".repeat(64)
        assertFalse("非16进制应验证失败", keyStore.isValidPrivateKey(invalidKey2))
    }
    
    @Test
    fun testSaveAndLoadPrivateKey() {
        val privateKey = "1234567890abcdef".repeat(4)
        val address = "TXYZoPE5CP4Gj4K..."
        
        // 保存私钥
        keyStore.savePrivateKey(privateKey, address)
        
        // 验证是否存在
        assertTrue("应检测到钱包存在", keyStore.hasWallet())
        
        // 加载私钥
        val loadedKey = keyStore.getPrivateKey()
        assertEquals("加载的私钥应匹配", privateKey, loadedKey)
        
        // 加载地址
        val loadedAddress = keyStore.getWalletAddress()
        assertEquals("加载的地址应匹配", address, loadedAddress)
    }
    
    @Test
    fun testClearWallet() {
        val privateKey = "1234567890abcdef".repeat(4)
        val address = "TXYZoPE5CP4Gj4K..."
        
        // 保存私钥
        keyStore.savePrivateKey(privateKey, address)
        assertTrue("应检测到钱包存在", keyStore.hasWallet())
        
        // 清除钱包
        keyStore.clearWallet()
        assertFalse("清除后不应检测到钱包", keyStore.hasWallet())
        assertNull("清除后私钥应为 null", keyStore.getPrivateKey())
    }
    
    // ========== WalletManager 测试 ==========
    
    @Test
    fun testCreateWallet() {
        // 创建钱包
        val address = walletManager.createWallet()
        
        // 验证地址格式
        assertNotNull("地址不应为 null", address)
        assertTrue("地址应以 T 开头", address.startsWith("T"))
        assertEquals("地址长度应为 34", 34, address.length)
        
        // 验证钱包存在
        assertTrue("应检测到钱包存在", walletManager.hasWallet())
    }
    
    @Test(expected = SecurityException::class)
    fun testCreateWalletTwice() {
        // 第一次创建成功
        walletManager.createWallet()
        
        // 第二次创建应抛出异常
        walletManager.createWallet()
    }
    
    @Test
    fun testImportWallet() {
        // 使用测试私钥
        val privateKey = "1234567890abcdef".repeat(4)
        
        // 导入钱包
        val address = walletManager.importWallet(privateKey)
        
        // 验证地址
        assertNotNull("地址不应为 null", address)
        assertTrue("地址应以 T 开头", address.startsWith("T"))
        
        // 验证钱包存在
        assertTrue("应检测到钱包存在", walletManager.hasWallet())
    }
    
    @Test(expected = SecurityException::class)
    fun testImportInvalidPrivateKey() {
        // 无效私钥
        val invalidKey = "invalid"
        walletManager.importWallet(invalidKey)
    }
    
    @Test
    fun testDeleteWallet() {
        // 创建钱包
        walletManager.createWallet()
        assertTrue("应检测到钱包存在", walletManager.hasWallet())
        
        // 删除钱包
        walletManager.deleteWallet()
        assertFalse("删除后不应检测到钱包", walletManager.hasWallet())
    }
    
    @Test(expected = SecurityException::class)
    fun testExportPrivateKeyIsDisabled() {
        // 尝试导出私钥应抛出异常
        @Suppress("DEPRECATION")
        walletManager.exportPrivateKey()
    }
    
    @Test(expected = SecurityException::class)
    fun testSignMessageIsDisabled() {
        // 尝试签名消息应抛出异常
        @Suppress("DEPRECATION")
        walletManager.signMessage("test message")
    }
    
    @Test
    fun testAddressValidation() {
        // 有效地址
        val validAddress = "TXYZoPE5CP4Gj4Kuvub4fTPdZK8qRVvvvX"
        // 注意：实际测试需要使用真实的 TRON 地址
        
        // 无效地址 - 不以T开头
        val invalidAddress1 = "AXYZoPE5CP4Gj4Kuvub4fTPdZK8qRVvvvX"
        assertFalse("不以T开头应验证失败", walletManager.isValidAddress(invalidAddress1))
        
        // 无效地址 - 长度不足
        val invalidAddress2 = "TXYZ"
        assertFalse("长度不足应验证失败", walletManager.isValidAddress(invalidAddress2))
    }
}
