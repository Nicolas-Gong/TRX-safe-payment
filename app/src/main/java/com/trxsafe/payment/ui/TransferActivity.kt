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
import com.trxsafe.payment.data.AppDatabase
import com.trxsafe.payment.data.repository.AddressBookRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tron.trident.proto.Chain

/**
 * 转账界面
 * 支持热钱包直接转账和冷钱包扫码流程
 */
class TransferActivity : BaseActivity() {
    
    private lateinit var binding: ActivityTransferBinding
    private lateinit var walletManager: WalletManager
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var transactionBuilder: TransactionBuilder
    private lateinit var addressBookRepository: AddressBookRepository
    private var currentConfig: SettingsConfig? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTransferBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        walletManager = WalletManager(this)
        settingsRepository = SettingsRepository(this)
        transactionBuilder = TransactionBuilder()
        val database = AppDatabase.getInstance(this)
        addressBookRepository = AddressBookRepository(database.addressBookDao())
        
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
                
                // 异步获取余额
                fetchBalance(config)
            } catch (e: Exception) {
                showError("加载配置失败：${e.message}")
            }
        }
    }

    private fun fetchBalance(config: SettingsConfig) {
        val address = walletManager.getAddress() ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val apiWrapper = org.tron.trident.core.ApiWrapper(config.nodeUrl)
            try {
                val broadcaster = com.trxsafe.payment.broadcast.TransactionBroadcaster(this@TransferActivity, apiWrapper)
                val balanceSun = broadcaster.getAccountBalance(address)
                
                withContext(Dispatchers.Main) {
                    if (balanceSun == -1L) {
                        binding.tvWalletBalance.text = "余额: 查询超时/错误"
                    } else {
                        val balanceTrx = AmountUtils.sunToTrx(balanceSun)
                        binding.tvWalletBalance.text = "余额: $balanceTrx TRX"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.tvWalletBalance.text = "余额: 无法获取"
                }
            } finally {
                apiWrapper.close()
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
    
    private fun updateRiskUi(riskResult: com.trxsafe.payment.risk.RiskCheckResult) {
        if (riskResult.level != com.trxsafe.payment.risk.RiskLevel.PASS) {
            binding.cardRiskWarning.visibility = View.VISIBLE
            binding.tvRiskWarning.text = riskResult.message
            
            if (riskResult.level == com.trxsafe.payment.risk.RiskLevel.BLOCK) {
                binding.cardRiskWarning.setCardBackgroundColor(android.graphics.Color.parseColor("#FFEBEE"))
                binding.tvRiskWarning.setTextColor(android.graphics.Color.parseColor("#C62828"))
            } else {
                binding.cardRiskWarning.setCardBackgroundColor(android.graphics.Color.parseColor("#FFF3E0"))
                binding.tvRiskWarning.setTextColor(android.graphics.Color.parseColor("#E65100"))
            }
        } else {
            binding.cardRiskWarning.visibility = View.GONE
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
        
        binding.btnPickAddress.setOnClickListener {
            showAddressBookSelectionDialog()
        }
    }
    
    /**
     * 显示地址挑选对话框
     */
    private fun showAddressBookSelectionDialog() {
        lifecycleScope.launch {
            val database = com.trxsafe.payment.data.AppDatabase.getInstance(this@TransferActivity)
            val addressBookRepo = com.trxsafe.payment.data.repository.AddressBookRepository(database.addressBookDao())
            
            addressBookRepo.getAllAddresses().collectLatest { list ->
                if (list.isEmpty()) {
                    Toast.makeText(this@TransferActivity, "地址簿为空", Toast.LENGTH_SHORT).show()
                    return@collectLatest
                }

                val names = list.map { "${it.name} (${it.address.take(6)}...${it.address.takeLast(6)})" }.toTypedArray()
                
                AlertDialog.Builder(this@TransferActivity)
                    .setTitle("选择收款地址")
                    .setItems(names) { _, which ->
                        val selected = list[which]
                        val config = currentConfig ?: return@setItems
                        
                        // 创建一个新的配置副本（仅用于当前交易）
                        val newConfig = config.copy(sellerAddress = selected.address)
                        currentConfig = newConfig
                        
                        // 更新 UI
                        binding.tvToAddress.text = selected.address
                        
                        // 重新执行风险检查
                        lifecycleScope.launch {
                            checkRisk(newConfig)
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
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
                val isWhitelisted = addressBookRepository.isWhitelisted(config.sellerAddress)
                val riskResult = riskValidator.checkRisk(transaction, config, isWhitelisted)
                
                // 处理风控显示
                updateRiskUi(riskResult)

                if (riskResult.level == com.trxsafe.payment.risk.RiskLevel.BLOCK) {
                    showError("交易被风控拦截：${riskResult.message}")
                    return@launch
                }
                
                if (riskResult.level == com.trxsafe.payment.risk.RiskLevel.WARN) {
                    // 弹出风险警告对话框
                    AlertDialog.Builder(this@TransferActivity)
                        .setTitle("风险警告")
                        .setMessage(riskResult.message)
                        .setPositiveButton("确认识别并继续") { _, _ ->
                            showConfirmDialog(config, fromAddress, transaction)
                        }
                        .setNegativeButton("取消交易", null)
                        .show()
                } else {
                    // 3. 弹出确认框
                    showConfirmDialog(config, fromAddress, transaction)
                }
                
            } catch (e: Exception) {
                showError("交易构造失败：${e.message}")
            }
        }
    }
    
    private var lastUnsignedData: com.trxsafe.payment.qrcode.UnsignedTransactionQR? = null
    
    /**
     * 处理生成二维码
     */
    private fun handleGenerateQR() {
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
                
                // 生成二维码数据
                val generator = com.trxsafe.payment.qrcode.MainAppQRGenerator()
                val unsignedData = generator.createUnsignedTransactionQR(transaction, fromAddress)
                
                // 保存数据用于后续签名回传校验
                lastUnsignedData = unsignedData
                
                val qrJson = com.trxsafe.payment.qrcode.QRCodec.encodeUnsignedTransaction(unsignedData)
                val qrContent = "TRX_UNSIGNED:$qrJson"
                
                // 支持分片二维码
                val qrCodeBitmaps = generator.generateMultiPartQRCodeBitmaps(qrContent)
                
                // 显示二维码
                showQRCodeDialog(qrCodeBitmaps, "请使用冷钱包扫描签名")
                
            } catch (e: Exception) {
                showError("生成二维码失败：${e.message}")
            }
        }
    }

    /**
     * 显示二维码对话框
     */
    private fun showQRCodeDialog(bitmaps: List<android.graphics.Bitmap>, title: String) {
        if (bitmaps.isEmpty()) return
        
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_qr_display, null)
        dialog.setContentView(view)

        val ivQR = view.findViewById<android.widget.ImageView>(R.id.ivQRCode)
        val tvTitle = view.findViewById<android.widget.TextView>(R.id.tvQRTitle)
        val tvPart = view.findViewById<android.widget.TextView>(R.id.tvQRPart)
        
        tvTitle.text = title
        
        if (bitmaps.size == 1) {
            ivQR.setImageBitmap(bitmaps[0])
            tvPart.visibility = View.GONE
        } else {
            // 多个分片展示逻辑：自动循环切换
            tvPart.visibility = View.VISIBLE
            var currentIndex = 0
            
            val runnable = object : Runnable {
                override fun run() {
                    if (!dialog.isShowing) return
                    ivQR.setImageBitmap(bitmaps[currentIndex])
                    tvPart.text = "分片 ${currentIndex + 1} / ${bitmaps.size}"
                    currentIndex = (currentIndex + 1) % bitmaps.size
                    ivQR.postDelayed(this, 1500) // 1.5 秒切换一次
                }
            }
            ivQR.post(runnable)
        }

        dialog.show()
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
            var apiWrapper: org.tron.trident.core.ApiWrapper? = null
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
                apiWrapper = org.tron.trident.core.ApiWrapper(config.nodeUrl, config.nodeUrl, "")
                val broadcaster = com.trxsafe.payment.broadcast.TransactionBroadcaster(this@TransferActivity, apiWrapper!!)
                
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
            } finally {
                apiWrapper?.close()
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
        try {
            if (qrContent.startsWith("TRX_UNSIGNED:")) {
                // 冷钱包流程：收到未签名交易 -> 确认 -> 签名 -> 生成已签名 QR
                val json = qrContent.substring("TRX_UNSIGNED:".length)
                val unsignedData = com.trxsafe.payment.qrcode.QRCodec.decodeUnsignedTransaction(json)
                showColdSignConfirmDialog(unsignedData)
            } else if (qrContent.startsWith("TRX_SIGNED:")) {
                // 热钱包流程：收到已签名交易 -> 验证 -> 广播
                val json = qrContent.substring("TRX_SIGNED:".length)
                val signedData = com.trxsafe.payment.qrcode.QRCodec.decodeSignedTransaction(json)
                
                // 校验：如果本地存有原始数据，则进行防篡改校验
                val original = lastUnsignedData
                if (original != null) {
                    val verifier = com.trxsafe.payment.qrcode.SignatureVerifier()
                    val verifyResult = verifier.verify(original, signedData)
                    if (verifyResult is com.trxsafe.payment.qrcode.VerificationResult.Failure) {
                        showError("安全风险：接收到的签名数据与原始交易不符！\n原因：${verifyResult.message}")
                        return
                    }
                } else {
                    // 提示用户风险，因为没有原始数据对照
                    Toast.makeText(this, "提示：无法验证签名数据的原始性，请核对金额后再继续", Toast.LENGTH_LONG).show()
                }

                // 广播逻辑
                performHotBroadcast(signedData)
            } else {
                Toast.makeText(this, "未知的二维码格式", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            showError("解析扫描结果失败：${e.message}")
        }
    }

    /**
     * 显示冷钱包签名确认框
     */
    private fun showColdSignConfirmDialog(unsigned: com.trxsafe.payment.qrcode.UnsignedTransactionQR) {
        val message = """
            确认签名交易？
            收款地址: ${unsigned.toAddress}
            转账金额: ${com.trxsafe.payment.utils.AmountUtils.sunToTrx(unsigned.amountSun)} TRX
            发送地址: ${unsigned.fromAddress}
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("签名确认 (冷钱包模式)")
            .setMessage(message)
            .setPositiveButton("开始签名") { _, _ ->
                performColdSign(unsigned)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 执行冷钱包签名并展示结果
     */
    private fun performColdSign(unsigned: com.trxsafe.payment.qrcode.UnsignedTransactionQR) {
        lifecycleScope.launch {
            try {
                if (walletManager.isWatchOnly()) {
                    showError("当前为观察钱包，无法进行冷钱包签名操作")
                    return@launch
                }

                val authManager = com.trxsafe.payment.security.BiometricAuthManager(this@TransferActivity)
                authManager.authenticate(
                    title = "冷钱包签名授权",
                    subtitle = "请验证身份以对交易进行离线签名",
                    onSuccess = {
                        lifecycleScope.launch {
                            val processor = com.trxsafe.payment.qrcode.ColdWalletQRProcessor()
                            val signedQRData = processor.signAndCreateQR(unsigned, walletManager)
                            val qrString = "TRX_SIGNED:" + com.trxsafe.payment.qrcode.QRCodec.encodeSignedTransaction(signedQRData)
                            
                            val generator = com.trxsafe.payment.qrcode.MainAppQRGenerator()
                            val bitmaps = generator.generateMultiPartQRCodeBitmaps(qrString)
                            
                            showQRCodeDialog(bitmaps, "签名成功！请使用在线手机扫描此码进行广播")
                        }
                    },
                    onError = { error ->
                        showError("认证失败：$error")
                    }
                )
            } catch (e: Exception) {
                showError("签名失败：${e.message}")
            }
        }
    }

    /**
     * 执行热钱包广播已签名交易
     */
    private fun performHotBroadcast(signed: com.trxsafe.payment.qrcode.SignedTransactionQR) {
        lifecycleScope.launch {
            var apiWrapper: org.tron.trident.core.ApiWrapper? = null
            try {
                binding.tvStatus.text = "正在准备广播..."
                
                // 1. 重建交易
                val verifier = com.trxsafe.payment.qrcode.SignatureVerifier()
                val transaction = verifier.rebuildTransaction(signed)
                
                // 2. 广播（这里使用当前配置的节点）
                val config = currentConfig ?: return@launch
                apiWrapper = org.tron.trident.core.ApiWrapper(config.nodeUrl)
                val broadcaster = com.trxsafe.payment.broadcast.TransactionBroadcaster(this@TransferActivity, apiWrapper!!)
                
                val result = broadcaster.broadcast(transaction, config)
                
                withContext(Dispatchers.Main) {
                    when (result) {
                        is com.trxsafe.payment.broadcast.BroadcastResult.Success -> {
                            binding.tvStatus.text = "交易广播成功！"
                            Toast.makeText(this@TransferActivity, "广播成功\nTXID: ${result.txid}", Toast.LENGTH_LONG).show()
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
                showError("广播失败：${e.message}")
            } finally {
                withContext(Dispatchers.IO) {
                    apiWrapper?.close()
                }
            }
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
