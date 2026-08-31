package com.nomad.droid.agent

import android.content.Context
import android.os.Build

class AgentConfigStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val secureValues by lazy { SecureValueStore() }

    fun load(): AgentConfig = AgentConfig(
        serverAddress = preferences.getString(KEY_SERVER, DEFAULT_SERVER).orEmpty(),
        nodeName = preferences.getString(KEY_NODE, defaultNodeName()).orEmpty(),
        datacenter = preferences.getString(KEY_DATACENTER, DEFAULT_DATACENTER).orEmpty(),
        introToken = secureValues.decrypt(preferences.getString(KEY_TOKEN, "").orEmpty()),
    )

    fun save(config: AgentConfig) {
        preferences.edit()
            .putString(KEY_SERVER, config.serverAddress.trim())
            .putString(KEY_NODE, config.nodeName.trim())
            .putString(KEY_DATACENTER, config.datacenter.trim())
            .putString(KEY_TOKEN, secureValues.encrypt(config.introToken))
            .apply()
    }

    var desiredRunning: Boolean
        get() = preferences.getBoolean(KEY_DESIRED_RUNNING, false)
        set(value) = preferences.edit().putBoolean(KEY_DESIRED_RUNNING, value).apply()

    var runtimeStatus: String
        get() = preferences.getString(KEY_RUNTIME_STATUS, "Stopped").orEmpty()
        set(value) = preferences.edit().putString(KEY_RUNTIME_STATUS, value).apply()

    var lastResult: String
        get() = preferences.getString(KEY_LAST_RESULT, "No operations yet.").orEmpty()
        set(value) = preferences.edit().putString(KEY_LAST_RESULT, value).apply()

    private fun defaultNodeName(): String {
        val raw = "${Build.MANUFACTURER}-${Build.MODEL}".lowercase()
        return raw.replace(Regex("[^a-z0-9._-]+"), "-").trim('-').ifEmpty { "android-node" }
    }

    private companion object {
        const val PREFS = "agent-config"
        const val KEY_SERVER = "server"
        const val KEY_NODE = "node"
        const val KEY_DATACENTER = "datacenter"
        const val KEY_TOKEN = "intro-token"
        const val KEY_DESIRED_RUNNING = "desired-running"
        const val KEY_RUNTIME_STATUS = "runtime-status"
        const val KEY_LAST_RESULT = "last-result"
        const val DEFAULT_SERVER = "127.0.0.1:4647"
        const val DEFAULT_DATACENTER = "android"
    }
}
