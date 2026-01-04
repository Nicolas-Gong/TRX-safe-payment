package com.trxsafe.payment.utils

import android.view.View
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DebouncedClickManagerTest {

    private lateinit var mockView: View

    @Before
    fun setUp() {
        mockView = mock()
        DebouncedClickManager.clearAllRecords()
    }

    @Test
    fun `canClick returns true when no previous click recorded`() {
        val result = DebouncedClickManager.canClick(mockView)
        assertTrue("首次点击应该返回 true", result)
    }

    @Test
    fun `canClick returns false when clicked recently within debounce period`() {
        DebouncedClickManager.recordClick(mockView)

        val result = DebouncedClickManager.canClick(mockView)
        assertFalse("在冷却期内应该返回 false", result)
    }

    @Test
    fun `canClick returns true after debounce period has elapsed`() {
        DebouncedClickManager.recordClick(mockView)

        Thread.sleep(310)

        val result = DebouncedClickManager.canClick(mockView)
        assertTrue("超过300ms冷却期后应该返回 true", result)
    }

    @Test
    fun `recordClick stores current timestamp`() {
        val beforeTime = System.currentTimeMillis()
        DebouncedClickManager.recordClick(mockView)
        val afterTime = System.currentTimeMillis()

        val lastClickTime = DebouncedClickManager.getLastClickTime(mockView)
        assertNotNull("点击时间应该被记录", lastClickTime)
        assertTrue("记录的时间应该在调用前后之间",
            lastClickTime!! >= beforeTime && lastClickTime <= afterTime)
    }

    @Test
    fun `clearClick removes click record`() {
        DebouncedClickManager.recordClick(mockView)
        assertTrue("点击后应该可以检查到记录", DebouncedClickManager.hasClickRecord(mockView))

        DebouncedClickManager.clearClick(mockView)
        assertFalse("清除后应该没有记录", DebouncedClickManager.hasClickRecord(mockView))
    }

    @Test
    fun `clearAllRecords removes all stored records`() {
        val mockView2 = mock<View>()
        val mockView3 = mock<View>()

        DebouncedClickManager.recordClick(mockView)
        DebouncedClickManager.recordClick(mockView2)
        DebouncedClickManager.recordClick(mockView3)

        DebouncedClickManager.clearAllRecords()

        assertFalse("清除后第一个View应该没有记录", DebouncedClickManager.hasClickRecord(mockView))
        assertFalse("清除后第二个View应该没有记录", DebouncedClickManager.hasClickRecord(mockView2))
        assertFalse("清除后第三个View应该没有记录", DebouncedClickManager.hasClickRecord(mockView3))
    }

    @Test
    fun `multiple views have independent click records`() {
        val mockView2 = mock<View>()

        DebouncedClickManager.recordClick(mockView)
        Thread.sleep(150)
        DebouncedClickManager.recordClick(mockView2)

        assertFalse("第一个View在冷却期内应该返回 false",
            DebouncedClickManager.canClick(mockView))
        assertFalse("第二个View在冷却期内应该返回 false",
            DebouncedClickManager.canClick(mockView2))

        Thread.sleep(200)

        assertFalse("第一个View仍然在冷却期内应该返回 false",
            DebouncedClickManager.canClick(mockView))
        assertTrue("第二个View已经超过300ms应该返回 true",
            DebouncedClickManager.canClick(mockView2))
    }

    @Test
    fun `getLastClickTime returns null for never clicked view`() {
        val result = DebouncedClickManager.getLastClickTime(mockView)
        assertNull("未点击过的View应该返回 null", result)
    }
}

class SetDebouncedClickTest {

    private lateinit var mockView: View
    private var clickCount = 0

    @Before
    fun setUp() {
        mockView = mock()
        clickCount = 0
        DebouncedClickManager.clearAllRecords()
    }

    @Test
    fun `setDebouncedClick triggers callback on first click`() {
        val latch = CountDownLatch(1)

        mockView.setDebouncedClick(debounceDelayMs = 1000) {
            clickCount++
            latch.countDown()
        }

        mockView.performClick()

        latch.await(1, TimeUnit.SECONDS)
        assertEquals("首次点击应该触发回调", 1, clickCount)
    }

    @Test
    fun `setDebouncedClick ignores rapid subsequent clicks`() {
        val latch = CountDownLatch(1)

        mockView.setDebouncedClick(debounceDelayMs = 1000) {
            clickCount++
            if (clickCount >= 1) {
                latch.countDown()
            }
        }

        mockView.performClick()
        mockView.performClick()
        mockView.performClick()

        latch.await(1, TimeUnit.SECONDS)
        assertEquals("快速点击应该只触发一次回调", 1, clickCount)
    }

    @Test
    fun `setDebouncedClick allows click after debounce period`() {
        val latch = CountDownLatch(2)

        mockView.setDebouncedClick(debounceDelayMs = 1000) {
            clickCount++
            if (clickCount == 1) {
                latch.countDown()
            } else if (clickCount == 2) {
                latch.countDown()
            }
        }

        mockView.performClick()

        Thread.sleep(200)

        mockView.performClick()

        latch.await(1, TimeUnit.SECONDS)
        assertEquals("超过冷却期后应该能再次触发", 2, clickCount)
    }

    @Test
    fun `setDebouncedClick with custom delay respects custom delay`() {
        val latch = CountDownLatch(2)

        mockView.setDebouncedClick(debounceDelayMs = 1000) {
            clickCount++
            if (clickCount == 1) {
                latch.countDown()
            } else if (clickCount == 2) {
                latch.countDown()
            }
        }

        mockView.performClick()

        Thread.sleep(300)

        mockView.performClick()

        assertEquals("300ms时不应该触发第二次点击", 1, clickCount)

        Thread.sleep(300)

        assertEquals("500ms后应该能触发第二次点击", 2, clickCount)
    }
}
