package com.nomad.droid.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AgentProfileTest {

    @Test
    fun roundTripsJson() {
        val config = AgentConfig("10.0.0.10:4647", "pixel-7", "android", "secret-token-123")
        val profile = AgentProfile("HomeLab", config)

        val json = profile.toJson()
        val restored = AgentProfile.fromJson(json)

        assertEquals("HomeLab", restored.name)
        assertEquals("10.0.0.10:4647", restored.config.serverAddress)
        assertEquals("pixel-7", restored.config.nodeName)
        assertEquals("android", restored.config.datacenter)
        assertEquals("secret-token-123", restored.config.introToken)
    }

    @Test
    fun roundTripsUri() {
        val config = AgentConfig("192.168.1.50:4647", "galaxy-s24", "edge-dc", "token-xyz")
        val profile = AgentProfile("Edge Server", config)

        val uriString = profile.toUriString()
        val restored = AgentProfile.fromUri(uriString)

        assertNotNull(restored)
        assertEquals("Edge Server", restored?.name)
        assertEquals("192.168.1.50:4647", restored?.config?.serverAddress)
        assertEquals("galaxy-s24", restored?.config?.nodeName)
        assertEquals("edge-dc", restored?.config?.datacenter)
        assertEquals("token-xyz", restored?.config?.introToken)
    }
}
