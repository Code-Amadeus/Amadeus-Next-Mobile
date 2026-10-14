package com.amadeus.mobile.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.amadeus.mobile.host.ConfirmGate
import com.amadeus.mobile.host.Kernel
import com.amadeus.mobile.host.KernelService
import com.amadeus.mobile.phone.NotifyListener
import com.amadeus.mobile.phone.PhoneAccessibilityService
import com.amadeus.mobile.phone.Reminders
import org.json.JSONObject

/**
 * 脸：WebView 只加载 APK 内置页面，所有网络与磁盘访问都经宿主转给控制面。
 * 高风险动作的确认用原生对话框，不交给网页渲染。
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private val dialogs = HashMap<String, AlertDialog>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 烘焙好的 emote 模型由控制面写在私有目录，按同源路径交给网页读取
        val emoteCache = java.io.File(filesDir, "data/emote-cache").apply { mkdirs() }
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/emote-cache/", WebViewAssetLoader.InternalStoragePathHandler(this, emoteCache))
            .build()
        web = Stage(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(request.url)

                // 页面不能跳到任何外部地址；同源的重新加载（换形象时）放行
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                    request.url.scheme != "https" || request.url.host != WebViewAssetLoader.DEFAULT_DOMAIN
            }
            addJavascriptInterface(Bridge(), "AmadeusHost")
            isHapticFeedbackEnabled = false
        }
        // 全面屏：网页自己用 safe-area 处理状态栏与手势条
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.parseColor("#0f0d12")
        setContentView(web)
        web.loadUrl("https://appassets.androidplatform.net/assets/web/index.html")
        // 事件通道跟随整个 Activity：她按 HOME 把应用切到后台时，回复和回合结束仍要送进网页
        Kernel.web = { method, params -> dispatch(method, params) }
        requestRuntimePermissions()
        KernelService.start(this)
    }

    override fun onResume() {
        super.onResume()
        dispatch("kernel.state", JSONObject().put("state", Kernel.last.first.name).put("detail", Kernel.last.second))
        ConfirmGate.presenter = object : ConfirmGate.Presenter {
            override fun show(nonce: String, prompt: String) = runOnUiThread {
                dialogs[nonce] = AlertDialog.Builder(this@MainActivity)
                    .setTitle("红莉栖请求确认")
                    .setMessage(prompt)
                    .setCancelable(false)
                    .setNegativeButton("拒绝") { _, _ -> ConfirmGate.resolve(nonce, false) }
                    .setPositiveButton("允许") { _, _ -> ConfirmGate.resolve(nonce, true) }
                    .show()
            }

            override fun dismiss(nonce: String) = runOnUiThread { dialogs.remove(nonce)?.dismiss() }
        }
    }

    override fun onPause() {
        ConfirmGate.presenter = null
        super.onPause()
    }

    @Deprecated("Activity 基类上仍是返回键的入口")
    override fun onBackPressed() {
        // 先让网页关掉最上层的浮层；网页说没有可关的才退到后台
        web.evaluateJavascript("window.amadeus&&window.amadeus.back?window.amadeus.back():false") { handled ->
            @Suppress("DEPRECATION")
            if (handled != "true") moveTaskToBack(true)
        }
    }

    override fun onDestroy() {
        Kernel.web = null
        web.destroy()
        super.onDestroy()
    }

    private fun dispatch(method: String, params: JSONObject) {
        web.evaluateJavascript("window.amadeus&&window.amadeus.event(${JSONObject.quote(method)},${JSONObject.quote(params.toString())})", null)
    }

    private fun reply(id: String, value: JSONObject) = runOnUiThread {
        web.evaluateJavascript("window.amadeus.reply(${JSONObject.quote(id)},${JSONObject.quote(value.toString())})", null)
    }

    private fun requestRuntimePermissions() {
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 让前台服务按新权限重新声明类型
        KernelService.start(this)
    }

    private fun hostStatus(): JSONObject {
        val enabledA11y = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val a11y = ComponentName(this, PhoneAccessibilityService::class.java).flattenToString()
        val listeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        val listener = ComponentName(this, NotifyListener::class.java).flattenToString()
        return JSONObject()
            .put("status", "done")
            .put("accessibility", enabledA11y.split(':').contains(a11y))
            .put("notificationListener", listeners.split(':').contains(listener))
            .put("batteryExempt", getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))
            .put("microphone", checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            .put("camera", checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            .put("exactAlarm", Reminders.exactAllowed(this))
    }

    @SuppressLint("BatteryLife")
    private fun open(target: String) {
        val intent = when (target) {
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "notification" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            "battery" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            "appinfo" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            "permissions" -> { runOnUiThread { requestRuntimePermissions() }; return }
            else -> return
        }
        startActivity(intent)
    }

    /**
     * 长按只留给输入框（选择、粘贴）。其他地方连长按都不执行：执行了就会发出长按的无障碍事件，
     * 三星的长按识别服务据此弹出浮层，挡住按住说话。
     */
    private class Stage(context: android.content.Context) : WebView(context) {
        private fun editing() = hitTestResult.type == HitTestResult.EDIT_TEXT_TYPE
        override fun performLongClick(): Boolean = if (editing()) super.performLongClick() else true
        override fun performLongClick(x: Float, y: Float): Boolean = if (editing()) super.performLongClick(x, y) else true
    }

    /** 网页的唯一出口：宿主自己处理少数本地方法，其余原样转给控制面。 */
    inner class Bridge {
        @JavascriptInterface
        fun request(id: String, method: String, params: String) {
            val args = runCatching { JSONObject(params) }.getOrElse { JSONObject() }
            when (method) {
                "host.status" -> reply(id, hostStatus())
                "host.open" -> { runOnUiThread { open(args.optString("target")) }; reply(id, JSONObject().put("status", "done")) }
                "audit.list" -> {
                    val service = Kernel.service
                    reply(id, if (service == null) JSONObject().put("status", "error").put("message", "服务未启动")
                    else JSONObject().put("status", "done").put("items", service.router.recentAudit(args.optInt("limit", 100))))
                }
                else -> {
                    val service = Kernel.service ?: return reply(id, JSONObject().put("status", "error").put("message", "服务未启动"))
                    service.request(method, args) { message ->
                        val head = JSONObject(message.head.toString()).apply { remove("kind"); remove("id") }
                        reply(id, head)
                        if (method == "config.set") service.refreshConfig()
                    }
                }
            }
        }
    }
}
