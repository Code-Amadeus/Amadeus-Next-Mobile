package com.amadeus.mobile.host

import org.json.JSONObject
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/** 与控制面 control/src/frame.js 对应：u32(BE) 头长度 | JSON 头 | u32(BE) 体长度 | 体。 */
object Frame {
    const val MAX_HEAD = 1 shl 20
    const val MAX_BODY = 16 shl 20
    private val KINDS = setOf("req", "res", "evt")

    class ProtocolException(message: String) : Exception(message)

    data class Message(val head: JSONObject, val body: ByteArray = ByteArray(0)) {
        val kind: String get() = head.optString("kind")
    }

    fun write(out: OutputStream, head: JSONObject, body: ByteArray = ByteArray(0)) {
        val json = head.toString().toByteArray(Charsets.UTF_8)
        if (json.size > MAX_HEAD) throw ProtocolException("frame head too large: ${json.size}")
        if (body.size > MAX_BODY) throw ProtocolException("frame body too large: ${body.size}")
        val buffer = ByteArray(8 + json.size + body.size)
        putU32(buffer, 0, json.size)
        json.copyInto(buffer, 4)
        putU32(buffer, 4 + json.size, body.size)
        body.copyInto(buffer, 8 + json.size)
        out.write(buffer)
        out.flush()
    }

    /** 阻塞读一帧；流在帧边界结束时返回 null，帧中途结束视为协议错误。 */
    fun read(input: InputStream): Message? {
        val data = if (input is DataInputStream) input else DataInputStream(input)
        val headLen = try { data.readInt() } catch (_: EOFException) { return null }
        if (headLen < 0 || headLen > MAX_HEAD) throw ProtocolException("frame head too large: $headLen")
        try {
            val json = ByteArray(headLen).also { data.readFully(it) }
            val bodyLen = data.readInt()
            if (bodyLen < 0 || bodyLen > MAX_BODY) throw ProtocolException("frame body too large: $bodyLen")
            val body = ByteArray(bodyLen).also { data.readFully(it) }
            val head = try { JSONObject(String(json, Charsets.UTF_8)) } catch (e: Exception) {
                throw ProtocolException("frame head is not JSON: ${e.message}")
            }
            if (head.optString("kind") !in KINDS) throw ProtocolException("frame head must have kind req/res/evt")
            return Message(head, body)
        } catch (_: EOFException) {
            throw ProtocolException("stream ended inside a frame")
        }
    }

    private fun putU32(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value ushr 24).toByte()
        buffer[offset + 1] = (value ushr 16).toByte()
        buffer[offset + 2] = (value ushr 8).toByte()
        buffer[offset + 3] = value.toByte()
    }
}
