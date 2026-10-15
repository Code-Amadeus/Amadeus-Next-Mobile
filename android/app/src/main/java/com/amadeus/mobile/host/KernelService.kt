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
import com.amadeus.mobile.phone.PhoneAccessibilityService
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
        /** 安装的唤醒锁上限，异常卡住时也不会一直耗电 */
        private const val INSTALL_WAKE_MS = 10 * 60_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, KernelService::class.java))
        }
    }

    private var host: KernelHost? = null
    private var ops: OpsServer? = null
    private var camera: CameraExec? = null
    private val main = Handler(Looper.getMainLooper())
    /** 只在主线程上读写 */
    private val agent = com.amadeus.mobile.host.AgentActivity()
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
            Kernel.webEvent("voice.playback", JSONObject().put("type", type).put("rms", rms.toDouble()), durable = false)
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
        // 解压一万多个文件要几十秒；熄屏后 CPU 一休眠就停在半路，主人亮屏前控制面都起不来
        val awake = getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "amadeus:payload-install")
        awake.acquire(INSTALL_WAKE_MS)
        val libs = try {
            installer.ensureInstalled { percent, detail -> Kernel.publish(KernelHost.State.STARTING, detail, percent) }
            installer.linkNativeLibraries()
        } catch (e: Exception) {
            Kernel.publish(KernelHost.State.STOPPED, "payload 安装失败：${e.message}")
            return
        } finally {
            if (awake.isHeld) awake.release()
        }
        val socket = File(filesDir, "run/ops.sock")
        ops = OpsServer(socket, router).also { it.start() }
        val kernel = KernelHost(this, installer.root, installer.nodeExecutable(), libs, socket, object : KernelHost.Listener {
            override fun onState(state: KernelHost.State, detail: String) {
                // 安装之后只剩拉起控制面一步，等它报就绪
                Kernel.publish(state, detail, if (state == KernelHost.State.STARTING) PayloadInstaller.LAUNCHING else null)
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
            "turn.begin" -> main.post { agent.turnBegin(Kernel.appResumed); refreshAgent() }
            "turn.tool" -> main.post {
                if (params.optString("phase") == "start") agent.toolStart(params.optString("name")) else agent.toolEnd()
                refreshAgent()
            }
            "turn.end" -> main.post {
                val a11y = PhoneAccessibilityService.instance
                if (agent.turnEnd(foregroundIsApp())) a11y?.bringAppToFront()
                // 主人不在 Amadeus 里时不切回，但她拉下的通知栏要收好；主人接管了就原样留给主人
                else if (!agent.takenOver) a11y?.collapseShade()
                refreshAgent()
            }
        }
        when (method) {
            "asr.command" -> recognizer.command(params)
            "voice.event" -> {
                player.event(params, message.body)
                if (params.optString("type") != "audio") Kernel.webEvent(method, params, durable = false)
            }
            // 识别中间结果、字幕与口型都是一闪而过的，错过不必重放
            "voice.asr" -> Kernel.webEvent(method, params, durable = false)
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

    private fun foregroundIsApp(): Boolean {
        // 通知栏盖在 Amadeus 上时 Activity 仍是 resumed，有无障碍服务就以活动窗口为准
        val a11y = PhoneAccessibilityService.instance ?: return Kernel.appResumed
        return a11y.foregroundPackage() == packageName
    }

    /** 按当前状态显示或隐藏"她正在操作"的浮层。 */
    fun refreshAgent() {
        main.post {
            val overlay = PhoneAccessibilityService.instance?.overlay ?: return@post
            val state = agent.overlay(foregroundIsApp())
            if (state.visible) overlay.show(state.text) else overlay.hide()
        }
    }

    /** 主人点了浮层上的「接管」：中止这一轮，手机交还给主人，结束后也不切回 Amadeus。 */
    fun takeOver() {
        main.post {
            if (agent.takeOver()) request("chat.abort") { }
            refreshAgent()
        }
    }

    /** 主人点了「终止」：中止这一轮并立刻回到 Amadeus。 */
    fun stopTask() {
        main.post {
            if (agent.takeOver()) request("chat.abort") { }
            PhoneAccessibilityService.instance?.bringAppToFront()
            refreshAgent()
        }
    }

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
    /** Amadeus 的界面是否在前台；决定回合结束后要不要切回 */
    @Volatile var appResumed = false
    /** 网页的事件入口：方法名、参数 JSON、序号（0 表示不进日志的瞬时事件）。 */
    @Volatile var web: ((String, String, Long) -> Unit)? = null
    /** 发往界面的事件日志；放在服务进程里，Activity 重建后照样能补拉。 */
    val feed = UiFeed()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<(KernelHost.State, String) -> Unit>()
    @Volatile var last: Pair<KernelHost.State, String> = KernelHost.State.STARTING to ""
        private set

    fun publish(state: KernelHost.State, detail: String, progress: Int? = null) {
        last = state to detail
        webEvent("kernel.state", JSONObject().put("state", state.name).put("detail", detail).apply { if (progress != null) put("progress", progress) })
        main.post { listeners.toList().forEach { it(state, detail) } }
    }

    fun subscribe(listener: (KernelHost.State, String) -> Unit): () -> Unit {
        main.post { listeners += listener; listener(last.first, last.second) }
        return { main.post { listeners -= listener } }
    }

    /**
     * durable 的事件先编号进日志再推送：界面不在时不丢，回来按序号重放。
     * 推送本身尽力而为，可靠性由界面的序号检查与重放保证。
     */
    fun webEvent(method: String, params: JSONObject, durable: Boolean = true) {
        val json = params.toString()
        val seq = if (durable) feed.append(method, json).seq else 0L
        val sink = web ?: return
        main.post { sink(method, json, seq) }
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
