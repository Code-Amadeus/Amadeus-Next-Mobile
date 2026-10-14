package com.amadeus.mobile.host

import android.content.Context
import com.amadeus.mobile.phone.Apps
import com.amadeus.mobile.phone.CameraExec
import com.amadeus.mobile.phone.PhoneAccessibilityService
import com.amadeus.mobile.phone.Reminders
import com.amadeus.mobile.phone.Snapshot
import com.amadeus.mobile.phone.VerbException
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 动词的唯一入口：参数校验 → Guard 判决 →（需确认时）等主人 → 执行 → 审计。
 * 所有动词在一个线程上串行执行：等待确认时其他动作只能排队，代理无法趁机去点确认通知。
 */
class VerbRouter(
    private val context: Context,
    private val audit: AuditLog,
    private val camera: () -> CameraExec?,
) {
    class Result(val data: JSONObject, val body: ByteArray? = null, val mime: String? = null)

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "verbs") }

    fun handle(verb: String, args: JSONObject): Result = try {
        worker.submit<Result> { run(verb, args) }.get()
    } catch (e: java.util.concurrent.ExecutionException) {
        throw e.cause ?: e
    }

    private fun run(verb: String, args: JSONObject): Result {
        val started = System.currentTimeMillis()
        var decision = "allowed"
        var code: String? = null
        try {
            return execute(verb, args) { decision = it }
        } catch (e: VerbException) {
            code = e.code
            if (e.code == "guard_block" || e.code == "screen_locked" || e.code == "bad_package") decision = "blocked"
            if (e.code == "user_denied" || e.code == "confirm_timeout") decision = "denied"
            throw e
        } catch (e: Exception) {
            code = "failed"
            throw VerbException("failed", e.message ?: e.javaClass.simpleName)
        } finally {
            val entry = JSONObject().put("at", started).put("verb", verb).put("args", AuditLog.summarize(verb, args))
                .put("decision", decision).put("ms", System.currentTimeMillis() - started)
            if (code != null) entry.put("code", code)
            audit.append(entry)
            Kernel.webEvent("verb.log", entry)
        }
    }

    private fun a11y(): PhoneAccessibilityService =
        PhoneAccessibilityService.instance ?: throw VerbException("a11y_off", "the accessibility service is off; ask the user to enable Amadeus in accessibility settings")

    private fun screenOf(a11y: PhoneAccessibilityService, snapshot: Snapshot) = Guard.Screen(
        packageName = snapshot.packageName,
        activity = snapshot.activity,
        locked = snapshot.locked,
        hasPassword = snapshot.hasPassword,
        texts = snapshot.texts,
        appLabel = Apps.label(a11y, snapshot.packageName),
    )

    private fun enforce(decision: Guard.Decision, mark: (String) -> Unit) {
        when (decision) {
            Guard.Decision.Allow -> Unit
            is Guard.Decision.Block -> throw VerbException(decision.code, decision.reason)
            is Guard.Decision.Confirm -> {
                when (ConfirmGate.ask(context, "红莉栖${decision.prompt}")) {
                    ConfirmGate.Outcome.ALLOWED -> mark("confirmed")
                    ConfirmGate.Outcome.DENIED -> throw VerbException("user_denied", "the user declined this action")
                    ConfirmGate.Outcome.TIMEOUT -> throw VerbException("confirm_timeout", "the user did not confirm within 60 seconds")
                }
            }
        }
    }

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

    private fun execute(verb: String, args: JSONObject, mark: (String) -> Unit): Result {
        when (verb) {
            "observe" -> {
                val a11y = a11y()
                val snapshot = a11y.observe()
                enforce(Guard.judge(verb, emptyMap(), screenOf(a11y, snapshot), null), mark)
                return Result(JSONObject().put("text", a11y.render(snapshot, args.optString("mode") == "text")))
            }
            "screenshot" -> {
                val a11y = a11y()
                // 判决用此刻的屏幕事实；不替换最新快照，以免让模型手里的编号失效
                val facts = a11y.observe(keep = false)
                enforce(Guard.judge(verb, emptyMap(), screenOf(a11y, facts), null), mark)
                val maxWidth = args.optInt("maxWidth", 720).coerceIn(240, 1440)
                return Result(JSONObject().put("package", facts.packageName), a11y.screenshot(maxWidth), "image/jpeg")
            }
            "click", "set_value", "enter", "swipe", "key" -> return act(verb, args, mark)
            "launch_app" -> {
                val pkg = args.optString("package")
                enforce(Guard.judge(verb, mapOf("package" to pkg), null, null), mark)
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

    private fun act(verb: String, args: JSONObject, mark: (String) -> Unit): Result {
        val a11y = a11y()
        val size = a11y.screenSize()
        // 判决用此刻的屏幕事实：快照之后可能已经弹出了收银台或密码框
        val facts = a11y.observe(keep = false)
        val screen = screenOf(a11y, facts)
        when (verb) {
            "key" -> {
                enforce(Guard.judge(verb, emptyMap(), screen, null), mark)
                a11y.key(args.optString("key"))
            }
            "swipe" -> {
                current(a11y)
                enforce(Guard.judge(verb, emptyMap(), screen, null), mark)
                a11y.swipe(
                    fraction(args, "x1") * size.x, fraction(args, "y1") * size.y,
                    fraction(args, "x2") * size.x, fraction(args, "y2") * size.y,
                    args.optLong("ms", 300).coerceIn(50, 3000),
                )
            }
            "click" -> {
                if (args.has("ref")) {
                    val (_, item) = item(a11y, args.getString("ref"))
                    enforce(Guard.judge(verb, emptyMap(), screen, item.label), mark)
                    a11y.clickNode(item)
                } else {
                    val snapshot = current(a11y)
                    val x = (fraction(args, "x") * size.x).toInt()
                    val y = (fraction(args, "y") * size.y).toInt()
                    enforce(Guard.judge(verb, emptyMap(), screen, snapshot.at(x, y)?.label), mark)
                    a11y.tap(x.toFloat(), y.toFloat())
                }
            }
            "set_value" -> {
                val (_, item) = item(a11y, args.optString("ref"))
                enforce(Guard.judge(verb, emptyMap(), screen, item.label), mark)
                a11y.setText(item, args.optString("text"))
            }
            "enter" -> {
                val item = if (args.has("ref")) item(a11y, args.getString("ref")).second else null
                enforce(Guard.judge(verb, emptyMap(), screen, item?.label), mark)
                a11y.enter(item)
            }
        }
        a11y.settle()
        return Result(JSONObject())
    }

    fun recentAudit(limit: Int): JSONArray = audit.recent(limit)
}
