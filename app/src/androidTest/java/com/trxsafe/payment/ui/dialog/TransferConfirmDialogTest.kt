package com.trxsafe.payment.ui.dialog

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trxsafe.payment.settings.SettingsConfig
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TransferConfirmDialog 测试
 * 
 * 注意：这是简化的单元测试，完整的 UI 测试需要使用 Espresso
 */
@RunWith(AndroidJUnit4::class)
class TransferConfirmDialogTest {
    
    private lateinit var context: Context
    
    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }
    
    @Test
    fun testDialogConstantsAreCorrect() {
        // 验证时间常量
        assertEquals(
            "最短等待时间应为 1 秒",
            1000L,
            TransferConfirmDialog.Companion::class.java
                .getDeclaredField("MIN_WAIT_TIME_MS")
                .apply { isAccessible = true }
                .get(null) as Long
        )
        
        assertEquals(
            "长按时间应为 2 秒",
            2000L,
            TransferConfirmDialog.Companion::class.java
                .getDeclaredField("LONG_PRESS_DURATION_MS")
                .apply { isAccessible = true }
                .get(null) as Long
        )
    }
    
    @Test
    fun testConfigDataBinding() {
        // 创建测试配置
        val config = SettingsConfig(
            sellerAddress = "TXYZoPE5CP4Gj4K...",
            pricePerUnitSun = 5_000_000L,
            multiplier = 3
        )
        
        // 验证总金额计算
        val expectedTotal = 15_000_000L
        assertEquals(
            "总金额应为 15 TRX",
            expectedTotal,
            config.getTotalAmountSun()
        )
    }
}
