package com.seb.ebikecanmonitor.record

/**
 * Builds the records of a session file (JSON Lines: one JSON object per line, UTF-8).
 *
 * Every record starts with a `"type"` field:
 *  - `meta`   first line of a file: format version, start time, phone and app description
 *  - `frame`  one CAN frame
 *  - `status` CAN driver counters sent by the ESP32
 *  - `link`   BLE link quality, once per second (see LinkDiagnostics)
 *  - `event`  one-off occurrence, identified by its `"name"` field (scan, connection, disconnection...)
 *  - `end`    last line of a file closed normally (its absence means the app was killed)
 *
 * Times of the phone are in milliseconds since the Unix epoch (`phone_time_ms`), times of the ESP32
 * in microseconds since its boot (`esp_time_us`). Unknown values are written as JSON null.
 */
object JsonLines {

    /** Version of the record layout, written in the `meta` record. */
    const val VERSION = 1

    /**
     * Serializes one record. Values may be String, Number, Boolean or null.
     * Fields keep the order in which they are given.
     */
    fun record(type: String, vararg fields: Pair<String, Any?>): String {
        val sb = StringBuilder(160)
        sb.append("{\"type\":")
        appendString(sb, type)
        for ((name, value) in fields) {
            sb.append(',')
            appendString(sb, name)
            sb.append(':')
            appendValue(sb, value)
        }
        return sb.append('}').toString()
    }

    /** An `event` record stamped with the phone time [timeMs]. */
    fun event(timeMs: Long, name: String, vararg fields: Pair<String, Any?>): String =
        record("event", "phone_time_ms" to timeMs, "name" to name, *fields)

    private fun appendValue(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is Boolean, is Int, is Long -> sb.append(value.toString())
            is Double -> if (value.isFinite()) sb.append(value.toString()) else sb.append("null")
            is Float -> if (value.isFinite()) sb.append(value.toString()) else sb.append("null")
            is Number -> sb.append(value.toString())
            else -> appendString(sb, value.toString())
        }
    }

    private fun appendString(sb: StringBuilder, text: String) {
        sb.append('"')
        for (c in text) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u").append(String.format("%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }
}
