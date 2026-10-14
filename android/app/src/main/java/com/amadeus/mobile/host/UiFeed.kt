package com.amadeus.mobile.host

/**
 * 发往界面的事件日志：每条带递增序号，最近若干条留在环形缓冲里。
 * 界面在后台、页面重载、Activity 被回收重建后，按最后处理的序号重放；
 * 缓冲已覆盖掉需要的部分时返回 complete = false，界面改做全量同步。
 */
class UiFeed(private val capacity: Int = 500) {
    data class Event(val seq: Long, val method: String, val params: String)
    data class Replay(val events: List<Event>, val latest: Long, val complete: Boolean)

    private val buffer = ArrayDeque<Event>()
    private var seq = 0L

    @Synchronized
    fun append(method: String, params: String): Event {
        val event = Event(++seq, method, params)
        buffer.addLast(event)
        while (buffer.size > capacity) buffer.removeFirst()
        return event
    }

    /** after 之后的事件；after 比当前序号还大说明界面记的是上一个宿主进程的序号，同样要全量同步。 */
    @Synchronized
    fun since(after: Long): Replay {
        val oldest = buffer.firstOrNull()?.seq ?: (seq + 1)
        val complete = after in (oldest - 1)..seq
        val events = if (complete) buffer.filter { it.seq > after } else emptyList()
        return Replay(events, seq, complete)
    }
}
