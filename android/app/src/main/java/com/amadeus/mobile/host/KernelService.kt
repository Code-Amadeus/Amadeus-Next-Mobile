package com.amadeus.mobile.host

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LifecycleService
import com.amadeus.mobile.phone.CameraExec
import com.amadeus.mobile.ui.MainActivity
import com.amadeus.mobile.voice.Player
import com.amadeus.mobile.voice.Recognizer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 前台服务：持有控制面进程、执行面（UDS + 动词路由）与语音 I/O。App 界面关掉后她仍在。
 * 生命周期给 CameraX 用，所以是 LifecycleService。
 */
class KernelService : LifecycleService() {
    companion object {
        private const val CHANNEL = "kernel"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, KernelService::class.java))
        }
    }

    private var host: KernelHost? = null
    private var ops: OpsServer? = null
    private var camera: CameraExec? = null
    private lateinit var player: Player
    private lateinit var recognizer: Recognizer
    lateinit var router: VerbRouter
        private set

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Amadeus 常驻", NotificationManager.IMPORTANCE_LOW))
        promote("启动中")
        Kernel.service = this
        val workspace = File(filesDir, "data/workspace").apply { mkdirs() }
        camera = CameraExec(this, this, workspace)
        router = VerbRouter(this, AuditLog(this)) { camera }
        player = Player { type, key, rms ->
            if (type != "level") host?.event("voice.playback", JSONObject(key.toString()).put("type", type).apply { remove("speech"); remove("calibration") })
            Kernel.webEvent("voice.playback", JSONObject().put("type", type).put("rms", rms.toDouble()))
        }
        recognizer = Recognizer(this) { message -> host?.event("asr.message", message) }
        Thread({ boot() }, "kernel-boot").start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // 权限变化后重新声明前台服务类型，相机与麦克风才能在后台使用
        promote(if (Kernel.last.first == KernelHost.State.READY) "红莉栖在线" else "启动中")
        return START_STICKY
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
        val socket = File(filesDir, "run/ops.sock")
        ops = OpsServer(socket, router).also { it.start() }
        val kernel = KernelHost(this, installer.root, installer.nodeExecutable(), libs, socket, object : KernelHost.Listener {
            override fun onState(state: KernelHost.State, detail: String) {
                Kernel.publish(state, detail)
                Handler(Looper.getMainLooper()).post { promote(if (state == KernelHost.State.READY) "红莉栖在线" else "正在恢复") }
                if (state == KernelHost.State.READY) onReady()
            }

            override fun onEvent(message: Frame.Message) = route(message)
        })
        host = kernel
        kernel.start()
    }

    private fun onReady() {
        refreshConfig()
        Kernel.flushProactive(this)
    }

    fun refreshConfig() {
        request("config.get") { reply ->
            val packages = reply.head.optJSONObject("notify")?.optJSONArray("packages") ?: JSONArray()
            Kernel.notifyPackages = (0 until packages.length()).map { packages.getString(it) }.toSet()
        }
    }

    /** 控制面的事件：音频给播放器，识别指令给识别器，其余给界面。 */
    private fun route(message: Frame.Message) {
        val head = message.head
        val method = head.optString("method")
        val params = head.optJSONObject("params") ?: JSONObject()
        when (method) {
            "asr.command" -> recognizer.command(params)
            "voice.event" -> {
                player.event(params, message.body)
                if (params.optString("type") != "audio") Kernel.webEvent(method, params)
            }
            "ready" -> Unit
            else -> Kernel.webEvent(method, params)
        }
    }

    fun request(method: String, params: JSONObject = JSONObject(), callback: (Frame.Message) -> Unit) {
        val kernel = host
        if (kernel == null) {
            callback(Frame.Message(JSONObject().put("kind", "res").put("status", "error").put("code", "not_ready").put("message", "控制面未就绪")))
            return
        }
        kernel.request(method, params, callback)
    }

    fun restartControl() = host?.restart()

    override fun onDestroy() {
        host?.stop()
        ops?.stop()
        if (Kernel.service === this) Kernel.service = null
        super.onDestroy()
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun promote(text: String) {
        val notification = notification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (granted(Manifest.permission.CAMERA)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (granted(Manifest.permission.RECORD_AUDIO)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTIFICATION_ID, notification, types)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            var types = 0
            if (granted(Manifest.permission.CAMERA)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (granted(Manifest.permission.RECORD_AUDIO)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTIFICATION_ID, notification, types)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
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

/** 进程内中转：服务发布状态与事件，界面订阅；主动开口在控制面就绪前先排队。 */
object Kernel {
    @Volatile var service: KernelService? = null
    @Volatile var notifyPackages: Set<String> = emptySet()
    @Volatile var web: ((String, JSONObject) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<(KernelHost.State, String) -> Unit>()
    @Volatile var last: Pair<KernelHost.State, String> = KernelHost.State.STARTING to ""
        private set

    fun publish(state: KernelHost.State, detail: String) {
        last = state to detail
        webEvent("kernel.state", JSONObject().put("state", state.name).put("detail", detail))
        main.post { listeners.toList().forEach { it(state, detail) } }
    }

    fun subscribe(listener: (KernelHost.State, String) -> Unit): () -> Unit {
        main.post { listeners += listener; listener(last.first, last.second) }
        return { main.post { listeners -= listener } }
    }

    fun webEvent(method: String, params: JSONObject) {
        val sink = web ?: return
        main.post { sink(method, params) }
    }

    private const val PREFS = "proactive"

    /** 提醒与通知先落盘再投递，控制面重启或尚未就绪时不丢。 */
    fun proactive(context: Context, kind: String, text: String, requestId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(this) {
            val queue = JSONArray(prefs.getString("queue", "[]"))
            queue.put(JSONObject().put("kind", kind).put("text", text).put("requestId", requestId))
            prefs.edit().putString("queue", queue.toString()).commit()
        }
        val running = service
        if (running == null) KernelService.start(context) else if (last.first == KernelHost.State.READY) flushProactive(running)
    }

    fun flushProactive(service: KernelService) {
        val prefs = service.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val items = synchronized(this) { JSONArray(prefs.getString("queue", "[]")) }
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            service.request("proactive", item) { reply ->
                val code = reply.head.optString("code")
                // 已投递、参数错误（永远投不了）就出队；控制面重启导致的失败留待下次就绪重投。
                // 模型未配置时通知直接丢弃——配好模型那一刻不该涌进一堆过期通知；提醒则留着等
                val drop = reply.head.optString("status") == "done" || code == "bad_args" ||
                    (code == "not_configured" && item.optString("kind") == "notification")
                if (drop) remove(service, item.getString("requestId"))
            }
        }
    }

    private fun remove(context: Context, requestId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(this) {
            val queue = JSONArray(prefs.getString("queue", "[]"))
            val kept = JSONArray()
            for (i in 0 until queue.length()) if (queue.getJSONObject(i).getString("requestId") != requestId) kept.put(queue.getJSONObject(i))
            prefs.edit().putString("queue", kept.toString()).commit()
        }
    }
}
