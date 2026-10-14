package com.amadeus.mobile.host

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import android.system.Os
import android.util.Log
import com.amadeus.mobile.phone.VerbException
import org.json.JSONObject
import java.io.DataInputStream
import java.io.File

/**
 * 执行面的动词入口：App 私有目录里的 unix socket。
 * 用文件系统命名空间而不是抽象命名空间——抽象 socket 没有文件权限，任何应用都能连。
 * 再按对端 uid 校验，只接受本应用（控制面与宿主同 uid）。
 */
class OpsServer(private val socketFile: File, private val router: VerbRouter) {
    companion object { private const val TAG = "OpsServer" }

    private var server: LocalServerSocket? = null
    // 必须持有：LocalServerSocket 共用它的 fd，它被回收时 finalizer 会关掉 fd，监听随之失效
    private var bound: LocalSocket? = null
    @Volatile private var running = false

    fun start() {
        socketFile.parentFile!!.apply { mkdirs(); Os.chmod(path, "700".toInt(8)) }
        socketFile.delete()
        val socket = LocalSocket(LocalSocket.SOCKET_STREAM).apply { bind(LocalSocketAddress(socketFile.path, LocalSocketAddress.Namespace.FILESYSTEM)) }
        bound = socket
        Os.chmod(socketFile.path, "600".toInt(8))
        server = LocalServerSocket(socket.fileDescriptor)
        running = true
        Thread({ acceptLoop() }, "ops-accept").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        runCatching { bound?.close() }
        socketFile.delete()
    }

    private fun acceptLoop() {
        while (running) {
            val socket = try { server?.accept() ?: break } catch (e: Exception) {
                if (running) Log.e(TAG, "accept failed", e)
                break
            }
            if (socket.peerCredentials.uid != Process.myUid()) {
                Log.w(TAG, "rejected connection from uid ${socket.peerCredentials.uid}")
                socket.close()
                continue
            }
            Thread({ serve(socket) }, "ops-conn").apply { isDaemon = true; start() }
        }
    }

    private fun serve(socket: LocalSocket) {
        val output = socket.outputStream
        val writeLock = Any()
        try {
            val input = DataInputStream(socket.inputStream.buffered())
            while (true) {
                val message = Frame.read(input) ?: break
                if (message.kind != "req") continue
                val head = message.head
                // 每个请求一个线程等结果；真正的执行由 VerbRouter 串行化
                Thread({
                    val id = head.optString("id")
                    val verb = head.optString("method")
                    val reply = try {
                        val result = router.handle(verb, head.optJSONObject("params") ?: JSONObject())
                        val frame = JSONObject(result.data.toString()).put("kind", "res").put("id", id).put("status", "done")
                        if (result.mime != null) frame.put("body", JSONObject().put("mime", result.mime))
                        frame to (result.body ?: ByteArray(0))
                    } catch (e: VerbException) {
                        JSONObject().put("kind", "res").put("id", id).put("status", "error").put("code", e.code).put("reason", e.message) to ByteArray(0)
                    } catch (e: Exception) {
                        JSONObject().put("kind", "res").put("id", id).put("status", "error").put("code", "failed").put("reason", e.message ?: "failed") to ByteArray(0)
                    }
                    try {
                        synchronized(writeLock) { Frame.write(output, reply.first, reply.second) }
                    } catch (e: Exception) {
                        Log.w(TAG, "reply failed", e)
                    }
                }, "ops-req").start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "connection ended", e)
        } finally {
            runCatching { socket.close() }
        }
    }
}
