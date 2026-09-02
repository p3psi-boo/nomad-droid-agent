package com.nomad.droid.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppLoggerTest {

    @Before
    fun setup() {
        AppLogger.clear()
    }

    @Test
    fun logsEntriesAndMaintainsHistory() {
        AppLogger.i("TestTag", "Hello info")
        AppLogger.e("TestTag", "Hello error")

        val entries = AppLogger.getAll()
        assertEquals(2, entries.size)
        assertEquals(AppLogger.Level.INFO, entries[0].level)
        assertEquals("Hello info", entries[0].message)
        assertEquals(AppLogger.Level.ERROR, entries[1].level)
        assertEquals("Hello error", entries[1].message)
    }

    @Test
    fun translatesConnectionErrorsToFriendlySuggestions() {
        val raw = "connect: connection refused"
        val translated = AppLogger.translateError(raw)
        assertTrue(translated.contains("Connection refused"))
        assertTrue(translated.contains("4647"))
    }

    @Test
    fun translatesTermuxErrors() {
        val raw = "missing allow-external-apps"
        val translated = AppLogger.translateError(raw)
        assertTrue(translated.contains("Termux permission missing"))
        assertTrue(translated.contains("allow-external-apps=true"))
    }

    @Test
    fun translatesTokenErrors() {
        val raw = "RPC unauthorized: token rejected"
        val translated = AppLogger.translateError(raw)
        assertTrue(translated.contains("Authentication failed"))
    }

    @Test
    fun clearsHistory() {
        AppLogger.i("Tag", "Msg")
        AppLogger.clear()
        assertTrue(AppLogger.getAll().isEmpty())
    }
}
