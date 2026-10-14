package com.amadeus.mobile.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.amadeus.mobile.host.Frame
import com.amadeus.mobile.host.Kernel
import com.amadeus.mobile.host.KernelService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 阶段 1 的临时状态页：显示控制面状态，手动发 ping / smoke.status，强制重启验证恢复。WebView 在阶段 3 替换它。 */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var log: TextView
    private var unsubscribe: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        log = TextView(this).apply { setTextIsSelectable(true); textSize = 13f }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("ping") { call("ping") })
            addView(button("状态") { call("smoke.status") })
            addView(button("重启控制面") { Kernel.service?.restartControl(); append("已请求重启") })
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            addView(buttons)
            addView(ScrollView(context).apply { addView(log) })
        })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // 拒绝也不影响运行，只是看不到常驻通知
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        KernelService.start(this)
    }

    override fun onStart() {
        super.onStart()
        unsubscribe = Kernel.subscribe { state, detail -> append("[$state] $detail") }
    }

    override fun onStop() {
        unsubscribe?.invoke()
        unsubscribe = null
        super.onStop()
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun call(method: String) {
        val service = Kernel.service ?: return append("服务未启动")
        service.request(method) { message: Frame.Message -> main.post { append("$method → ${message.head}") } }
    }

    private fun append(line: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        log.append("$time  $line\n")
    }
}
