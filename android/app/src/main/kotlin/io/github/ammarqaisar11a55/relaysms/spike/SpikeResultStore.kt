package io.github.ammarqaisar11a55.relaysms.spike

import android.content.Context

/**
 * Phase Zero feasibility record. Stores only non-sensitive facts about received SMS events:
 * how many arrived, when the last one arrived and how many PDU parts it had. Never the sender,
 * body or any code contained in the message.
 */
class SpikeResultStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("feasibility_spike", Context.MODE_PRIVATE)

    val eventCount: Int get() = prefs.getInt(KEY_COUNT, 0)
    val lastEventAtMillis: Long get() = prefs.getLong(KEY_LAST_AT, 0L)
    val lastPartCount: Int get() = prefs.getInt(KEY_LAST_PARTS, 0)

    fun recordEvent(partCount: Int, nowMillis: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putInt(KEY_COUNT, eventCount + 1)
            .putLong(KEY_LAST_AT, nowMillis)
            .putInt(KEY_LAST_PARTS, partCount)
            .apply()
    }

    fun reset() = prefs.edit().clear().apply()

    private companion object {
        const val KEY_COUNT = "event_count"
        const val KEY_LAST_AT = "last_event_at"
        const val KEY_LAST_PARTS = "last_part_count"
    }
}
