package com.amadeus.mobile.phone

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.amadeus.mobile.host.Kernel
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.UUID

/** 提醒：AlarmManager 到点唤醒，交给控制面以"主动开口"处理。登记持久化，重启后补登记。 */
object Reminders {
    private const val PREFS = "reminders"
    private const val KEY = "items"
    const val EXTRA_ID = "id"

    data class Item(val id: String, val at: Long, val message: String)

    private fun load(context: Context): MutableList<Item> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]")
        val array = JSONArray(raw)
        return MutableList(array.length()) { i ->
            val o = array.getJSONObject(i)
            Item(o.getString("id"), o.getLong("at"), o.getString("message"))
        }
    }

    private fun save(context: Context, items: List<Item>) {
        val array = JSONArray()
        for (item in items) array.put(JSONObject().put("id", item.id).put("at", item.at).put("message", item.message))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }

    @Synchronized
    fun set(context: Context, args: JSONObject): JSONObject {
        val message = args.optString("message").trim()
        if (message.isEmpty()) throw VerbException("bad_args", "message is required")
        val at = when {
            args.has("inMinutes") -> System.currentTimeMillis() + (args.getDouble("inMinutes") * 60_000).toLong()
            args.has("at") -> try {
                LocalDateTime.parse(args.getString("at")).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                throw VerbException("bad_args", "at must look like 2026-10-15T08:00")
            }
            else -> throw VerbException("bad_args", "give inMinutes or at")
        }
        if (at <= System.currentTimeMillis()) throw VerbException("bad_args", "the time is already past")
        val item = Item(UUID.randomUUID().toString().take(8), at, message)
        save(context, load(context).apply { add(item) })
        schedule(context, item)
        return JSONObject().put("id", item.id).put("at", Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDateTime().toString())
            .put("exact", exactAllowed(context))
    }

    @Synchronized
    fun list(context: Context): JSONObject {
        val zone = ZoneId.systemDefault()
        val text = load(context).sortedBy { it.at }.joinToString("\n") {
            "${it.id}  ${Instant.ofEpochMilli(it.at).atZone(zone).toLocalDateTime()}  ${it.message}"
        }
        return JSONObject().put("text", text.ifEmpty { "no pending reminders" })
    }

    @Synchronized
    fun cancel(context: Context, id: String): JSONObject {
        val items = load(context)
        val item = items.firstOrNull { it.id == id } ?: throw VerbException("not_found", "no reminder with id $id")
        items.remove(item)
        save(context, items)
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, item.id))
        return JSONObject().put("cancelled", id)
    }

    @Synchronized
    fun take(context: Context, id: String): Item? {
        val items = load(context)
        val item = items.firstOrNull { it.id == id } ?: return null
        items.remove(item)
        save(context, items)
        return item
    }

    fun rescheduleAll(context: Context) {
        for (item in load(context)) schedule(context, item)
    }

    fun exactAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun schedule(context: Context, item: Item) {
        val manager = context.getSystemService(AlarmManager::class.java)
        // 过期未触发的（如关机期间）立即补发
        val at = maxOf(item.at, System.currentTimeMillis() + 1_000)
        if (exactAllowed(context)) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context, item.id))
        else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context, item.id))
    }

    private fun pending(context: Context, id: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, id.hashCode(),
            Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(Reminders.EXTRA_ID) ?: return
        val item = Reminders.take(context, id) ?: return
        Kernel.proactive(context, "reminder", item.message, "reminder:${item.id}")
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Reminders.rescheduleAll(context)
        }
    }
}
