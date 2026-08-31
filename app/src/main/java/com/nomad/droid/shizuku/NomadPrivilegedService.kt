package com.nomad.droid.shizuku

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.system.Os
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.security.MessageDigest

class NomadPrivilegedService : INomadPrivilegedService.Stub {
    constructor()
    constructor(@Suppress("UNUSED_PARAMETER") context: Context)

    override fun destroy() {
        Runtime.getRuntime().exit(0)
    }

    override fun getUid(): Int = Os.getuid()

    override fun getCapabilities(): Bundle {
        val uid = getUid()
        val privileged = uid == SHELL_UID || uid == ROOT_UID
        return response(
            ok = privileged,
            exitCode = if (privileged) 0 else 1,
            output = "uid=$uid",
        ).apply {
            putInt("uid", uid)
            putBoolean("install_package", privileged && File(PM).canExecute())
            putBoolean("start_service", privileged && File(AM).canExecute())
            putBoolean("force_stop", privileged && File(AM).canExecute())
        }
    }

    override fun installPackage(
        apk: ParcelFileDescriptor,
        expectedSha256: String,
        replace: Boolean,
    ): Bundle {
        val expected = expectedSha256.trim().lowercase()
        if (!expected.matches(SHA256)) {
            return response(false, 2, "A valid SHA-256 digest is required")
        }

        val temp = File("/data/local/tmp", "nomad-droid-$expected.apk")
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(apk.fileDescriptor).use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }

            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != expected) {
                response(false, 3, "APK digest mismatch")
            } else {
                Os.chmod(temp.absolutePath, 0b110100100)
                val args = mutableListOf(PM, "install")
                if (replace) args += "-r"
                args += temp.absolutePath
                runCommand(args)
            }
        } catch (error: Throwable) {
            response(false, 1, error.message ?: error.javaClass.simpleName)
        } finally {
            runCatching { temp.delete() }
            runCatching { apk.close() }
        }
    }

    override fun inspectPackage(packageName: String): Bundle {
        validatePackage(packageName)?.let { return it }
        return runCommand(listOf(PM, "path", packageName))
    }

    override fun inspectService(packageName: String, componentName: String): Bundle {
        validateComponent(packageName, componentName)?.let { return it }
        val result = runCommand(listOf(DUMPSYS, "activity", "services", component(packageName, componentName)))
        val inspected = result.getInt(KEY_EXIT_CODE) == 0
        val output = result.getString(KEY_OUTPUT).orEmpty()
        val running = inspected && output.contains("ServiceRecord{") && output.contains(packageName)
        result.putBoolean("inspected", inspected)
        result.putBoolean("running", running)
        result.putBoolean(KEY_OK, running)
        return result
    }

    override fun startService(packageName: String, componentName: String): Bundle {
        validateComponent(packageName, componentName)?.let { return it }
        return runCommand(
            listOf(AM, "start-foreground-service", "-n", component(packageName, componentName)),
        )
    }

    override fun stopService(packageName: String, componentName: String): Bundle {
        validateComponent(packageName, componentName)?.let { return it }
        return runCommand(listOf(AM, "stopservice", "-n", component(packageName, componentName)))
    }

    override fun forceStopPackage(packageName: String): Bundle {
        validatePackage(packageName)?.let { return it }
        return runCommand(listOf(AM, "force-stop", packageName))
    }

    private fun runCommand(arguments: List<String>): Bundle = try {
        val process = ProcessBuilder(arguments)
            .redirectErrorStream(true)
            .start()
        val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }.trim()
        val exitCode = process.waitFor()
        response(exitCode == 0, exitCode, output)
    } catch (error: Throwable) {
        response(false, 1, error.message ?: error.javaClass.simpleName)
    }

    private fun validatePackage(packageName: String): Bundle? =
        if (packageName.matches(PACKAGE_NAME)) null
        else response(false, 2, "Invalid package name")

    private fun validateComponent(packageName: String, componentName: String): Bundle? {
        validatePackage(packageName)?.let { return it }
        return if (componentName.matches(COMPONENT_NAME)) null
        else response(false, 2, "Invalid component name")
    }

    private fun component(packageName: String, componentName: String): String =
        "$packageName/$componentName"

    private fun response(ok: Boolean, exitCode: Int, output: String): Bundle = Bundle().apply {
        putBoolean(KEY_OK, ok)
        putInt(KEY_EXIT_CODE, exitCode)
        putString(KEY_OUTPUT, output)
    }

    private companion object {
        const val ROOT_UID = 0
        const val SHELL_UID = 2000
        const val PM = "/system/bin/pm"
        const val AM = "/system/bin/am"
        const val DUMPSYS = "/system/bin/dumpsys"
        const val KEY_OK = "ok"
        const val KEY_EXIT_CODE = "exit_code"
        const val KEY_OUTPUT = "output"
        val SHA256 = Regex("[a-f0-9]{64}")
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
        val COMPONENT_NAME = Regex("\\.?[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*")
    }
}
