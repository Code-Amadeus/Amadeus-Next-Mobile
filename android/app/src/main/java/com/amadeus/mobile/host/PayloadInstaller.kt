package com.amadeus.mobile.host

import android.content.Context
import android.system.Os
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * 把 APK 内的 payload 解到私有目录。清单 id 未变就跳过；变了就整体重装。
 * 每个文件都按清单校验 sha256，清单之外的条目一律拒绝，防止被篡改的 payload 写出目录。
 */
class PayloadInstaller(private val context: Context) {
    companion object {
        /** 解压结束时的进度 */
        const val EXTRACTED = 80
        const val INSTALLED = 85
        /** 拉起控制面时的进度；就绪后横幅直接隐藏 */
        const val LAUNCHING = 90
    }

    private val base = File(context.filesDir, "payload")
    val root = File(base, "root")
    private val marker = File(base, "installed")

    class InstallException(message: String) : Exception(message)

    private data class Entry(val sha256: String, val exec: Boolean)

    /** @param progress 安装进度百分比（0–[INSTALLED]）与说明 */
    fun ensureInstalled(progress: (Int, String) -> Unit): String {
        val manifest = context.assets.open("manifest.json").use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
        val id = manifest.getString("id")
        if (root.isDirectory && marker.isFile && marker.readText() == id) return id

        progress(0, "安装 payload $id")
        val staging = File(base, "staging")
        staging.deleteRecursively()
        staging.mkdirs()

        val files = HashMap<String, Entry>()
        manifest.getJSONArray("files").let { array ->
            for (i in 0 until array.length()) {
                val file = array.getJSONObject(i)
                files[file.getString("path")] = Entry(file.getString("sha256"), file.getBoolean("exec"))
            }
        }

        var written = 0
        var reported = 0
        context.assets.open("payload.zip").use { raw ->
            ZipInputStream(raw.buffered(1 shl 16)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val expected = files[entry.name] ?: throw InstallException("unexpected entry: ${entry.name}")
                    val target = inside(staging, entry.name)
                    target.parentFile!!.mkdirs()
                    val actual = copyHashing(zip, target)
                    if (actual != expected.sha256) throw InstallException("sha256 mismatch: ${entry.name}")
                    if (expected.exec && !target.setExecutable(true, true)) throw InstallException("chmod failed: ${entry.name}")
                    written++
                    // 解压占安装的绝大部分时间，按百分比变化上报，避免一万多个文件逐个刷界面
                    val percent = written * EXTRACTED / files.size
                    if (percent != reported) {
                        reported = percent
                        progress(percent, "已解压 $written / ${files.size}")
                    }
                }
            }
        }
        if (written != files.size) throw InstallException("payload incomplete: $written / ${files.size}")

        manifest.getJSONArray("links").let { array ->
            for (i in 0 until array.length()) {
                val link = array.getJSONObject(i)
                val relative = link.getString("path")
                val path = inside(staging, relative)
                val target = link.getString("target")
                // 链接目标同样不能逃出 payload 目录
                if (target.startsWith("/")) throw InstallException("absolute link target: $relative -> $target")
                val parent = relative.substringBeforeLast('/', "")
                inside(staging, if (parent.isEmpty()) target else "$parent/$target")
                path.parentFile!!.mkdirs()
                Os.symlink(target, path.path)
            }
        }

        root.deleteRecursively()
        if (!staging.renameTo(root)) throw InstallException("cannot move staging to root")
        marker.writeText(id)
        progress(INSTALLED, "payload $id 安装完成（${files.size} 个文件）")
        return id
    }

    /**
     * 在私有目录建一组与原库名同名的链接，指向 nativeLibraryDir 里改过名的 .so，供 LD_LIBRARY_PATH 使用。
     * App 更新后 nativeLibraryDir 路径会变，所以每次启动都重建。
     */
    fun linkNativeLibraries(): File {
        val manifest = context.assets.open("manifest.json").use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
        val native = manifest.getJSONObject("native")
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val libs = File(base, "libs")
        libs.deleteRecursively()
        libs.mkdirs()
        val array = native.getJSONArray("libs")
        for (i in 0 until array.length()) {
            val lib = array.getJSONObject(i)
            val target = File(nativeDir, lib.getString("file"))
            if (!target.isFile) throw InstallException("native library missing: ${target.name}")
            Os.symlink(target.path, inside(libs, lib.getString("name")).path)
        }
        return libs
    }

    fun nodeExecutable(): File {
        val manifest = context.assets.open("manifest.json").use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
        return File(context.applicationInfo.nativeLibraryDir, manifest.getJSONObject("native").getString("exe"))
    }

    private fun inside(dir: File, relative: String): File {
        val canonicalDir = dir.canonicalFile
        val file = File(canonicalDir, relative)
        // 用规范化后的路径比较，拒绝 ".." 或绝对路径造成的越界
        val normalized = file.toPath().normalize().toFile()
        if (!normalized.path.startsWith(canonicalDir.path + File.separator)) throw InstallException("path escapes payload: $relative")
        return normalized
    }

    private fun copyHashing(input: InputStream, target: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        target.outputStream().use { out ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                out.write(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
