package com.trxsafe.payment.ui.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.trxsafe.payment.R
import com.trxsafe.payment.databinding.DialogTransferConfirmBinding
import com.trxsafe.payment.network.TronHttpClient
import com.trxsafe.payment.security.BiometricAuthManager
import com.trxsafe.payment.settings.SettingsConfig
import com.trxsafe.payment.utils.AmountUtils
import com.trxsafe.payment.utils.setDebouncedClick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TransferConfirmDialog(
    context: Context,
    private val config: SettingsConfig,
    private val fromAddress: String,
    private val transaction: org.tron.trident.proto.Chain.Transaction,
    private val httpClient: TronHttpClient,
    private val onConfirmed: () -> Unit
) : BottomSheetDialog(context, R.style.Theme_TrxSafe) {

    companion object {
        private const val MIN_WAIT_TIME_MS = 1000L
        private const val LONG_PRESS_DURATION_MS = 2000L
        private const val PROGRESS_UPDATE_INTERVAL_MS = 50L
    }

    private var dialogShownTime: Long = 0
    private var isLongPressing = false
    private var longPressStartTime: Long = 0

    private val handler = Handler(Looper.getMainLooper())
    private var progressUpdateRunnable: Runnable? = null
    private var biometricAuthManager: BiometricAuthManager? = null

    private lateinit var binding: DialogTransferConfirmBinding

    private var availableBandwidth: Long = 0
    private var burnForBandwidthSun: Long = 0
    private var accountBalanceSun: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = DialogTransferConfirmBinding.inflate(LayoutInflater.from(context))
        setContentView(binding.root)

        binding.progressBar.max = 100
        binding.progressBar.visibility = View.GONE

        setCancelable(false)
        setCanceledOnTouchOutside(false)

        bindData()
        setupClickListeners()

        dialogShownTime = System.currentTimeMillis()
        loadAccountResources()
    }

    private fun bindData() {
        binding.tvTransactionType.text = context.getString(R.string.confirm_transaction_type_trx)
        binding.tvRecipientAddress.text = config.sellerAddress

        val priceTrx = AmountUtils.sunToTrx(config.pricePerUnitSun)
        binding.tvPricePerUnit.text = context.getString(R.string.confirm_price_per_unit, priceTrx)

        binding.tvMultiplier.text = context.getString(R.string.confirm_multiplier, config.multiplier)

        val totalTrx = config.getTotalAmountTrx()
        binding.tvTotalAmount.text = context.getString(R.string.confirm_total_amount, totalTrx)

        binding.tvWarning1.text = context.getString(R.string.confirm_warning_no_approve)
        binding.tvWarning2.text = context.getString(R.string.confirm_warning_no_contract)

        val rawDataHex = org.tron.trident.utils.Numeric.toHexString(transaction.toByteArray())
        binding.tvRawHex.text = rawDataHex
    }

    private fun loadAccountResources() {
        handler.post {
            binding.tvHoldPrompt.text = context.getString(R.string.confirm_resource_loading)
        }

        lifecycleScope.launch {
            try {
                val resources = withContext(Dispatchers.IO) {
                    httpClient.getAccountResources(fromAddress)
                }

                val balance = withContext(Dispatchers.IO) {
                    httpClient.getAccountBalance(fromAddress)
                }

                availableBandwidth = resources?.get("freeNetLimit")?.asLong
                    ?: resources?.get("NetLimit")?.asLong
                    ?: 0L

                val netUsage = resources?.get("freeNetUsed")?.asLong
                    ?: resources?.get("NetUsed")?.asLong
                    ?: resources?.get("freeNetUsage")?.asLong
                    ?: resources?.get("NetUsage")?.asLong
                    ?: 0L

                availableBandwidth = (availableBandwidth - netUsage).coerceAtLeast(0)
                android.util.Log.d("TransferConfirmDialog", "带宽信息: availableBandwidth=$availableBandwidth, netUsage=$netUsage")

                burnForBandwidthSun = httpClient.calculateBandwidthBurn(availableBandwidth)
                accountBalanceSun = balance

                updateResourceDisplay()
            } catch (e: Exception) {
                android.util.Log.e("TransferConfirmDialog", "获取资源失败: ${e.message}")
                updateResourceDisplay()
            }
        }
    }

    private fun updateResourceDisplay() {
        handler.post {
            try {
                val requiredBandwidth = httpClient.estimateTransferBandwidth()
                val burnTrx = burnForBandwidthSun / 1_000_000f
                val balanceTrx = accountBalanceSun / 1_000_000f
                val totalTrx = config.getTotalAmountTrx()
                val totalCost = totalTrx + burnTrx

                binding.tvAvailableBandwidth.text = context.getString(
                    R.string.confirm_available_bandwidth,
                    "$availableBandwidth ${context.getString(R.string.confirm_bandwidth_unit)}"
                )
                binding.tvRequiredBandwidth.text = context.getString(
                    R.string.confirm_required_bandwidth,
                    "$requiredBandwidth ${context.getString(R.string.confirm_bandwidth_unit)}"
                )

                if (burnForBandwidthSun > 0) {
                    binding.tvBurnInfo.text = context.getString(
                        R.string.confirm_bandwidth_insufficient,
                        String.format("%.2f", burnTrx)
                    ) + "\n" + context.getString(R.string.confirm_burn_for_bandwidth) + ": " +
                      context.getString(R.string.confirm_burn_amount, String.format("%.2f", burnTrx))
                    binding.tvBurnInfo.setTextColor(ContextCompat.getColor(context, android.R.color.holo_red_dark))
                } else {
                    binding.tvBurnInfo.text = context.getString(R.string.confirm_bandwidth_sufficient)
                    binding.tvBurnInfo.setTextColor(ContextCompat.getColor(context, android.R.color.holo_green_dark))
                }

                binding.tvTotalCost.text = String.format("%.2f TRX", totalCost)
                binding.tvAccountBalance.text = context.getString(R.string.confirm_account_balance, String.format("%.2f", balanceTrx))

                val canAfford = accountBalanceSun >= (config.pricePerUnitSun * config.multiplier + burnForBandwidthSun)
                if (canAfford) {
                    binding.tvBalanceStatus.text = "✓ 余额充足，可以转账"
                    binding.tvBalanceStatus.setTextColor(ContextCompat.getColor(context, android.R.color.holo_green_dark))
                } else {
                    binding.tvBalanceStatus.text = "✗ 余额不足，无法转账"
                    binding.tvBalanceStatus.setTextColor(ContextCompat.getColor(context, android.R.color.holo_red_dark))
                }

                val sb = StringBuilder()
                sb.append("━━━━━━━━━━━━━━━\n")
                sb.append(context.getString(R.string.confirm_resource_info)).append("\n\n")
                sb.append(context.getString(R.string.confirm_available_bandwidth, "$availableBandwidth ${context.getString(R.string.confirm_bandwidth_unit)}")).append("\n")
                sb.append(context.getString(R.string.confirm_required_bandwidth, "$requiredBandwidth ${context.getString(R.string.confirm_bandwidth_unit)}")).append("\n\n")

                if (burnForBandwidthSun > 0) {
                    sb.append(context.getString(R.string.confirm_bandwidth_insufficient, String.format("%.2f", burnTrx))).append("\n")
                    sb.append(context.getString(R.string.confirm_burn_for_bandwidth)).append(": ")
                    sb.append(context.getString(R.string.confirm_burn_amount, String.format("%.2f", burnTrx))).append("\n")
                } else {
                    sb.append(context.getString(R.string.confirm_bandwidth_sufficient)).append("\n")
                }

                sb.append("\n")
                sb.append(context.getString(R.string.confirm_total_cost)).append(": ")
                sb.append(String.format("%.2f", totalCost)).append(" TRX\n")
                sb.append("├─ 转账金额: ").append(String.format("%.2f", totalTrx)).append(" TRX\n")
                sb.append("└─ 燃烧金额: ").append(String.format("%.2f", burnTrx)).append(" TRX\n")

                sb.append("\n账户余额: ").append(String.format("%.2f", balanceTrx)).append(" TRX\n")

                if (canAfford) {
                    sb.append("\n✓ 余额充足，可以转账")
                } else {
                    sb.append("\n✗ 余额不足，无法转账")
                }

                binding.tvHoldPrompt.text = sb.toString()

                if (!canAfford) {
                    binding.tvHoldPrompt.setTextColor(ContextCompat.getColor(context, android.R.color.holo_red_dark))
                    binding.btnConfirm.isEnabled = false
                } else {
                    binding.tvHoldPrompt.setTextColor(ContextCompat.getColor(context, android.R.color.holo_blue_dark))
                    binding.btnConfirm.isEnabled = true
                }
            } catch (e: Exception) {
                binding.tvHoldPrompt.text = context.getString(R.string.confirm_resource_error)
            }
        }
    }

    private fun setupClickListeners() {
        binding.btnCancel.setDebouncedClick(debounceDelayMs = 1000) {
            dismiss()
        }

        binding.btnShowRaw.setDebouncedClick(debounceDelayMs = 1000) {
            if (binding.tvRawHex.visibility == View.VISIBLE) {
                binding.tvRawHex.visibility = View.GONE
                binding.btnShowRaw.text = "查看原始交易字节 (开发者审计)"
            } else {
                binding.tvRawHex.visibility = View.VISIBLE
                binding.btnShowRaw.text = "收起原始交易字节"
            }
        }

        binding.btnConfirm.setOnTouchListener { _, event ->
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

    private fun handlePressDown() {
        val elapsedTime = System.currentTimeMillis() - dialogShownTime
        if (elapsedTime < MIN_WAIT_TIME_MS) {
            val remainingTime = (MIN_WAIT_TIME_MS - elapsedTime) / 1000.0
            binding.tvHoldPrompt.text = context.getString(
                R.string.confirm_wait_prompt,
                String.format("%.1f", remainingTime)
            )
            return
        }

        isLongPressing = true
        longPressStartTime = System.currentTimeMillis()

        binding.progressBar.visibility = View.VISIBLE
        binding.tvHoldPrompt.text = context.getString(R.string.confirm_hold_prompt)

        startProgressUpdate()
    }

    private fun handlePressUp() {
        if (!isLongPressing) {
            return
        }

        isLongPressing = false
        stopProgressUpdate()

        val pressDuration = System.currentTimeMillis() - longPressStartTime

        if (pressDuration >= LONG_PRESS_DURATION_MS) {
            onConfirmSuccess()
        } else {
            resetProgress()
            val remainingTime = (LONG_PRESS_DURATION_MS - pressDuration) / 1000.0
            binding.tvHoldPrompt.text = context.getString(
                R.string.confirm_hold_insufficient,
                String.format("%.1f", remainingTime)
            )
        }
    }

    private fun startProgressUpdate() {
        progressUpdateRunnable = object : Runnable {
            override fun run() {
                if (!isLongPressing) {
                    return
                }

                val pressDuration = System.currentTimeMillis() - longPressStartTime
                val progress = ((pressDuration.toFloat() / LONG_PRESS_DURATION_MS) * 100).toInt()

                binding.progressBar.progress = progress.coerceIn(0, 100)

                if (progress < 100) {
                    handler.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MS)
                }
            }
        }

        handler.post(progressUpdateRunnable!!)
    }

    private fun stopProgressUpdate() {
        progressUpdateRunnable?.let {
            handler.removeCallbacks(it)
            progressUpdateRunnable = null
        }
    }

    private fun resetProgress() {
        binding.progressBar.progress = 0
        binding.progressBar.visibility = View.GONE
    }

    private fun onConfirmSuccess() {
        binding.progressBar.visibility = View.GONE

        val settingsRepo = com.trxsafe.payment.settings.SettingsRepository.getInstance(context)
        val config = kotlinx.coroutines.runBlocking {
            settingsRepo.loadConfig()
        }

        if (config.isBiometricEnabled) {
            binding.tvHoldPrompt.text = context.getString(R.string.biometric_prompt)
            performBiometricAuth()
        } else {
            completeConfirmation()
        }
    }

    private fun performBiometricAuth() {
        val activity = context as? AppCompatActivity ?: run {
            completeConfirmation()
            return
        }

        biometricAuthManager = BiometricAuthManager(activity)

        if (!biometricAuthManager!!.canAuthenticate()) {
            Toast.makeText(context, R.string.biometric_not_available, Toast.LENGTH_SHORT).show()
            completeConfirmation()
            return
        }

        biometricAuthManager!!.authenticate(
            title = context.getString(R.string.biometric_title),
            subtitle = context.getString(R.string.biometric_subtitle),
            onSuccess = {
                handler.post {
                    completeConfirmation()
                }
            },
            onError = { errorMessage ->
                handler.post {
                    if (errorMessage.isNotEmpty() && 
                        errorMessage != context.getString(R.string.biometric_canceled)) {
                        Toast.makeText(context, errorMessage, Toast.LENGTH_SHORT).show()
                    }
                    binding.tvHoldPrompt.text = context.getString(R.string.confirm_hold_prompt)
                    binding.tvHoldPrompt.setTextColor(ContextCompat.getColor(context, android.R.color.holo_blue_dark))
                    isLongPressing = false
                }
            }
        )
    }

    private fun completeConfirmation() {
        binding.tvHoldPrompt.text = context.getString(R.string.confirm_success)
        binding.tvHoldPrompt.setTextColor(ContextCompat.getColor(context, android.R.color.holo_blue_dark))

        handler.postDelayed({
            dismiss()
            onConfirmed()
        }, 500)
    }

    override fun dismiss() {
        stopProgressUpdate()
        super.dismiss()
    }
}
