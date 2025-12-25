package com.trxsafe.payment.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.trxsafe.payment.R
import com.trxsafe.payment.broadcast.TransactionRecord
import com.trxsafe.payment.broadcast.TransactionRecorder
import com.trxsafe.payment.broadcast.TransactionStatus
import com.trxsafe.payment.databinding.ActivityTransactionHistoryBinding
import com.trxsafe.payment.databinding.ItemTransactionBinding
import com.trxsafe.payment.utils.AmountUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 交易历史界面
 */
class TransactionHistoryActivity : BaseActivity() {
    
    private lateinit var binding: ActivityTransactionHistoryBinding
    private lateinit var recorder: TransactionRecorder
    private lateinit var adapter: TransactionAdapter
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTransactionHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        recorder = TransactionRecorder(this)
        
        initViews()
        loadData()
        startStatusPolling()
    }
    
    private fun startStatusPolling() {
        androidx.lifecycle.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            while (kotlinx.coroutines.isActive) {
                val records = recorder.getAllRecords().filter { it.status == TransactionStatus.PENDING }
                if (records.isEmpty()) {
                    // 如果没有待定交易，等一会儿再查，或者直接结束（这里选择等 30 秒再查，如果有的话则 10 秒）
                    kotlinx.coroutines.delay(30_000)
                    continue
                }
                
                // 获取当前配置节点
                val settingsRepo = com.trxsafe.payment.settings.SettingsRepository(this@TransactionHistoryActivity)
                val config = settingsRepo.loadConfig()
                
                // 使用 try-finally 确保 apiWrapper 关闭
                val apiWrapper = org.tron.trident.core.ApiWrapper(config.nodeUrl)
                try {
                    val broadcaster = com.trxsafe.payment.broadcast.TransactionBroadcaster(this@TransactionHistoryActivity, apiWrapper)
                    
                    for (record in records) {
                        try {
                            val statusInfo = broadcaster.getTransactionStatus(record.txid)
                            handleStatusUpdate(record, statusInfo)
                        } catch (e: Exception) {
                            // 单个任务失败不影响其他
                        }
                    }
                } finally {
                    apiWrapper.close()
                }
                
                // 刷新列表
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    loadData()
                }
                
                // 每 10 秒轮询一次
                kotlinx.coroutines.delay(10_000)
            }
        }
    }

    private fun handleStatusUpdate(record: TransactionRecord, info: com.trxsafe.payment.broadcast.TransactionStatusInfo) {
        when (info) {
            is com.trxsafe.payment.broadcast.TransactionStatusInfo.Success -> {
                recorder.updateRecord(record.txid) { 
                    it.copy(
                        status = TransactionStatus.SUCCESS,
                        blockHeight = info.blockHeight,
                        feeSun = info.feeSun,
                        netUsage = info.netUsage,
                        energyUsage = info.energyUsage
                    )
                }
            }
            is com.trxsafe.payment.broadcast.TransactionStatusInfo.Failed -> {
                recorder.updateRecord(record.txid) { 
                    it.copy(status = TransactionStatus.FAILURE, errorMessage = info.reason)
                }
            }
            else -> { /* Keep PENDING or handle error */ }
        }
    }
    
    private fun initViews() {
        adapter = TransactionAdapter(
            onItemClick = { record ->
                showTransactionDetail(record)
            }
        )
        
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.chipGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.chipSent -> loadData()
                R.id.chipReceived -> loadIncomingData()
            }
        }
    }
    
    private fun loadData() {
        val records = recorder.getAllRecords()
        updateList(records)
    }

    private fun loadIncomingData() {
        androidx.lifecycle.lifecycleScope.launch {
            binding.recyclerView.visibility = View.GONE
            binding.tvEmpty.visibility = View.VISIBLE
            binding.tvEmpty.text = "正在查询链上记录..."
            
            try {
                val walletManager = com.trxsafe.payment.wallet.WalletManager(this@TransactionHistoryActivity)
                val address = walletManager.getAddress() ?: return@launch
                
                val settingsRepo = com.trxsafe.payment.settings.SettingsRepository(this@TransactionHistoryActivity)
                val config = settingsRepo.loadConfig()
                
                val apiWrapper = org.tron.trident.core.ApiWrapper(config.nodeUrl)
                try {
                    val broadcaster = com.trxsafe.payment.broadcast.TransactionBroadcaster(this@TransactionHistoryActivity, apiWrapper)
                    val incoming = broadcaster.getIncomingTransactions(address)
                    
                    if (incoming.isEmpty()) {
                        binding.tvEmpty.text = "暂无收到转账记录\n(或当前节点不支持此查询)"
                    } else {
                        updateList(incoming)
                    }
                } finally {
                    apiWrapper.close()
                }
            } catch (e: Exception) {
                binding.tvEmpty.text = "查询失败：${e.message}"
            }
        }
    }

    private fun updateList(records: List<TransactionRecord>) {
        if (records.isEmpty()) {
            binding.recyclerView.visibility = View.GONE
            binding.tvEmpty.visibility = View.VISIBLE
            binding.tvEmpty.text = getString(R.string.no_transactions)
        } else {
            binding.recyclerView.visibility = View.VISIBLE
            binding.tvEmpty.visibility = View.GONE
            adapter.submitList(records)
        }
    }
    
    private fun showTransactionDetail(record: TransactionRecord) {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_transaction_detail, null)
        dialog.setContentView(view)

        // Amount
        val tvAmountLarge = view.findViewById<TextView>(R.id.tvAmountLarge)
        tvAmountLarge.text = "- ${AmountUtils.formatAmount(record.amountSun)}"

        // Status
        val tvStatusBadge = view.findViewById<TextView>(R.id.tvStatusBadge)
        when (record.status) {
            TransactionStatus.SUCCESS -> {
                tvStatusBadge.text = "交易成功"
                tvStatusBadge.setBackgroundColor(Color.parseColor("#4CAF50"))
            }
            TransactionStatus.FAILURE -> {
                tvStatusBadge.text = "交易失败"
                tvStatusBadge.setBackgroundColor(Color.parseColor("#F44336"))
            }
            TransactionStatus.PENDING -> {
                tvStatusBadge.text = "确认中"
                tvStatusBadge.setBackgroundColor(Color.parseColor("#FF9800"))
            }
        }

        // Rows
        setupDetailRow(view.findViewById(R.id.rowFrom), "发送方", record.fromAddress.ifEmpty { "本地钱包" })
        setupDetailRow(view.findViewById(R.id.rowTo), "接收方", record.toAddress)
        setupDetailRow(view.findViewById(R.id.rowTxId), "交易 ID", record.txid) {
            copyToClipboard(record.txid, "TXID 已复制")
        }
        setupDetailRow(view.findViewById(R.id.rowBlock), "区块高度", if (record.blockHeight > 0) record.blockHeight.toString() else "等待中")
        setupDetailRow(view.findViewById(R.id.rowFee), "手续费", "${AmountUtils.formatAmount(record.feeSun)}")
        
        val usageStr = buildString {
            if (record.netUsage > 0) append("${record.netUsage} Bandwidth ")
            if (record.energyUsage > 0) append("${record.energyUsage} Energy")
            if (isEmpty()) append("无消耗")
        }
        setupDetailRow(view.findViewById(R.id.rowUsage), "资源消耗", usageStr)
        
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        setupDetailRow(view.findViewById(R.id.rowTime), "交易时间", sdf.format(Date(record.timestamp)))
        
        if (record.memo.isNotEmpty()) {
            setupDetailRow(view.findViewById(R.id.rowMemo), "备注", record.memo)
        } else {
            view.findViewById<View>(R.id.rowMemo).visibility = View.GONE
        }

        // Tronscan Button
        view.findViewById<View>(R.id.btnTronscan).setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://tronscan.org/#/transaction/${record.txid}"))
            startActivity(intent)
        }

        dialog.show()
    }

    private fun setupDetailRow(rowView: View, label: String, value: String, onClick: (() -> Unit)? = null) {
        rowView.findViewById<TextView>(R.id.tvLabel).text = label
        val tvValue = rowView.findViewById<TextView>(R.id.tvValue)
        tvValue.text = value
        if (onClick != null) {
            rowView.setOnClickListener { onClick() }
            tvValue.setTextColor(getColor(R.color.primary))
        }
    }

    private fun copyToClipboard(text: String, toastMsg: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("TRX Safe", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show()
    }
}

/**
 * 交易列表适配器
 */
class TransactionAdapter(
    private val onItemClick: (TransactionRecord) -> Unit
) : RecyclerView.Adapter<TransactionAdapter.ViewHolder>() {
    
    private var items = listOf<TransactionRecord>()
    
    fun submitList(newItems: List<TransactionRecord>) {
        items = newItems
        notifyDataSetChanged()
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTransactionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }
    
    override fun getItemCount() = items.size
    inner class ViewHolder(private val binding: ItemTransactionBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(record: TransactionRecord) {
            binding.root.setOnClickListener { onItemClick(record) }
            
            // 获取当前钱包地址以区分进出账
            val walletManager = com.trxsafe.payment.wallet.WalletManager(binding.root.context)
            val myAddress = walletManager.getAddress()
            val isIncoming = record.toAddress == myAddress && record.fromAddress != myAddress
            
            // 接收地址
            binding.tvToAddress.text = if (isIncoming) "来自: ${record.fromAddress}" else "发往: ${record.toAddress}"
            
            // 金额
            val amountTrx = AmountUtils.formatAmount(record.amountSun)
            if (isIncoming) {
                binding.tvAmount.text = "+ $amountTrx"
                binding.tvAmount.setTextColor(Color.parseColor("#4CAF50")) // Green
            } else {
                binding.tvAmount.text = "- $amountTrx"
                binding.tvAmount.setTextColor(Color.parseColor("#F44336")) // Red
            }
            
            // 时间
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            binding.tvTime.text = sdf.format(Date(record.timestamp))
            
            // 状态
            when (record.status) {
                TransactionStatus.SUCCESS -> {
                    binding.tvStatus.text = "成功"
                    binding.tvStatus.setTextColor(Color.parseColor("#4CAF50")) // Green
                }
                TransactionStatus.FAILURE -> {
                    binding.tvStatus.text = "失败"
                    binding.tvStatus.setTextColor(Color.parseColor("#F44336")) // Red
                }
                TransactionStatus.PENDING -> {
                    binding.tvStatus.text = "待确认"
                    binding.tvStatus.setTextColor(Color.parseColor("#FF9800")) // Orange
                }
            }
            
            // TXID
            if (record.txid.isNotEmpty()) {
                binding.tvTxId.visibility = View.VISIBLE
                binding.tvTxId.text = "TXID: ${record.txid}"
            } else {
                binding.tvTxId.visibility = View.GONE
            }
        }
    }
}
