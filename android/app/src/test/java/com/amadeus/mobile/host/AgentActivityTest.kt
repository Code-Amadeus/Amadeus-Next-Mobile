package com.amadeus.mobile.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentActivityTest {
    @Test
    fun returnsToTheAppAfterWorkingElsewhere() {
        val agent = AgentActivity()
        agent.turnBegin(appInForeground = true)
        assertTrue(agent.turnEnd(foregroundIsApp = false))
    }

    @Test
    fun staysPutWhenAlreadyHomeOrWhenTheUserWasElsewhere() {
        val agent = AgentActivity()
        agent.turnBegin(appInForeground = true)
        assertFalse(agent.turnEnd(foregroundIsApp = true))
        // 通知触发等：主人本来就不在 Amadeus 里
        agent.turnBegin(appInForeground = false)
        assertFalse(agent.turnEnd(foregroundIsApp = false))
    }

    @Test
    fun takeoverLeavesThePhoneWithTheUser() {
        val agent = AgentActivity()
        agent.turnBegin(appInForeground = true)
        assertTrue(agent.takeOver())
        assertFalse(agent.turnEnd(foregroundIsApp = false))

        // 没有进行中的回合时接管什么也不做
        assertFalse(agent.takeOver())
    }

    @Test
    fun overlayOnlyShowsWhileWorkingInAnotherApp() {
        val agent = AgentActivity()
        assertFalse(agent.overlay(foregroundIsApp = false).visible)
        agent.turnBegin(appInForeground = true)
        assertFalse(agent.overlay(foregroundIsApp = true).visible)
        agent.toolStart("click")
        assertEquals("红莉栖正在点击", agent.overlay(foregroundIsApp = false).text)
        agent.toolEnd()
        assertEquals("红莉栖正在思考", agent.overlay(foregroundIsApp = false).text)
        agent.takeOver()
        assertFalse(agent.overlay(foregroundIsApp = false).visible)
    }
}
