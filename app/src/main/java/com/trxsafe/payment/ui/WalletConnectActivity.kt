package com.trxsafe.payment.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Observer
import androidx.compose.runtime.livedata.observeAsState
import com.trxsafe.payment.TrxSafeApplication
import com.trxsafe.payment.wallet.WalletConnectManager

/**
 * WalletConnect 连接界面
 *
 * 主要功能:
 * 1. 显示连接状态和用户提示
 * 2. 打开 AppKit Modal 进行钱包连接
 * 3. 监听连接状态变化
 * 4. 提供取消连接选项
 */
class WalletConnectActivity : BaseActivity() {

    private lateinit var walletConnectManager: WalletConnectManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        walletConnectManager = (application as TrxSafeApplication).getWalletConnectManagerSync()

        if (!walletConnectManager.isInitialized) {
            showErrorAndFinish("WalletConnect 未初始化")
            return
        }

        if (walletConnectManager.isConnected()) {
            val address = walletConnectManager.getConnectedAddress()
            showSuccessAndFinish("钱包已连接: ${address?.take(10)}...")
            return
        }

        setContent {
            WalletConnectScreen(
                walletConnectManager = walletConnectManager,
                onConnectionComplete = { success, message ->
                    if (success) {
                        showSuccessAndFinish(message)
                    } else {
                        showErrorAndFinish(message)
                    }
                },
                onCancel = {
                    finish()
                }
            )
        }
    }

    private fun showErrorAndFinish(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun showSuccessAndFinish(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        window?.decorView?.postDelayed({
            finish()
        }, 1500)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletConnectScreen(
    walletConnectManager: WalletConnectManager,
    onConnectionComplete: (Boolean, String) -> Unit,
    onCancel: () -> Unit
) {
    val connectionState by walletConnectManager.connectionState.observeAsState(WalletConnectManager.ConnectionState.Disconnected)

    LaunchedEffect(Unit) {
        walletConnectManager.connectWallet()
    }

    LaunchedEffect(connectionState) {
        when (connectionState) {
            is WalletConnectManager.ConnectionState.Connected -> {
                val address = walletConnectManager.getConnectedAddress()
                onConnectionComplete(true, "连接成功: ${address?.take(10)}...")
            }
            is WalletConnectManager.ConnectionState.Error -> {
                val error = (connectionState as WalletConnectManager.ConnectionState.Error).message
                onConnectionComplete(false, "连接失败: $error")
            }
            else -> {}
        }
    }

    BackHandler {
        onCancel()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("连接钱包") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Text("取消")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                when (connectionState) {
                    is WalletConnectManager.ConnectionState.Connecting -> {
                        CircularProgressIndicator()
                        Text("正在连接钱包...")
                        Text(
                            "请在钱包应用中确认连接请求",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is WalletConnectManager.ConnectionState.Connected -> {
                        Text("✓ 连接成功", color = MaterialTheme.colorScheme.primary)
                        val address = walletConnectManager.getConnectedAddress()
                        Text(
                            address ?: "",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    is WalletConnectManager.ConnectionState.Disconnected -> {
                        Text("连接已断开")
                    }
                    is WalletConnectManager.ConnectionState.Error -> {
                        val error = (connectionState as WalletConnectManager.ConnectionState.Error).message
                        Text("✗ 连接失败", color = MaterialTheme.colorScheme.error)
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}