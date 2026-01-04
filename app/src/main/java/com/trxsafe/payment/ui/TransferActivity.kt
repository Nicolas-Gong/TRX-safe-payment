package com.trxsafe.payment.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.trxsafe.payment.R
import com.trxsafe.payment.TrxSafeApplication
import com.trxsafe.payment.data.AppDatabase
import com.trxsafe.payment.data.entity.AddressBook
import com.trxsafe.payment.data.repository.AddressBookRepository
import com.trxsafe.payment.databinding.ActivityTransferBinding
import com.trxsafe.payment.network.TronHttpClient
import com.trxsafe.payment.utils.setDebouncedClick
import com.trxsafe.payment.service.TransferService
import com.trxsafe.payment.validation.AddressValidator
import com.trxsafe.payment.wallet.WalletManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * TRX转账界面
 * 支持选择地址簿地址或手动输入地址和金额
 */
class TransferActivity : BaseActivity() {

    private lateinit var binding: ActivityTransferBinding
    private lateinit var addressBookRepository: AddressBookRepository
    private lateinit var transferService: TransferService
    private lateinit var walletManager: WalletManager

    // 地址簿数据
    private val addressBook = mutableListOf<AddressBook>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTransferBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化地址簿仓库
        val database = AppDatabase.getInstance(this)
        addressBookRepository = AddressBookRepository(database.addressBookDao())

        // 初始化转账服务
        transferService = TransferService(this)

        // 初始化钱包管理器
        walletManager = WalletManager(this)

        initViews()
        setupAddressBook()
    }
    
    override fun onResume() {
        super.onResume()
    }

    private fun initViews() {
        // 返回按钮
        binding.btnBack.setDebouncedClick(debounceDelayMs = 1000) {
            finish()
        }

        // 地址簿选择
        binding.btnSelectFromAddressBook.setDebouncedClick(debounceDelayMs = 1000) {
            showAddressBookDialog()
        }

        // 确认转账按钮
        binding.btnConfirmTransfer.setDebouncedClick(debounceDelayMs = 1000) {
            executeTransfer()
        }

        // 地址输入框变化监听
        binding.etRecipientAddress.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                validateInputs()
            }
        })

        // 金额输入框变化监听
        binding.etTransferAmount.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                validateInputs()
            }
        })
    }

    /**
     * 设置地址簿
     */
    private fun setupAddressBook() {
        // 从数据库加载地址簿数据
        lifecycleScope.launch {
            addressBookRepository.getAllAddresses().collectLatest { addresses ->
                addressBook.clear()
                addressBook.addAll(addresses)
            }
        }
    }

    /**
     * 显示地址簿选择对话框
     */
    private fun showAddressBookDialog() {
        if (addressBook.isEmpty()) {
            Toast.makeText(this, "地址簿为空，请先在地址簿页面添加地址", Toast.LENGTH_SHORT).show()
            return
        }

        val addressNames = addressBook.map { "${it.name} - ${it.address.take(10)}..." }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("选择收款地址")
            .setItems(addressNames) { _, which ->
                val selectedAddress = addressBook[which]
                binding.etRecipientAddress.setText(selectedAddress.address)
                Toast.makeText(this, "已选择：${selectedAddress.name}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 验证输入
     */
    private fun validateInputs() {
        val address = binding.etRecipientAddress.text?.toString()?.trim() ?: ""
        val amountStr = binding.etTransferAmount.text?.toString()?.trim() ?: ""

        val isAddressValid = address.isNotEmpty() && AddressValidator.isValidTronAddress(address)
        val isAmountValid = amountStr.isNotEmpty() && amountStr.toDoubleOrNull()?.let { it > 0 } == true

        binding.btnConfirmTransfer.isEnabled = isAddressValid && isAmountValid

        // 显示验证状态
        binding.etRecipientAddress.error = if (!isAddressValid && address.isNotEmpty()) "无效的TRON地址" else null
        binding.etTransferAmount.error = if (!isAmountValid && amountStr.isNotEmpty()) "请输入有效金额" else null
    }

    /**
     * 执行转账
     */
    private fun executeTransfer() {
        val toAddress = binding.etRecipientAddress.text?.toString()?.trim() ?: ""
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

        AddressValidator.validateAndNormalize(toAddress)
            .onFailure { showError("请输入有效的TRON地址") }
            ?: return

        calculateAndShowTransferFee(toAddress, amount)
    }

    private fun calculateAndShowTransferFee(toAddress: String, amount: Double) {
        binding.btnConfirmTransfer.isEnabled = false
        binding.btnConfirmTransfer.text = "准备中..."

        lifecycleScope.launch {
            try {
                val fromAddress = walletManager.getAddress()

                if (fromAddress == null) {
                    showError("未找到钱包地址")
                    binding.btnConfirmTransfer.isEnabled = true
                    binding.btnConfirmTransfer.text = "立即转账"
                    return@launch
                }

                val httpClient = TronHttpClient("https://api.trongrid.io")

                val resources = httpClient.getAccountResources(fromAddress)
                var availableBandwidth = 0L

                if (resources != null) {
                    val netLimit = resources.get("freeNetLimit")?.asLong ?: resources.get("NetLimit")?.asLong ?: 0L
                    val netUsage = resources.get("freeNetUsed")?.asLong ?: resources.get("NetUsed")?.asLong ?: resources.get("freeNetUsage")?.asLong ?: resources.get("NetUsage")?.asLong ?: 0L
                    availableBandwidth = (netLimit - netUsage).coerceAtLeast(0)
                    android.util.Log.d("TransferActivity", "转账带宽信息: freeNetLimit=$netLimit, freeNetUsed=$netUsage, availableBandwidth=$availableBandwidth")
                }

                val requiredBandwidth = httpClient.estimateTransferBandwidth()
                val burnForBandwidthSun = httpClient.calculateBandwidthBurn(availableBandwidth)
                val burnTrx = burnForBandwidthSun / 1_000_000.0

                val balance = httpClient.getAccountBalance(fromAddress)
                val canAfford = balance >= ((amount * 1_000_000).toLong() + burnForBandwidthSun)

                withContext(Dispatchers.Main) {
                    if (!canAfford) {
                        val balanceTrx = balance / 1_000_000.0
                        val totalCost = amount + burnTrx
                        showError("余额不足，无法完成转账\n需要：${String.format("%.6f", totalCost)} TRX\n余额：${String.format("%.6f", balanceTrx)} TRX")
                        binding.btnConfirmTransfer.isEnabled = true
                        binding.btnConfirmTransfer.text = "立即转账"
                        return@withContext
                    }

                    performTransfer(toAddress, amount, burnTrx, availableBandwidth, requiredBandwidth, balance)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showError("准备转账失败：${e.message}")
                    binding.btnConfirmTransfer.isEnabled = true
                    binding.btnConfirmTransfer.text = "立即转账"
                }
            }
        }
    }

    private fun performTransfer(toAddress: String, amount: Double, burnTrx: Double, availableBandwidth: Long, requiredBandwidth: Long, balanceSun: Long) {
        binding.btnConfirmTransfer.isEnabled = false
        binding.btnConfirmTransfer.text = "正在处理..."
        Toast.makeText(this, "正在处理转账...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            try {
                val fromAddress = walletManager.getAddress() ?: run {
                    showError("未找到钱包地址")
                    binding.btnConfirmTransfer.isEnabled = true
                    binding.btnConfirmTransfer.text = "立即转账"
                    return@launch
                }

                val amountSun = (amount * 1_000_000).toLong()
                val burnSun = (burnTrx * 1_000_000).toLong()

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
                    context = this@TransferActivity,
                    transaction = transaction,
                    toAddress = toAddress,
                    amountSun = amountSun,
                    burnSun = burnSun,
                    availableBandwidth = availableBandwidth,
                    requiredBandwidth = requiredBandwidth,
                    balanceSun = balanceSun,
                    onSignComplete = { _ ->
                        binding.btnConfirmTransfer.isEnabled = true
                        binding.btnConfirmTransfer.text = "立即转账"
                    },
                    onSignError = { errorMessage ->
                        showError(errorMessage)
                        binding.btnConfirmTransfer.isEnabled = true
                        binding.btnConfirmTransfer.text = "立即转账"
                    },
                    onCancel = {
                        binding.btnConfirmTransfer.isEnabled = true
                        binding.btnConfirmTransfer.text = "立即转账"
                    }
                )
                dialog.show()

            } catch (e: Exception) {
                showError("转账失败：${e.message}")
                binding.btnConfirmTransfer.isEnabled = true
                binding.btnConfirmTransfer.text = "立即转账"
            }
        }
    }

    private fun showTransferSuccessDialog(txid: String, toAddress: String, amount: Double) {
        AlertDialog.Builder(this)
            .setTitle("转账成功")
            .setMessage("转账已提交到TRON网络\n\n交易ID：$txid\n收款地址：$toAddress\n转账金额：$amount TRX")
            .setPositiveButton("确定") { _, _ ->
                finish()
            }
            .show()
    }

    /**
     * 显示错误信息
     */
    private fun showError(message: String) {
        AlertDialog.Builder(this)
            .setTitle("错误")
            .setMessage(message)
            .setPositiveButton(R.string.confirm, null)
            .show()
    }

    /**
     * 地址簿条目数据类
     */
    data class AddressBookEntry(
        val name: String,
        val address: String
    )
}
