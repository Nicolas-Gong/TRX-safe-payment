package com.trxsafe.payment.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.trxsafe.payment.R
import com.trxsafe.payment.databinding.ActivityMainBinding
import com.trxsafe.payment.network.Sun
import com.trxsafe.payment.utils.setDebouncedClick

import com.trxsafe.payment.wallet.WalletManager
import com.trxsafe.payment.validation.AddressValidator
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主界面
 * 显示钱包余额、地址和操作入口
 */
class MainActivity : BaseActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var walletManager: WalletManager
    private var walletConnectManager: com.trxsafe.payment.wallet.WalletConnectManager? = null
    private var walletConnectInitialized = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        walletManager = WalletManager(this)
        
        initViews()
    }
    
    override fun onResume() {
        super.onResume()
        
        // 触发 WalletConnect 后台初始化（不阻塞主线程）
        triggerWalletConnectInit()
        
        refreshWalletState()
    }
    
    /**
     * 触发 WalletConnect 在后台初始化
     * 此方法不会阻塞主线程，初始化完成后会自动设置状态观察者
     */
    private fun triggerWalletConnectInit() {
        if (walletConnectInitialized) {
            return
        }
        
        val app = application as com.trxsafe.payment.TrxSafeApplication
        val manager = app.getOrCreateWalletConnectManager()
        
        if (manager != null) {
            // 初始化已完成，直接设置观察者
            walletConnectManager = manager
            observeWalletConnectState()
            walletConnectInitialized = true
            Log.d("MainActivity", "WalletConnectManager 已就绪")
        } else {
            // 正在后台初始化，延迟检查
            Log.d("MainActivity", "WalletConnectManager 正在后台初始化...")
            binding.root.postDelayed({
                checkWalletConnectInitialized()
            }, 500)
        }
    }
    
    /**
     * 检查 WalletConnect 是否已初始化完成
     */
    private fun checkWalletConnectInitialized() {
        if (walletConnectInitialized) {
            return
        }
        
        val app = application as com.trxsafe.payment.TrxSafeApplication
        val manager = app.getOrCreateWalletConnectManager()
        
        if (manager != null) {
            walletConnectManager = manager
            observeWalletConnectState()
            walletConnectInitialized = true
            Log.d("MainActivity", "WalletConnectManager 初始化完成")
        } else {
            // 继续等待
            binding.root.postDelayed({
                checkWalletConnectInitialized()
            }, 500)
        }
    }
    
    /**
     * 获取 WalletConnectManager 实例，如果未初始化则同步等待
     * 只在真正需要使用 WalletConnect 时调用（如发起连接时）
     */
    private fun getWalletConnectManager(): com.trxsafe.payment.wallet.WalletConnectManager {
        if (walletConnectManager != null && walletConnectManager!!.isInitialized) {
            return walletConnectManager!!
        }
        
        Log.d("MainActivity", "同步获取 WalletConnectManager...")
        val app = application as com.trxsafe.payment.TrxSafeApplication
        val manager = app.getWalletConnectManagerSync()
        
        if (walletConnectManager == null) {
            walletConnectManager = manager
            observeWalletConnectState()
            walletConnectInitialized = true
        }
        
        return manager
    }
    
    private fun initViews() {
        // 刷新余额按钮
        binding.btnRefreshBalance.setDebouncedClick(debounceDelayMs = 1000) {
            refreshWalletState()
            Toast.makeText(this, "余额正在更新...", Toast.LENGTH_SHORT).show()
        }

        // 功能按钮
        binding.btnHistory.setDebouncedClick(debounceDelayMs = 1000) {
            startActivity(Intent(this, TransactionHistoryActivity::class.java))
        }

        binding.btnAddressBook.setDebouncedClick(debounceDelayMs = 1000) {
            startActivity(Intent(this, AddressBookActivity::class.java))
        }

        binding.btnSettings.setDebouncedClick(debounceDelayMs = 1000) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 钱包选择器 - 点击显示钱包选择器，长按复制地址
        binding.cardWalletSelector.setDebouncedClick(debounceDelayMs = 1000) {
            // 点击显示钱包选择器
            showWalletSelector()
        }

        binding.cardWalletSelector.setOnLongClickListener {
            // 长按复制当前钱包地址
            val address = walletManager.getAddress()
            if (address != null) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("TRX Address", address)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, getString(R.string.success_address_copied), Toast.LENGTH_SHORT).show()
            }
            true // 返回true表示消费了长按事件
        }

        // TRX转账按钮
        binding.btnTransfer.setDebouncedClick(debounceDelayMs = 1000) {
            startActivity(Intent(this, TransferActivity::class.java))
        }

        // 钱包管理按钮
        binding.btnManageWalletMain.setDebouncedClick(debounceDelayMs = 1000) {
            startActivity(Intent(this, WalletManagementActivity::class.java))
        }

        // 添加WalletConnect测试按钮（长按余额区域）
        binding.tvBalance.setOnLongClickListener {
            testWalletConnectConnection()
            true
        }

        // 确认转账按钮（首页转账表单）
        binding.btnExecuteTransfer.setDebouncedClick(debounceDelayMs = 1000) {
            executeTransfer()
        }

        // TRX闪付按钮
        binding.btnFlashPay.setDebouncedClick(debounceDelayMs = 1000) {
            executeFlashPay()
        }

        // 设置toolbar菜单
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_lock -> {
                    val app = com.trxsafe.payment.TrxSafeApplication.getInstance(this)
                    app.appLockManager.lock()
                    startActivity(Intent(this, LockActivity::class.java))
                    Toast.makeText(this, "应用已锁定", Toast.LENGTH_SHORT).show()
                    true
                }
                else -> false
            }
        }
        // 启用菜单
        binding.toolbar.inflateMenu(R.menu.menu_main)
    }
    
    /**
     * 初始化主界面状态
     */
    private fun refreshWalletState() {
        lifecycleScope.launch {
            try {
                val repository = com.trxsafe.payment.settings.SettingsRepository.getInstance(this@MainActivity)
                val config = repository.loadConfig()

                // 首先检查配置是否完整
                if (!config.isConfigComplete()) {
                    // 配置不完整，显示设置提示界面
                    showSetupRequiredView()
                    return@launch
                }

                // 配置完整，显示正常的功能界面
                binding.layoutWalletInfo.visibility = View.VISIBLE
                binding.layoutSetupRequired.visibility = View.GONE

                // 检查钱包状态
                if (walletManager.hasWallet()) {
                    // 确保使用当前选择的钱包
                    val currentWallet = walletManager.getCurrentWallet()
                    if (currentWallet != null) {
                        // 更新UI显示当前钱包信息
                        binding.tvCurrentWalletName.text = currentWallet.name
                        binding.tvCurrentWalletAddress.text = currentWallet.address

                        // 根据钱包类型更新转账功能
                        val walletType = when (currentWallet.type) {
                            com.trxsafe.payment.wallet.WalletType.PRIVATE_KEY -> WalletType.PRIVATE_KEY
                            com.trxsafe.payment.wallet.WalletType.WATCH_ONLY -> WalletType.WATCH_ONLY
                            com.trxsafe.payment.wallet.WalletType.HARDWARE -> WalletType.HARDWARE
                        }
                        updateTransferFunctions(walletType)
                    }

                    // 异步获取余额
                    loadWalletBalance()
                } else {
                    // 无钱包时显示默认状态
                    binding.tvBalance.text = "请先绑定钱包"
                    binding.tvCurrentWalletName.text = "未选择钱包"
                    binding.tvCurrentWalletAddress.text = "TXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"

                    // 启用所有转账功能（因为没有钱包，所以按钮应该是可用的，用于创建新钱包）
                    updateTransferFunctions(WalletType.PRIVATE_KEY)
                }

                // 检查设置状态
                checkSettingsStatus()
            } catch (e: Exception) {
                // 如果创建失败，显示错误并刷新界面
                showError("创建失败：${e.message}")
                refreshWalletState()
            }
        }
    }

    /**
     * 显示设置提示界面
     */
    private fun showSetupRequiredView() {
        binding.layoutWalletInfo.visibility = View.GONE
        binding.layoutSetupRequired.visibility = View.VISIBLE

        // 设置按钮点击事件
        binding.btnGoToSettings.setDebouncedClick(debounceDelayMs = 1000) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 钱包管理按钮点击事件
        binding.btnManageWalletSetup.setDebouncedClick(debounceDelayMs = 1000) {
            showWalletManagementDialog()
        }
    }

    /**
     * 显示钱包管理对话框
     */
    private fun showWalletManagementDialog() {
        val options = arrayOf("导入观察钱包", "创建新钱包", "导入私钥钱包")
        AlertDialog.Builder(this)
            .setTitle("钱包管理")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showImportWatchWalletDialog()
                    1 -> showCreateWalletDialog()
                    2 -> showImportWalletDialog()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 加载钱包余额
     */
    private fun loadWalletBalance(retryCount: Int = 0) {
        val repository = com.trxsafe.payment.settings.SettingsRepository.getInstance(this)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.Main) {
                    binding.tvBalance.text = "加载中..."
                    binding.tvBalanceUSD.visibility = View.GONE
                    binding.tvUsdtBalance.text = ""
                    binding.btnRefreshBalance.isEnabled = false
                }

                val config = repository.loadConfig()
                val httpClient = com.trxsafe.payment.network.TronHttpClient(config.nodeUrl)
                val currentAddress = walletManager.getAddress() ?: run {
                    withContext(Dispatchers.Main) {
                        binding.tvBalance.text = "请先绑定钱包"
                        binding.tvBalanceUSD.visibility = View.GONE
                        binding.btnRefreshBalance.isEnabled = true
                    }
                    return@launch
                }

                val balances = httpClient.getAccountBalances(currentAddress)

                withContext(Dispatchers.Main) {
                    if (balances.trxBalance == -1L) {
                        if (retryCount < 3) {
                            binding.tvBalance.text = "重试中 (${retryCount + 1}/3)..."
                            kotlinx.coroutines.delay(1000)
                            loadWalletBalance(retryCount + 1)
                        } else {
                            binding.tvBalance.text = "查询失败"
                            binding.tvBalanceUSD.visibility = View.GONE
                            binding.tvUsdtBalance.text = ""
                            binding.btnRefreshBalance.isEnabled = true
                        }
                    } else {
                        val balanceTrxStr = com.trxsafe.payment.utils.AmountUtils.sunToTrx(balances.trxBalance)
                        binding.tvBalance.text = String.format("%.6f", balanceTrxStr.toDouble()) + " TRX"
                        binding.tvUsdtBalance.text = String.format("%.6f", balances.usdtBalance) + " USDT"
                        binding.btnRefreshBalance.isEnabled = true
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (retryCount < 3) {
                        binding.tvBalance.text = "重试中 (${retryCount + 1}/3)..."
                        kotlinx.coroutines.delay(1000)
                        loadWalletBalance(retryCount + 1)
                    } else {
                        binding.tvBalance.text = "无法获取"
                        binding.tvBalanceUSD.visibility = View.GONE
                        binding.tvUsdtBalance.text = ""
                        binding.btnRefreshBalance.isEnabled = true
                    }
                }
            }
        }
    }

    /**
     * 检查设置状态并加载闪付数据
     */
    private fun checkSettingsStatus() {
        lifecycleScope.launch {
            try {
                val repository = com.trxsafe.payment.settings.SettingsRepository.getInstance(this@MainActivity)
                val config = repository.loadConfig()

                // 加载闪付数据到UI
                loadFlashPayData(config)

            } catch (e: Exception) {
                Log.e("MainActivity", "加载闪付数据失败：${e.message}")
            }
        }
    }

    /**
     * 加载闪付数据到UI
     */
    private fun loadFlashPayData(config: com.trxsafe.payment.settings.SettingsConfig) {
        // 设置收款地址
        binding.etFlashPayAddress.setText(config.sellerAddress)

        // 计算并设置支付金额（TRX）
        val amountTrx = com.trxsafe.payment.utils.AmountUtils.sunToTrx(config.pricePerUnitSun * config.multiplier)
        binding.etFlashPayAmount.setText(amountTrx)
    }

    /**
     * 执行TRX闪付转账
     */
    private fun executeFlashPay() {
        lifecycleScope.launch {
            try {
                val repository = com.trxsafe.payment.settings.SettingsRepository.getInstance(this@MainActivity)
                val config = repository.loadConfig()

                // 检查配置是否完整
                if (!config.isConfigComplete()) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("配置不完整")
                        .setMessage("请先在设置中配置收款地址和金额。")
                        .setPositiveButton("去设置") { _, _ ->
                            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                        }
                        .setNegativeButton("取消", null)
                        .show()
                    return@launch
                }

                // 检查本地钱包是否存在
                if (!walletManager.hasWallet()) {
                    showError("请先创建或导入本地钱包")
                    return@launch
                }

                // 获取收款地址和金额
                val toAddress = config.sellerAddress
                val amountSun = config.pricePerUnitSun * config.multiplier
                val amountTrx = com.trxsafe.payment.utils.AmountUtils.sunToTrx(amountSun)
                val amountTrxValue = amountSun / 1_000_000.0

                // 获取本地钱包地址
                val fromAddress = walletManager.getAddress()
                if (fromAddress == null) {
                    showError("未找到钱包地址")
                    return@launch
                }

                lifecycleScope.launch {
                    val httpClient = com.trxsafe.payment.network.TronHttpClient("https://api.trongrid.io")
                    
                    val resources = httpClient.getAccountResources(fromAddress)
                    var availableBandwidth = 0L
                    
                    if (resources != null) {
                        val netLimit = resources.get("freeNetLimit")?.asLong ?: resources.get("NetLimit")?.asLong ?: 0L
                        val netUsage = resources.get("freeNetUsed")?.asLong ?: resources.get("NetUsed")?.asLong ?: resources.get("freeNetUsage")?.asLong ?: resources.get("NetUsage")?.asLong ?: 0L
                        availableBandwidth = (netLimit - netUsage).coerceAtLeast(0)
                        android.util.Log.d("MainActivity", "闪付带宽信息: freeNetLimit=$netLimit, freeNetUsed=$netUsage, availableBandwidth=$availableBandwidth")
                    }
                    
                    val requiredBandwidth = httpClient.estimateTransferBandwidth()
                    val burnForBandwidthSun = httpClient.calculateBandwidthBurn(availableBandwidth)
                    val burnTrx = burnForBandwidthSun / 1_000_000f
                    val totalCost = amountTrxValue + burnTrx
                    
                    val balance = httpClient.getAccountBalance(fromAddress)
                    val balanceTrx = balance / 1_000_000f
                    val canAfford = balance >= ((amountTrxValue * 1_000_000).toLong() + burnForBandwidthSun)

                    val sb = StringBuilder()
                    val formattedAmount = com.trxsafe.payment.utils.AmountUtils.sunToTrx((amountTrxValue * 1_000_000).toLong())
                    val formattedBurn = com.trxsafe.payment.utils.AmountUtils.sunToTrx((burnTrx * 1_000_000).toLong())
                    val formattedTotal = com.trxsafe.payment.utils.AmountUtils.sunToTrx(((amountTrxValue + burnTrx) * 1_000_000).toLong())
                    val formattedBalance = com.trxsafe.payment.utils.AmountUtils.sunToTrx(balance)

                    sb.append("━━━━━━━━━━━━━━━\n")
                    sb.append("资源消耗详情\n\n")
                    sb.append("可用带宽：$availableBandwidth 点\n")
                    sb.append("需要带宽：$requiredBandwidth 点\n\n")
                    
                    if (burnForBandwidthSun > 0) {
                        sb.append("⚠ 带宽不足，需燃烧 $formattedBurn TRX\n")
                    } else {
                        sb.append("✓ 带宽充足，无需燃烧TRX\n")
                    }
                    
                    sb.append("\n━━━━━━━━━━━━━━━\n")
                    sb.append("转账详情\n\n")
                    sb.append("收款地址：$toAddress\n")
                    sb.append("转账金额：$formattedAmount TRX\n\n")
                    sb.append("总支出（含燃烧）：$formattedTotal TRX\n")
                    sb.append("├─ 转账金额：$formattedAmount TRX\n")
                    sb.append("└─ 燃烧金额：$formattedBurn TRX\n\n")
                    sb.append("账户余额：$formattedBalance TRX\n")
                    
                    if (canAfford) {
                        sb.append("\n✓ 余额充足，可以转账")
                    } else {
                        sb.append("\n✗ 余额不足，无法转账")
                    }

                    val dialogMessage = sb.toString()

                    if (!canAfford) {
                        showError("余额不足，无法完成闪付转账\n需要：$formattedTotal TRX\n余额：$formattedBalance TRX")
                        return@launch
                    }

                    lifecycleScope.launch {
                        performFlashPayTransfer(toAddress, amountSun, burnForBandwidthSun, fromAddress, availableBandwidth, requiredBandwidth, balance)
                    }
                }

            } catch (e: Exception) {
                showError("闪付转账失败：${e.message}")
            }
        }
    }

    /**
     * 执行闪付转账操作
     */
    private suspend fun performFlashPayTransfer(toAddress: String, amountSun: Long, burnSun: Long, fromAddress: String, availableBandwidth: Long = 0, requiredBandwidth: Long = 0, balanceSun: Long = 0) {
        try {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "正在处理闪付转账...", Toast.LENGTH_SHORT).show()
            }

            val transferConfig = com.trxsafe.payment.settings.SettingsConfig(
                sellerAddress = toAddress,
                pricePerUnitSun = amountSun,
                multiplier = 1,
                nodeUrl = "https://api.trongrid.io"
            )

            val transactionBuilder = com.trxsafe.payment.transaction.TransactionBuilder()
            val transaction = transactionBuilder.buildTransferTransaction(
                fromAddress = fromAddress,
                config = transferConfig,
                httpClient = com.trxsafe.payment.network.TronHttpClient("https://api.trongrid.io")
            )

            val dialog = com.trxsafe.payment.ui.dialog.SignatureConfirmDialog(
                        context = this@MainActivity,
                        transaction = transaction,
                        toAddress = toAddress,
                        amountSun = amountSun,
                        burnSun = burnSun,
                        availableBandwidth = availableBandwidth,
                        requiredBandwidth = requiredBandwidth,
                        balanceSun = balanceSun,
                        onSignComplete = { _ ->
                            Toast.makeText(this@MainActivity, "闪付转账已提交", Toast.LENGTH_SHORT).show()
                        },
                        onSignError = { errorMessage ->
                            showError(errorMessage)
                        },
                        onCancel = {
                        }
                    )
            dialog.show()

        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                showError("闪付转账失败：${e.message}")
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
            walletManager.createWallet()
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
    
    /**
     * 开始付款流程
     */
    private fun startPaymentProcess() {
        lifecycleScope.launch {
            try {
                val repository = com.trxsafe.payment.settings.SettingsRepository.getInstance(this@MainActivity)
                val config = repository.loadConfig()

                // 检查支付参数是否配置完整
                if (!config.isConfigComplete()) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("配置不完整")
                        .setMessage("请先在设置中配置收款地址、单价和倍率。")
                        .setPositiveButton("去设置") { _, _ ->
                            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                        }
                        .setNegativeButton("取消", null)
                        .show()
                    return@launch
                }

                // 检查本地钱包是否存在
                if (!walletManager.hasWallet()) {
                    showError("请先创建或导入本地钱包")
                    return@launch
                }

                // 显示付款确认对话框
                showPaymentConfirmationDialog(config)

            } catch (e: Exception) {
                showError("启动付款流程失败：${e.message}")
            }
        }
    }

    /**
     * 显示付款确认对话框
     */
    private fun showPaymentConfirmationDialog(config: com.trxsafe.payment.settings.SettingsConfig) {
        val amountTrx = com.trxsafe.payment.utils.AmountUtils.sunToTrx(config.pricePerUnitSun * config.multiplier)

        AlertDialog.Builder(this)
            .setTitle("确认付款")
            .setMessage("您将向以下地址付款：\n\n收款地址：${config.sellerAddress}\n付款金额：$amountTrx TRX\n\n确认后将连接钱包进行签名和付款。")
            .setPositiveButton("确认付款") { _, _ ->
                connectWallet()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 连接WalletConnect钱包
     */
    private fun connectWallet() {
        try {
            getWalletConnectManager().connectWallet()
        } catch (e: Exception) {
            showError("启动连接界面失败：${e.message}")
        }
    }

    /**
     * 观察WalletConnect状态变化
     */
    private fun observeWalletConnectState() {
        val manager = walletConnectManager ?: return
        
        manager.connectionState.observe(this) { state ->
            when (state) {
                is com.trxsafe.payment.wallet.WalletConnectManager.ConnectionState.Connected -> {
                    Toast.makeText(this, "钱包已连接", Toast.LENGTH_SHORT).show()
                    Log.d("MainActivity", "WalletConnect连接成功，地址: ${manager.getConnectedAddress()}")
                    triggerPaymentAfterConnect()
                }
                is com.trxsafe.payment.wallet.WalletConnectManager.ConnectionState.Connecting -> {
                    Toast.makeText(this, "正在连接钱包...", Toast.LENGTH_SHORT).show()
                    Log.d("MainActivity", "WalletConnect正在连接")
                }
                is com.trxsafe.payment.wallet.WalletConnectManager.ConnectionState.Disconnected -> {
                    Toast.makeText(this, "钱包已断开", Toast.LENGTH_SHORT).show()
                    Log.d("MainActivity", "WalletConnect已断开连接")
                }
                is com.trxsafe.payment.wallet.WalletConnectManager.ConnectionState.Error -> {
                    showError("钱包连接失败：${state.message}")
                    Log.e("MainActivity", "WalletConnect连接失败: ${state.message}")
                }
            }
        }

        manager.signResult.observe(this) { result ->
            when (result) {
                is com.trxsafe.payment.wallet.WalletConnectManager.SignResult.Success -> {
                    Toast.makeText(this, "交易签名成功", Toast.LENGTH_SHORT).show()
                    Log.d("MainActivity", "WalletConnect签名成功: ${result.signature.take(10)}...")
                    // 处理签名成功的交易
                    handleSignedTransaction(result.signature)
                }
                is com.trxsafe.payment.wallet.WalletConnectManager.SignResult.Error -> {
                    showError("签名失败：${result.message}")
                    Log.e("MainActivity", "WalletConnect签名失败: ${result.message}")
                }
                is com.trxsafe.payment.wallet.WalletConnectManager.SignResult.Pending -> {
                    Log.d("MainActivity", "WalletConnect签名请求待处理")
                }
            }
        }
    }

    /**
     * 连接成功后触发支付
     */
    private fun triggerPaymentAfterConnect() {
        lifecycleScope.launch {
            try {
                val repository = com.trxsafe.payment.settings.SettingsRepository.getInstance(this@MainActivity)
                val config = repository.loadConfig()

                if (config.isPriceLocked && config.sellerAddress.isNotEmpty()) {
                    // 使用当前倍率计算支付金额
                    val amountTrx = com.trxsafe.payment.utils.AmountUtils.sunToTrx(config.pricePerUnitSun)

                    // 获取当前本地钱包地址作为发送方
                    val fromAddress = walletManager.getAddress() ?: run {
                        showError("未找到本地钱包地址")
                        return@launch
                    }

                    // 发送支付请求给连接的钱包
                    getWalletConnectManager().sendPaymentRequest(
                        toAddress = config.sellerAddress,
                        amount = amountTrx,
                        fromAddress = fromAddress
                    )

                    Toast.makeText(this@MainActivity, "已发送支付请求，请在钱包中确认", Toast.LENGTH_LONG).show()
                } else {
                    showError("支付参数未配置，请先在设置中配置价格和地址")
                }
            } catch (e: Exception) {
                showError("支付请求失败：${e.message}")
            }
        }
    }

    /**
     * 处理已签名的交易
     */
    private fun handleSignedTransaction(signature: String) {
        lifecycleScope.launch {
            try {
                Toast.makeText(this@MainActivity, "正在广播交易...", Toast.LENGTH_SHORT).show()

                val repository = com.trxsafe.payment.settings.SettingsRepository.getInstance(this@MainActivity)
                val config = repository.loadConfig()

                // 解析签名并构造完整交易
                val signedTransaction = constructSignedTransaction(signature, config) ?: run {
                    showError("交易构造失败")
                    return@launch
                }

                // 使用HTTP客户端广播交易
                val httpClient = com.trxsafe.payment.network.TronHttpClient(config.nodeUrl)
                val broadcaster = com.trxsafe.payment.broadcast.TransactionBroadcaster(
                    this@MainActivity,
                    null,
                    httpClient
                )

                // 广播交易
                val broadcastResult = broadcaster.broadcast(signedTransaction, repository.loadConfig())

                when (broadcastResult) {
                    is com.trxsafe.payment.broadcast.BroadcastResult.Success -> {
                        Toast.makeText(this@MainActivity, "交易广播成功！\nTXID: ${broadcastResult.txid}", Toast.LENGTH_LONG).show()

                        // 保存交易记录
                        saveTransactionRecord(broadcastResult.txid)

                        // 刷新界面
                        refreshWalletState()
                    }
                    is com.trxsafe.payment.broadcast.BroadcastResult.Failure -> {
                        showError("交易广播失败：${broadcastResult.message}")
                    }
                }

            } catch (e: Exception) {
                showError("处理签名结果失败：${e.message}")
            }
        }
    }

    /**
     * 构造已签名的交易
     */
    private suspend fun constructSignedTransaction(signature: String, config: com.trxsafe.payment.settings.SettingsConfig): org.tron.trident.proto.Chain.Transaction? {
        return try {
            // 获取本地钱包地址
            val fromAddress = walletManager.getAddress() ?: return null

            // 获取HTTP客户端用于TAPOS
            val repository = com.trxsafe.payment.settings.SettingsRepository.getInstance(this@MainActivity)
            val settingsConfig = repository.loadConfig()
            val httpClient = com.trxsafe.payment.network.TronHttpClient(settingsConfig.nodeUrl)

            // 使用TransactionBuilder构造基础交易（带TAPOS）
            val transactionBuilder = com.trxsafe.payment.transaction.TransactionBuilder()
            val signatureBytes = signature.hexStringToByteArray()

            // 创建带签名的交易
            val unsignedTx = transactionBuilder.buildTransferTransaction(
                fromAddress = fromAddress,
                config = config,
                httpClient = httpClient
            )
            unsignedTx.toBuilder()
                .addSignature(com.google.protobuf.ByteString.copyFrom(signatureBytes))
                .build()

        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "构造签名交易失败: ${e.message}")
            null
        }
    }

    /**
     * 显示TRX转账界面
     */
    private fun showTransferInterface() {
        binding.layoutTransfer.visibility = View.VISIBLE
        // 滚动到转账区域
        binding.nestedScrollView.smoothScrollTo(0, binding.layoutTransfer.top)
    }

    /**
     * 执行TRX转账
     */
    private fun executeTransfer() {
        val toAddress = binding.etTransferAddress.text?.toString()?.trim() ?: ""
        val amountStr = binding.etTransferAmount.text?.toString()?.trim() ?: ""

        if (toAddress.isEmpty()) {
            showError("请输入收款地址")
            return
        }

        if (amountStr.isEmpty()) {
            showError("请输入转账金额")
            return
        }

        val amount = try {
            amountStr.toDouble()
        } catch (e: Exception) {
            showError("请输入有效的金额")
            return
        }

        if (amount <= 0) {
            showError("转账金额必须大于0")
            return
        }

        if (!AddressValidator.isValidTronAddress(toAddress)) {
            showError("请输入有效的TRON地址")
            return
        }

        lifecycleScope.launch {
            val fromAddress = walletManager.getAddress()
            if (fromAddress == null) {
                showError("未找到钱包地址")
                return@launch
            }

            val httpClient = com.trxsafe.payment.network.TronHttpClient("https://api.trongrid.io")

            val resources = httpClient.getAccountResources(fromAddress)
            var availableBandwidth = 0L

            if (resources != null) {
                val netLimit = resources.get("freeNetLimit")?.asLong ?: resources.get("NetLimit")?.asLong ?: 0L
                val netUsage = resources.get("freeNetUsed")?.asLong ?: resources.get("NetUsed")?.asLong ?: resources.get("freeNetUsage")?.asLong ?: resources.get("NetUsage")?.asLong ?: 0L
                availableBandwidth = (netLimit - netUsage).coerceAtLeast(0)
                android.util.Log.d("MainActivity", "带宽信息: freeNetLimit=$netLimit, freeNetUsed=$netUsage, availableBandwidth=$availableBandwidth")
            }

            val requiredBandwidth = httpClient.estimateTransferBandwidth()
            val burnForBandwidthSun = httpClient.calculateBandwidthBurn(availableBandwidth)
            val burnTrx = burnForBandwidthSun / 1_000_000f
            val totalCost = amount + burnTrx

            val balance = httpClient.getAccountBalance(fromAddress)
            val balanceTrx = balance / 1_000_000f
            val canAfford = balance >= ((amount * 1_000_000).toLong() + burnForBandwidthSun)

            if (!canAfford) {
                showError("余额不足，无法完成转账\n需要：${String.format("%.6f", totalCost)} TRX\n余额：${String.format("%.6f", balanceTrx)} TRX")
                return@launch
            }

            lifecycleScope.launch {
                performTransfer(
                    toAddress = toAddress,
                    amount = amount,
                    burnTrx = burnTrx.toDouble(),
                    availableBandwidth = availableBandwidth,
                    requiredBandwidth = requiredBandwidth,
                    balanceSun = balance
                )
            }
        }
    }

    /**
     * 执行转账操作
     */
    private fun performTransfer(
        toAddress: String,
        amount: Double,
        burnTrx: Double = 0.0,
        availableBandwidth: Long = 0,
        requiredBandwidth: Long = 0,
        balanceSun: Long = 0
    ) {
        lifecycleScope.launch {
            try {
                Toast.makeText(this@MainActivity, "正在处理转账...", Toast.LENGTH_SHORT).show()

                val fromAddress = walletManager.getAddress() ?: run {
                    showError("未找到钱包地址")
                    return@launch
                }

                val amountSun = (amount * 1_000_000).toLong()

                val transferConfig = com.trxsafe.payment.settings.SettingsConfig(
                    sellerAddress = toAddress,
                    pricePerUnitSun = amountSun,
                    multiplier = 1,
                    nodeUrl = "https://api.trongrid.io"
                )

                val transactionBuilder = com.trxsafe.payment.transaction.TransactionBuilder()
                val transaction = transactionBuilder.buildTransferTransaction(
                    fromAddress = fromAddress,
                    config = transferConfig,
                    httpClient = com.trxsafe.payment.network.TronHttpClient("https://api.trongrid.io")
                )

                val dialog = com.trxsafe.payment.ui.dialog.SignatureConfirmDialog(
                    context = this@MainActivity,
                    transaction = transaction,
                    toAddress = toAddress,
                    amountSun = amountSun,
                    burnSun = (burnTrx * 1_000_000).toLong(),
                    availableBandwidth = availableBandwidth,
                    requiredBandwidth = requiredBandwidth,
                    balanceSun = balanceSun,
                    onSignComplete = { _ ->
                        binding.layoutTransfer.visibility = View.GONE
                        binding.etTransferAddress.text?.clear() ?: binding.etTransferAddress.setText("")
                        binding.etTransferAmount.text?.clear() ?: binding.etTransferAmount.setText("")
                    },
                    onSignError = { errorMessage ->
                        showError(errorMessage)
                    },
                    onCancel = {
                        binding.layoutTransfer.visibility = View.GONE
                        binding.etTransferAddress.text?.clear() ?: binding.etTransferAddress.setText("")
                        binding.etTransferAmount.text?.clear() ?: binding.etTransferAmount.setText("")
                    }
                )
                dialog.show()

            } catch (e: Exception) {
                showError("转账失败：${e.message}")
            }
        }
    }

    /**
     * 保存交易记录
     */
    private suspend fun saveTransactionRecord(txid: String) {
        try {
            val recorder = com.trxsafe.payment.broadcast.TransactionRecorder(this)

            // 计算交易金额
            val config = com.trxsafe.payment.settings.SettingsRepository.getInstance(this).loadConfig()
            val fromAddress = walletManager.getAddress() ?: ""
            val amountSun = config.pricePerUnitSun

            // 创建交易记录
            val record = com.trxsafe.payment.broadcast.TransactionRecord(
                txid = txid,
                fromAddress = fromAddress,
                toAddress = config.sellerAddress,
                amountSun = amountSun,
                timestamp = System.currentTimeMillis(),
                status = com.trxsafe.payment.broadcast.TransactionStatus.PENDING, // 等待确认
                feeSun = 0L, // 暂时设为0
                blockHeight = 0L,
                netUsage = 0,
                energyUsage = 0,
                memo = "WalletConnect支付"
            )

            recorder.saveRecord(record)
            android.util.Log.d("MainActivity", "交易记录已保存: $txid")

        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "保存交易记录失败: ${e.message}")
        }
    }

    /**
     * 显示钱包选择器
     */
    private fun showWalletSelector() {
        // 获取所有可用的钱包列表
        val wallets = getAvailableWallets()

        if (wallets.isEmpty()) {
            // 没有钱包，直接跳转到钱包管理页面
            startActivity(Intent(this, WalletManagementActivity::class.java))
            return
        }

        // 显示钱包选择对话框，包含钱包名称和地址
        val walletDetails = wallets.map { "${it.name}\n${it.address.take(10)}...${it.address.takeLast(8)}" }.toTypedArray()
        val currentWalletAddress = walletManager.getAddress()

        AlertDialog.Builder(this)
            .setTitle("选择钱包")
            .setSingleChoiceItems(walletDetails, wallets.indexOfFirst { it.address == currentWalletAddress }) { dialog, which ->
                val selectedWallet = wallets[which]
                switchToWallet(selectedWallet)
                dialog.dismiss()
            }
            .setPositiveButton("钱包管理") { _, _ ->
                // 跳转到钱包管理页面
                startActivity(Intent(this, WalletManagementActivity::class.java))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 获取所有可用的钱包
     */
    private fun getAvailableWallets(): List<WalletInfo> {
        // 从钱包管理器获取所有钱包信息
        val wallets = walletManager.getAllWallets()
        return wallets.map { walletData ->
            val walletType = when (walletData.type) {
                com.trxsafe.payment.wallet.WalletType.PRIVATE_KEY -> WalletType.PRIVATE_KEY
                com.trxsafe.payment.wallet.WalletType.WATCH_ONLY -> WalletType.WATCH_ONLY
                com.trxsafe.payment.wallet.WalletType.HARDWARE -> WalletType.HARDWARE
            }
            WalletInfo(walletData.name, walletData.address, walletType, walletData.id)
        }
    }

    /**
     * 切换到指定的钱包
     */
    private fun switchToWallet(wallet: WalletInfo) {
        try {
            // 找到对应的钱包ID并切换
            val allWallets = walletManager.getAllWallets()
            val targetWallet = allWallets.find { it.address == wallet.address }

            if (targetWallet != null) {
                walletManager.setCurrentWallet(targetWallet.id)

                // 更新UI显示
                binding.tvCurrentWalletName.text = wallet.name
                binding.tvCurrentWalletAddress.text = wallet.address

                // 根据钱包类型更新转账功能
                updateTransferFunctions(wallet.type)

                // 刷新钱包状态
                refreshWalletState()

                Toast.makeText(this, "已切换到钱包：${wallet.name}", Toast.LENGTH_SHORT).show()
            } else {
                showError("找不到指定的钱包")
            }

        } catch (e: Exception) {
            showError("钱包切换失败：${e.message}")
        }
    }

    /**
     * 根据钱包类型更新转账功能
     */
    private fun updateTransferFunctions(walletType: WalletType) {
        val isWatchOnly = walletType == WalletType.WATCH_ONLY

        // 启用/禁用转账相关按钮
        binding.btnTransfer.isEnabled = !isWatchOnly
        binding.btnExecuteTransfer.isEnabled = !isWatchOnly

        // 更新按钮外观
        binding.btnTransfer.alpha = if (isWatchOnly) 0.5f else 1.0f
        binding.btnExecuteTransfer.alpha = if (isWatchOnly) 0.5f else 1.0f

        // 显示提示信息
        if (isWatchOnly) {
            Toast.makeText(this, "观察钱包不支持转账操作", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 测试WalletConnect连接状态
     */
    private fun testWalletConnectConnection() {
        try {
            val manager = getWalletConnectManager()
            val status = manager.testConnection()
            val details = manager.getConnectionStatus()

            val message = """
                WalletConnect状态测试

                状态: $status

                详细信息:
                • 初始化: ${details["isInitialized"]}
                • 已连接: ${details["isConnected"]}
                • 连接地址: ${details["connectedAddress"]}
                • 项目ID: ${details["projectId"]}
            """.trimIndent()

            AlertDialog.Builder(this)
                .setTitle("WalletConnect状态")
                .setMessage(message)
                .setPositiveButton("确定", null)
                .show()

        } catch (e: Exception) {
            showError("测试失败: ${e.message}")
        }
    }

    private fun showError(message: String) {
        AlertDialog.Builder(this)
            .setTitle("错误")
            .setMessage(message)
            .setPositiveButton(R.string.confirm, null)
            .show()
    }
}

/**
 * 扩展函数：将十六进制字符串转换为字节数组
 */
private fun String.hexStringToByteArray(): ByteArray {
    val hexString = if (startsWith("0x")) substring(2) else this
    val len = hexString.length
    val data = ByteArray(len / 2)
    var i = 0
    while (i < len) {
        data[i / 2] = ((Character.digit(hexString[i], 16) shl 4) + Character.digit(hexString[i + 1], 16)).toByte()
        i += 2
    }
    return data
}

/**
 * 钱包信息数据类
 */
data class WalletInfo(
    val name: String,
    val address: String,
    val type: WalletType,
    val id: String = ""
)

/**
 * 钱包类型枚举
 */
enum class WalletType {
    PRIVATE_KEY,    // 私钥钱包
    WATCH_ONLY,     // 观察钱包
    HARDWARE        // 硬件钱包
}
