package com.amadeus.mobile.host

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 监管控制面进程：启动 node、按帧协议收发、退出后指数退避重启。
 * 控制面重启时，所有未应答的请求以 restarted 失败（文档第四节）。
 */
class KernelHost(
    private val context: Context,
    private val payloadRoot: File,
    private val node: File,
    private val libs: File,
    private val listener: Listener,
) {
    interface Listener {
        fun onState(state: State, detail: String)
        fun onEvent(message: Frame.Message)
    }

    enum class State { STARTING, READY, RESTARTING, STOPPED }

    companion object {
        const val PROTOCOL = 1
        private const val TAG = "KernelHost"
        private const val MIN_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 60_000L
        // 活过这么久才算稳定，退避归零；否则说明是启动即崩，继续拉长间隔
        private const val STABLE_MS = 60_000L
        private const val LOG_LIMIT = 2L shl 20
    }

    private val pending = ConcurrentHashMap<String, (Frame.Message) -> Unit>()
    private val nextId = AtomicLong(1)
    private val writeLock = Any()
    @Volatile private var stdin: OutputStream? = null
    @Volatile private var process: Process? = null
    @Volatile private var running = false
    @Volatile private var ready = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread(::superviseLoop, "kernel-host").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        process?.destroy()
        thread?.interrupt()
    }

    /** 主动杀掉当前进程，交给监管循环重启；用于验证恢复。 */
    fun restart() {
        process?.destroy()
    }

    fun request(method: String, params: JSONObject = JSONObject(), callback: (Frame.Message) -> Unit) {
        val out = stdin
        if (!ready || out == null) {
            callback(errorResponse("", "not_ready", "control plane is not ready"))
            return
        }
        val id = nextId.getAndIncrement().toString()
        pending[id] = callback
        try {
            synchronized(writeLock) {
                Frame.write(out, JSONObject().put("kind", "req").put("id", id).put("method", method).put("params", params))
            }
        } catch (e: Exception) {
            pending.remove(id)?.invoke(errorResponse(id, "restarted", "write failed: ${e.message}"))
        }
    }

    private fun superviseLoop() {
        var backoff = MIN_BACKOFF_MS
        while (running) {
            listener.onState(State.STARTING, "启动控制面")
            val startedAt = System.currentTimeMillis()
            val detail = try { runOnce() } catch (e: Exception) { "启动失败：${e.message}" }
            ready = false
            stdin = null
            failPending()
            if (!running) break
            if (System.currentTimeMillis() - startedAt > STABLE_MS) backoff = MIN_BACKOFF_MS
            listener.onState(State.RESTARTING, "$detail；${backoff / 1000}s 后重启")
            try { Thread.sleep(backoff) } catch (_: InterruptedException) { break }
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
        listener.onState(State.STOPPED, "已停止")
    }

    /** 跑一轮控制面进程直到它退出，返回退出描述。 */
    private fun runOnce(): String {
        val main = File(payloadRoot, "app/control/src/main.js")
        val data = File(context.filesDir, "data").apply { mkdirs() }
        val home = File(context.filesDir, "home").apply { mkdirs() }
        val log = File(context.filesDir, "logs/control.log").apply { parentFile!!.mkdirs() }
        if (log.length() > LOG_LIMIT) log.renameTo(File(log.path + ".1"))

        val builder = ProcessBuilder(node.path, main.path)
            .directory(home)
            .redirectError(ProcessBuilder.Redirect.appendTo(log))
        builder.environment().apply {
            // Termux 的 ELF 把 RUNPATH 写死在 com.termux 前缀下，用 LD_LIBRARY_PATH 改指向库名链接目录
            put("LD_LIBRARY_PATH", libs.path)
            put("SSL_CERT_FILE", File(payloadRoot, "usr/etc/tls/cert.pem").path)
            put("TMPDIR", context.cacheDir.path)
            put("HOME", home.path)
            put("AMADEUS_DATA", data.path)
        }
        val proc = builder.start()
        process = proc
        stdin = proc.outputStream
        val input = DataInputStream(proc.inputStream.buffered(1 shl 16))
        try {
            while (true) {
                val message = Frame.read(input) ?: break
                dispatch(message)
            }
        } catch (e: Frame.ProtocolException) {
            Log.e(TAG, "protocol error", e)
            proc.destroy()
        } catch (_: java.io.IOException) {
            // 进程被销毁时管道读会被打断，这属于正常退出路径，以退出码为准
        }
        val code = proc.waitFor()
        return "控制面退出（code $code）"
    }

    private fun dispatch(message: Frame.Message) {
        val head = message.head
        when (message.kind) {
            "res" -> pending.remove(head.optString("id"))?.invoke(message)
            "evt" -> {
                if (head.optString("method") == "ready") {
                    val protocol = head.optJSONObject("params")?.optInt("protocol") ?: -1
                    if (protocol != PROTOCOL) {
                        listener.onState(State.RESTARTING, "协议版本不匹配：$protocol ≠ $PROTOCOL，请重装 payload")
                        process?.destroy()
                        return
                    }
                    ready = true
                    listener.onState(State.READY, head.optJSONObject("params")?.toString() ?: "")
                }
                listener.onEvent(message)
            }
            else -> Log.w(TAG, "unexpected frame from control plane: $head")
        }
    }

    private fun failPending() {
        for (id in pending.keys.toList()) pending.remove(id)?.invoke(errorResponse(id, "restarted", "control plane restarted"))
    }

    private fun errorResponse(id: String, code: String, message: String) = Frame.Message(
        JSONObject().put("kind", "res").put("id", id).put("status", "error").put("code", code).put("message", message),
    )
}
