package com.nomad.droid.log

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet

object AppLogger {
    enum class Level {
        DEBUG, INFO, WARN, ERROR, SUCCESS
    }

    data class Entry(
        val id: Long,
        val timestamp: Long,
        val level: Level,
        val tag: String,
        val message: String,
        val details: String? = null,
    ) {
        val formattedTime: String
            get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

        fun toDisplayText(): String {
            val prefix = when (level) {
                Level.DEBUG -> "[DEBUG]"
                Level.INFO -> "[INFO]"
                Level.WARN -> "[WARN]"
                Level.ERROR -> "[ERROR]"
                Level.SUCCESS -> "[OK]"
            }
            val base = "$formattedTime $prefix [$tag] $message"
            return if (details.isNullOrBlank()) base else "$base\n$details"
        }
    }

    private const val MAX_ENTRIES = 300
    private var nextId = 1L
    private val entries = CopyOnWriteArrayList<Entry>()
    private val listeners = CopyOnWriteArraySet<(List<Entry>) -> Unit>()

    fun addListener(listener: (List<Entry>) -> Unit) {
        listeners += listener
        listener(getAll())
    }

    fun removeListener(listener: (List<Entry>) -> Unit) {
        listeners -= listener
    }

    @Synchronized
    fun log(level: Level, tag: String, message: String, details: String? = null) {
        val entry = Entry(
            id = nextId++,
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            details = details,
        )
        entries.add(entry)
        while (entries.size > MAX_ENTRIES) {
            entries.removeAt(0)
        }
        val snapshot = getAll()
        listeners.forEach { it(snapshot) }
    }

    fun d(tag: String, message: String, details: String? = null) = log(Level.DEBUG, tag, message, details)
    fun i(tag: String, message: String, details: String? = null) = log(Level.INFO, tag, message, details)
    fun w(tag: String, message: String, details: String? = null) = log(Level.WARN, tag, message, details)
    fun e(tag: String, message: String, details: String? = null) = log(Level.ERROR, tag, message, details)
    fun ok(tag: String, message: String, details: String? = null) = log(Level.SUCCESS, tag, message, details)

    fun getAll(): List<Entry> = entries.toList()

    @Synchronized
    fun clear() {
        entries.clear()
        val snapshot = emptyList<Entry>()
        listeners.forEach { it(snapshot) }
    }

    fun exportText(): String {
        return entries.joinToString("\n") { it.toDisplayText() }
    }

    /**
     * Translates raw technical / Go / network errors into user-friendly diagnosis tips.
     */
    fun translateError(raw: String?): String {
        if (raw.isNullOrBlank()) return "No details available."
        val lower = raw.lowercase()

        return when {
            lower.contains("connection refused") || lower.contains("econnrefused") ->
                "⚠️ Connection refused: The Nomad Server RPC port (4647) is unreachable. Please verify that the server IP is correct, Nomad Server is running with client/server RPC enabled, and firewall/security groups allow TCP 4647."

            lower.contains("no route to host") || lower.contains("network is unreachable") || lower.contains("etimedout") || lower.contains("i/o timeout") ->
                "⚠️ Network timeout: Cannot reach Nomad Server. Verify your phone's Wi-Fi / VPN connection and ensure the server IP is routable from this device."

            lower.contains("unauthorized") || lower.contains("token") || (lower.contains("permission denied") && lower.contains("rpc")) ->
                "⚠️ Authentication failed: Client Introduction Token is invalid or rejected by the Nomad cluster. Please verify the intro token in settings."

            lower.contains("allow-external-apps") ->
                "⚠️ Termux permission missing: Please edit ~/.termux/termux.properties in Termux, add 'allow-external-apps=true', and run 'termux-reload-settings'."

            lower.contains("shizuku") && (lower.contains("dead") || lower.contains("unavailable") || lower.contains("not running")) ->
                "⚠️ Shizuku service is not running: Open the Shizuku app and start the service via Wireless Debugging, ADB, or Root."

            lower.contains("su executable is unavailable") || lower.contains("su: not found") ->
                "⚠️ Root access unavailable: No 'su' binary found on device. If this device is not rooted, use Shizuku or Termux driver instead."

            else -> raw
        }
    }
}
