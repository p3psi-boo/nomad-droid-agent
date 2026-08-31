package com.nomad.droid.termux

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TermuxCommandSpecTest {
    @Test
    fun normalizesOnlyDocumentedTermuxPrefixes() {
        assertEquals("/data/data/com.termux/files/home/job", TermuxCommandSpec.normalizePath("~/job"))
        assertEquals("/data/data/com.termux/files/usr/bin/bash", TermuxCommandSpec.normalizePath("\$PREFIX/bin/bash"))
        assertEquals("python", TermuxCommandSpec.normalizePath("python"))
    }

    @Test
    fun keepsUserValuesOutOfShellProgram() {
        val hostile = "'; touch /data/local/tmp/escaped; '"
        val args = TermuxCommandSpec.startArguments(
            "/home/task.pid",
            hostile,
            listOf("$(id)", "a b"),
            mapOf("SAFE_NAME" to "$(getprop)"),
        )

        assertFalse(TermuxCommandSpec.START_SCRIPT.contains(hostile))
        assertArrayEquals(
            arrayOf(
                "-c",
                TermuxCommandSpec.START_SCRIPT,
                "nomad-droid",
                "/home/task.pid",
                "1",
                "SAFE_NAME=$(getprop)",
                hostile,
                "$(id)",
                "a b",
            ),
            args,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidEnvironmentNames() {
        TermuxCommandSpec.startArguments("/home/task.pid", "true", emptyList(), mapOf("BAD-NAME" to "x"))
    }
}
