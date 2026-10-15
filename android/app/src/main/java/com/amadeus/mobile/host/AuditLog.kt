package com.amadeus.mobile.host

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 每个动词与 Guard 判决的记录，追加写在宿主私有目录。
 * 控制面与宿主同 uid，理论上能改这个文件；它的作用是事后可查，不是防篡改。
 */
class AuditLog(context: Context) {
    private val file = File(context.filesDir, "audit/audit.jsonl").apply { parentFile!!.mkdirs() }
    private val lock = Any()

    fun append(entry: JSONObject) {
        synchronized(lock) {
            if (file.length() > LIMIT) file.renameTo(File(file.path + ".1"))
            file.appendText(entry.toString() + "\n")
        }
    }

    companion object {
        private const val LIMIT = 1L shl 20

        /** 写进审计的参数摘要：输入的文字只记长度。 */
        fun summarize(verb: String, args: JSONObject): JSONObject {
            val copy = JSONObject(args.toString())
            if (verb == "set_value" && copy.has("text")) copy.put("text", "<${copy.optString("text").length} chars>")
            if (verb == "remind_set" && copy.has("message")) copy.put("message", "<${copy.optString("message").length} chars>")
            return copy
        }
    }
}
