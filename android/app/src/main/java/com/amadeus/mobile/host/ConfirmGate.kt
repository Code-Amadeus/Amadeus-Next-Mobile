package com.amadeus.mobile.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 需确认的动作在这里挂起，直到主人触屏点"允许"或"拒绝"。
 * 只认界面点击：语音"可以"不算，避免被外放音频冒充；放行只对这一个动作有效。
 */
object ConfirmGate {
    private const val CHANNEL = "confirm"
    private const val TIMEOUT_SECONDS = 60L
    const val ACTION = "com.amadeus.mobile.CONFIRM"
    const val EXTRA_NONCE = "nonce"
    const val EXTRA_ALLOW = "allow"

    enum class Outcome { ALLOWED, DENIED, TIMEOUT }

    /** 界面在前台时注册，用对话框代替通知。 */
    interface Presenter {
        fun show(nonce: String, prompt: String)
        fun dismiss(nonce: String)
    }

    @Volatile var presenter: Presenter? = null
    private val pending = ConcurrentHashMap<String, CompletableFuture<Boolean>>()
    private val random = SecureRandom()

    fun ask(context: Context, prompt: String): Outcome {
        val nonce = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val future = CompletableFuture<Boolean>()
        pending[nonce] = future
        val notificationId = nonce.hashCode()
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            presenter?.show(nonce, prompt)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "操作确认", NotificationManager.IMPORTANCE_HIGH))
            manager.notify(notificationId, notification(context, nonce, prompt))
            return try {
                if (future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)) Outcome.ALLOWED else Outcome.DENIED
            } catch (_: TimeoutException) {
                Outcome.TIMEOUT
            }
        } finally {
            pending.remove(nonce)
            manager.cancel(notificationId)
            presenter?.dismiss(nonce)
        }
    }

    fun resolve(nonce: String, allow: Boolean) {
        pending[nonce]?.complete(allow)
    }

    private fun notification(context: Context, nonce: String, prompt: String): Notification {
        fun action(allow: Boolean): PendingIntent {
            val intent = Intent(context, ConfirmReceiver::class.java).setAction(ACTION)
                .putExtra(EXTRA_NONCE, nonce).putExtra(EXTRA_ALLOW, allow)
            return PendingIntent.getBroadcast(context, (nonce + allow).hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("红莉栖请求确认")
            .setContentText(prompt)
            .setStyle(Notification.BigTextStyle().bigText(prompt))
            .setCategory(Notification.CATEGORY_CALL)
            .setTimeoutAfter(TIMEOUT_SECONDS * 1000)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "拒绝", action(false)).build())
            .addAction(Notification.Action.Builder(null, "允许", action(true)).build())
            .build()
    }
}

/** 不导出：只有本应用的 PendingIntent 能投递到这里。 */
class ConfirmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ConfirmGate.ACTION) return
        val nonce = intent.getStringExtra(ConfirmGate.EXTRA_NONCE) ?: return
        ConfirmGate.resolve(nonce, intent.getBooleanExtra(ConfirmGate.EXTRA_ALLOW, false))
    }
}
