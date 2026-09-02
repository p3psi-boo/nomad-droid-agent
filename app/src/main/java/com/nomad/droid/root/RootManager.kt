package com.nomad.droid.root

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

object RootManager {
    data class State(
        val suAvailable: Boolean,
        val checking: Boolean,
        val permissionGranted: Boolean,
        val uid: Int?,
        val message: String,
    )

    private data class CommandResult(
        val exitCode: Int,
        val output: String,
    )

    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()
    private val executor = Executors.newSingleThreadExecutor()
    private val commandLock = Any()
    private var appContext: Context? = null
    private var suExecutable: String? = null
    @Volatile
    private var currentState = State(
        suAvailable = false,
        checking = false,
        permissionGranted = false,
        uid = null,
        message = "Root access has not been checked",
    )

    @Synchronized
    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        suExecutable = findSuExecutable()
        currentState = unavailableOrUncheckedState()
        publish()
        if (preferences().getBoolean(KEY_PREVIOUSLY_GRANTED, false)) {
            requestAccess()
        }
    }

    fun addListener(listener: (State) -> Unit) {
        listeners += listener
        listener(state())
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners -= listener
    }

    fun state(): State = currentState

    fun requestAccess() {
        synchronized(commandLock) {
            if (currentState.checking) return
            suExecutable = findSuExecutable()
            val available = suExecutable != null
            currentState = if (available) {
                currentState.copy(checking = true, message = "Checking root access")
            } else {
                State(false, false, false, null, "su executable is unavailable")
            }
            publish()
            if (!available) return
        }

        executor.execute {
            synchronized(commandLock) {
                probeAccess()
            }
        }
    }

    fun execute(request: JSONObject): JSONObject = synchronized(commandLock) {
        if (!ensureAccess()) return@synchronized failure(currentState.message)

        when (request.getString("action")) {
            "capabilities" -> capabilities()
            "install_package" -> installPackage(
                request.getString("apk_path"),
                request.getString("sha256"),
                request.optBoolean("replace", true),
            )
            "inspect_package" -> inspectPackage(request.getString("package"))
            "inspect_service" -> inspectService(
                request.getString("package"),
                request.getString("component"),
            )
            "start_service" -> startService(
                request.getString("package"),
                request.getString("component"),
            )
            "stop_service" -> stopService(
                request.getString("package"),
                request.getString("component"),
            )
            "force_stop" -> forceStopPackage(request.getString("package"))
            else -> failure("Unsupported root action: ${request.getString("action")}")
        }
    }

    private fun ensureAccess(): Boolean {
        if (currentState.permissionGranted) return true
        suExecutable = findSuExecutable()
        if (suExecutable == null) {
            currentState = State(false, false, false, null, "su executable is unavailable")
            publish()
            return false
        }
        return probeAccess()
    }

    private fun probeAccess(): Boolean {
        val result = runRoot(listOf(ID, "-u"))
        val uid = result.output.lineSequence().lastOrNull()?.trim()?.toIntOrNull()
        val granted = result.exitCode == 0 && uid == ROOT_UID
        preferences().edit().putBoolean(KEY_PREVIOUSLY_GRANTED, granted).apply()
        currentState = State(
            suAvailable = true,
            checking = false,
            permissionGranted = granted,
            uid = uid,
            message = when {
                granted -> "Root access is ready"
                result.output.isNotBlank() -> "Root access was not granted: ${result.output}"
                else -> "Root access was not granted"
            },
        )
        publish()
        return granted
    }

    private fun capabilities(): JSONObject = success("uid=0")
        .put("uid", ROOT_UID)
        .put("install_package", true)
        .put("start_service", true)
        .put("force_stop", true)

    private fun installPackage(path: String, expectedSha256: String, replace: Boolean): JSONObject {
        val context = requireContext()
        val expected = RootCommandSpec.requireSha256(expectedSha256)
        val allocationDirectory = File(context.filesDir, "nomad/alloc")
        val apk = RootCommandSpec.requireFileInside(File(path), allocationDirectory)
        val actual = apk.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        if (actual != expected) return failure("APK digest mismatch", 3)

        val temporaryAPK = File(ROOT_TEMP_DIRECTORY, "nomad-droid-$expected.apk")
        return try {
            runRoot(listOf(CP, apk.absolutePath, temporaryAPK.absolutePath)).requireSuccess()
            runRoot(listOf(CHMOD, "0644", temporaryAPK.absolutePath)).requireSuccess()
            val arguments = mutableListOf(PM, "install")
            if (replace) arguments += "-r"
            arguments += temporaryAPK.absolutePath
            runRoot(arguments).toJson()
        } catch (error: RootCommandException) {
            failure(error.message.orEmpty(), error.exitCode)
        } finally {
            runRoot(listOf(RM, "-f", temporaryAPK.absolutePath))
        }
    }

    private fun inspectPackage(packageName: String): JSONObject {
        val validated = RootCommandSpec.requirePackageName(packageName)
        return runRoot(listOf(PM, "path", validated)).toJson()
    }

    private fun inspectService(packageName: String, componentName: String): JSONObject {
        val component = component(packageName, componentName)
        val result = runRoot(listOf(DUMPSYS, "activity", "services", component))
        val inspected = result.exitCode == 0
        val running = inspected && result.output.contains("ServiceRecord{") && result.output.contains(packageName)
        return result.toJson(ok = running)
            .put("inspected", inspected)
            .put("running", running)
    }

    private fun startService(packageName: String, componentName: String): JSONObject =
        runRoot(listOf(AM, "start-foreground-service", "-n", component(packageName, componentName))).toJson()

    private fun stopService(packageName: String, componentName: String): JSONObject =
        runRoot(listOf(AM, "stopservice", "-n", component(packageName, componentName))).toJson()

    private fun forceStopPackage(packageName: String): JSONObject {
        val validated = RootCommandSpec.requirePackageName(packageName)
        return runRoot(listOf(AM, "force-stop", validated)).toJson()
    }

    private fun component(packageName: String, componentName: String): String {
        val validatedPackage = RootCommandSpec.requirePackageName(packageName)
        val validatedComponent = RootCommandSpec.requireComponentName(componentName)
        return "$validatedPackage/$validatedComponent"
    }

    private fun runRoot(arguments: List<String>): CommandResult {
        val executable = suExecutable ?: return CommandResult(127, "su executable is unavailable")
        return try {
            val process = ProcessBuilder(executable, "-c", RootCommandSpec.rootProgram(arguments))
                .redirectErrorStream(true)
                .start()
            val rawOutput = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText().trim() }
            val exitCode = process.waitFor()
            val parsedOutput = RootCommandSpec.parseOutput(rawOutput)
            if (!parsedOutput.accessConfirmed) {
                preferences().edit().putBoolean(KEY_PREVIOUSLY_GRANTED, false).apply()
                currentState = State(true, false, false, null, "Root access was not granted")
                publish()
            }
            CommandResult(exitCode, parsedOutput.commandOutput)
        } catch (error: Throwable) {
            preferences().edit().putBoolean(KEY_PREVIOUSLY_GRANTED, false).apply()
            currentState = State(
                suAvailable = File(executable).canExecute(),
                checking = false,
                permissionGranted = false,
                uid = null,
                message = "Root command could not start",
            )
            publish()
            CommandResult(1, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun CommandResult.requireSuccess() {
        if (exitCode != 0) throw RootCommandException(exitCode, output.ifBlank { "Root command failed" })
    }

    private fun CommandResult.toJson(ok: Boolean = exitCode == 0): JSONObject = JSONObject()
        .put("ok", ok)
        .put("exit_code", exitCode)
        .put("output", output)
        .put("uid", ROOT_UID)

    private fun success(output: String): JSONObject = JSONObject()
        .put("ok", true)
        .put("exit_code", 0)
        .put("output", output)

    private fun failure(message: String, exitCode: Int = 1): JSONObject = JSONObject()
        .put("ok", false)
        .put("exit_code", exitCode)
        .put("output", message)

    private fun unavailableOrUncheckedState(): State {
        val available = suExecutable != null
        return State(
            suAvailable = available,
            checking = false,
            permissionGranted = false,
            uid = null,
            message = if (available) "Root access has not been checked" else "su executable is unavailable",
        )
    }

    private fun findSuExecutable(): String? {
        val candidates = buildList {
            addAll(SU_PATHS)
            System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
                .filter { it.isNotBlank() }
                .forEach { add(File(it, "su").absolutePath) }
        }
        return candidates.distinct().firstOrNull { File(it).canExecute() }
    }

    private fun preferences() = requireContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun requireContext(): Context = checkNotNull(appContext) { "RootManager is not initialized" }

    private fun publish() {
        val state = currentState
        listeners.forEach { it(state) }
    }

    private class RootCommandException(val exitCode: Int, message: String) : RuntimeException(message)

    private const val ROOT_UID = 0
    private const val PREFERENCES = "root-access"
    private const val KEY_PREVIOUSLY_GRANTED = "previously-granted"
    private const val ROOT_TEMP_DIRECTORY = "/data/local/tmp"
    private const val ID = "/system/bin/id"
    private const val PM = "/system/bin/pm"
    private const val AM = "/system/bin/am"
    private const val DUMPSYS = "/system/bin/dumpsys"
    private const val CP = "/system/bin/cp"
    private const val CHMOD = "/system/bin/chmod"
    private const val RM = "/system/bin/rm"
    private val SU_PATHS = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su")
}
