package com.trxsafe.payment.ui.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.trxsafe.payment.R
import com.trxsafe.payment.security.BiometricAuthManager
import com.trxsafe.payment.utils.AmountUtils
import com.trxsafe.payment.utils.setDebouncedClick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tron.trident.proto.Chain

class SignatureConfirmDialog(
    context: Context,
    private val transaction: Chain.Transaction,
    private val toAddress: String,
    private val amountSun: Long,
    private val burnSun: Long = 0L,
    private val availableBandwidth: Long = 0,
    private val requiredBandwidth: Long = 0,
    private val balanceSun: Long = 0,
    private val onSignComplete: (Chain.Transaction) -> Unit,
    private val onSignError: (String) -> Unit,
    private val onCancel: () -> Unit
) : BottomSheetDialog(context, R.style.Theme_TrxSafe) {

    companion object {
        private const val MIN_WAIT_TIME_MS = 1000L
        private const val SIGNING_TIMEOUT_MS = 30000L
    }

    private var dialogShownTime: Long = 0
    private val handler = Handler(Looper.getMainLooper())
    private var signingTimeoutRunnable: Runnable? = null
    private var biometricAuthManager: BiometricAuthManager? = null

    private lateinit var tvTransactionId: TextView
    private lateinit var tvRecipientAddress: TextView
    private lateinit var tvAmount: TextView
    private lateinit var tvFee: TextView
    private lateinit var tvAvailableBandwidth: TextView
    private lateinit var tvRequiredBandwidth: TextView
    private lateinit var tvBandwidthStatus: TextView
    private lateinit var tvBalance: TextView
    private lateinit var tvBalanceStatus: TextView
    private lateinit var tvSignWarning: TextView
    private lateinit var ivSignatureStatus: ImageView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgressStatus: TextView
    private lateinit var btnConfirm: Button
    private lateinit var btnCancel: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val view = LayoutInflater.from(context).inflate(
            R.layout.dialog_signature_confirm,
            null,
            false
        )
        setContentView(view)

        setCancelable(false)
        setCanceledOnTouchOutside(false)

        initViews(view)
        bindData()
        setupClickListeners()

        dialogShownTime = System.currentTimeMillis()
    }

    private fun initViews(view: View) {
        tvTransactionId = view.findViewById(R.id.tvTransactionId)
        tvRecipientAddress = view.findViewById(R.id.tvRecipientAddress)
        tvAmount = view.findViewById(R.id.tvAmount)
        tvFee = view.findViewById(R.id.tvFee)
        tvAvailableBandwidth = view.findViewById(R.id.tvAvailableBandwidth)
        tvRequiredBandwidth = view.findViewById(R.id.tvRequiredBandwidth)
        tvBandwidthStatus = view.findViewById(R.id.tvBandwidthStatus)
        tvBalance = view.findViewById(R.id.tvBalance)
        tvBalanceStatus = view.findViewById(R.id.tvBalanceStatus)
        tvSignWarning = view.findViewById(R.id.tvSignWarning)
        ivSignatureStatus = view.findViewById(R.id.ivSignatureStatus)
        progressBar = view.findViewById(R.id.progressBar)
        tvProgressStatus = view.findViewById(R.id.tvProgressStatus)
        btnConfirm = view.findViewById(R.id.btnConfirm)
        btnCancel = view.findViewById(R.id.btnCancel)

        progressBar.max = 100
        progressBar.visibility = View.GONE
        ivSignatureStatus.setImageResource(R.drawable.ic_signature_pending)
    }

    private fun bindData() {
        try {
            val rawDataBytes = transaction.rawData.toByteArray()
            val txHash = org.tron.trident.crypto.Hash.sha256(rawDataBytes)
            val txId = txHash.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            tvTransactionId.text = context.getString(R.string.sign_transaction_id, txId.take(16) + "...")

            tvRecipientAddress.text = toAddress

            val formattedAmount = AmountUtils.sunToTrx(amountSun)
            val formattedBurn = AmountUtils.sunToTrx(burnSun)
            val formattedTotal = AmountUtils.sunToTrx(amountSun + burnSun)
            tvAmount.text = context.getString(R.string.sign_amount_with_burn_format, formattedAmount, formattedBurn, formattedTotal)

            val rawData = transaction.rawData
            if (rawData.contractCount > 0) {
                val contract = rawData.getContract(0)
                val parameter = contract.parameter
                val any = parameter.value
                val transferContract = org.tron.trident.proto.Contract.TransferContract.parseFrom(any)
                val feeSun = transferContract.amount
                tvFee.text = context.getString(R.string.sign_fee_burn_format, formattedBurn)
            } else {
                tvFee.text = context.getString(R.string.sign_fee_burn_format, formattedBurn)
            }

            tvAvailableBandwidth.text = "$availableBandwidth 点"
            tvRequiredBandwidth.text = "$requiredBandwidth 点"

            if (burnSun > 0) {
                tvBandwidthStatus.text = "需燃烧 $formattedBurn TRX"
                tvBandwidthStatus.setTextColor(context.getColor(R.color.warning))
            } else {
                tvBandwidthStatus.text = "✓ 带宽充足"
                tvBandwidthStatus.setTextColor(context.getColor(R.color.success))
            }

            val formattedBalance = AmountUtils.sunToTrx(balanceSun)
            tvBalance.text = "$formattedBalance TRX"

            val totalNeededSun = amountSun + burnSun
            if (balanceSun >= totalNeededSun) {
                tvBalanceStatus.text = "✓ 余额充足"
                tvBalanceStatus.setTextColor(context.getColor(R.color.success))
            } else {
                val shortageTrx = AmountUtils.sunToTrx(totalNeededSun - balanceSun)
                tvBalanceStatus.text = "✗ 不足 $shortageTrx TRX"
                tvBalanceStatus.setTextColor(context.getColor(R.color.error))
            }

            tvSignWarning.text = context.getString(R.string.sign_security_warning)
            tvProgressStatus.text = context.getString(R.string.sign_ready)
            btnConfirm.isEnabled = true

        } catch (e: Exception) {
            tvTransactionId.text = context.getString(R.string.sign_transaction_error)
            tvAmount.text = context.getString(R.string.sign_amount_error)
            tvFee.text = context.getString(R.string.sign_fee_error)
            btnConfirm.isEnabled = false
        }
    }

    private fun setupClickListeners() {
        btnCancel.setDebouncedClick(debounceDelayMs = 1000) {
            cancelSigning()
            onCancel()
            dismiss()
        }

        btnConfirm.setDebouncedClick(debounceDelayMs = 1000) {
            initiateBiometricVerification()
        }
    }

    private fun initiateBiometricVerification() {
        val activity = context as? AppCompatActivity ?: run {
            startSigningProcess()
            return
        }

        biometricAuthManager = BiometricAuthManager(activity)

        if (!biometricAuthManager!!.canAuthenticate()) {
            Toast.makeText(context, R.string.biometric_not_available, Toast.LENGTH_SHORT).show()
            startSigningProcess()
            return
        }

        tvProgressStatus.text = context.getString(R.string.biometric_prompt)

        biometricAuthManager!!.authenticate(
            title = context.getString(R.string.biometric_title),
            subtitle = context.getString(R.string.biometric_subtitle),
            onSuccess = {
                handler.post {
                    startSigningProcess()
                }
            },
            onError = { errorMessage ->
                handler.post {
                    if (errorMessage.isNotEmpty() && 
                        errorMessage != context.getString(R.string.biometric_canceled)) {
                        Toast.makeText(context, errorMessage, Toast.LENGTH_SHORT).show()
                    }
                    btnConfirm.isEnabled = true
                    btnCancel.isEnabled = true
                    tvProgressStatus.text = context.getString(R.string.sign_ready)
                }
            }
        )
    }

    private fun startSigningProcess() {
        val elapsedTime = System.currentTimeMillis() - dialogShownTime
        if (elapsedTime < MIN_WAIT_TIME_MS) {
            val remainingTime = (MIN_WAIT_TIME_MS - elapsedTime) / 1000.0
            tvProgressStatus.text = context.getString(
                R.string.sign_wait_prompt,
                String.format("%.1f", remainingTime)
            )
            handler.postDelayed({
                startSigningProcess()
            }, 100)
            return
        }

        btnConfirm.isEnabled = false
        btnCancel.isEnabled = false
        progressBar.visibility = View.VISIBLE
        ivSignatureStatus.setImageResource(R.drawable.ic_signature_in_progress)

        tvProgressStatus.text = context.getString(R.string.signing_in_progress)

        startSigningTimeout()

        lifecycleScope.launch {
            try {
                performSigning()
            } catch (e: Exception) {
                handler.post {
                    onSignError(e.message ?: context.getString(R.string.sign_error_unknown))
                }
            }
        }
    }

    private suspend fun performSigning() {
        withContext(Dispatchers.Main) {
            progressBar.progress = 30
            tvProgressStatus.text = context.getString(R.string.signing_validate)
        }

        val validator = com.trxsafe.payment.transaction.TransactionValidator()
        val config = com.trxsafe.payment.settings.SettingsRepository.getInstance(context).getConfigSync()
        val validationResult = try {
            validator.validateTransactionWithConfig(
                transaction = transaction,
                config = config,
                fromAddress = ""
            )
            com.trxsafe.payment.transaction.ValidationResult.Success("验证通过")
        } catch (e: com.trxsafe.payment.transaction.TransactionValidationException) {
            com.trxsafe.payment.transaction.ValidationResult.Failure(e.message ?: "验证失败")
        }

        when (validationResult) {
            is com.trxsafe.payment.transaction.ValidationResult.Success -> {
                // 继续执行签名流程
            }
            is com.trxsafe.payment.transaction.ValidationResult.Failure -> {
                handler.post {
                    onSignError(validationResult.message)
                }
                return
            }
        }

        withContext(Dispatchers.Main) {
            progressBar.progress = 60
            tvProgressStatus.text = context.getString(R.string.signing_generating)
        }

        try {
            val signedTransaction = withContext(Dispatchers.Default) {
                val walletManager = com.trxsafe.payment.wallet.WalletManager(context)
                walletManager.signTransferContract(transaction)
            }

            withContext(Dispatchers.Main) {
                progressBar.progress = 100
                ivSignatureStatus.setImageResource(R.drawable.ic_signature_success)
                tvProgressStatus.text = context.getString(R.string.sign_complete)

                handler.postDelayed({
                    onSignComplete(signedTransaction)
                    dismiss()
                }, 1000)
            }

        } catch (e: SecurityException) {
            handler.post {
                ivSignatureStatus.setImageResource(R.drawable.ic_signature_failed)
                tvProgressStatus.text = context.getString(R.string.sign_security_error)
                btnConfirm.isEnabled = false
                btnCancel.isEnabled = true
                stopSigningTimeout()
            }
            throw e
        } catch (e: Exception) {
            handler.post {
                ivSignatureStatus.setImageResource(R.drawable.ic_signature_failed)
                tvProgressStatus.text = context.getString(R.string.sign_failed, e.message)
                btnConfirm.isEnabled = false
                btnCancel.isEnabled = true
                stopSigningTimeout()
            }
            onSignError(e.message ?: context.getString(R.string.sign_error_unknown))
        }
    }

    private fun startSigningTimeout() {
        signingTimeoutRunnable = object : Runnable {
            override fun run() {
                ivSignatureStatus.setImageResource(R.drawable.ic_signature_failed)
                tvProgressStatus.text = context.getString(R.string.sign_timeout)
                progressBar.visibility = View.GONE
                btnConfirm.isEnabled = false
                btnCancel.isEnabled = true
                onSignError(context.getString(R.string.sign_timeout_message))
            }
        }
        handler.postDelayed(signingTimeoutRunnable!!, SIGNING_TIMEOUT_MS)
    }

    private fun stopSigningTimeout() {
        signingTimeoutRunnable?.let {
            handler.removeCallbacks(it)
            signingTimeoutRunnable = null
        }
    }

    private fun cancelSigning() {
        stopSigningTimeout()
    }

    override fun dismiss() {
        cancelSigning()
        super.dismiss()
    }
}
