package com.amadeus.mobile.host

import android.content.Context
import com.amadeus.mobile.phone.Apps
import com.amadeus.mobile.phone.CameraExec
import com.amadeus.mobile.phone.PhoneAccessibilityService
import com.amadeus.mobile.phone.Reminders
import com.amadeus.mobile.phone.Snapshot
import com.amadeus.mobile.phone.VerbException
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 动词的唯一入口：参数校验 → 执行 → 审计。所有动词在一个线程上串行执行。
 */
class VerbRouter(
    private val context: Context,
    private val audit: AuditLog,
    private val camera: () -> CameraExec?,
) {
    class Result(val data: JSONObject, val body: ByteArray? = null, val mime: String? = null)

    private companion object {
        /** 屏幕比例；更短的滑动按点击处理 */
        const val MIN_SWIPE = 0.03
    }

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "verbs") }

    fun handle(verb: String, args: JSONObject): Result = try {
        worker.submit<Result> { run(verb, args) }.get()
    } catch (e: java.util.concurrent.ExecutionException) {
        throw e.cause ?: e
    }

    private fun run(verb: String, args: JSONObject): Result {
        val started = System.currentTimeMillis()
        var code: String? = null
        try {
            return execute(verb, args)
        } catch (e: VerbException) {
            code = e.code
            throw e
        } catch (e: Exception) {
            code = "failed"
            throw VerbException("failed", e.message ?: e.javaClass.simpleName)
        } finally {
            val entry = JSONObject().put("at", started).put("verb", verb).put("args", AuditLog.summarize(verb, args))
                .put("ms", System.currentTimeMillis() - started)
            if (code != null) entry.put("code", code)
            audit.append(entry)
        }
    }

    private fun a11y(): PhoneAccessibilityService =
        PhoneAccessibilityService.instance ?: throw VerbException("a11y_off", "the accessibility service is off; ask the user to enable Amadeus in accessibility settings")

    private fun fraction(args: JSONObject, name: String): Float {
        if (!args.has(name)) throw VerbException("bad_args", "$name is required")
        val value = args.getDouble(name)
        if (value !in 0.0..1.0) throw VerbException("bad_args", "$name must be between 0 and 1")
        return value.toFloat()
    }

    /** 只接受最新快照的编号，且快照之后没有切换过窗口。 */
    private fun current(a11y: PhoneAccessibilityService): Snapshot {
        val snapshot = a11y.latest ?: throw VerbException("stale_ref", "observe the screen first")
        if (snapshot.generation != a11y.currentGeneration()) throw VerbException("stale_ref", "the screen changed since the last observe; observe again")
        return snapshot
    }

    private fun item(a11y: PhoneAccessibilityService, ref: String): Pair<Snapshot, Snapshot.Item> {
        val snapshot = current(a11y)
        if (ref.substringBefore(':') != snapshot.id) throw VerbException("stale_ref", "$ref is from an old snapshot; observe again")
        val item = snapshot.find(ref) ?: throw VerbException("not_found", "no element $ref in the latest snapshot")
        return snapshot to item
    }

    private fun execute(verb: String, args: JSONObject): Result {
        when (verb) {
            "observe" -> {
                val a11y = a11y()
                val snapshot = a11y.observe()
                return Result(JSONObject().put("text", a11y.render(snapshot, args.optString("mode") == "text")))
            }
            "screenshot" -> {
                val a11y = a11y()
                val maxWidth = args.optInt("maxWidth", 720).coerceIn(240, 1440)
                return Result(JSONObject().put("package", a11y.foregroundPackage()), a11y.screenshot(maxWidth), "image/jpeg")
            }
            "click", "set_value", "enter", "swipe", "key" -> return act(verb, args)
            "launch_app" -> {
                val pkg = args.optString("package")
                Apps.launch(PhoneAccessibilityService.instance ?: context, pkg)
                PhoneAccessibilityService.instance?.settle()
                return Result(JSONObject().put("text", "opened ${Apps.label(context, pkg)}"))
            }
            "list_apps" -> {
                val text = Apps.list(context).joinToString("\n") { "${it.packageName}\t${it.label}" }
                return Result(JSONObject().put("text", text))
            }
            "wait" -> {
                Thread.sleep(args.optLong("ms", 1000).coerceIn(100, 10_000))
                return Result(JSONObject())
            }
            "camera_snap" -> {
                val exec = camera() ?: throw VerbException("camera_denied", "camera is not ready")
                val photo = exec.snap(args.optString("facing").ifEmpty { null }, args.optInt("maxWidth", 1280).coerceIn(320, 2048))
                return Result(JSONObject().put("path", photo.path), photo.jpeg, "image/jpeg")
            }
            "camera_clip" -> {
                val exec = camera() ?: throw VerbException("camera_denied", "camera is not ready")
                val path = exec.clip(args.optString("facing").ifEmpty { null }, args.optLong("durationMs", 5000).coerceIn(1000, 60_000), args.optBoolean("includeAudio"))
                return Result(JSONObject().put("path", path))
            }
            "remind_set" -> return Result(Reminders.set(context, args))
            "remind_list" -> return Result(Reminders.list(context))
            "remind_cancel" -> return Result(Reminders.cancel(context, args.optString("id")))
            else -> throw VerbException("unknown_verb", "unknown verb: $verb")
        }
    }

    private fun act(verb: String, args: JSONObject): Result {
        val a11y = a11y()
        val size = a11y.screenSize()
        when (verb) {
            "key" -> a11y.key(args.optString("key"))
            "swipe" -> {
                val (x1, y1, x2, y2) = listOf("x1", "y1", "x2", "y2").map { fraction(args, it) }
                // 起止点重合的 swipe 落下去就是一次点击，绕开了 click 的元素定位，容易误触开关
                if (Math.hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()) < MIN_SWIPE) {
                    throw VerbException("bad_args", "start and end are the same point; use click to tap")
                }
                a11y.swipe(x1 * size.x, y1 * size.y, x2 * size.x, y2 * size.y, args.optLong("ms", 300).coerceIn(50, 3000))
            }
            "click" -> {
                if (args.has("ref")) {
                    val (_, item) = item(a11y, args.getString("ref"))
                    a11y.clickNode(item)
                } else {
                    val x = (fraction(args, "x") * size.x).toInt()
                    val y = (fraction(args, "y") * size.y).toInt()
                    a11y.tap(x.toFloat(), y.toFloat())
                }
            }
            "set_value" -> {
                val (_, item) = item(a11y, args.optString("ref"))
                a11y.setText(item, args.optString("text"))
            }
            "enter" -> {
                val item = if (args.has("ref")) item(a11y, args.getString("ref")).second else null
                a11y.enter(item)
            }
        }
        a11y.settle()
        return Result(JSONObject())
    }

}
