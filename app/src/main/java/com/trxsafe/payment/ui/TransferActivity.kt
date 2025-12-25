package com.trxsafe.payment.ui

import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.integration.android.IntentIntegrator
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.trxsafe.payment.R
import com.trxsafe.payment.databinding.ActivityTransferBinding
import com.trxsafe.payment.qrcode.MainAppQRGenerator
import com.trxsafe.payment.qrcode.QRCodec
import com.trxsafe.payment.risk.RiskValidator
import com.trxsafe.payment.settings.SettingsConfig
import com.trxsafe.payment.settings.SettingsRepository
import com.trxsafe.payment.transaction.TransactionBuilder
import com.trxsafe.payment.ui.dialog.TransferConfirmDialog
import com.trxsafe.payment.utils.AmountUtils
import com.trxsafe.payment.wallet.WalletManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tron.trident.proto.Chain

/**
 * 转账界面
 * 支持热钱包直接转账和冷钱包扫码流程
 */
class TransferActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityTransferBinding
    private lateinit var walletManager: WalletManager
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var transactionBuilder: TransactionBuilder
    private var currentConfig: SettingsConfig? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTransferBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        walletManager = WalletManager(this)
        settingsRepository = SettingsRepository(this)
        transactionBuilder = TransactionBuilder()
        
        loadConfig()
        initViews()
    }
    
    private fun loadConfig() {
        lifecycleScope.launch {
            try {
                val config = settingsRepository.loadConfig()
                // 检查配置是否完整
                if (!config.isConfigComplete()) {
                    Toast.makeText(this@TransferActivity, getString(R.string.settings_config_incomplete), Toast.LENGTH_LONG).show()
                    // 跳转到设置页
                    startActivity(Intent(this@TransferActivity, SettingsActivity::class.java))
                    finish()
                    return@launch
                }
                currentConfig = config
                updateUiWithConfig(config)
            } catch (e: Exception) {
                showError("加载配置失败：${e.message}")
            }
        }
    }
    
    private fun updateUiWithConfig(config: SettingsConfig) {
        val totalTrx = config.getTotalAmountTrx()
        binding.tvAmount.text = getString(R.string.confirm_total_amount, totalTrx)
        binding.tvToAddress.text = config.sellerAddress
        
        // 观察钱包适配
        if (walletManager.isWatchOnly()) {
            binding.tvLabelHotWallet.visibility = View.GONE
            binding.btnDirectTransfer.visibility = View.GONE
            binding.tvLabelColdWallet.text = getString(R.string.operation_cold_wallet) + " (观察模式)"
            
            // 自动高亮冷钱包操作
            binding.btnGenerateQR.setBackgroundColor(getColor(R.color.primary))
            binding.btnGenerateQR.setTextColor(getColor(R.color.white))
        }
    }
    
    private fun initViews() {
        // 热钱包直接转账
        binding.btnDirectTransfer.setOnClickListener {
            handleDirectTransfer()
        }
        
        // 扫描二维码
        binding.btnScan.setOnClickListener {
            startScan()
        }
        
        // 生成二维码
        binding.btnGenerateQR.setOnClickListener {
            handleGenerateQR()
        }
    }
    
    /**
     * 处理热钱包直接转账
     */
    private fun handleDirectTransfer() {
        if (!walletManager.hasWallet()) {
            showError("请先在主界面创建或导入钱包")
            return
        }
        
        val config = currentConfig ?: return
        val fromAddress = walletManager.getAddress() ?: return
        
        lifecycleScope.launch {
            try {
                // 1. 构造交易
                val transaction = transactionBuilder.buildTransferTransaction(fromAddress, config)
                
                // 2. 风控检查
                val riskValidator = RiskValidator()
                val riskResult = riskValidator.validate(transaction, config)
                
                if (riskResult.level == com.trxsafe.payment.risk.RiskLevel.BLOCK) {
                    showError("交易被风控拦截：${riskResult.message}")
                    return@launch
                }
                
                // 3. 弹出确认框
                showConfirmDialog(config, fromAddress, transaction)
                
            } catch (e: Exception) {
                showError("交易构造失败：${e.message}")
            }
        }
    }
    
    /**
     * 处理生成二维码
     */
    private fun handleGenerateQR() {
        // 如果有钱包则使用当前地址作为发送方，否则需要用户输入（这里简化为必须有钱包）
        // 实际上冷钱包场景下，在线端可能没有私钥，应该允许输入 Sender Address。
        // 为修复当前逻辑，假设在线端也有钱包地址（即 Watch Wallet 概念）
        val fromAddress = walletManager.getAddress()
        if (fromAddress == null) {
            showError("请先创建或导入钱包以获取发送地址")
            return
        }
        
        val config = currentConfig ?: return
        
        lifecycleScope.launch {
            try {
                // 构造交易
                val transaction = transactionBuilder.buildTransferTransaction(fromAddress, config)
                
                // 生成二维码
                val qrCodeBitmap = MainAppQRGenerator.generateUnsignedTransactionQR(transaction, config)
                
                // 显示二维码
                showQRCodeDialog(qrCodeBitmap, "请使用冷钱包扫描签名")
                
            } catch (e: Exception) {
                showError("生成二维码失败：${e.message}")
            }
        }
    }
    
    /**
     * 显示确认对话框
     */
    private fun showConfirmDialog(config: SettingsConfig, fromAddress: String, transaction: org.tron.trident.proto.Chain.Transaction) {
        val dialog = TransferConfirmDialog(this, config, fromAddress, transaction) {
            // 确认回调
            checkBiometricBeforeSign(transaction)
        }
        dialog.show()
    }

    /**
     * 签名通过前检查生物识别
     */
    private fun checkBiometricBeforeSign(transaction: Chain.Transaction) {
        val config = currentConfig ?: return
        
        if (config.isBiometricEnabled) {
             val authManager = com.trxsafe.payment.security.BiometricAuthManager(this)
             authManager.authenticate(
                 title = "验证身份以签名",
                 subtitle = "确认转账操作",
                 onSuccess = {
                     performSignAndBroadcast(transaction)
                 },
                 onError = { error ->
                     Toast.makeText(this, "认证失败：$error", Toast.LENGTH_SHORT).show()
                 }
             )
        } else {
             performSignAndBroadcast(transaction)
        }
    }
    
    /**
     * 执行签名和广播
     */
    private fun performSignAndBroadcast(transaction: Chain.Transaction) {
        val config = currentConfig ?: return
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    binding.tvStatus.text = "正在签名..."
                }
                
                // 签名
                val signedTx = walletManager.signTransferContract(transaction)
                
                withContext(Dispatchers.Main) {
                    binding.tvStatus.text = "正在广播 (节点: ${config.nodeUrl})..."
                }
                
                // 初始化 ApiWrapper 和广播器
                val apiWrapper = org.tron.trident.core.ApiWrapper(config.nodeUrl, config.nodeUrl, "")
                val broadcaster = com.trxsafe.payment.broadcast.TransactionBroadcaster(this@TransferActivity, apiWrapper)
                
                // 广播
                val result = broadcaster.broadcast(signedTx, config)
                
                withContext(Dispatchers.Main) {
                    when (result) {
                        is com.trxsafe.payment.broadcast.BroadcastResult.Success -> {
                            binding.tvStatus.text = "交易广播成功！"
                            Toast.makeText(this@TransferActivity, "交易广播成功\nTXID: ${result.txid}", Toast.LENGTH_LONG).show()
                            
                            // 延迟关闭，让用户看清 TXID
                            kotlinx.coroutines.delay(2000)
                            finish()
                        }
                        is com.trxsafe.payment.broadcast.BroadcastResult.Failure -> {
                            binding.tvStatus.text = "广播失败：${result.message}"
                            showError("广播失败：${result.message}")
                        }
                    }
                }
                
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.tvStatus.text = "操作失败：${e.message}"
                    showError("操作失败：${e.message}")
                }
            }
        }
    }
    
    /**
     * 启动扫描
     */
    private fun startScan() {
        val integrator = IntentIntegrator(this)
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
        integrator.setPrompt("请扫描二维码")
        integrator.setCameraId(0)
        integrator.setBeepEnabled(false)
        integrator.setBarcodeImageEnabled(false)
        integrator.initiateScan()
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            if (result.contents != null) {
                handleScanResult(result.contents)
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }
    
    private fun handleScanResult(qrContent: String) {
        // 尝试解码
        try {
            if (qrContent.startsWith("TRX_UNSIGNED:")) {
                // 冷钱包流程：收到未签名交易 -> 签名
                // TODO: 实现冷钱包签名流程（需解码 -> 验证 -> 签名 -> 展示 Signed QR）
                // 暂时仅提示
                Toast.makeText(this, "识别到未签名交易，请使用冷钱包功能签名", Toast.LENGTH_LONG).show()
            } else if (qrContent.startsWith("TRX_SIGNED:")) {
                // 热钱包流程：收到已签名交易 -> 广播
                 // TODO: 实现广播流程（需解码 -> 广播）
                Toast.makeText(this, "识别到已签名交易，准备广播", Toast.LENGTH_LONG).show()
            } else {
                showError("无效的二维码格式")
            }
        } catch (e: Exception) {
            showError("二维码解析失败：${e.message}")
        }
    }
    
    /**
     * 显示二维码对话框
     */
    private fun showQRCodeDialog(bitmap: Bitmap, title: String) {
        val imageView = ImageView(this)
        imageView.setImageBitmap(bitmap)
        imageView.setPadding(32, 32, 32, 32)
        
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(imageView)
            .setPositiveButton(R.string.confirm, null)
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
