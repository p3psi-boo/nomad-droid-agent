package com.nomad.droid.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RootCommandSpecTest {
    @Test
    fun quotesEveryRootCommandArgument() {
        val program = RootCommandSpec.rootProgram(
            listOf(
                "/system/bin/am",
                "force-stop",
                "com.example.workload; echo unexpected",
                "value'with-quote",
            ),
        )

        assertTrue(
            program.endsWith(
                "exec '/system/bin/am' 'force-stop' " +
                    "'com.example.workload; echo unexpected' 'value'\"'\"'with-quote'",
            ),
        )
        assertTrue(program.contains(RootCommandSpec.ACCESS_MARKER))
    }

    @Test
    fun recognizesOnlyOutputFromAConfirmedRootShell() {
        val confirmed = RootCommandSpec.parseOutput(
            "${RootCommandSpec.ACCESS_MARKER}\ncommand output",
        )
        val denied = RootCommandSpec.parseOutput("permission denied")

        assertTrue(confirmed.accessConfirmed)
        assertEquals("command output", confirmed.commandOutput)
        assertEquals(false, denied.accessConfirmed)
        assertEquals("permission denied", denied.commandOutput)
    }

    @Test
    fun validatesStructuredRootInputs() {
        assertEquals(
            "com.example.workload",
            RootCommandSpec.requirePackageName("com.example.workload"),
        )
        assertEquals(
            ".WorkService",
            RootCommandSpec.requireComponentName(".WorkService"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            RootCommandSpec.requirePackageName("com.example;id")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RootCommandSpec.requireComponentName(".WorkService --user 0")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RootCommandSpec.requireSha256("not-a-sha256")
        }
    }

    @Test
    fun confinesApkToAllocationDirectory() {
        val root = createTempDir(prefix = "root-command-")
        try {
            val allocationDirectory = File(root, "alloc").apply { mkdirs() }
            val apk = File(allocationDirectory, "work.apk").apply { writeText("apk") }
            val outside = File(root, "outside.apk").apply { writeText("apk") }

            assertEquals(apk.canonicalFile, RootCommandSpec.requireFileInside(apk, allocationDirectory))
            assertThrows(IllegalArgumentException::class.java) {
                RootCommandSpec.requireFileInside(outside, allocationDirectory)
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
