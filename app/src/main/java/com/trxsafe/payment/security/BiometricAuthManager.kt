package com.trxsafe.payment.security

import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor

/**
 * 生物识别管理器
 * 封装生物识别的检查和调用逻辑
 */
class BiometricAuthManager(private val activity: AppCompatActivity) {

    private val executor: Executor = ContextCompat.getMainExecutor(activity)
    private val biometricManager = BiometricManager.from(activity)

    /**
     * 检查是否可以进行生物识别
     * 
     * @return true 表示可用
     */
    fun canAuthenticate(): Boolean {
        // 模拟器可能不支持强生物识别，使用 BIOMETRIC_WEAK 以兼容更多设备（包括面部解锁）
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or 
                            BiometricManager.Authenticators.BIOMETRIC_WEAK or 
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        
        return biometricManager.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * 发起生物识别认证
     * 
     * @param title 标题
     * @param subtitle 副标题
     * @param onSuccess 认证成功回调
     * @param onError 认证失败回调
     */
    fun authenticate(
        title: String = "验证您的身份",
        subtitle: String = "使用指纹或面部识别解锁",
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!canAuthenticate()) {
            onError("当前设备不支持生物识别或未设置")
            return
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or 
                BiometricManager.Authenticators.BIOMETRIC_WEAK or 
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
            
        // 如果使用了 DEVICE_CREDENTIAL (PIN/密码)，则不能设置 NegativeButton
        // 上面的 setAllowedAuthenticators 包含了 DEVICE_CREDENTIAL，所以这里不需要 (也不能) setNegativeButtonText

        val biometricPrompt = BiometricPrompt(activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    // 只有非取消类错误才报错，用户主动取消通常不需要弹 Toast
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && 
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        onError(errString.toString())
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    // 指纹不匹配等，无需 finish，SDK自带震动或提示
                }
            })

        biometricPrompt.authenticate(promptInfo)
    }
}
