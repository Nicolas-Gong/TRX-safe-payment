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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.trxsafe.payment.R
import com.trxsafe.payment.broadcast.TransactionRecord
import com.trxsafe.payment.broadcast.TransactionRecorder
import com.trxsafe.payment.broadcast.TransactionStatus
import com.trxsafe.payment.databinding.ActivityTransactionHistoryBinding
import com.trxsafe.payment.databinding.ItemTransactionBinding
import com.trxsafe.payment.utils.AmountUtils
import com.trxsafe.payment.utils.TransactionConverter
import com.trxsafe.payment.utils.setDebouncedClick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private var currentChipId: Int = R.id.chipSent
    private var currentSentChipId: Int = R.id.chipAppSent

    // 分页相关 - 优化：单页加载更多数据，减少网络请求频率
    private val pageSize = 50 // 从20条增加到50条，减少网络请求次数
    private var currentPage = 0
    private var isLoading = false
    private var hasMoreData = true
    private var isLoadingMore = false // 新增：标记是否正在加载更多数据

    // 防抖相关 - 避免快速滑动时频繁触发懒加载
    private var lastScrollTime = 0L
    private val scrollDebounceDelay = 100L // 降低到100ms，避免阻止快速滚动触发

    // 数据缓存 - 按类型存储
    private var appOutgoingTransactions: MutableList<TransactionRecord> = mutableListOf()
    private var chainOutgoingTransactions: MutableList<TransactionRecord> = mutableListOf()
    private var incomingTransactions: MutableList<TransactionRecord> = mutableListOf()

    // 分页URL缓存
    private var chainOutgoingNextUrl: String? = null
    private var incomingNextUrl: String? = null

    // 加载状态
    private var isLoadingAppOutgoing = false
    private var isLoadingChainOutgoing = false
    private var isLoadingIncoming = false

    // 优化：预先获取钱包地址，避免每次bind都调用
    private var myWalletAddress: String = ""

    // 优化：缓存时间格式化器
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    // 协程作业管理
    private var statusPollingJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTransactionHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        recorder = TransactionRecorder(this)

        initViews()
        // 预加载所有类型的数据，提升用户体验
        preloadAllData()
        startStatusPolling()
    }
    
    override fun onResume() {
        super.onResume()
    }
    
    private fun startStatusPolling() {
        statusPollingJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val records =
                    recorder.getAllRecords().filter { it.status == TransactionStatus.PENDING }
                if (records.isEmpty()) {
                    // 如果没有待定交易，等一会儿再查，或者直接结束（这里选择等 30 秒再查，如果有的话则 10 秒）
                    delay(30_000)
                    continue
                }

                // 获取当前配置节点
                val settingsRepo =
                    com.trxsafe.payment.settings.SettingsRepository.getInstance(this@TransactionHistoryActivity)
                val config = settingsRepo.loadConfig()

                // 使用HTTP客户端
                val httpClient = com.trxsafe.payment.network.TronHttpClient(config.nodeUrl)
                val broadcaster = com.trxsafe.payment.broadcast.TransactionBroadcaster(
                    this@TransactionHistoryActivity, null, httpClient
                )

                for (record in records) {
                    try {
                        val statusInfo = broadcaster.getTransactionStatus(record.txid)
                        handleStatusUpdate(record, statusInfo)
                    } catch (e: Exception) {
                        // 单个任务失败不影响其他
                        android.util.Log.w("TransactionHistory", "查询交易状态失败: ${e.message}")
                    }
                }

                // 刷新列表 - 重新加载当前显示的数据
                withContext(Dispatchers.Main) {
                    when (currentChipId) {
                        R.id.chipSent -> refreshCurrentData()
                        R.id.chipReceived -> refreshCurrentData()
                    }
                }

                // 每 10 秒轮询一次
                delay(10_000)
            }
        }
    }

    private fun handleStatusUpdate(
        record: TransactionRecord, info: com.trxsafe.payment.broadcast.TransactionStatusInfo
    ) {
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

            else -> { /* Keep PENDING or handle error */
            }
        }
    }

    private fun initViews() {
        // 预先获取钱包地址，避免每次bind都调用
        val walletManager = com.trxsafe.payment.wallet.WalletManager(this)
        myWalletAddress = walletManager.getAddress() ?: ""

        adapter = TransactionAdapter(
            myWalletAddress = myWalletAddress, // 传递预获取的地址
            dateFormat = dateFormat, // 传递缓存的时间格式化器
            onItemClick = { record ->
                showTransactionDetail(record)
            })

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        // 添加懒加载监听器 - 优化触发时机和频率，避免快速滑动卡顿
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)

                // 防抖：避免快速滑动时频繁触发
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastScrollTime < scrollDebounceDelay) {
                    return
                }
                lastScrollTime = currentTime

                val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                val visibleItemCount = layoutManager.childCount
                val totalItemCount = layoutManager.itemCount
                val firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()

                // 控制回到顶部按钮显示/隐藏
                val shouldShowScrollToTop = firstVisibleItemPosition > 5 // 滚动超过5个项目时显示
                binding.fabScrollToTop.visibility =
                    if (shouldShowScrollToTop) View.VISIBLE else View.GONE

                // 提前触发加载：当滚动到距离底部10个项目时开始加载（避免接口延迟导致的多次触发）
                val shouldLoadMore =
                    !isLoading && hasMoreData && (visibleItemCount + firstVisibleItemPosition) >= totalItemCount - 10

                if (shouldLoadMore) {
                    // 直接触发，避免延迟导致错过时机
                    loadMoreData()
                }
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
            }
        })

        // 回到顶部按钮点击事件
        binding.fabScrollToTop.setDebouncedClick(debounceDelayMs = 1000) {
            binding.recyclerView.smoothScrollToPosition(0)
            binding.fabScrollToTop.visibility = View.GONE
        }

        // 设置下拉刷新
        binding.swipeRefreshLayout.setOnRefreshListener {
            refreshCurrentData()
        }

        // 设置颜色主题
        binding.swipeRefreshLayout.setColorSchemeResources(
            R.color.primary, R.color.secondary, R.color.tertiary
        )

        // 主分类选择监听器
        binding.chipGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            currentChipId = checkedId
            when (checkedId) {
                R.id.chipSent -> {
                    binding.chipGroupSent.visibility = View.VISIBLE
                    loadCurrentSentType() // 使用当前选择的子分类
                }

                R.id.chipReceived -> {
                    binding.chipGroupSent.visibility = View.GONE
                    // 优先使用预加载的数据
                    if (incomingTransactions.isNotEmpty()) {
                        updateList(incomingTransactions)
                        hasMoreData = incomingNextUrl != null
                    } else {
                        // 如果预加载失败，重新加载
                        loadIncomingTransactions()
                    }
                }
            }
        }

        // 转出子分类选择监听器
        binding.chipGroupSent.setOnCheckedStateChangeListener { group, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            currentSentChipId = checkedId
            when (checkedId) {
                R.id.chipAppSent -> loadAppOutgoingTransactions()
                R.id.chipChainSent -> {
                    // 优先使用预加载的数据
                    if (chainOutgoingTransactions.isNotEmpty()) {
                        android.util.Log.d("TransactionHistory", "使用预加载的全部转出数据: ${chainOutgoingTransactions.size}条")
                        updateList(chainOutgoingTransactions)
                        hasMoreData = chainOutgoingNextUrl != null
                    } else {
                        android.util.Log.d("TransactionHistory", "预加载数据不存在，重新加载全部转出")
                        // 如果预加载失败，重新加载
                        loadChainOutgoingTransactions()
                    }
                }
            }
        }

        // 隐藏区块链历史加载按钮（现在集成到分类中）
        binding.btnLoadBlockchainHistory.visibility = View.GONE
    }

    private fun refreshCurrentData() {
        // 下拉刷新时清除所有缓存，确保数据一致性
        clearAllCaches()

        // 重新加载当前显示的数据
        when (currentChipId) {
            R.id.chipSent -> {
                when (currentSentChipId) {
                    R.id.chipAppSent -> loadAppOutgoingTransactions()
                    R.id.chipChainSent -> loadChainOutgoingTransactions()
                }
            }

            R.id.chipReceived -> loadIncomingTransactions()
        }
    }

    private fun clearAllCaches() {
        // 清除所有交易缓存，避免数据不一致
        appOutgoingTransactions.clear()
        chainOutgoingTransactions.clear()
        incomingTransactions.clear()

        // 重置分页状态
        currentPage = 0
        hasMoreData = true

        // 重置加载状态
        isLoadingAppOutgoing = false
        isLoadingChainOutgoing = false
        isLoadingIncoming = false
    }

    private fun loadCurrentSentType() {
        when (currentSentChipId) {
            R.id.chipAppSent -> loadAppOutgoingTransactions()
            R.id.chipChainSent -> {
                // 优先使用预加载的数据，避免重复加载
                if (chainOutgoingTransactions.isNotEmpty()) {
                    android.util.Log.d("TransactionHistory", "切换使用预加载的全部转出数据: ${chainOutgoingTransactions.size}条")
                    updateList(chainOutgoingTransactions)
                    hasMoreData = chainOutgoingNextUrl != null
                } else {
                    android.util.Log.d("TransactionHistory", "切换时预加载数据不存在，重新加载全部转出")
                    loadChainOutgoingTransactions()
                }
            }
        }
    }

    private fun preloadAllData() {
        // 并行预加载全部转出和转入数据，提升切换标签的用户体验
        lifecycleScope.launch {
            try {
                // 只预加载链上数据（全部转出和转入），本地数据加载快不需要预加载
                val chainOutgoingJob = launch { preloadChainOutgoingTransactions() }
                val incomingJob = launch { preloadIncomingTransactions() }

                // 等待链上数据加载完成（本地数据会立即显示）
                chainOutgoingJob.join()
                incomingJob.join()

                // 预加载完成后数据已存储在缓存中，等待用户主动选择标签时显示
            } catch (e: Exception) {
                android.util.Log.e("TransactionHistory", "预加载数据失败: ${e.message}", e)
                // 预加载失败不影响基本功能
            }
        }

        // 立即显示本地转出数据（加载快）
        loadOutgoingTransactions()
    }

    /**
     * 预加载链上转出交易数据（只加载数据，不显示UI）
     */
    private suspend fun preloadChainOutgoingTransactions() {
        try {
            val walletManager = com.trxsafe.payment.wallet.WalletManager(this@TransactionHistoryActivity)
            val address = walletManager.getAddress() ?: return

            val settingsRepo = com.trxsafe.payment.settings.SettingsRepository.getInstance(this@TransactionHistoryActivity)
            val config = settingsRepo.loadConfig()

            // 使用HTTP客户端查询链上转出交易
            val httpClient = com.trxsafe.payment.network.TronHttpClient(config.nodeUrl)
            val result = httpClient.getOutgoingTransactions(address, pageSize, null)

            if (result.data.isNotEmpty()) {
                // 将交易转换为TransactionRecord格式
                val records = result.data.mapNotNull { txJson ->
                    try {
                        TransactionConverter.convertBlockchainTransactionToRecord(txJson, address, false)
                    } catch (e: Exception) {
                        android.util.Log.w("TransactionHistory", "预加载解析链上转出交易失败: ${e.message}", e)
                        null
                    }
                }

                // 只存储到缓存，不显示UI
                chainOutgoingTransactions.clear()
                chainOutgoingTransactions.addAll(records)
                chainOutgoingNextUrl = result.nextUrl
            }
        } catch (e: Exception) {
            android.util.Log.w("TransactionHistory", "预加载链上转出交易失败: ${e.message}", e)
        }
    }

    /**
     * 预加载转入交易数据（只加载数据，不显示UI）
     */
    private suspend fun preloadIncomingTransactions() {
        try {
            val walletManager = com.trxsafe.payment.wallet.WalletManager(this@TransactionHistoryActivity)
            val address = walletManager.getAddress() ?: return

            val settingsRepo = com.trxsafe.payment.settings.SettingsRepository.getInstance(this@TransactionHistoryActivity)
            val config = settingsRepo.loadConfig()

            // 使用HTTP客户端查询转入交易
            val httpClient = com.trxsafe.payment.network.TronHttpClient(config.nodeUrl)
            val result = httpClient.getIncomingTransactions(address, pageSize, null)

            if (result.data.isNotEmpty()) {
                // 将交易转换为TransactionRecord格式
                val records = result.data.mapNotNull { txJson ->
                    try {
                        TransactionConverter.convertBlockchainTransactionToRecord(txJson, address, true)
                    } catch (e: Exception) {
                        android.util.Log.w("TransactionHistory", "预加载解析转入交易失败: ${e.message}", e)
                        null
                    }
                }

                // 只存储到缓存，不显示UI
                incomingTransactions.clear()
                incomingTransactions.addAll(records)
                incomingNextUrl = result.nextUrl
            }
        } catch (e: Exception) {
            android.util.Log.w("TransactionHistory", "预加载转入交易失败: ${e.message}", e)
        }
    }

    private fun loadOutgoingTransactions() {
        // 默认加载本APP转出
        loadAppOutgoingTransactions()
    }

    private fun loadAppOutgoingTransactions() {
        if (isLoadingAppOutgoing) return

        lifecycleScope.launch {
            isLoadingAppOutgoing = true
            binding.recyclerView.visibility = View.GONE
            binding.tvEmpty.visibility = View.VISIBLE
            binding.tvEmpty.text = "正在加载本APP转出记录..."

            try {
                // 加载本APP记录的交易（通过TransactionRecorder）
                val records =
                    recorder.getAllRecords().filter { it.fromAddress.isNotEmpty() } // 只显示转出记录
                        .sortedByDescending { it.timestamp }

                appOutgoingTransactions.clear()
                appOutgoingTransactions.addAll(records)

                if (records.isEmpty()) {
                    binding.tvEmpty.text = "暂无本APP转出记录"
                } else {
                    updateList(records)
                }
            } catch (e: Exception) {
                binding.tvEmpty.text = "加载失败：${e.message}"
            } finally {
                isLoadingAppOutgoing = false
            }
        }
    }

    private fun loadChainOutgoingTransactions() {
        if (isLoadingChainOutgoing) return

        lifecycleScope.launch {
            isLoadingChainOutgoing = true
            binding.recyclerView.visibility = View.GONE
            binding.tvEmpty.visibility = View.VISIBLE
            binding.tvEmpty.text = "正在查询链上转出记录..."

            try {
                val walletManager =
                    com.trxsafe.payment.wallet.WalletManager(this@TransactionHistoryActivity)
                val address = walletManager.getAddress() ?: run {
                    binding.tvEmpty.text = "未找到钱包地址，请先设置钱包"
                    isLoadingChainOutgoing = false
                    return@launch
                }

                val settingsRepo =
                    com.trxsafe.payment.settings.SettingsRepository.getInstance(this@TransactionHistoryActivity)
                val config = settingsRepo.loadConfig()

                // 重置分页状态
                currentPage = 0
                hasMoreData = true
                chainOutgoingNextUrl = null

                // 使用HTTP客户端查询链上转出交易
                val httpClient = com.trxsafe.payment.network.TronHttpClient(config.nodeUrl)
                val result =
                    httpClient.getOutgoingTransactions(address, pageSize, chainOutgoingNextUrl)

                if (result.data.isEmpty()) {
                    binding.tvEmpty.text = "暂无链上转出记录\n(可能是网络问题或节点不支持此查询)"
                    hasMoreData = false
                } else {
                    // 将交易转换为TransactionRecord格式
                    val records = result.data.mapNotNull { txJson ->
                        try {
                            TransactionConverter.convertBlockchainTransactionToRecord(txJson, address, false)
                        } catch (e: Exception) {
                            android.util.Log.w(
                                "TransactionHistory", "解析链上转出交易失败: ${e.message}", e
                            )
                            null
                        }
                    }

                    chainOutgoingTransactions.clear()
                    chainOutgoingTransactions.addAll(records)
                    chainOutgoingNextUrl = result.nextUrl
                    updateList(records)

                    // 使用API返回的分页信息
                    hasMoreData = result.hasMore
                }
            } catch (e: Exception) {
                android.util.Log.e("TransactionHistory", "加载链上转出交易失败: ${e.message}", e)
                binding.tvEmpty.text =
                    "查询失败：${e.localizedMessage ?: e.message}\n请检查网络连接或更换节点"
            } finally {
                isLoadingChainOutgoing = false
            }
        }
    }

    private fun loadIncomingTransactions() {
        if (isLoadingIncoming) return

        lifecycleScope.launch {
            isLoadingIncoming = true
            binding.recyclerView.visibility = View.GONE
            binding.tvEmpty.visibility = View.VISIBLE
            binding.tvEmpty.text = "正在查询转入记录..."

            try {
                val walletManager =
                    com.trxsafe.payment.wallet.WalletManager(this@TransactionHistoryActivity)
                val address = walletManager.getAddress() ?: run {
                    binding.tvEmpty.text = "未找到钱包地址，请先设置钱包"
                    isLoadingIncoming = false
                    return@launch
                }

                val settingsRepo =
                    com.trxsafe.payment.settings.SettingsRepository.getInstance(this@TransactionHistoryActivity)
                val config = settingsRepo.loadConfig()

                // 重置分页状态
                currentPage = 0
                hasMoreData = true
                incomingNextUrl = null

                // 使用HTTP客户端查询转入交易
                val httpClient = com.trxsafe.payment.network.TronHttpClient(config.nodeUrl)
                val result = httpClient.getIncomingTransactions(address, pageSize, incomingNextUrl)

                if (result.data.isEmpty()) {
                    binding.tvEmpty.text = "暂无转入记录\n(可能是网络问题或节点不支持此查询)"
                    hasMoreData = false
                } else {
                    // 将交易转换为TransactionRecord格式
                    val records = result.data.mapNotNull { txJson ->
                        try {
                            TransactionConverter.convertBlockchainTransactionToRecord(txJson, address, true)
                        } catch (e: Exception) {
                            android.util.Log.w(
                                "TransactionHistory", "解析转入交易失败: ${e.message}", e
                            )
                            null
                        }
                    }

                    this@TransactionHistoryActivity.incomingTransactions.clear()
                    this@TransactionHistoryActivity.incomingTransactions.addAll(records)
                    incomingNextUrl = result.nextUrl
                    updateList(records)

                    // 使用API返回的分页信息
                    hasMoreData = result.hasMore
                }
            } catch (e: Exception) {
                android.util.Log.e("TransactionHistory", "加载转入交易失败: ${e.message}", e)
                binding.tvEmpty.text =
                    "查询失败：${e.localizedMessage ?: e.message}\n请检查网络连接或更换节点"
            } finally {
                isLoadingIncoming = false
            }
        }
    }

    private fun loadMoreData() {
        if (isLoading || !hasMoreData) {
            // 如果没有更多数据，显示到底提示
            if (!hasMoreData && !isLoadingMore) {
                showNoMoreDataToast()
            }
            return
        }

        Toast.makeText(this, "正在加载更多数据...", Toast.LENGTH_SHORT).show()

        isLoadingMore = true
        isLoading = true
        currentPage++

        lifecycleScope.launch {
            try {
                when (currentChipId) {
                    R.id.chipSent -> {
                        when (currentSentChipId) {
                            R.id.chipAppSent -> {
                                // 本APP记录不支持分页，直接返回
                                hasMoreData = false
                                showNoMoreDataToast()
                            }

                            R.id.chipChainSent -> {
                                loadMoreChainOutgoingTransactions()
                            }
                        }
                    }

                    R.id.chipReceived -> {
                        loadMoreIncomingTransactions()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("LazyLoad", "加载更多数据失败: ${e.message}")
                Toast.makeText(
                    this@TransactionHistoryActivity, "加载失败: ${e.message}", Toast.LENGTH_SHORT
                ).show()
            } finally {
                isLoading = false
                isLoadingMore = false
            }
        }
    }

    private fun showNoMoreDataToast() {
        Toast.makeText(this, "已经到底了，没有更多数据", Toast.LENGTH_SHORT).show()
    }

    private suspend fun loadMoreChainOutgoingTransactions() {
        try {
            val walletManager =
                com.trxsafe.payment.wallet.WalletManager(this@TransactionHistoryActivity)
            val address = walletManager.getAddress() ?: return

            val settingsRepo =
                com.trxsafe.payment.settings.SettingsRepository.getInstance(this@TransactionHistoryActivity)
            val config = settingsRepo.loadConfig()

            val httpClient = com.trxsafe.payment.network.TronHttpClient(config.nodeUrl)
            val result = httpClient.getOutgoingTransactions(address, pageSize, chainOutgoingNextUrl)

            if (result.data.isNotEmpty()) {
                val walletManager =
                    com.trxsafe.payment.wallet.WalletManager(this@TransactionHistoryActivity)
                val address = walletManager.getAddress() ?: return

                val records = result.data.mapNotNull { txJson ->
                    try {
                        TransactionConverter.convertBlockchainTransactionToRecord(txJson, address, false)
                    } catch (e: Exception) {
                        android.util.Log.w(
                            "TransactionHistory", "解析更多链上转出交易失败: ${e.message}", e
                        )
                        null
                    }
                }

                if (records.isNotEmpty()) {
                    chainOutgoingTransactions.addAll(records)
                    chainOutgoingNextUrl = result.nextUrl
                    updateList(chainOutgoingTransactions)

                    // 使用API返回的分页信息
                    hasMoreData = result.hasMore
                } else {
                    // 如果过滤后没有有效记录，说明可能没有更多数据了
                    hasMoreData = false
                }
            } else {
                // API返回空列表，说明没有更多数据了
                hasMoreData = false
            }
        } catch (e: Exception) {
            android.util.Log.w("TransactionHistory", "加载更多链上转出交易失败: ${e.message}")
            hasMoreData = false
        }
    }

    private suspend fun loadMoreIncomingTransactions() {
        try {
            val walletManager =
                com.trxsafe.payment.wallet.WalletManager(this@TransactionHistoryActivity)
            val address = walletManager.getAddress() ?: return

            val settingsRepo =
                com.trxsafe.payment.settings.SettingsRepository.getInstance(this@TransactionHistoryActivity)
            val config = settingsRepo.loadConfig()

            val httpClient = com.trxsafe.payment.network.TronHttpClient(config.nodeUrl)
            val result = httpClient.getIncomingTransactions(address, pageSize, incomingNextUrl)

            if (result.data.isNotEmpty()) {
                val walletManager =
                    com.trxsafe.payment.wallet.WalletManager(this@TransactionHistoryActivity)
                val address = walletManager.getAddress() ?: return

                val records = result.data.mapNotNull { txJson ->
                    try {
                        TransactionConverter.convertBlockchainTransactionToRecord(txJson, address, true)
                    } catch (e: Exception) {
                        android.util.Log.w(
                            "TransactionHistory", "解析更多转入交易失败: ${e.message}", e
                        )
                        null
                    }
                }

                if (records.isNotEmpty()) {
                    incomingTransactions.addAll(records)
                    incomingNextUrl = result.nextUrl
                    updateList(incomingTransactions)

                    // 使用API返回的分页信息
                    hasMoreData = result.hasMore
                } else {
                    // 如果过滤后没有有效记录，说明可能没有更多数据了
                    hasMoreData = false
                }
            } else {
                // API返回空列表，说明没有更多数据了
                hasMoreData = false
            }
        } catch (e: Exception) {
            android.util.Log.w("TransactionHistory", "加载更多转入交易失败: ${e.message}")
            hasMoreData = false
        }
    }

    private fun updateList(records: List<TransactionRecord>) {
        // 确保在主线程执行UI更新
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            runOnUiThread {
                updateListInternal(records)
            }
        } else {
            updateListInternal(records)
        }
    }

    private fun updateListInternal(records: List<TransactionRecord>) {
        if (records.isEmpty()) {
            binding.recyclerView.visibility = View.GONE
            binding.tvEmpty.visibility = View.VISIBLE
            binding.tvEmpty.text = getString(R.string.no_transactions)
        } else {
            binding.recyclerView.visibility = View.VISIBLE
            binding.tvEmpty.visibility = View.GONE
            adapter.submitList(records)
        }

        // 停止下拉刷新动画
        binding.swipeRefreshLayout.isRefreshing = false
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
        setupDetailRow(
            view.findViewById(R.id.rowFrom), "发送方", record.fromAddress.ifEmpty { "本地钱包" })
        setupDetailRow(view.findViewById(R.id.rowTo), "接收方", record.toAddress)
        setupDetailRow(view.findViewById(R.id.rowTxId), "交易 ID", record.txid) {
            copyToClipboard(record.txid, "TXID 已复制")
        }
        setupDetailRow(
            view.findViewById(R.id.rowBlock),
            "区块高度",
            if (record.blockHeight > 0) record.blockHeight.toString() else "等待中"
        )
        setupDetailRow(
            view.findViewById(R.id.rowFee), "手续费", "${AmountUtils.formatAmount(record.feeSun)}"
        )

        val usageStr = buildString {
            if (record.netUsage > 0) append("${record.netUsage} Bandwidth ")
            if (record.energyUsage > 0) append("${record.energyUsage} Energy")
            if (isEmpty()) append("无消耗")
        }
        setupDetailRow(view.findViewById(R.id.rowUsage), "资源消耗", usageStr)

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        setupDetailRow(
            view.findViewById(R.id.rowTime), "交易时间", sdf.format(Date(record.timestamp))
        )

        if (record.memo.isNotEmpty()) {
            setupDetailRow(view.findViewById(R.id.rowMemo), "备注", record.memo)
        } else {
            view.findViewById<View>(R.id.rowMemo).visibility = View.GONE
        }

        // Tronscan Button
        view.findViewById<View>(R.id.btnTronscan).setDebouncedClick(debounceDelayMs = 1000) {
            val intent = Intent(
                Intent.ACTION_VIEW, Uri.parse("https://tronscan.org/#/transaction/${record.txid}")
            )
            startActivity(intent)
        }

        dialog.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 取消状态轮询协程，避免内存泄漏
        statusPollingJob?.cancel()
        statusPollingJob = null
    }

    private fun setupDetailRow(
        rowView: View, label: String, value: String, onClick: (() -> Unit)? = null
    ) {
        rowView.findViewById<TextView>(R.id.tvLabel).text = label
        val tvValue = rowView.findViewById<TextView>(R.id.tvValue)
        tvValue.text = value
        if (onClick != null) {
            rowView.setDebouncedClick(debounceDelayMs = 1000) { onClick() }
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
    private val myWalletAddress: String, // 预获取的钱包地址
    private val dateFormat: SimpleDateFormat, // 缓存的时间格式化器
    private val onItemClick: (TransactionRecord) -> Unit
) : RecyclerView.Adapter<TransactionAdapter.ViewHolder>() {

    private var items = listOf<TransactionRecord>()

    fun submitList(newItems: List<TransactionRecord>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTransactionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(private val binding: ItemTransactionBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(record: TransactionRecord) {
            binding.root.setDebouncedClick(debounceDelayMs = 1000) { onItemClick(record) }

            // 使用预获取的钱包地址，避免每次bind都调用WalletManager
            val isIncoming = isTransactionIncoming(record, myWalletAddress)

            // 交易类型显示
            binding.tvTxType.text = if (isIncoming) "转入" else "转出"

            // 地址标签和地址显示逻辑
            if (isIncoming) {
                binding.tvAddressLabel.text = "来自："
                binding.tvToAddress.text =
                    record.fromAddress.takeIf { it.isNotEmpty() } ?: "未知地址"
            } else {
                binding.tvAddressLabel.text = "发往："
                binding.tvToAddress.text = record.toAddress.takeIf { it.isNotEmpty() } ?: "未知地址"
            }

            // 金额显示
            val amountTrx = AmountUtils.formatAmount(record.amountSun)
            if (isIncoming) {
                binding.tvAmount.text = "+ $amountTrx"
                binding.tvAmount.setTextColor(Color.parseColor("#4CAF50")) // Green
            } else {
                binding.tvAmount.text = "- $amountTrx"
                binding.tvAmount.setTextColor(Color.parseColor("#F44336")) // Red
            }

            // 时间 - 使用缓存的格式化器，避免每次创建
            binding.tvTime.text = dateFormat.format(Date(record.timestamp))

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

        private fun isTransactionIncoming(record: TransactionRecord, myAddress: String): Boolean {
            if (myAddress.isEmpty()) return false

            // 直接比较地址（支持 base58 和 hex 格式）
            val toAddressMatches = addressesMatch(record.toAddress, myAddress)
            val fromAddressMatches = addressesMatch(record.fromAddress, myAddress)

            // 如果接收地址匹配且发送地址不匹配，则是转入
            return toAddressMatches && !fromAddressMatches
        }

        private fun addressesMatch(addr1: String, addr2: String): Boolean {
            if (addr1.isEmpty() || addr2.isEmpty()) return false

            // 直接字符串比较
            if (addr1 == addr2) return true

            // 如果其中一个是 hex 格式（41开头），尝试转换后比较
            if (addr1.startsWith("41") && addr1.length == 42) {
                try {
                    val base58Addr1 = org.tron.trident.utils.Base58Check.bytesToBase58(
                        org.tron.trident.utils.Numeric.hexStringToByteArray(addr1)
                    )
                    if (base58Addr1 == addr2) return true
                } catch (e: Exception) {
                    // 转换失败，继续
                }
            }

            if (addr2.startsWith("41") && addr2.length == 42) {
                try {
                    val base58Addr2 = org.tron.trident.utils.Base58Check.bytesToBase58(
                        org.tron.trident.utils.Numeric.hexStringToByteArray(addr2)
                    )
                    if (base58Addr2 == addr1) return true
                } catch (e: Exception) {
                    // 转换失败，继续
                }
            }

            return false
        }
    }
}
