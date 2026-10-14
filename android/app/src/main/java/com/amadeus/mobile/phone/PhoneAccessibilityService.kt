package com.amadeus.mobile.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 一次 observe 的结果：编号只在这次快照内有效。 */
class Snapshot(
    val id: String,
    val generation: Int,
    val packageName: String,
    val activity: String,
    val locked: Boolean,
    val nodes: List<Item>,
    val hasPassword: Boolean,
    val texts: List<String>,
) {
    class Item(val ref: String, val node: AccessibilityNodeInfo, val role: String, val label: String, val bounds: Rect, val clickable: Boolean, val editable: Boolean)

    fun find(ref: String): Item? = nodes.firstOrNull { it.ref == ref }

    /** 覆盖该点的最小节点，用于判断坐标点击落在什么上。 */
    fun at(x: Int, y: Int): Item? = nodes.filter { it.bounds.contains(x, y) }.minByOrNull { it.bounds.width().toLong() * it.bounds.height() }
}

class VerbException(val code: String, message: String) : Exception(message)

/**
 * 执行面的眼与手。所有方法在 VerbRouter 的工作线程上调用；手势等待系统回调。
 * 窗口切换会让 generation 前进，旧快照的编号随之作废，避免点到已经换掉的界面。
 */
class PhoneAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: PhoneAccessibilityService? = null
            private set
        private const val MAX_NODES = 250
        private const val SETTLE_QUIET_MS = 300L
        private const val SETTLE_MAX_MS = 2_000L
    }

    private val generation = AtomicInteger(0)
    private val snapshotCounter = AtomicInteger(0)
    @Volatile private var lastEventAt = 0L
    @Volatile private var currentActivity = ""
    @Volatile private var currentPackage = ""
    @Volatile var latest: Snapshot? = null
        private set
    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        screenshotExecutor.shutdown()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        lastEventAt = SystemClock.uptimeMillis()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString().orEmpty()
            val cls = event.className?.toString().orEmpty()
            // 只有 Activity 级别的切换才记为当前界面；弹出的 Toast、对话框类名不覆盖
            if (pkg.isNotEmpty() && !cls.startsWith("android.widget.")) {
                currentPackage = pkg
                currentActivity = cls
            }
            generation.incrementAndGet()
        }
    }

    override fun onInterrupt() = Unit

    fun isLocked(): Boolean = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    // ---- 读屏 ----

    /** @param keep false 时只读屏幕事实给 Guard 判决，不替换最新快照，模型手里的编号保持有效 */
    fun observe(keep: Boolean = true): Snapshot {
        val root = rootInActiveWindow ?: throw VerbException("not_found", "no active window to read")
        val id = if (keep) "s${snapshotCounter.incrementAndGet()}" else "facts"
        val items = ArrayList<Snapshot.Item>()
        val texts = ArrayList<String>()
        var hasPassword = false
        var index = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 40 || !node.isVisibleToUser) return
            if (node.isPassword) hasPassword = true
            val label = labelOf(node)
            if (label.isNotEmpty()) texts += label
            val clickable = node.isClickable || node.isLongClickable
            val editable = node.isEditable
            if (items.size < MAX_NODES && (label.isNotEmpty() || clickable || editable || node.isScrollable || node.isCheckable)) {
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                if (bounds.width() > 0 && bounds.height() > 0) {
                    items += Snapshot.Item("$id:${++index}", node, roleOf(node), label, bounds, clickable, editable)
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        val pkg = root.packageName?.toString().orEmpty()
        val snapshot = Snapshot(
            id = id,
            generation = generation.get(),
            packageName = pkg,
            activity = if (pkg == currentPackage) currentActivity else "",
            locked = isLocked(),
            nodes = items,
            hasPassword = hasPassword,
            texts = texts,
        )
        if (keep) latest = snapshot
        return snapshot
    }

    fun render(snapshot: Snapshot, textOnly: Boolean): String {
        val size = screenSize()
        val out = StringBuilder()
        out.append("snapshot=${snapshot.id} package=${snapshot.packageName}")
        if (snapshot.activity.isNotEmpty()) out.append(" activity=${snapshot.activity.substringAfterLast('.')}")
        out.append(" locked=${snapshot.locked}\n")
        for (item in snapshot.nodes) {
            if (textOnly) {
                if (item.label.isNotEmpty()) out.append(item.label).append('\n')
                continue
            }
            val b = item.bounds
            fun f(v: Int, total: Int) = "%.2f".format(v.toFloat() / total)
            out.append("[${item.ref}] ${item.role}")
            if (item.label.isNotEmpty()) out.append(" \"").append(item.label).append('"')
            out.append(" (${f(b.left, size.x)},${f(b.top, size.y)},${f(b.right, size.x)},${f(b.bottom, size.y)})")
            val flags = buildList {
                if (item.clickable) add("click")
                if (item.editable) add("edit")
                if (item.node.isScrollable) add("scroll")
                if (item.node.isCheckable) add(if (item.node.isChecked) "checked" else "unchecked")
                if (!item.node.isEnabled) add("disabled")
            }
            if (flags.isNotEmpty()) out.append(' ').append(flags.joinToString(","))
            out.append('\n')
        }
        if (snapshot.nodes.size >= MAX_NODES) out.append("(list truncated at $MAX_NODES elements)\n")
        return out.toString()
    }

    private fun labelOf(node: AccessibilityNodeInfo): String {
        // 密码框的内容不读出来，只标明它是密码框
        if (node.isPassword) return "[password field]"
        val parts = listOfNotNull(node.text, node.contentDescription, node.hintText.takeIf { node.text.isNullOrEmpty() })
            .map { it.toString().trim().replace('\n', ' ') }
            .filter { it.isNotEmpty() }
            .distinct()
        return parts.joinToString(" / ").take(80)
    }

    private fun roleOf(node: AccessibilityNodeInfo): String {
        val name = node.className?.toString()?.substringAfterLast('.') ?: return "view"
        return when {
            node.isEditable || name.contains("EditText") -> "input"
            name.contains("Button") -> "button"
            name.contains("CheckBox") || name.contains("Switch") || name.contains("Toggle") -> "toggle"
            name.contains("Image") -> "image"
            name.contains("TextView") -> "text"
            name.contains("RecyclerView") || name.contains("ListView") || name.contains("ScrollView") || name.contains("GridView") -> "list"
            name.contains("WebView") -> "web"
            else -> if (node.isClickable) "item" else "view"
        }
    }

    // ---- 截图 ----

    fun screenshot(maxWidth: Int): ByteArray {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) throw VerbException("unsupported", "screenshots need Android 11 or later")
        val latch = CountDownLatch(1)
        var bitmap: Bitmap? = null
        var failure: Int? = null
        takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val hardware = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                bitmap = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                hardware?.recycle()
                result.hardwareBuffer.close()
                latch.countDown()
            }

            override fun onFailure(errorCode: Int) {
                failure = errorCode
                latch.countDown()
            }
        })
        if (!latch.await(5, TimeUnit.SECONDS)) throw VerbException("timeout", "screenshot timed out")
        failure?.let {
            if (it == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) throw VerbException("screenshot_throttled", "screenshots are limited to about one per second")
            throw VerbException("failed", "screenshot failed ($it)")
        }
        val full = bitmap ?: throw VerbException("failed", "screenshot returned no image")
        val scaled = if (full.width > maxWidth) Bitmap.createScaledBitmap(full, maxWidth, full.height * maxWidth / full.width, true) else full
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
        if (scaled !== full) scaled.recycle()
        full.recycle()
        return out.toByteArray()
    }

    // ---- 动作 ----

    fun screenSize(): android.graphics.Point {
        val manager = getSystemService(android.view.WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = manager.maximumWindowMetrics.bounds
            return android.graphics.Point(bounds.width(), bounds.height())
        }
        @Suppress("DEPRECATION")
        return android.graphics.Point().also { manager.defaultDisplay.getRealSize(it) }
    }

    fun clickNode(item: Snapshot.Item) {
        val node = item.node
        if (!node.refresh() || !node.isVisibleToUser) throw VerbException("not_found", "the element is no longer on screen; observe again")
        var target: AccessibilityNodeInfo? = node
        var depth = 0
        // 语义点击优先：自身不可点时找最近的可点祖先，再不行才按坐标点
        while (target != null && depth < 5) {
            if (target.isClickable && target.isEnabled && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            target = target.parent
            depth++
        }
        val b = Rect().also { node.getBoundsInScreen(it) }
        tap(b.exactCenterX(), b.exactCenterY())
    }

    fun tap(x: Float, y: Float) = gesture(Path().apply { moveTo(x, y) }, 60)

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) = gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, ms)

    private fun gesture(path: Path, ms: Long) {
        val latch = CountDownLatch(1)
        var ok = false
        val description = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build()
        val accepted = dispatchGesture(description, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { ok = true; latch.countDown() }
            override fun onCancelled(gestureDescription: GestureDescription?) { latch.countDown() }
        }, null)
        if (!accepted) throw VerbException("failed", "the system rejected the gesture")
        if (!latch.await(ms + 3_000, TimeUnit.MILLISECONDS) || !ok) throw VerbException("failed", "the gesture was cancelled")
    }

    fun setText(item: Snapshot.Item, text: String) {
        val node = item.node
        if (!node.refresh() || !node.isVisibleToUser) throw VerbException("not_found", "the field is no longer on screen; observe again")
        if (!node.isEditable) throw VerbException("bad_args", "the element is not editable")
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) throw VerbException("failed", "the field did not accept the text")
    }

    fun enter(item: Snapshot.Item?) {
        val node = item?.node?.takeIf { it.refresh() } ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: throw VerbException("not_found", "no focused field")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        ) return
        throw VerbException("failed", "the field has no keyboard action")
    }

    fun key(name: String) {
        val action = when (name) {
            "BACK" -> GLOBAL_ACTION_BACK
            "HOME" -> GLOBAL_ACTION_HOME
            "RECENTS" -> GLOBAL_ACTION_RECENTS
            "NOTIFICATIONS" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> throw VerbException("bad_args", "unknown key: $name")
        }
        if (!performGlobalAction(action)) throw VerbException("failed", "the system rejected $name")
    }

    /** 动作后等界面安静下来：300 ms 内没有新事件，最多等 2 s。 */
    fun settle() {
        val start = SystemClock.uptimeMillis()
        Thread.sleep(SETTLE_QUIET_MS)
        while (SystemClock.uptimeMillis() - start < SETTLE_MAX_MS && SystemClock.uptimeMillis() - lastEventAt < SETTLE_QUIET_MS) {
            Thread.sleep(50)
        }
    }

    fun currentGeneration(): Int = generation.get()
}
