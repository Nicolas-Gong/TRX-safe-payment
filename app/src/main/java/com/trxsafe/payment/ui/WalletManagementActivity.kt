package com.trxsafe.payment.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.trxsafe.payment.R
import com.trxsafe.payment.databinding.ActivityWalletManagementBinding
import com.trxsafe.payment.utils.setDebouncedClick
import com.trxsafe.payment.wallet.WalletManager
import kotlinx.coroutines.launch

/**
 * 钱包管理界面
 * 支持多钱包管理、创建、导入、删除等功能
 */
class WalletManagementActivity : BaseActivity() {

    private lateinit var binding: ActivityWalletManagementBinding
    private lateinit var walletManager: WalletManager
    private lateinit var walletAdapter: WalletAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWalletManagementBinding.inflate(layoutInflater)
        setContentView(binding.root)

        walletManager = WalletManager(this)

        initViews()
        loadWallets()
    }
    
    override fun onResume() {
        super.onResume()
        loadWallets()
    }

    private fun initViews() {
        // 返回按钮
        binding.btnBack.setDebouncedClick(debounceDelayMs = 1000) {
            finish()
        }

        // 添加钱包按钮
        binding.btnAddWallet.setDebouncedClick(debounceDelayMs = 1000) {
            showAddWalletDialog()
        }

        // 钱包列表
        binding.recyclerWallets.layoutManager = LinearLayoutManager(this)
        walletAdapter = WalletAdapter(
            onWalletClick = { wallet -> showWalletDetails(wallet) },
            onWalletDelete = { wallet -> showDeleteWalletDialog(wallet) },
            onWalletExport = { wallet -> showExportWalletDialog(wallet) }
        )
        binding.recyclerWallets.adapter = walletAdapter
    }

    /**
     * 加载钱包列表
     */
    private fun loadWallets() {
        lifecycleScope.launch {
            try {
                // 从钱包管理器获取所有钱包
                val walletManager = com.trxsafe.payment.wallet.WalletManager(this@WalletManagementActivity)
                val wallets = walletManager.getAllWallets().map { walletData ->
                    val walletType = when (walletData.type) {
                        com.trxsafe.payment.wallet.WalletType.PRIVATE_KEY -> WalletType.PRIVATE_KEY
                        com.trxsafe.payment.wallet.WalletType.WATCH_ONLY -> WalletType.WATCH_ONLY
                        com.trxsafe.payment.wallet.WalletType.HARDWARE -> WalletType.HARDWARE
                    }
                    WalletInfo(walletData.name, walletData.address, walletType, walletData.id)
                }

                walletAdapter.submitList(wallets)

                if (wallets.isEmpty()) {
                    binding.tvEmpty.visibility = android.view.View.VISIBLE
                    binding.recyclerWallets.visibility = android.view.View.GONE
                } else {
                    binding.tvEmpty.visibility = android.view.View.GONE
                    binding.recyclerWallets.visibility = android.view.View.VISIBLE
                }

            } catch (e: Exception) {
                showError("加载钱包列表失败：${e.message}")
            }
        }
    }

    /**
     * 显示添加钱包对话框
     */
    private fun showAddWalletDialog() {
        val options = arrayOf("创建新钱包", "导入私钥钱包", "导入观察钱包", "连接外部钱包")
        AlertDialog.Builder(this)
            .setTitle("添加钱包")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showCreateWalletDialog()
                    1 -> showImportPrivateKeyDialog()
                    2 -> showImportWatchWalletDialog()
                    3 -> showConnectExternalWalletDialog()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 显示创建钱包对话框
     */
    private fun showCreateWalletDialog() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
        }

        val nameInput = com.google.android.material.textfield.TextInputLayout(this).apply {
            hint = "钱包名称"
        }
        val nameEdit = com.google.android.material.textfield.TextInputEditText(this).apply {
            setText("新钱包") // 默认名称
            setSelection(text?.length ?: 0) // 光标移到末尾
        }
        nameInput.addView(nameEdit)

        layout.addView(nameInput)

        AlertDialog.Builder(this)
            .setTitle("创建新钱包")
            .setView(layout)
            .setMessage("将在本地生成一个新的安全钱包。私钥将加密存储在设备安全芯片中，无法导出。请确保您了解风险。")
            .setPositiveButton("创建") { _, _ ->
                val name = nameEdit.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    android.widget.Toast.makeText(this@WalletManagementActivity, "钱包名称不能为空", android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                createWallet(name)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 显示导入私钥对话框
     */
    private fun showImportPrivateKeyDialog() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
        }

        val nameInput = com.google.android.material.textfield.TextInputLayout(this).apply {
            hint = "钱包名称"
        }
        val nameEdit = com.google.android.material.textfield.TextInputEditText(this)
        nameInput.addView(nameEdit)

        val keyInput = com.google.android.material.textfield.TextInputLayout(this).apply {
            hint = "私钥"
        }
        val keyEdit = com.google.android.material.textfield.TextInputEditText(this).apply {
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        keyInput.addView(keyEdit)

        layout.addView(nameInput)
        layout.addView(keyInput)

        AlertDialog.Builder(this)
            .setTitle("导入私钥钱包")
            .setView(layout)
            .setPositiveButton("导入") { _, _ ->
                val name = nameEdit.text?.toString()?.trim() ?: "导入钱包"
                val privateKey = keyEdit.text?.toString()?.trim() ?: ""
                if (privateKey.isNotEmpty()) {
                    importPrivateKeyWallet(name, privateKey)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 显示导入观察钱包对话框
     */
    private fun showImportWatchWalletDialog() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
        }

        val nameInput = com.google.android.material.textfield.TextInputLayout(this).apply {
            hint = "钱包名称"
        }
        val nameEdit = com.google.android.material.textfield.TextInputEditText(this)
        nameInput.addView(nameEdit)

        val addressInput = com.google.android.material.textfield.TextInputLayout(this).apply {
            hint = "钱包地址"
        }
        val addressEdit = com.google.android.material.textfield.TextInputEditText(this)
        addressInput.addView(addressEdit)

        layout.addView(nameInput)
        layout.addView(addressInput)

        AlertDialog.Builder(this)
            .setTitle("导入观察钱包")
            .setView(layout)
            .setPositiveButton("导入") { _, _ ->
                val name = nameEdit.text?.toString()?.trim() ?: "观察钱包"
                val address = addressEdit.text?.toString()?.trim() ?: ""
                if (address.isNotEmpty()) {
                    importWatchWallet(name, address)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 创建钱包
     */
    private fun createWallet(name: String) {
        try {
            walletManager.createWallet(name)
            Toast.makeText(this, "钱包创建成功", Toast.LENGTH_SHORT).show()
            loadWallets()
        } catch (e: Exception) {
            showError("创建钱包失败：${e.message}")
        }
    }

    /**
     * 导入私钥钱包
     */
    private fun importPrivateKeyWallet(name: String, privateKey: String) {
        try {
            walletManager.importPrivateKeyWallet(name, privateKey)
            Toast.makeText(this, "私钥钱包导入成功", Toast.LENGTH_SHORT).show()
            loadWallets()
        } catch (e: Exception) {
            showError("导入失败：${e.message}")
        }
    }

    /**
     * 导入观察钱包
     */
    private fun importWatchWallet(name: String, address: String) {
        try {
            walletManager.importWatchWallet(name, address)
            Toast.makeText(this, "观察钱包导入成功", Toast.LENGTH_SHORT).show()
            loadWallets()
        } catch (e: Exception) {
            showError("导入失败：${e.message}")
        }
    }

    /**
     * 显示钱包详情
     */
    private fun showWalletDetails(wallet: WalletInfo) {
        val details = """
            |钱包名称：${wallet.name}
            |钱包类型：${getWalletTypeName(wallet.type)}
            |钱包地址：${wallet.address}
        """.trimMargin()

        AlertDialog.Builder(this)
            .setTitle("钱包详情")
            .setMessage(details)
            .setPositiveButton("复制地址") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Wallet Address", wallet.address)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "地址已复制", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .setNeutralButton("编辑") { _, _ ->
                showEditWalletDialog(wallet)
            }
            .show()
    }

    /**
     * 显示编辑钱包对话框
     */
    private fun showEditWalletDialog(wallet: WalletInfo) {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
        }

        // 钱包名称输入框
        val nameInput = com.google.android.material.textfield.TextInputLayout(this).apply {
            hint = "钱包名称"
        }
        val nameEdit = com.google.android.material.textfield.TextInputEditText(this).apply {
            setText(wallet.name)
            setSelection(text?.length ?: 0)
        }
        nameInput.addView(nameEdit)
        layout.addView(nameInput)

        // 地址输入框（仅观察钱包可编辑）
        var addressEdit: com.google.android.material.textfield.TextInputEditText? = null
        if (wallet.type == WalletType.WATCH_ONLY) {
            val addressInput = com.google.android.material.textfield.TextInputLayout(this).apply {
                hint = "钱包地址"
            }
            addressEdit = com.google.android.material.textfield.TextInputEditText(this).apply {
                setText(wallet.address)
            }
            addressInput.addView(addressEdit)
            layout.addView(addressInput)
        }

        AlertDialog.Builder(this)
            .setTitle("编辑钱包")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val newName = nameEdit.text?.toString()?.trim().orEmpty()
                if (newName.isEmpty()) {
                    android.widget.Toast.makeText(this@WalletManagementActivity, "钱包名称不能为空", android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val newAddress = if (wallet.type == WalletType.WATCH_ONLY) {
                    addressEdit?.text?.toString()?.trim().orEmpty()
                } else {
                    null
                }

                editWallet(wallet, newName, newAddress)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 编辑钱包
     */
    private fun editWallet(wallet: WalletInfo, newName: String, newAddress: String?) {
        try {
            // 如果地址有变化且是观察钱包，需要验证新地址格式
            if (newAddress != null && wallet.type == WalletType.WATCH_ONLY && newAddress != wallet.address) {
                if (!walletManager.isValidAddress(newAddress)) {
                    showError("新地址格式错误")
                    return
                }
            }

            walletManager.updateWallet(wallet.id, newName, newAddress)
            Toast.makeText(this, "钱包更新成功", Toast.LENGTH_SHORT).show()
            loadWallets() // 重新加载列表
        } catch (e: Exception) {
            showError("更新钱包失败：${e.message}")
        }
    }

    /**
     * 显示删除钱包对话框
     */
    private fun showDeleteWalletDialog(wallet: WalletInfo) {
        AlertDialog.Builder(this)
            .setTitle("删除钱包")
            .setMessage("确定要删除钱包\"${wallet.name}\"吗？\n\n删除后将无法恢复（除非您有私钥备份），该操作不可撤销！")
            .setPositiveButton("删除") { _, _ ->
                deleteWallet(wallet)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 显示导出钱包对话框
     */
    private fun showExportWalletDialog(wallet: WalletInfo) {
        if (wallet.type == WalletType.WATCH_ONLY) {
            // 观察钱包只能导出地址
            AlertDialog.Builder(this)
                .setTitle("导出观察钱包")
                .setMessage("观察钱包只能导出地址，无法导出私钥。")
                .setPositiveButton("复制地址") { _, _ ->
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Wallet Address", wallet.address)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this, "地址已复制", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            // 私钥钱包可以导出私钥（需要密码验证）
            showExportPrivateKeyDialog(wallet)
        }
    }

    /**
     * 显示导出私钥对话框
     */
    private fun showExportPrivateKeyDialog(wallet: WalletInfo) {
        val inputLayout = com.google.android.material.textfield.TextInputLayout(this).apply {
            setPadding(32, 16, 32, 16)
            hint = "输入密码确认"
        }
        val editText = com.google.android.material.textfield.TextInputEditText(this).apply {
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        inputLayout.addView(editText)

        AlertDialog.Builder(this)
            .setTitle("导出私钥")
            .setMessage("警告：私钥一旦导出，将无法保证安全性。请确保在安全环境下操作，且不要将私钥告诉任何人。")
            .setView(inputLayout)
            .setPositiveButton("导出") { _, _ ->
                val password = editText.text?.toString()?.trim() ?: ""
                if (password.isNotEmpty()) {
                    exportPrivateKey(wallet, password)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 删除钱包
     */
    private fun deleteWallet(wallet: WalletInfo) {
        try {
            if (wallet.id.isNotEmpty()) {
                walletManager.deleteWallet(wallet.id)
                Toast.makeText(this, "钱包已删除", Toast.LENGTH_SHORT).show()
                loadWallets() // 重新加载列表
            } else {
                showError("无法删除钱包：钱包ID为空")
            }
        } catch (e: Exception) {
            showError("删除钱包失败：${e.message}")
        }
    }

    /**
     * 导出私钥
     */
    private fun exportPrivateKey(wallet: WalletInfo, password: String) {
        try {
            // 首先验证密码长度（简单验证）
            if (password.length < 6) {
                showError("密码长度至少需要6位")
                return
            }

            // 检查是否为当前选中的钱包
            val currentWallet = walletManager.getCurrentWallet()
            if (currentWallet?.id != wallet.id) {
                showError("只能导出当前选中的钱包的私钥")
                return
            }

            // 检查是否为观察钱包
            if (wallet.type == WalletType.WATCH_ONLY) {
                showError("观察钱包没有私钥")
                return
            }

            // 获取私钥（需要生物识别验证）
            val privateKey = walletManager.getPrivateKeyForBackup()

            // 显示私钥（带警告）
            showPrivateKeyDialog(wallet, privateKey)

        } catch (e: Exception) {
            showError("导出私钥失败：${e.message}")
        }
    }

    /**
     * 显示私钥对话框
     */
    private fun showPrivateKeyDialog(wallet: WalletInfo, privateKey: String) {
        val message = """
            |⚠️ 安全警告 ⚠️
            |
            |私钥一旦泄露，您的资产将完全丢失！
            |请确保在安全环境下操作，不要截屏或拍照。
            |
            |钱包：${wallet.name}
            |地址：${wallet.address}
            |
            |私钥：${privateKey}
            |
            |请立即将私钥保存在安全的地方，然后销毁此显示。
        """.trimMargin()

        AlertDialog.Builder(this)
            .setTitle("私钥导出")
            .setMessage(message)
            .setPositiveButton("复制私钥") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Private Key", privateKey)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "私钥已复制到剪贴板，请立即安全保存", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("关闭", null)
            .setCancelable(false) // 防止误触关闭
            .show()
    }

    /**
     * 显示连接外部钱包对话框
     */
    private fun showConnectExternalWalletDialog() {
        AlertDialog.Builder(this)
            .setTitle("连接外部钱包")
            .setMessage("将通过WalletConnect连接外部钱包应用（如Trust Wallet、MetaMask等）。\n\n连接后您可以使用外部钱包进行签名和交易。")
            .setPositiveButton("开始连接") { _, _ ->
                connectExternalWallet()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 连接外部钱包
     */
    private fun connectExternalWallet() {
        try {
            val intent = Intent(this, WalletConnectActivity::class.java)
            startActivity(intent)
        } catch (e: Exception) {
            showError("启动连接界面失败：${e.message}")
        }
    }

    /**
     * 获取钱包类型名称
     */
    private fun getWalletTypeName(type: WalletType): String {
        return when (type) {
            WalletType.PRIVATE_KEY -> "私钥钱包"
            WalletType.WATCH_ONLY -> "观察钱包"
            WalletType.HARDWARE -> "硬件钱包"
        }
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
}
