package com.nomad.droid.agent

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

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

    private val statusListeners = java.util.concurrent.CopyOnWriteArraySet<(String) -> Unit>()
    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_RUNTIME_STATUS || key == KEY_DESIRED_RUNNING || key == KEY_LAST_RESULT) {
            val status = runtimeStatus
            statusListeners.forEach { it(status) }
        }
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(prefListener)
    }

    fun addStatusListener(listener: (String) -> Unit) {
        statusListeners += listener
        listener(runtimeStatus)
    }

    fun removeStatusListener(listener: (String) -> Unit) {
        statusListeners -= listener
    }

    var desiredRunning: Boolean
        get() = preferences.getBoolean(KEY_DESIRED_RUNNING, false)
        set(value) {
            preferences.edit().putBoolean(KEY_DESIRED_RUNNING, value).apply()
        }

    var runtimeStatus: String
        get() = preferences.getString(KEY_RUNTIME_STATUS, "Stopped").orEmpty()
        set(value) {
            preferences.edit().putString(KEY_RUNTIME_STATUS, value).apply()
        }

    var lastResult: String
        get() = preferences.getString(KEY_LAST_RESULT, "No operations yet.").orEmpty()
        set(value) {
            preferences.edit().putString(KEY_LAST_RESULT, value).apply()
        }

    // Profile Management
    fun listProfiles(): List<String> {
        val raw = preferences.getString(KEY_PROFILE_LIST, null) ?: return listOf("Default")
        return runCatching {
            val array = JSONArray(raw)
            val list = mutableListOf<String>()
            for (i in 0 until array.length()) {
                list.add(array.getString(i))
            }
            if (!list.contains("Default")) list.add(0, "Default")
            list.distinct()
        }.getOrDefault(listOf("Default"))
    }

    fun saveProfile(profile: AgentProfile) {
        val name = profile.name.trim().ifEmpty { "Profile" }
        preferences.edit()
            .putString("${KEY_PROFILE_PREFIX}$name", profile.toJson())
            .apply()
        val currentProfiles = listProfiles().toMutableList()
        if (!currentProfiles.contains(name)) {
            currentProfiles.add(name)
            saveProfileList(currentProfiles)
        }
    }

    fun loadProfile(name: String): AgentProfile? {
        val raw = preferences.getString("${KEY_PROFILE_PREFIX}$name", null) ?: return null
        return runCatching { AgentProfile.fromJson(raw) }.getOrNull()
    }

    fun deleteProfile(name: String) {
        if (name == "Default") return
        preferences.edit().remove("${KEY_PROFILE_PREFIX}$name").apply()
        val currentProfiles = listProfiles().toMutableList()
        currentProfiles.remove(name)
        saveProfileList(currentProfiles)
    }

    private fun saveProfileList(profiles: List<String>) {
        val array = JSONArray()
        profiles.forEach { array.put(it) }
        preferences.edit().putString(KEY_PROFILE_LIST, array.toString()).apply()
    }

    fun defaultNodeName(): String {
        val raw = "${Build.MANUFACTURER}-${Build.MODEL}".lowercase()
        return raw.replace(Regex("[^a-z0-9._-]+"), "-").trim('-').ifEmpty { "android-node" }
    }

    companion object {
        private const val PREFS = "agent-config"
        private const val KEY_SERVER = "server"
        private const val KEY_NODE = "node"
        private const val KEY_DATACENTER = "datacenter"
        private const val KEY_TOKEN = "intro-token"
        private const val KEY_DESIRED_RUNNING = "desired-running"
        private const val KEY_RUNTIME_STATUS = "runtime-status"
        private const val KEY_LAST_RESULT = "last-result"
        private const val KEY_PROFILE_LIST = "profile-list"
        private const val KEY_PROFILE_PREFIX = "profile:"
        const val DEFAULT_SERVER = "127.0.0.1:4647"
        const val DEFAULT_DATACENTER = "android"
    }
}
