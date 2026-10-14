package com.amadeus.mobile.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.amadeus.mobile.ui.MainActivity
import org.json.JSONObject

/** 前台服务：持有 payload 安装与控制面进程，App 界面关掉后控制面仍在。 */
class KernelService : Service() {
    companion object {
        private const val CHANNEL = "kernel"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, KernelService::class.java))
        }
    }

    private var host: KernelHost? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Amadeus 常驻", NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification("启动中"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification("启动中"))
        }
        Kernel.service = this
        Thread({ boot() }, "kernel-boot").start()
    }

    private fun boot() {
        val installer = PayloadInstaller(this)
        val libs = try {
            installer.ensureInstalled { Kernel.publish(KernelHost.State.STARTING, it) }
            installer.linkNativeLibraries()
        } catch (e: Exception) {
            Kernel.publish(KernelHost.State.STOPPED, "payload 安装失败：${e.message}")
            return
        }
        val kernel = KernelHost(this, installer.root, installer.nodeExecutable(), libs, object : KernelHost.Listener {
            override fun onState(state: KernelHost.State, detail: String) {
                Kernel.publish(state, detail)
                updateNotification(state)
            }

            override fun onEvent(message: Frame.Message) = Unit
        })
        host = kernel
        kernel.start()
    }

    fun request(method: String, params: JSONObject = JSONObject(), callback: (Frame.Message) -> Unit) {
        val kernel = host
        if (kernel == null) {
            callback(Frame.Message(JSONObject().put("kind", "res").put("status", "error").put("code", "not_ready")))
            return
        }
        kernel.request(method, params, callback)
    }

    fun restartControl() = host?.restart()

    override fun onDestroy() {
        host?.stop()
        if (Kernel.service === this) Kernel.service = null
        super.onDestroy()
    }

    private fun updateNotification(state: KernelHost.State) {
        val text = when (state) {
            KernelHost.State.READY -> "红莉栖在线"
            KernelHost.State.STARTING -> "启动中"
            KernelHost.State.RESTARTING -> "正在恢复"
            KernelHost.State.STOPPED -> "已停止"
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Amadeus")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}

/** 进程内的状态中转：服务发布、界面订阅，回调统一切到主线程。 */
object Kernel {
    @Volatile var service: KernelService? = null
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<(KernelHost.State, String) -> Unit>()
    @Volatile var last: Pair<KernelHost.State, String> = KernelHost.State.STARTING to ""
        private set

    fun publish(state: KernelHost.State, detail: String) {
        last = state to detail
        main.post { listeners.toList().forEach { it(state, detail) } }
    }

    fun subscribe(listener: (KernelHost.State, String) -> Unit): () -> Unit {
        main.post { listeners += listener; listener(last.first, last.second) }
        return { main.post { listeners -= listener } }
    }
}
