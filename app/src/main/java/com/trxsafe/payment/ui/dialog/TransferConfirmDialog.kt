package com.trxsafe.payment.ui.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.trxsafe.payment.R
import com.trxsafe.payment.settings.SettingsConfig
import com.trxsafe.payment.utils.AmountUtils

/**
 * 转账确认对话框
 * 
 * 安全特性：
 * - 禁止返回键关闭
 * - 禁止 1 秒内确认
 * - 必须长按 2 秒确认
 */
class TransferConfirmDialog(
    context: Context,
    private val config: SettingsConfig,
    private val fromAddress: String,
    private val transaction: org.tron.trident.proto.Chain.Transaction,
    private val onConfirmed: () -> Unit
) : BottomSheetDialog(context, R.style.Theme_TrxSafe) {
    
    companion object {
        /**
         * 最短等待时间（毫秒）
         * 禁止 1 秒内确认
         */
        private const val MIN_WAIT_TIME_MS = 1000L
        
        /**
         * 长按确认时间（毫秒）
         * 必须长按 2 秒
         */
        private const val LONG_PRESS_DURATION_MS = 2000L
        
        /**
         * 进度更新间隔（毫秒）
         */
        private const val PROGRESS_UPDATE_INTERVAL_MS = 50L
    }
    
    private var dialogShownTime: Long = 0
    private var isLongPressing = false
    private var longPressStartTime: Long = 0
    
    private val handler = Handler(Looper.getMainLooper())
    private var progressUpdateRunnable: Runnable? = null
    
    // 视图组件
    private lateinit var tvTransactionType: TextView
    private lateinit var tvRecipientAddress: TextView
    private lateinit var tvPricePerUnit: TextView
    private lateinit var tvMultiplier: TextView
    private lateinit var tvTotalAmount: TextView
    private lateinit var tvWarning1: TextView
    private lateinit var tvWarning2: TextView
    private lateinit var btnConfirm: Button
    private lateinit var btnCancel: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvHoldPrompt: TextView
    private lateinit var btnShowRaw: TextView
    private lateinit var tvRawHex: TextView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val view = LayoutInflater.from(context).inflate(
            R.layout.dialog_transfer_confirm,
            null,
            false
        )
        setContentView(view)
        
        // 禁止返回键关闭
        setCancelable(false)
        setCanceledOnTouchOutside(false)
        
        // 初始化视图
        initViews(view)
        
        // 绑定数据
        bindData()
        
        // 设置点击事件
        setupClickListeners()
        
        // 记录对话框显示时间
        dialogShownTime = System.currentTimeMillis()
    }
    
    /**
     * 初始化视图组件
     */
    private fun initViews(view: View) {
        tvTransactionType = view.findViewById(R.id.tvTransactionType)
        tvRecipientAddress = view.findViewById(R.id.tvRecipientAddress)
        tvPricePerUnit = view.findViewById(R.id.tvPricePerUnit)
        tvMultiplier = view.findViewById(R.id.tvMultiplier)
        tvTotalAmount = view.findViewById(R.id.tvTotalAmount)
        tvWarning1 = view.findViewById(R.id.tvWarning1)
        tvWarning2 = view.findViewById(R.id.tvWarning2)
        btnConfirm = view.findViewById(R.id.btnConfirm)
        btnCancel = view.findViewById(R.id.btnCancel)
        progressBar = view.findViewById(R.id.progressBar)
        tvHoldPrompt = view.findViewById(R.id.tvHoldPrompt)
        
        // 初始化进度条
        progressBar.max = 100
        progressBar.visibility = View.GONE
        
        btnShowRaw = view.findViewById(R.id.btnShowRaw)
        tvRawHex = view.findViewById(R.id.tvRawHex)
    }
    
    /**
     * 绑定数据
     */
    private fun bindData() {
        // 交易类型
        tvTransactionType.text = context.getString(R.string.confirm_transaction_type_trx)
        
        // 收款地址（完整）
        tvRecipientAddress.text = config.sellerAddress
        
        // 单价
        val priceTrx = AmountUtils.sunToTrx(config.pricePerUnitSun)
        tvPricePerUnit.text = context.getString(R.string.confirm_price_per_unit, priceTrx)
        
        // 倍率
        tvMultiplier.text = context.getString(R.string.confirm_multiplier, config.multiplier)
        
        // 总金额
        val totalTrx = config.getTotalAmountTrx()
        tvTotalAmount.text = context.getString(R.string.confirm_total_amount, totalTrx)
        
        // 安全提示
        tvWarning1.text = context.getString(R.string.confirm_warning_no_approve)
        tvWarning2.text = context.getString(R.string.confirm_warning_no_contract)
        
        // 绑定原始数据
        val rawDataHex = org.tron.trident.utils.Numeric.toHexString(transaction.toByteArray())
        tvRawHex.text = rawDataHex
    }
    
    /**
     * 设置点击事件
     */
    private fun setupClickListeners() {
        // 取消按钮
        btnCancel.setOnClickListener {
            dismiss()
        }
        
        // 显示原始数据
        btnShowRaw.setOnClickListener {
            if (tvRawHex.visibility == View.VISIBLE) {
                tvRawHex.visibility = View.GONE
                btnShowRaw.text = "查看原始交易字节 (开发者审计)"
            } else {
                tvRawHex.visibility = View.VISIBLE
                btnShowRaw.text = "收起原始交易字节"
            }
        }
        
        // 确认按钮 - 长按逻辑
        btnConfirm.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    handlePressDown()
                    true
                }
                
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handlePressUp()
                    true
                }
                
                else -> false
            }
        }
    }
    
    /**
     * 处理按钮按下
     */
    private fun handlePressDown() {
        // 检查是否已等待足够时间
        val elapsedTime = System.currentTimeMillis() - dialogShownTime
        if (elapsedTime < MIN_WAIT_TIME_MS) {
            val remainingTime = (MIN_WAIT_TIME_MS - elapsedTime) / 1000.0
            tvHoldPrompt.text = context.getString(
                R.string.confirm_wait_prompt,
                String.format("%.1f", remainingTime)
            )
            return
        }
        
        // 开始长按
        isLongPressing = true
        longPressStartTime = System.currentTimeMillis()
        
        // 显示进度条和提示
        progressBar.visibility = View.VISIBLE
        tvHoldPrompt.text = context.getString(R.string.confirm_hold_prompt)
        
        // 启动进度更新
        startProgressUpdate()
    }
    
    /**
     * 处理按钮释放
     */
    private fun handlePressUp() {
        if (!isLongPressing) {
            return
        }
        
        // 停止长按
        isLongPressing = false
        
        // 停止进度更新
        stopProgressUpdate()
        
        // 检查长按时长
        val pressDuration = System.currentTimeMillis() - longPressStartTime
        
        if (pressDuration >= LONG_PRESS_DURATION_MS) {
            // 长按成功，确认交易
            onConfirmSuccess()
        } else {
            // 长按时间不足，重置
            resetProgress()
            val remainingTime = (LONG_PRESS_DURATION_MS - pressDuration) / 1000.0
            tvHoldPrompt.text = context.getString(
                R.string.confirm_hold_insufficient,
                String.format("%.1f", remainingTime)
            )
        }
    }
    
    /**
     * 启动进度更新
     */
    private fun startProgressUpdate() {
        progressUpdateRunnable = object : Runnable {
            override fun run() {
                if (!isLongPressing) {
                    return
                }
                
                val pressDuration = System.currentTimeMillis() - longPressStartTime
                val progress = ((pressDuration.toFloat() / LONG_PRESS_DURATION_MS) * 100).toInt()
                
                progressBar.progress = progress.coerceIn(0, 100)
                
                if (progress < 100) {
                    handler.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MS)
                }
            }
        }
        
        handler.post(progressUpdateRunnable!!)
    }
    
    /**
     * 停止进度更新
     */
    private fun stopProgressUpdate() {
        progressUpdateRunnable?.let {
            handler.removeCallbacks(it)
            progressUpdateRunnable = null
        }
    }
    
    /**
     * 重置进度
     */
    private fun resetProgress() {
        progressBar.progress = 0
        progressBar.visibility = View.GONE
    }
    
    /**
     * 确认成功
     */
    private fun onConfirmSuccess() {
        // 隐藏进度条
        progressBar.visibility = View.GONE
        tvHoldPrompt.text = context.getString(R.string.confirm_success)
        
        // 关闭对话框
        dismiss()
        
        // 回调确认
        onConfirmed()
    }
    
    override fun dismiss() {
        // 清理资源
        stopProgressUpdate()
        super.dismiss()
    }
}
