package com.amadeus.mobile.host

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class FrameTest {
    private fun encode(head: JSONObject, body: ByteArray = ByteArray(0)) =
        ByteArrayOutputStream().also { Frame.write(it, head, body) }.toByteArray()

    @Test
    fun roundTripsHeadAndBody() {
        val bytes = encode(JSONObject().put("kind", "evt").put("method", "voice.event"), byteArrayOf(0, 1, -1))
        val input = ByteArrayInputStream(bytes)
        val message = Frame.read(input)!!
        assertEquals("voice.event", message.head.getString("method"))
        assertArrayEquals(byteArrayOf(0, 1, -1), message.body)
        assertNull(Frame.read(input))
    }

    @Test
    fun decodesBytesProducedByJavaScriptEncoder() {
        // control/src/frame.js 的 encodeFrame({kind:'res',id:'1'}, Buffer.from('ok')) 的输出
        val head = """{"kind":"res","id":"1"}""".toByteArray()
        val js = byteArrayOf(0, 0, 0, head.size.toByte()) + head + byteArrayOf(0, 0, 0, 2) + "ok".toByteArray()
        val message = Frame.read(ByteArrayInputStream(js))!!
        assertEquals("1", message.head.getString("id"))
        assertEquals("ok", String(message.body))
    }

    @Test(expected = Frame.ProtocolException::class)
    fun rejectsTruncatedFrame() {
        val bytes = encode(JSONObject().put("kind", "res").put("id", "9"), ByteArray(10))
        Frame.read(ByteArrayInputStream(bytes.copyOf(bytes.size - 3)))
    }

    @Test(expected = Frame.ProtocolException::class)
    fun rejectsUnknownKind() {
        Frame.read(ByteArrayInputStream(encode(JSONObject().put("kind", "other"))))
    }
}
