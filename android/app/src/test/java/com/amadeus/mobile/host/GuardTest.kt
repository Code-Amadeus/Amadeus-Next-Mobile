package com.amadeus.mobile.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardTest {
    private fun screen(pkg: String = "com.sankuai.meituan", activity: String = "", texts: List<String> = emptyList(), password: Boolean = false, locked: Boolean = false) =
        Guard.Screen(pkg, activity, locked, password, texts, appLabel = "美团")

    private fun block(decision: Guard.Decision) = (decision as Guard.Decision.Block).code

    @Test
    fun plainTapsAreAllowed() {
        assertEquals(Guard.Decision.Allow, Guard.judge("click", emptyMap(), screen(), "麦当劳（XX 店）"))
        assertEquals(Guard.Decision.Allow, Guard.judge("set_value", emptyMap(), screen(), "搜索"))
    }

    @Test
    fun consequentialTapsNeedConfirmation() {
        assertTrue(Guard.judge("click", emptyMap(), screen(), "提交订单") is Guard.Decision.Confirm)
        assertTrue(Guard.judge("click", emptyMap(), screen(pkg = "com.tencent.mm"), "发送") is Guard.Decision.Confirm)
        assertTrue(Guard.judge("click", emptyMap(), screen(pkg = "com.example"), "Delete account") is Guard.Decision.Confirm)
        assertTrue(Guard.judge("enter", emptyMap(), screen(pkg = "com.tencent.mm"), null) is Guard.Decision.Confirm)
    }

    @Test
    fun englishWordsMatchWholeWordsOnly() {
        assertEquals(Guard.Decision.Allow, Guard.judge("click", emptyMap(), screen(pkg = "com.example"), "Display settings"))
        assertEquals(Guard.Decision.Allow, Guard.judge("click", emptyMap(), screen(pkg = "com.example"), "Recall history"))
    }

    @Test
    fun paymentScreensAreInvisibleAndUntouchable() {
        val cashier = screen(texts = listOf("美团收银台", "¥38.5"))
        assertEquals("guard_block", block(Guard.judge("observe", emptyMap(), cashier, null)))
        assertEquals("guard_block", block(Guard.judge("screenshot", emptyMap(), cashier, null)))
        assertEquals("guard_block", block(Guard.judge("click", emptyMap(), cashier, "返回")))
        assertEquals("guard_block", block(Guard.judge("observe", emptyMap(), screen(password = true), null)))
        assertEquals("guard_block", block(Guard.judge("observe", emptyMap(), screen(pkg = "com.eg.android.AlipayGphone"), null)))
        // 敏感界面上仍允许按返回键离开
        assertEquals(Guard.Decision.Allow, Guard.judge("key", mapOf("key" to "BACK"), cashier, null))
    }

    @Test
    fun securityScreensAreForTheUser() {
        assertEquals(Guard.Decision.Allow, Guard.judge("observe", emptyMap(), screen(pkg = "com.android.permissioncontroller"), null))
        assertEquals("guard_block", block(Guard.judge("click", emptyMap(), screen(pkg = "com.android.permissioncontroller"), "允许")))
        assertEquals("guard_block", block(Guard.judge("click", emptyMap(), screen(pkg = "com.android.settings", activity = "com.android.settings.Settings\$AccessibilitySettingsActivity"), "Amadeus")))
        assertEquals("guard_block", block(Guard.judge("click", emptyMap(), screen(pkg = Guard.SELF), "允许")))
        assertEquals(Guard.Decision.Allow, Guard.judge("click", emptyMap(), screen(pkg = "com.android.settings", activity = "com.android.settings.SubSettings"), "显示"))
    }

    @Test
    fun lockedPhoneOnlyAllowsReading() {
        assertEquals("screen_locked", block(Guard.judge("click", emptyMap(), screen(locked = true), "x")))
        assertEquals(Guard.Decision.Allow, Guard.judge("observe", emptyMap(), screen(locked = true), null))
    }

    @Test
    fun paymentAppsCannotBeLaunched() {
        assertEquals("bad_package", block(Guard.judge("launch_app", mapOf("package" to "com.eg.android.AlipayGphone"), null, null)))
        assertEquals("bad_package", block(Guard.judge("launch_app", mapOf("package" to Guard.SELF), null, null)))
        assertEquals(Guard.Decision.Allow, Guard.judge("launch_app", mapOf("package" to "com.sankuai.meituan"), null, null))
    }
}
