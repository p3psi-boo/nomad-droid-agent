package com.nomad.droid.agent

import org.json.JSONObject
import java.net.URI

data class AgentConfig(
    val serverAddress: String,
    val nodeName: String,
    val datacenter: String,
    val introToken: String,
) {
    fun validate(): Result<Unit> = runCatching {
        val server = serverAddress.trim()
        require(server.isNotEmpty()) { "Nomad server address is required" }

        val uri = URI("nomad://$server")
        require(
            !uri.host.isNullOrBlank() &&
                uri.port in 1..65535 &&
                uri.rawUserInfo == null &&
                uri.rawPath.isNullOrEmpty() &&
                uri.rawQuery == null &&
                uri.rawFragment == null,
        ) {
            "Server must use host:port or [IPv6]:port format"
        }
        require(nodeName.matches(NODE_NAME)) {
            "Node name may contain letters, digits, dots, underscores and hyphens"
        }
        require(datacenter.matches(DATACENTER)) {
            "Datacenter may contain letters, digits, underscores and hyphens"
        }
    }

    fun toNativeJson(stateDir: String, allocDir: String, bridgeSocket: String): String =
        JSONObject()
            .put("server_address", serverAddress.trim())
            .put("node_name", nodeName.trim())
            .put("datacenter", datacenter.trim())
            .put("intro_token", introToken)
            .put("state_dir", stateDir)
            .put("alloc_dir", allocDir)
            .put("bridge_socket", bridgeSocket)
            .toString()

    companion object {
        private val NODE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
        private val DATACENTER = Regex("[A-Za-z0-9][A-Za-z0-9_-]*")
    }
}
