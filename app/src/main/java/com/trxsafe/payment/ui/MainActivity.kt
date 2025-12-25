package com.trxsafe.payment.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.trxsafe.payment.R
import com.trxsafe.payment.databinding.ActivityMainBinding
import com.trxsafe.payment.wallet.WalletManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主界面
 * 显示钱包余额、地址和操作入口
 */
class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var walletManager: WalletManager
    private var quickPayMultiplier = 1
    private var currentSettings: com.trxsafe.payment.settings.SettingsConfig? = null
    private val transactionBuilder = com.trxsafe.payment.transaction.TransactionBuilder()
    private val qrGenerator = com.trxsafe.payment.qrcode.MainAppQRGenerator()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        walletManager = WalletManager(this)
        
        initViews()
        
        // 检查应用锁
        checkAppLock()
    }
    
    override fun onResume() {
        super.onResume()
        
        // 检查 App 锁定状态
        checkAppLockOnResume()
        
        refreshWalletState()
    }
    
    private fun initViews() {
        // 复制地址
        binding.cardAddress.setOnClickListener {
            val address = binding.tvAddress.text.toString()
            if (address.isNotEmpty() && address != "T...") {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("TRX Address", address)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, getString(R.string.success_address_copied), Toast.LENGTH_SHORT).show()
            }
        }
        
        // 刷新余额 (点击余额文本)
        binding.tvBalance.setOnClickListener {
            // TODO: 实现余额刷新逻辑
            Toast.makeText(this, "正在刷新余额...", Toast.LENGTH_SHORT).show()
        }
        
        // 导航按钮
        binding.btnTransfer.setOnClickListener {
            startActivity(Intent(this, TransferActivity::class.java))
        }
        
        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, TransactionHistoryActivity::class.java))
        }
        
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        
        binding.btnAddressBook.setOnClickListener {
            startActivity(Intent(this, AddressBookActivity::class.java))
        }
        
        // 钱包管理按钮
        binding.btnCreateWallet.setOnClickListener {
            showCreateWalletDialog()
        }
        
        binding.btnImportWallet.setOnClickListener {
            showImportWalletDialog()
        }
        
        binding.btnImportWatchWallet.setOnClickListener {
            showImportWatchWalletDialog()
        }
        
        
        binding.btnDeleteWallet.setOnClickListener {
            showDeleteWalletDialog()
        }
        
        // 解锁按钮
        binding.btnUnlock.setOnClickListener {
            performUnlock()
        }
        
        // 闪付数量调整
        binding.btnQuickMinus.setOnClickListener {
            if (quickPayMultiplier > 1) {
                quickPayMultiplier--
                updateQuickPayQR()
            }
        }
        binding.btnQuickPlus.setOnClickListener {
            quickPayMultiplier++
            updateQuickPayQR()
        }
    }
    
    /**
     * 检查是否需要应用锁（onCreate 时调用）
     */
    private fun checkAppLock() {
        // 使用协程加载配置
        androidx.lifecycle.lifecycleScope.launchWhenStarted {
             val repository = com.trxsafe.payment.settings.SettingsRepository(this@MainActivity)
             val config = repository.loadConfig()
             
             if (config.isBiometricEnabled) {
                 // 锁定界面
                 binding.layoutLockScreen.visibility = View.VISIBLE
                 // 立即尝试解锁
                 performUnlock()
             }
        }
    }
    
    /**
     * 检查 App 锁定状态（onResume 时调用）
     */
    private fun checkAppLockOnResume() {
        val app = com.trxsafe.payment.TrxSafeApplication.getInstance(this)
        val appLockManager = app.appLockManager
        
        // 检查是否需要锁定
        if (appLockManager.checkShouldLock()) {
            binding.layoutLockScreen.visibility = View.VISIBLE
            performUnlock()
        }
    }
    
    /**
     * 执行解锁逻辑
     */
    private fun performUnlock() {
        val authManager = com.trxsafe.payment.security.BiometricAuthManager(this)
        val app = com.trxsafe.payment.TrxSafeApplication.getInstance(this)
        val appLockManager = app.appLockManager
        
        authManager.authenticate(
            title = "解锁应用",
            subtitle = "验证您的身份以进入",
            onSuccess = {
                // 解锁成功，隐藏遮罩
                binding.layoutLockScreen.visibility = View.GONE
                appLockManager.unlock()
                Toast.makeText(this, "解锁成功", Toast.LENGTH_SHORT).show()
                refreshWalletState()
            },
            onError = { error ->
                // 指纹错误等
                Toast.makeText(this, "解锁失败：$error", Toast.LENGTH_SHORT).show()
                // 保持锁定状态
            }
        )
    }

    /**
     * 刷新钱包状态（根据是否存在钱包切换 UI）
     */
    private fun refreshWalletState() {
        if (walletManager.hasWallet()) {
            // 有钱包 -> 显示信息页
            binding.layoutWalletSetup.visibility = View.GONE
            binding.layoutWalletInfo.visibility = View.VISIBLE
            
            // 显示地址
            val address = walletManager.getAddress()
            binding.tvAddress.text = address ?: "Error"
            
            // 显示钱包类型
            binding.tvWalletType.visibility = View.VISIBLE
            if (walletManager.isWatchOnly()) {
                binding.tvWalletType.text = getString(R.string.wallet_type_watch_only)
                binding.tvWalletType.setTextColor(getColor(R.color.warning)) // Yellow/Orange
                binding.tvWalletType.setBackgroundResource(R.drawable.bg_badge_warning) // Assuming you have a badge drawable or just set color
            } else {
                binding.tvWalletType.text = getString(R.string.wallet_type_hot)
                binding.tvWalletType.setTextColor(getColor(R.color.primary))
                binding.tvWalletType.setBackgroundResource(R.drawable.bg_badge_primary)
            }
            
            // TODO: 异步获取余额 (观察钱包和热钱包都可以查询余额)
            
            // 检查闪付模式 (Flash Pay Mode)
            checkFlashPayMode()
        } else {
            // 无钱包 -> 显示设置页
            binding.layoutWalletInfo.visibility = View.GONE
            binding.layoutWalletSetup.visibility = View.VISIBLE
        }
    }
    
    /**
     * 检查并显示闪付模式
     */
    private fun checkFlashPayMode() {
        val fromAddress = walletManager.getAddress() ?: return
        
        androidx.lifecycle.lifecycleScope.launch {
            val repository = com.trxsafe.payment.settings.SettingsRepository(this@MainActivity)
            val config = repository.loadConfig()
            currentSettings = config
            
            if (config.isPriceLocked && config.sellerAddress.isNotEmpty()) {
                binding.layoutQuickPay.visibility = View.VISIBLE
                quickPayMultiplier = config.multiplier
                updateQuickPayQR()
            } else {
                binding.layoutQuickPay.visibility = View.GONE
            }
        }
    }
    
    /**
     * 更新闪付模式下的二维码和 UI
     * 改用标准 TRON URI 协议 (tron:address?amount=xxx)，确保 100% 离线可用且兼容所有钱包
     */
    private fun updateQuickPayQR() {
        val config = currentSettings ?: return
        
        // 计算当前倍率下的总金额
        val totalAmountSun = config.pricePerUnitSun * quickPayMultiplier
        val totalAmountTrx = (totalAmountSun.toDouble() / 1_000_000.0)
        
        binding.tvQuickMultiplier.text = quickPayMultiplier.toString()
        binding.tvQuickTotal.text = "结算：${String.format("%.6f", totalAmountTrx)} TRX"
        binding.tvQuickSummary.text = "收款地址: ${config.sellerAddress}"
        
        // 构造标准 TRON 支付 URI: tron:ADDRESS?amount=SUN_VALUE
        // 这是标准协议，没有任何网络依赖，任何钱包扫描都能识别金额和地址
        val uri = "tron:${config.sellerAddress}?amount=$totalAmountSun"
        
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                // 生成二维码图片
                val bitmap = qrGenerator.generateQRCodeBitmap(uri, 600)
                
                withContext(Dispatchers.Main) {
                    binding.ivQuickQR.setImageBitmap(bitmap)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.ivQuickQR.setImageBitmap(null)
                    Toast.makeText(this@MainActivity, "快速生成失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    /**
     * 显示创建钱包确认对话框
     */
    private fun showCreateWalletDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.create_wallet)
            .setMessage("将在本地生成一个新的安全钱包。\n\n注意：私钥将加密存储在设备安全芯片中，无法导出。请确保您了解风险。")
            .setPositiveButton(R.string.confirm) { _, _ ->
                createWallet()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    
    /**
     * 执行创建钱包
     */
    private fun createWallet() {
        try {
            val address = walletManager.createWallet()
            Toast.makeText(this, getString(R.string.success_wallet_created), Toast.LENGTH_SHORT).show()
            refreshWalletState()
        } catch (e: Exception) {
            showError("创建失败：${e.message}")
        }
    }
    
    /**
     * 显示导入钱包对话框
     */
    private fun showImportWalletDialog() {
        val inputLayout = com.google.android.material.textfield.TextInputLayout(this).apply {
            setPadding(32, 16, 32, 16)
            hint = getString(R.string.hint_private_key)
        }
        val editText = com.google.android.material.textfield.TextInputEditText(this)
        inputLayout.addView(editText)
        
        AlertDialog.Builder(this)
            .setTitle(R.string.import_wallet)
            .setView(inputLayout)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val privateKey = editText.text.toString().trim()
                if (privateKey.isNotEmpty()) {
                    importWallet(privateKey)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    
    /**
     * 显示导入观察钱包对话框
     */
    private fun showImportWatchWalletDialog() {
        val inputLayout = com.google.android.material.textfield.TextInputLayout(this).apply {
            setPadding(32, 16, 32, 16)
            hint = getString(R.string.hint_watch_address)
        }
        val editText = com.google.android.material.textfield.TextInputEditText(this)
        inputLayout.addView(editText)
        
        AlertDialog.Builder(this)
            .setTitle(R.string.import_watch_wallet)
            .setView(inputLayout)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val address = editText.text.toString().trim()
                if (address.isNotEmpty()) {
                    importWatchWallet(address)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    
    /**
     * 执行导入钱包
     */
    private fun importWallet(privateKey: String) {
        try {
            walletManager.importWallet(privateKey)
            Toast.makeText(this, getString(R.string.success_wallet_imported), Toast.LENGTH_SHORT).show()
            refreshWalletState()
        } catch (e: Exception) {
            showError("导入失败：${e.message}")
        }
    }

    /**
     * 执行导入观察钱包
     */
    private fun importWatchWallet(address: String) {
        try {
            walletManager.importWatchWallet(address)
            Toast.makeText(this, "观察钱包导入成功", Toast.LENGTH_SHORT).show()
            refreshWalletState()
        } catch (e: Exception) {
            showError("导入失败：${e.message}")
        }
    }
    
    /**
     * 显示删除钱包对话框
     */
    private fun showDeleteWalletDialog() {
        AlertDialog.Builder(this)
            .setTitle("删除钱包")
            .setMessage("确定要删除当前钱包吗？\n\n警告：删除后将无法恢复（除非您有私钥备份），且不仅限于本应用，该操作不可撤销！")
            .setPositiveButton("删除") { _, _ ->
                try {
                    walletManager.deleteWallet()
                    refreshWalletState()
                    Toast.makeText(this, "钱包已删除", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    showError(e.message ?: "删除失败")
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    
    private fun showError(message: String) {
        AlertDialog.Builder(this)
            .setTitle("错误")
            .setMessage(message)
            .setPositiveButton(R.string.confirm, null)
            .show()
    }
}
