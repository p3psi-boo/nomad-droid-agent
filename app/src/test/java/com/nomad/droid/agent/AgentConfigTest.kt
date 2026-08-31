package com.nomad.droid.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConfigTest {
    @Test
    fun acceptsHostnameAndPort() {
        val config = AgentConfig("nomad.example:4647", "pixel-01", "android", "")
        assertTrue(config.validate().isSuccess)
    }

    @Test
    fun rejectsAddressWithoutPort() {
        val config = AgentConfig("nomad.example", "pixel-01", "android", "")
        assertTrue(config.validate().isFailure)
    }

    @Test
    fun rejectsAddressWithUrlPath() {
        val config = AgentConfig("nomad.example:4647/v1", "pixel-01", "android", "")
        assertTrue(config.validate().isFailure)
    }

    @Test
    fun acceptsBracketedIpv6() {
        val config = AgentConfig("[2001:db8::1]:4647", "pixel-01", "android", "")
        assertTrue(config.validate().isSuccess)
    }
}
