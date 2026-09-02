package com.nomad.droid.root

import java.io.File

internal object RootCommandSpec {
    data class Output(
        val accessConfirmed: Boolean,
        val commandOutput: String,
    )

    const val ACCESS_MARKER = "__NOMAD_DROID_ROOT_UID_0__"

    private val packageName = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    private val componentName = Regex("\\.?[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*")
    private val sha256 = Regex("[a-f0-9]{64}")

    fun requirePackageName(value: String): String = value.also {
        require(it.matches(packageName)) { "Invalid package name" }
    }

    fun requireComponentName(value: String): String = value.also {
        require(it.matches(componentName)) { "Invalid component name" }
    }

    fun requireSha256(value: String): String = value.trim().lowercase().also {
        require(it.matches(sha256)) { "A valid SHA-256 digest is required" }
    }

    fun requireFileInside(file: File, directory: File): File {
        val resolvedFile = file.canonicalFile
        val resolvedDirectory = directory.canonicalFile
        require(
            resolvedFile.path.startsWith(resolvedDirectory.path + File.separator),
        ) { "APK path must stay inside the allocation directory" }
        require(resolvedFile.isFile) { "APK artifact is not a readable file" }
        return resolvedFile
    }

    fun rootProgram(arguments: List<String>): String {
        require(arguments.isNotEmpty()) { "Root command is required" }
        val command = arguments.joinToString(" ") { shellQuote(it) }
        return "uid=\$(/system/bin/id -u) || exit \$?; " +
            "if [ \"\$uid\" != 0 ]; then " +
            "echo 'Nomad Droid did not receive root access'; exit 126; fi; " +
            "printf '%s\\n' '$ACCESS_MARKER'; " +
            "exec $command"
    }

    fun parseOutput(value: String): Output {
        val lines = value.lineSequence().toList()
        val markerIndex = lines.indexOf(ACCESS_MARKER)
        return Output(
            accessConfirmed = markerIndex >= 0,
            commandOutput = lines.filterIndexed { index, _ -> index != markerIndex }
                .joinToString("\n")
                .trim(),
        )
    }

    private fun shellQuote(value: String): String =
        "'${value.replace("'", "'\"'\"'")}'"
}
