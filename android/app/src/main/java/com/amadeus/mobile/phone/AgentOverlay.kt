package com.amadeus.mobile.phone

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.cos

/**
 * 她在别的应用里操作时的提示：四周呼吸的柔光、跟着动作落点移动的光标（不拦截触摸），
 * 以及顶部胶囊（状态、「接管」「终止任务」，可拖动）。
 * 用无障碍服务的 overlay 窗口类型，不需要悬浮窗权限。所有方法可在任意线程调用。
 */
class AgentOverlay(
    private val context: Context,
    private val onTakeOver: () -> Unit,
    private val onStop: () -> Unit,
) {
    companion object {
        private val ACCENT = Color.parseColor("#62D3C6")
        private const val MOVE_MS = 280L
        private const val CYCLE_MS = 2400L
    }

    private val main = Handler(Looper.getMainLooper())
    private val windows = context.getSystemService(WindowManager::class.java)
    private var stage: Stage? = null
    private var pill: LinearLayout? = null
    private var title: TextView? = null
    private var spinner: Spinner? = null
    private var ticker: ValueAnimator? = null
    @Volatile private var shown = false

    private fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics)

    fun show(text: String) = main.post {
        if (stage == null) attach()
        title?.text = text
        stage?.label = text
        stage?.visibility = View.VISIBLE
        pill?.visibility = View.VISIBLE
        if (ticker?.isStarted != true) ticker?.start()
        shown = true
    }

    fun hide() = main.post {
        stage?.visibility = View.GONE
        stage?.resetCursor()
        pill?.visibility = View.GONE
        ticker?.cancel()
        shown = false
    }

    /** 光标移到动作落点。在动词线程上调用：等移动走完再真正动手，主人能看清她要点哪里。 */
    fun pointAt(x: Float, y: Float) {
        if (!shown) return
        main.post { stage?.moveTo(x, y, MOVE_MS) }
        Thread.sleep(MOVE_MS)
    }

    /** 滑动时光标跟着手势一起走。 */
    fun dragTo(x: Float, y: Float, ms: Long) {
        if (shown) main.post { stage?.moveTo(x, y, ms) }
    }

    fun tapped() {
        if (shown) main.post { stage?.ripple() }
    }

    /**
     * 浮层全程连续显示：光边与光标不拦截触摸，手势直接穿透。只有坐标手势的起点正好落在胶囊上时，
     * 才把胶囊单独藏一下，免得误触按钮或挡住下面的控件。
     */
    fun <T> clearOf(x: Float, y: Float, block: () -> T): T {
        val bar = pill
        if (!shown || bar == null) return block()
        val location = IntArray(2)
        val hit = java.util.concurrent.CompletableFuture<Boolean>()
        main.post {
            bar.getLocationOnScreen(location)
            val inside = x >= location[0] && x <= location[0] + bar.width && y >= location[1] && y <= location[1] + bar.height
            if (inside) bar.visibility = View.INVISIBLE
            hit.complete(inside)
        }
        val inside = runCatching { hit.get(500, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!inside) return block()
        Thread.sleep(50)
        return try { block() } finally {
            main.post { if (shown) bar.visibility = View.VISIBLE }
        }
    }

    fun destroy() = main.post {
        ticker?.cancel()
        stage?.let { runCatching { windows.removeView(it) } }
        pill?.let { runCatching { windows.removeView(it) } }
        stage = null; pill = null; title = null; spinner = null
    }

    private fun attach() {
        val canvas = Stage(context, ::dp)
        windows.addView(canvas, WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES })
        stage = canvas

        val spin = Spinner(context, dp(2f)).apply {
            layoutParams = LinearLayout.LayoutParams(dp(18f).toInt(), dp(18f).toInt()).apply { marginEnd = dp(10f).toInt() }
        }
        spinner = spin
        val heading = TextView(context).apply {
            setTextColor(Color.parseColor("#E8F1EF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1
        }
        title = heading
        val sub = TextView(context).apply {
            text = "Amadeus 正在控制"
            setTextColor(ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(12f).toInt(), 0)
            addView(heading)
            addView(sub)
        }
        val takeOver = button("接管", Color.parseColor("#E8F1EF"), Color.parseColor("#26FFFFFF")) { onTakeOver() }
        val stop = button("终止任务", Color.parseColor("#FF8F8F"), Color.parseColor("#33FF6B6B")) { onStop() }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(6f).toInt()
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // 让开状态栏，下拉通知栏的手势起点不会落在胶囊上
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(44f).toInt()
        }
        val bar = DragBar(context, params) { windows.updateViewLayout(it, params) }.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f).toInt(), dp(8f).toInt(), dp(8f).toInt(), dp(8f).toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(16f)
                setColor(Color.parseColor("#EB0F1D20"))
                setStroke(dp(1f).toInt(), Color.parseColor("#4062D3C6"))
            }
            elevation = dp(8f)
            addView(spin)
            addView(texts)
            addView(takeOver)
            addView(stop)
        }
        windows.addView(bar, params)
        pill = bar

        ticker = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CYCLE_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                canvas.breath = ((1 - cos(2 * PI * t)) / 2).toFloat()
                canvas.spin = t * 360f
                canvas.invalidate()
                spin.angle = t * 360f * 2
                spin.invalidate()
            }
        }
    }

    private fun button(text: String, color: Int, fill: Int, onClick: () -> Unit) = TextView(context).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(dp(12f).toInt(), dp(7f).toInt(), dp(12f).toInt(), dp(7f).toInt())
        background = GradientDrawable().apply { cornerRadius = dp(10f); setColor(fill) }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    /**
     * 可拖动的胶囊：移动超过触摸阈值才算拖动并截下手势，点按照常交给「接管」「终止任务」。
     * 位置记在窗口参数上，隐藏再显示时停在主人拖到的地方。
     */
    private class DragBar(
        context: Context,
        private val params: WindowManager.LayoutParams,
        private val relayout: (View) -> Unit,
    ) : LinearLayout(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> begin(event)
                MotionEvent.ACTION_MOVE -> if (!dragging && moved(event)) dragging = true
            }
            return dragging
        }

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> begin(event)
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && moved(event)) dragging = true
                    if (dragging) follow(event)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
            }
            return true
        }

        private fun begin(event: MotionEvent) {
            downX = event.rawX; downY = event.rawY
            startX = params.x; startY = params.y
            dragging = false
        }

        private fun moved(event: MotionEvent) =
            Math.abs(event.rawX - downX) > slop || Math.abs(event.rawY - downY) > slop

        private fun follow(event: MotionEvent) {
            val screen = resources.displayMetrics
            // 水平居中对齐时 x 是相对屏幕中线的偏移
            val reachX = ((screen.widthPixels - width) / 2).coerceAtLeast(0)
            params.x = (startX + (event.rawX - downX).toInt()).coerceIn(-reachX, reachX)
            params.y = (startY + (event.rawY - downY).toInt()).coerceIn(0, (screen.heightPixels - height).coerceAtLeast(0))
            relayout(this)
        }
    }

    /** 胶囊里的转圈：一段青色圆弧。 */
    private class Spinner(context: Context, stroke: Float) : View(context) {
        var angle = 0f
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke; color = Color.parseColor("#3362D3C6")
        }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND; color = ACCENT
        }
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val inset = arc.strokeWidth
            rect.set(inset, inset, width - inset, height - inset)
            canvas.drawOval(rect, track)
            canvas.drawArc(rect, angle, 100f, false, arc)
        }
    }

    /** 全屏画布：四周柔光与操作光标。坐标一律是屏幕坐标，绘制时换算到本窗口。 */
    private class Stage(context: Context, private val dp: (Float) -> Float) : View(context) {
        var label = ""
        var breath = 0f
        var spin = 0f
        private var cursorX = 0f
        private var cursorY = 0f
        private var cursorShown = false
        private var rippleAt = 0L
        private var move: ValueAnimator? = null
        private val origin = IntArray(2)

        private val band = dp(40f)
        private val edges = Array(4) { Paint(Paint.ANTI_ALIAS_FLAG) }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.5f); color = ACCENT }
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(2f); color = ACCENT
            pathEffect = DashPathEffect(floatArrayOf(dp(2f), dp(4f)), 0f)
        }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3062D3C6") }
        private val rippleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f); color = ACCENT }
        private val arrowFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val arrowEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(1.2f); strokeJoin = Paint.Join.ROUND; color = Color.parseColor("#0B1A1C")
        }
        private val bubble = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E60F1D20") }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E8F1EF")
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, context.resources.displayMetrics)
        }
        private val arrow = Path().apply {
            // 经典箭头光标，尖端在原点
            moveTo(0f, 0f); lineTo(0f, dp(18f)); lineTo(dp(4.5f), dp(13.5f)); lineTo(dp(7.5f), dp(20f))
            lineTo(dp(10f), dp(19f)); lineTo(dp(7f), dp(12.5f)); lineTo(dp(13f), dp(12.5f)); close()
        }
        private val rect = RectF()

        fun moveTo(x: Float, y: Float, ms: Long) {
            move?.cancel()
            if (!cursorShown) {
                cursorX = x; cursorY = y; cursorShown = true
                invalidate()
                return
            }
            val fromX = cursorX
            val fromY = cursorY
            move = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = ms
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    val t = it.animatedFraction
                    cursorX = fromX + (x - fromX) * t
                    cursorY = fromY + (y - fromY) * t
                    invalidate()
                }
                start()
            }
        }

        fun ripple() {
            rippleAt = SystemClock.uptimeMillis()
            invalidate()
        }

        fun resetCursor() {
            move?.cancel()
            cursorShown = false
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val solid = Color.parseColor("#7062D3C6")
            val clear = Color.TRANSPARENT
            edges[0].shader = LinearGradient(0f, 0f, 0f, band, solid, clear, Shader.TileMode.CLAMP)
            edges[1].shader = LinearGradient(0f, h.toFloat(), 0f, h - band, solid, clear, Shader.TileMode.CLAMP)
            edges[2].shader = LinearGradient(0f, 0f, band, 0f, solid, clear, Shader.TileMode.CLAMP)
            edges[3].shader = LinearGradient(w.toFloat(), 0f, w - band, 0f, solid, clear, Shader.TileMode.CLAMP)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val alpha = (110 + 145 * breath).toInt()
            edges.forEach { it.alpha = alpha }
            canvas.drawRect(0f, 0f, w, band, edges[0])
            canvas.drawRect(0f, h - band, w, h, edges[1])
            canvas.drawRect(0f, 0f, band, h, edges[2])
            canvas.drawRect(w - band, 0f, w, h, edges[3])
            line.alpha = alpha
            val inset = line.strokeWidth / 2
            rect.set(inset, inset, w - inset, h - inset)
            canvas.drawRoundRect(rect, dp(28f), dp(28f), line)

            if (!cursorShown) return
            getLocationOnScreen(origin)
            val cx = cursorX - origin[0]
            val cy = cursorY - origin[1]

            canvas.drawCircle(cx, cy, dp(20f), glow)
            canvas.save()
            canvas.rotate(spin, cx, cy)
            canvas.drawCircle(cx, cy, dp(20f), halo)
            canvas.restore()

            val age = SystemClock.uptimeMillis() - rippleAt
            if (age < 500) {
                val t = age / 500f
                rippleRing.alpha = ((1 - t) * 220).toInt()
                canvas.drawCircle(cx, cy, dp(8f) + dp(30f) * t, rippleRing)
                postInvalidateOnAnimation()
            }

            canvas.save()
            canvas.translate(cx, cy)
            canvas.drawPath(arrow, arrowFill)
            canvas.drawPath(arrow, arrowEdge)
            canvas.restore()

            if (label.isEmpty()) return
            val padX = dp(8f)
            val textWidth = text.measureText(label)
            val boxW = textWidth + padX * 2
            val boxH = dp(24f)
            val left = (cx + dp(10f)).coerceAtMost(w - boxW - dp(8f)).coerceAtLeast(dp(8f))
            val top = (cy + dp(26f)).let { if (it + boxH > h - dp(8f)) cy - dp(26f) - boxH else it }
            rect.set(left, top, left + boxW, top + boxH)
            canvas.drawRoundRect(rect, dp(12f), dp(12f), bubble)
            val baseline = top + boxH / 2 - (text.descent() + text.ascent()) / 2
            canvas.drawText(label, left + padX, baseline, text)
        }
    }
}
