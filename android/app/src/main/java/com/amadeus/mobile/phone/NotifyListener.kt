package com.amadeus.mobile.phone

import android.app.Notification
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.amadeus.mobile.host.Guard
import com.amadeus.mobile.host.Kernel
import java.util.concurrent.ConcurrentHashMap

/**
 * 通知触发的主动开口：只转述主人在设置里列出的应用。
 * 通知内容按不可信数据交给控制面；支付类应用的通知一律不转。
 */
class NotifyListener : NotificationListenerService() {
    companion object {
        private const val PER_APP_MS = 60_000L
        private const val GLOBAL_MS = 15_000L
    }

    private val lastPerApp = ConcurrentHashMap<String, Long>()
    @Volatile private var lastGlobal = 0L

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg == Guard.SELF || pkg in Guard.PAYMENT_PACKAGES || pkg !in Kernel.notifyPackages) return
        val notification = sbn.notification
        if (sbn.isOngoing || notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val now = SystemClock.elapsedRealtime()
        // 群聊刷屏时不连续开口：同一应用 1 分钟一次，全局 15 秒一次
        if (now - (lastPerApp[pkg] ?: 0) < PER_APP_MS || now - lastGlobal < GLOBAL_MS) return
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        lastPerApp[pkg] = now
        lastGlobal = now
        val body = "${Apps.label(this, pkg)}：$title — $text".take(300)
        Kernel.proactive(this, "notification", body, "notification:${sbn.key}:${sbn.postTime}")
    }
}
