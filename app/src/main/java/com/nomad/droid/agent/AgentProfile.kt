package com.nomad.droid.agent

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class AgentProfile(
    val name: String,
    val config: AgentConfig,
) {
    fun toJson(): String {
        return JSONObject()
            .put("name", name)
            .put("server_address", config.serverAddress)
            .put("node_name", config.nodeName)
            .put("datacenter", config.datacenter)
            .put("intro_token", config.introToken)
            .toString()
    }

    fun toUriString(): String {
        val server = URLEncoder.encode(config.serverAddress, StandardCharsets.UTF_8.name())
        val node = URLEncoder.encode(config.nodeName, StandardCharsets.UTF_8.name())
        val dc = URLEncoder.encode(config.datacenter, StandardCharsets.UTF_8.name())
        val token = URLEncoder.encode(config.introToken, StandardCharsets.UTF_8.name())
        val profileName = URLEncoder.encode(name, StandardCharsets.UTF_8.name())
        return "nomad-droid://import?name=$profileName&server=$server&node=$node&datacenter=$dc&token=$token"
    }

    companion object {
        fun fromJson(raw: String): AgentProfile {
            val json = JSONObject(raw)
            return AgentProfile(
                name = json.optString("name", "Imported Profile"),
                config = AgentConfig(
                    serverAddress = json.optString("server_address", json.optString("server", "")),
                    nodeName = json.optString("node_name", json.optString("node", "")),
                    datacenter = json.optString("datacenter", "android"),
                    introToken = json.optString("intro_token", json.optString("token", "")),
                ),
            )
        }

        fun fromUri(uriString: String): AgentProfile? = runCatching {
            val uri = URI(uriString)
            if (uri.scheme != "nomad-droid" || (uri.host != "import" && uri.path != "/import" && uri.path != "import")) {
                return null
            }
            val query = uri.rawQuery ?: return null
            val params = query.split("&").associate {
                val parts = it.split("=", limit = 2)
                val key = parts[0]
                val value = if (parts.size > 1) URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name()) else ""
                key to value
            }
            AgentProfile(
                name = params["name"]?.ifBlank { "Imported Profile" } ?: "Imported Profile",
                config = AgentConfig(
                    serverAddress = params["server"].orEmpty(),
                    nodeName = params["node"].orEmpty(),
                    datacenter = params["datacenter"]?.ifBlank { "android" } ?: "android",
                    introToken = params["token"].orEmpty(),
                ),
            )
        }.getOrNull()
    }
}
