package com.amadeus.mobile.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiFeedTest {
    @Test
    fun replaysEverythingAfterTheLastSeenSequence() {
        val feed = UiFeed()
        repeat(5) { feed.append("turn.delta", "{\"n\":$it}") }
        val replay = feed.since(2)
        assertTrue(replay.complete)
        assertEquals(listOf(3L, 4L, 5L), replay.events.map { it.seq })
        assertEquals(5L, replay.latest)
    }

    @Test
    fun upToDateClientsGetAnEmptyCompleteReplay() {
        val feed = UiFeed()
        feed.append("turn.begin", "{}")
        val replay = feed.since(1)
        assertTrue(replay.complete)
        assertTrue(replay.events.isEmpty())
        // 刚启动、还没有任何事件时，从 0 开始也算完整
        assertTrue(UiFeed().since(0).complete)
    }

    @Test
    fun overwrittenOrForeignSequencesRequireAFullSync() {
        val feed = UiFeed(capacity = 3)
        repeat(10) { feed.append("turn.delta", "{}") }
        // 需要的第 2–7 条已被覆盖
        assertFalse(feed.since(1).complete)
        // 恰好接得上最旧的一条
        assertTrue(feed.since(7).complete)
        // 序号比当前还大：界面记的是上一个宿主进程的序号
        assertFalse(feed.since(42).complete)
    }
}
