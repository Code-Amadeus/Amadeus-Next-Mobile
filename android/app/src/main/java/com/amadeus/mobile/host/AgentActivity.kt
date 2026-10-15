package com.amadeus.mobile.host

/**
 * 她这一轮在做什么，以及结束后要不要把 Amadeus 切回前台。纯逻辑，便于测试；
 * 浮层的显示与切回动作由调用方根据这里的判定执行。
 */
class AgentActivity {
    data class Overlay(val visible: Boolean, val text: String)

    var running = false
        private set
    private var tool: String? = null
    /** 这一轮开始时主人正在 Amadeus 里——只有这种情况结束后才切回 */
    private var startedInApp = false
    /** 主人点了接管，手机交还给主人 */
    var takenOver = false
        private set

    fun turnBegin(appInForeground: Boolean) {
        running = true
        tool = null
        startedInApp = appInForeground
        takenOver = false
    }

    fun toolStart(name: String) { tool = name }
    fun toolEnd() { tool = null }

    /** 主人接管：返回 true 表示需要中止这一轮 */
    fun takeOver(): Boolean {
        if (!running) return false
        takenOver = true
        return true
    }

    /** 返回 true 表示应把 Amadeus 切回前台 */
    fun turnEnd(foregroundIsApp: Boolean): Boolean {
        val back = running && startedInApp && !takenOver && !foregroundIsApp
        running = false
        tool = null
        return back
    }

    /** 只有她在别的应用里干活时才显示浮层；在 Amadeus 里有自己的状态显示 */
    fun overlay(foregroundIsApp: Boolean): Overlay {
        if (!running || takenOver || foregroundIsApp) return Overlay(false, "")
        val doing = tool?.let { VERB_NAMES[it] } ?: "思考"
        return Overlay(true, "红莉栖正在$doing")
    }

    companion object {
        val VERB_NAMES = mapOf(
            "observe" to "看屏幕", "screenshot" to "截图", "click" to "点击", "swipe" to "滑动", "set_value" to "输入文字",
            "enter" to "确认输入", "key" to "按键", "launch_app" to "打开应用", "list_apps" to "查找应用", "wait" to "等待",
            "camera_snap" to "拍照", "camera_clip" to "录像", "remind_set" to "设提醒",
        )
    }
}
