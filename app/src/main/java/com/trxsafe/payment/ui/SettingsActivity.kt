package com.trxsafe.payment.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.trxsafe.payment.R
import com.trxsafe.payment.databinding.ActivitySettingsBinding
import com.trxsafe.payment.settings.SettingsConfig
import com.trxsafe.payment.settings.SettingsRepository
import com.trxsafe.payment.settings.SettingsUiState
import com.trxsafe.payment.settings.SettingsViewModel
import com.trxsafe.payment.utils.AmountUtils
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 设置界面
 * 配置卖能量相关参数
 */
class SettingsActivity : BaseActivity() {
    
    private lateinit var viewModel: SettingsViewModel
    private lateinit var binding: ActivitySettingsBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // 初始化 ViewModel
        val repository = SettingsRepository(this)
        val factory = SettingsViewModelFactory(repository)
        viewModel = ViewModelProvider(this, factory)[SettingsViewModel::class.java]
        
        // 初始化界面
        initViews()
        
        // 观察状态变化
        observeStates()
    }
    
    /**
     * 初始化界面组件
     */
    private fun initViews() {
        // 设置地址输入监听
        binding.etSellerAddress.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val address = binding.etSellerAddress.text.toString().trim()
                if (address.isNotEmpty()) {
                    viewModel.updateSellerAddress(address)
                }
            }
        }

        // 从地址簿选取图标点击
        binding.layoutSellerAddress.setEndIconOnClickListener {
            showAddressBookSelectionDialog()
        }
        
        // 设置单价输入监听
        binding.etPrice.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val priceStr = binding.etPrice.text.toString().trim()
                if (priceStr.isNotEmpty()) {
                    viewModel.updatePrice(priceStr)
                }
            }
        }
        
        // 设置倍率输入监听
        binding.etMultiplier.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val multiplierStr = binding.etMultiplier.text.toString().trim()
                if (multiplierStr.isNotEmpty()) {
                    viewModel.updateMultiplier(multiplierStr)
                }
            }
        }
        
        // 实时更新总金额预览
        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateTotalAmountPreview()
            }
        }
        binding.etPrice.addTextChangedListener(textWatcher)
        binding.etMultiplier.addTextChangedListener(textWatcher)
        
        // 生物识别开关
        binding.switchBiometric.setOnCheckedChangeListener { _, isChecked ->
            // 如果用户尝试开启，进行生物识别验证
            if (isChecked && !viewModel.configState.value.isBiometricEnabled) {
                // 先临时设回 false，等待验证通过
                binding.switchBiometric.isChecked = false
                performBiometricAuthForEnable()
            } else if (!isChecked && viewModel.configState.value.isBiometricEnabled) {
                 // 关闭也需要验证？通常不需要，或者看安全级别。这里简单处理，关闭无需验证
                 viewModel.toggleBiometricEnabled(false)
            }
        }
        
        // App 锁定开关
        binding.switchAppLock.setOnCheckedChangeListener { _, isChecked ->
            val app = com.trxsafe.payment.TrxSafeApplication.getInstance(this)
            app.appLockManager.isLockEnabled = isChecked
            
            // 显示/隐藏超时设置
            binding.layoutLockTimeout.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        
        // 超时时间选择器
        setupLockTimeoutSpinner()
        
        // 节点选择器
        setupNodeSpinner()
        
        // 保存配置按钮
        binding.btnSaveConfig.setOnClickListener {
            // 强制触发一次更新，确保焦点在输入框时点击保存也能生效
            binding.etSellerAddress.clearFocus()
            binding.etPrice.clearFocus()
            binding.etMultiplier.clearFocus()
            
            // 稍作延迟等待 ViewModel 更新完成
            binding.root.postDelayed({
                viewModel.saveConfig()
            }, 100)
        }
        
        // 锁定/解锁单价按钮
        binding.btnTogglePriceLock.setOnClickListener {
            if (viewModel.configState.value.isPriceLocked) {
                viewModel.unlockPrice()
            } else {
                viewModel.lockPrice()
            }
        }
        
        // 查看私钥按钮
        binding.btnViewPrivateKey.setOnClickListener {
            handleViewPrivateKey()
        }
    }
    
    /**
     * 处理查看私钥请求
     */
    private fun handleViewPrivateKey() {
        val walletManager = com.trxsafe.payment.wallet.WalletManager(this)
        if (!walletManager.hasWallet() || walletManager.isWatchOnly()) {
            Toast.makeText(this, "当前无私钥可查看", Toast.LENGTH_SHORT).show()
            return
        }
        
        // 高强度生物识别验证
        val authManager = com.trxsafe.payment.security.BiometricAuthManager(this)
        authManager.authenticate(
            title = "验证身份以查看私钥",
            subtitle = "私钥是您的资产唯一控制权，请确保周围安全",
            onSuccess = {
                try {
                    val privateKey = walletManager.getPrivateKeyForBackup()
                    showPrivateKeyDialog(privateKey)
                } catch (e: Exception) {
                    Toast.makeText(this, "获取失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            },
            onError = { error ->
                Toast.makeText(this, "认证失败：$error", Toast.LENGTH_SHORT).show()
            }
        )
    }

    /**
     * 显示私钥
     */
    private fun showPrivateKeyDialog(privateKey: String) {
        val message = "您的私钥：\n\n$privateKey\n\n请绝对禁止将此信息发送给任何人！建议离线抄写在纸上。"
        
        AlertDialog.Builder(this)
            .setTitle("安全提示：私钥")
            .setMessage(message)
            .setPositiveButton("复制到剪贴板") { _, _ ->
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("TRX Private Key", privateKey)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "私钥已复制，请尽快粘贴到安全处并清除剪贴板", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }
    
    /**
     * 显示地址簿选择对话框
     */
    private fun showAddressBookSelectionDialog() {
        lifecycleScope.launch {
            val database = com.trxsafe.payment.data.AppDatabase.getInstance(this@SettingsActivity)
            val addressBookRepo = com.trxsafe.payment.data.repository.AddressBookRepository(database.addressBookDao())
            
            // 获取所有地址
            addressBookRepo.getAllAddresses().collectLatest { list ->
                if (list.isEmpty()) {
                    Toast.makeText(this@SettingsActivity, "地址簿为空", Toast.LENGTH_SHORT).show()
                    return@collectLatest
                }

                val names = list.map { "${it.name} (${it.address.take(6)}...${it.address.takeLast(6)})" }.toTypedArray()
                
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("选择收款地址")
                    .setItems(names) { _, which ->
                        val selected = list[which]
                        binding.etSellerAddress.setText(selected.address)
                        viewModel.updateSellerAddress(selected.address)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }
    
    private fun setupLockTimeoutSpinner() {
        val app = com.trxsafe.payment.TrxSafeApplication.getInstance(this)
        val appLockManager = app.appLockManager
        
        val timeoutOptions = arrayOf(
            "立即锁定",
            "30 秒",
            "1 分钟",
            "5 分钟"
        )
        
        val adapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            timeoutOptions
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerLockTimeout.adapter = adapter
        
        // 设置当前选中项
        val currentTimeout = appLockManager.lockTimeout
        val currentIndex = when (currentTimeout) {
            com.trxsafe.payment.security.AppLockManager.TIMEOUT_IMMEDIATELY -> 0
            com.trxsafe.payment.security.AppLockManager.TIMEOUT_30_SECONDS -> 1
            com.trxsafe.payment.security.AppLockManager.TIMEOUT_1_MINUTE -> 2
            com.trxsafe.payment.security.AppLockManager.TIMEOUT_5_MINUTES -> 3
            else -> 2
        }
        binding.spinnerLockTimeout.setSelection(currentIndex)
        
        // 监听选择变化
        binding.spinnerLockTimeout.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newTimeout = when (position) {
                    0 -> com.trxsafe.payment.security.AppLockManager.TIMEOUT_IMMEDIATELY
                    1 -> com.trxsafe.payment.security.AppLockManager.TIMEOUT_30_SECONDS
                    2 -> com.trxsafe.payment.security.AppLockManager.TIMEOUT_1_MINUTE
                    3 -> com.trxsafe.payment.security.AppLockManager.TIMEOUT_5_MINUTES
                    else -> com.trxsafe.payment.security.AppLockManager.TIMEOUT_1_MINUTE
                }
                appLockManager.lockTimeout = newTimeout
            }
            
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {
                // Do nothing
            }
        }
        
        // 初始化显示状态
        binding.switchAppLock.isChecked = appLockManager.isLockEnabled
        binding.layoutLockTimeout.visibility = if (appLockManager.isLockEnabled) View.VISIBLE else View.GONE
    }
    
    private fun setupNodeSpinner() {
        val nodes = com.trxsafe.payment.settings.NodeConfig.getAllDefaults()
        val nodeNames = nodes.map { it.name }.toTypedArray()
        
        val adapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            nodeNames
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerNodes.adapter = adapter
        
        // 监听选择
        binding.spinnerNodes.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedNode = nodes[position]
                viewModel.updateNodeUrl(selectedNode.grpcUrl)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        
        // 设置初始选中
        val currentUrl = viewModel.configState.value.nodeUrl
        val initialIndex = nodes.indexOfFirst { it.grpcUrl == currentUrl }.let { if (it == -1) 0 else it }
        binding.spinnerNodes.setSelection(initialIndex)
    }
    
    private fun performBiometricAuthForEnable() {
        val authManager = com.trxsafe.payment.security.BiometricAuthManager(this)
        authManager.authenticate(
            onSuccess = {
                viewModel.toggleBiometricEnabled(true)
                binding.switchBiometric.isChecked = true
                Toast.makeText(this, "生物识别已启用", Toast.LENGTH_SHORT).show()
            },
            onError = { error ->
                Toast.makeText(this, "验证失败：$error", Toast.LENGTH_SHORT).show()
                binding.switchBiometric.isChecked = false
            }
        )
    }
    
    /**
     * 本地计算并更新总金额预览
     */
    private fun updateTotalAmountPreview() {
        try {
            val priceStr = binding.etPrice.text.toString().trim()
            val multiplierStr = binding.etMultiplier.text.toString().trim()
            
            // 使用 AmountUtils 安全转换
            val priceSun = try {
                AmountUtils.trxToSun(if (priceStr.isEmpty()) "0" else priceStr)
            } catch (e: Exception) {
                0L
            }
            
            val multiplier = multiplierStr.toIntOrNull() ?: 0
            val totalSun = priceSun * multiplier
            
            // 使用 sunToTrx 避免重复 "TRX" 单位
            val totalTrxStr = AmountUtils.sunToTrx(totalSun)
            binding.tvTotalAmount.text = getString(R.string.settings_total_amount, totalTrxStr)
        } catch (e: Exception) {
            binding.tvTotalAmount.text = getString(R.string.settings_total_amount, "0")
        }
    }
    
    /**
     * 观察状态变化
     */
    private fun observeStates() {
        // 观察配置状态
        lifecycleScope.launch {
            viewModel.configState.collectLatest { config ->
                updateUiWithConfig(config)
            }
        }
        
        // 观察 UI 状态
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                handleUiState(state)
            }
        }
    }
    
    /**
     * 更新界面显示配置
     */
    private fun updateUiWithConfig(config: SettingsConfig) {
        // 更新地址（仅当内容不同且没有焦点时更新，防止光标跳动）
        if (binding.etSellerAddress.text.toString() != config.sellerAddress && !binding.etSellerAddress.hasFocus()) {
            binding.etSellerAddress.setText(config.sellerAddress)
        }
        
        // 更新单价
        if (config.pricePerUnitSun > 0) {
            val priceTrx = AmountUtils.sunToTrx(config.pricePerUnitSun)
            // 简单的字符串比较可能因为格式化问题不准确，但对于单价通常足够
            if (binding.etPrice.text.toString() != priceTrx && !binding.etPrice.hasFocus()) {
                binding.etPrice.setText(priceTrx)
            }
        }
        
        // 更新倍率
        if (config.multiplier > 0) {
            val multiplierStr = config.multiplier.toString()
            if (binding.etMultiplier.text.toString() != multiplierStr && !binding.etMultiplier.hasFocus()) {
                binding.etMultiplier.setText(multiplierStr)
            }
        }
        
        // 更新锁定状态
        binding.etPrice.isEnabled = !config.isPriceLocked
        
        if (config.isPriceLocked) {
            binding.btnTogglePriceLock.text = getString(R.string.settings_unlock_price)
            binding.tvPriceLockedHint.visibility = View.VISIBLE
        } else {
            binding.btnTogglePriceLock.text = getString(R.string.settings_lock_price)
            binding.tvPriceLockedHint.visibility = View.GONE
        }
        
        // 更新生物识别开关 (防止循环触发)
        if (binding.switchBiometric.isChecked != config.isBiometricEnabled) {
             // 临时移除监听器以避免死循环? 
             // 实际上 setChecked 会触发 Listener。
             // 简单的做法是：如果不一致且不在交互中，才设置。
             // 由于我们在 Listener 里有逻辑判断 (isChecked && !enabled)，所以直接设置问题不大，但要在 Listener 里小心。
             // 更好的做法是 temporarily detach listener。
             binding.switchBiometric.setOnCheckedChangeListener(null)
             binding.switchBiometric.isChecked = config.isBiometricEnabled
             binding.switchBiometric.setOnCheckedChangeListener { _, isChecked ->
                 if (isChecked && !viewModel.configState.value.isBiometricEnabled) {
                     binding.switchBiometric.isChecked = false
                     performBiometricAuthForEnable()
                 } else if (!isChecked && viewModel.configState.value.isBiometricEnabled) {
                     viewModel.toggleBiometricEnabled(false)
                 }
             }
        }
        
        // 更新总金额
        updateTotalAmountPreview()
    }
    
    /**
     * 处理 UI 状态
     */
    private fun handleUiState(state: SettingsUiState) {
        when (state) {
            is SettingsUiState.Idle -> {
                // 空闲状态
            }
            
            is SettingsUiState.Success -> {
                Toast.makeText(this, state.message, Toast.LENGTH_SHORT).show()
                viewModel.resetUiState()
            }
            
            is SettingsUiState.Error -> {
                Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
                viewModel.resetUiState()
            }
            
            is SettingsUiState.RequireAddressConfirmation -> {
                showAddressConfirmationDialog(state.address)
            }
            
            is SettingsUiState.RequirePriceConfirmation -> {
                showPriceConfirmationDialog(state.priceSun, state.warningMessage)
            }
        }
    }
    
    /**
     * 显示地址二次确认对话框
     */
    private fun showAddressConfirmationDialog(address: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_confirm_address_title)
            .setMessage(getString(R.string.settings_confirm_address_message, address))
            .setPositiveButton(R.string.confirm) { _, _ ->
                viewModel.confirmSellerAddress(address)
            }
            .setNegativeButton(R.string.cancel) { dialog, _ ->
                dialog.dismiss()
                viewModel.resetUiState()
            }
            .setCancelable(false)
            .show()
    }
    
    /**
     * 显示单价警告确认对话框
     */
    private fun showPriceConfirmationDialog(priceSun: Long, warningMessage: String) {
        val priceTrx = AmountUtils.sunToTrx(priceSun)
        
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_price_warning_title)
            .setMessage("$warningMessage\n\n${getString(R.string.settings_price_warning_message, priceTrx)}")
            .setPositiveButton(R.string.confirm) { _, _ ->
                viewModel.confirmPrice(priceSun)
            }
            .setNegativeButton(R.string.cancel) { dialog, _ ->
                dialog.dismiss()
                viewModel.resetUiState()
            }
            .setCancelable(false)
            .show()
    }
}

/**
 * SettingsViewModel 工厂类
 */
class SettingsViewModelFactory(
    private val repository: SettingsRepository
) : ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SettingsViewModel(repository = repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

