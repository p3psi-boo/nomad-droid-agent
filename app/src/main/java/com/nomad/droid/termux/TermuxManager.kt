package com.nomad.droid.termux

import android.app.Activity
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

object TermuxManager {
    data class State(
        val installed: Boolean,
        val permissionGranted: Boolean,
        val serviceAvailable: Boolean,
        val ready: Boolean,
        val setupState: String,
        val message: String,
    )

    private data class TaskRecord(
        val id: String,
        val state: String,
        val startedAt: Long,
        val completedAt: Long = 0,
        val exitCode: Int = 0,
        val error: String = "",
        val stdoutOriginalLength: String = "",
        val stderrOriginalLength: String = "",
        val truncated: Boolean = false,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("state", state)
            .put("started_at", startedAt)
            .put("completed_at", completedAt)
            .put("exit_code", exitCode)
            .put("error", error)
            .put("stdout_original_length", stdoutOriginalLength)
            .put("stderr_original_length", stderrOriginalLength)
            .put("truncated", truncated)

        companion object {
            fun fromJson(raw: String): TaskRecord {
                val json = JSONObject(raw)
                return TaskRecord(
                    id = json.getString("id"),
                    state = json.getString("state"),
                    startedAt = json.getLong("started_at"),
                    completedAt = json.optLong("completed_at"),
                    exitCode = json.optInt("exit_code"),
                    error = json.optString("error"),
                    stdoutOriginalLength = json.optString("stdout_original_length"),
                    stderrOriginalLength = json.optString("stderr_original_length"),
                    truncated = json.optBoolean("truncated"),
                )
            }
        }
    }

    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()
    private var appContext: Context? = null

    @Synchronized
    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        resultDirectory().mkdirs()
        publish()
    }

    fun addListener(listener: (State) -> Unit) {
        listeners += listener
        listener(state())
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners -= listener
    }

    fun state(): State {
        val context = requireContext()
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(TermuxContract.PACKAGE_NAME, 0)
        }.getOrNull()
        val installed = packageInfo != null
        val granted = installed &&
            context.checkSelfPermission(TermuxContract.RUN_COMMAND_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
        val serviceAvailable = installed && runCatching {
            context.packageManager.resolveService(baseIntent(), 0) != null
        }.getOrDefault(false)
        val preferences = preferences()
        val verifiedForCurrentVersion = packageInfo != null &&
            preferences.getLong(KEY_SETUP_PACKAGE_UPDATE, Long.MIN_VALUE) == packageInfo.lastUpdateTime
        val savedSetup = preferences.getString(KEY_SETUP_STATE, SETUP_UNVERIFIED) ?: SETUP_UNVERIFIED
        val setup = if (verifiedForCurrentVersion) savedSetup else SETUP_UNVERIFIED
        val ready = installed && granted && serviceAvailable && setup == SETUP_READY
        val message = when {
            !installed -> "Termux is not installed"
            !granted -> "Grant Run commands in Termux environment"
            !serviceAvailable -> "Termux RunCommandService is unavailable"
            setup == SETUP_READY -> "Termux RUN_COMMAND setup verified"
            setup == SETUP_FAILED -> preferences.getString(KEY_SETUP_MESSAGE, null)
                ?: "Termux setup test failed"
            else -> "Set allow-external-apps=true in Termux, then run the setup test"
        }
        return State(installed, granted, serviceAvailable, ready, setup, message)
    }

    fun statusJson(): JSONObject {
        val state = state()
        return JSONObject()
            .put("ok", true)
            .put("exit_code", 0)
            .put("installed", state.installed)
            .put("permission_granted", state.permissionGranted)
            .put("service_available", state.serviceAvailable)
            .put("ready", state.ready)
            .put("setup", state.setupState)
            .put("output", state.message)
    }

    @Synchronized
    fun start(request: JSONObject): JSONObject {
        requireAvailable()
        val taskID = request.getString("task_id").also {
            require(it.isNotBlank()) { "task_id is required" }
        }
        record(taskID)?.takeIf { it.state in ACTIVE_STATES }?.let {
            error("Termux task already exists: $taskID")
        }
        val command = request.getString("command")
        val arguments = request.optJSONArray("args").toStringList()
        val environment = request.optJSONObject("env").toStringMap()
        val workDir = request.optString("work_dir").ifBlank { TermuxContract.TERMUX_HOME }
        val stdin = if (request.has("stdin") && !request.isNull("stdin")) {
            request.getString("stdin")
        } else {
            null
        }
        val pidFile = pidFile(taskID)
        val record = TaskRecord(taskID, STATE_RUNNING, System.currentTimeMillis())
        stdoutFile(taskID).delete()
        stderrFile(taskID).delete()
        persist(record)
        return runCatching {
            dispatch(
                taskID = taskID,
                kind = KIND_COMMAND,
                command = TermuxContract.SHELL,
                arguments = TermuxCommandSpec.startArguments(
                    pidFile,
                    command,
                    arguments,
                    environment,
                ),
                workDir = TermuxCommandSpec.normalizePath(workDir),
                stdin = stdin,
            )
            success("Termux task dispatched", STATE_RUNNING)
        }.getOrElse { failure ->
            persist(record.failed(failure.message ?: failure.javaClass.simpleName))
            publish()
            throw failure
        }
    }

    @Synchronized
    fun stop(request: JSONObject): JSONObject {
        requireAvailable()
        val taskID = request.getString("task_id")
        val current = record(taskID) ?: error("Termux task not found: $taskID")
        if (current.state !in ACTIVE_STATES) return success("Termux task is already complete", current.state)
        val stopping = current.copy(state = STATE_STOPPING)
        persist(stopping)
        return runCatching {
            dispatch(
                taskID = taskID,
                kind = KIND_STOP,
                command = TermuxContract.SHELL,
                arguments = TermuxCommandSpec.stopArguments(pidFile(taskID), request.optBoolean("force")),
                workDir = TermuxContract.TERMUX_HOME,
            )
            success("Termux stop dispatched", STATE_STOPPING)
        }.getOrElse { failure ->
            persist(current)
            throw failure
        }
    }

    @Synchronized
    fun inspect(taskID: String): JSONObject {
        val record = record(taskID) ?: return JSONObject()
            .put("ok", false)
            .put("exit_code", 1)
            .put("inspected", false)
            .put("running", false)
            .put("state", STATE_MISSING)
            .put("output", "Termux task not found: $taskID")
        return JSONObject()
            .put("ok", true)
            .put("exit_code", record.exitCode)
            .put("inspected", true)
            .put("running", record.state in ACTIVE_STATES)
            .put("state", record.state)
            .put("started_at", record.startedAt)
            .put("completed_at", record.completedAt)
            .put("truncated", record.truncated)
            .put("output", record.error)
    }

    @Synchronized
    fun result(taskID: String): JSONObject {
        val record = record(taskID) ?: error("Termux task not found: $taskID")
        require(record.state !in ACTIVE_STATES) { "Termux task is still running: $taskID" }
        return JSONObject()
            .put("ok", true)
            .put("exit_code", record.exitCode)
            .put("state", record.state)
            .put("stdout", stdoutFile(taskID).takeIf { it.isFile }?.readText().orEmpty())
            .put("stderr", stderrFile(taskID).takeIf { it.isFile }?.readText().orEmpty())
            .put("stdout_original_length", record.stdoutOriginalLength)
            .put("stderr_original_length", record.stderrOriginalLength)
            .put("truncated", record.truncated)
            .put("output", record.error)
    }

    @Synchronized
    fun destroy(taskID: String): JSONObject {
        val record = record(taskID) ?: return success("Termux task state is already removed", STATE_MISSING)
        require(record.state !in ACTIVE_STATES) { "Termux task is still running: $taskID" }
        preferences().edit().remove(taskKey(taskID)).commitOrThrow()
        stdoutFile(taskID).delete()
        stderrFile(taskID).delete()
        return success("Termux task state removed", STATE_MISSING)
    }

    @Synchronized
    fun startSetupProbe(): JSONObject {
        requireAvailable()
        val taskID = "setup-${UUID.randomUUID()}"
        persist(TaskRecord(taskID, STATE_RUNNING, System.currentTimeMillis()))
        preferences().edit()
            .putString(KEY_SETUP_STATE, SETUP_RUNNING)
            .putString(KEY_SETUP_MESSAGE, "Termux setup test is running")
            .commitOrThrow()
        return runCatching {
            dispatch(
                taskID = taskID,
                kind = KIND_PROBE,
                command = TermuxContract.TRUE,
                arguments = emptyArray(),
                workDir = TermuxContract.TERMUX_HOME,
            )
            publish()
            success("Termux setup test dispatched", SETUP_RUNNING)
        }.getOrElse { failure ->
            removeRecord(taskID)
            saveSetup(SETUP_FAILED, failure.message ?: failure.javaClass.simpleName)
            throw failure
        }
    }

    @Synchronized
    fun handleResult(taskID: String, kind: String, bundle: Bundle?) {
        val current = record(taskID) ?: return
        if (bundle == null) {
            finishWithFailure(current, "Termux result bundle is missing", kind)
            return
        }

        val stdout = bundle.getString(TermuxContract.EXTRA_STDOUT).orEmpty()
        var stderr = bundle.getString(TermuxContract.EXTRA_STDERR).orEmpty()
        val stdoutOriginal = bundle.getString(TermuxContract.EXTRA_STDOUT_ORIGINAL_LENGTH).orEmpty()
        val stderrOriginal = bundle.getString(TermuxContract.EXTRA_STDERR_ORIGINAL_LENGTH).orEmpty()
        val exitCode = bundle.getInt(TermuxContract.EXTRA_EXIT_CODE, 1)
        val internalError = bundle.getInt(TermuxContract.EXTRA_ERR, Activity.RESULT_OK)
        val internalMessage = bundle.getString(TermuxContract.EXTRA_ERRMSG).orEmpty()
        val truncated = isTruncated(stdoutOriginal, stdout) || isTruncated(stderrOriginal, stderr)
        if (truncated) {
            stderr += buildString {
                if (stderr.isNotEmpty() && !stderr.endsWith('\n')) append('\n')
                append("[nomad-droid] Termux truncated command output")
                if (stdoutOriginal.isNotEmpty()) append("; stdout original length=").append(stdoutOriginal)
                if (stderrOriginal.isNotEmpty()) append("; stderr original length=").append(stderrOriginal)
                append('\n')
            }
        }

        if (kind == KIND_PROBE) {
            val success = internalError == Activity.RESULT_OK && exitCode == 0
            removeRecord(taskID)
            if (success) {
                saveSetup(SETUP_READY, "Termux RUN_COMMAND setup verified")
            } else {
                saveSetup(
                    SETUP_FAILED,
                    internalMessage.ifBlank { "Termux setup test exited with code $exitCode" },
                )
            }
            return
        }

        if (kind == KIND_STOP) {
            if (current.state !in ACTIVE_STATES) return
            if (internalError != Activity.RESULT_OK || exitCode != 0) {
                val message = internalMessage.ifBlank { "Termux stop helper exited with code $exitCode" }
                // A failed helper does not prove that the workload exited.
                persist(current.copy(state = STATE_RUNNING, error = message))
                saveSetup(SETUP_FAILED, message)
            }
            return
        }

        if (current.state !in ACTIVE_STATES) return
        stdoutFile(taskID).writeText(stdout)
        stderrFile(taskID).writeText(stderr)
        val stoppedByDriver = current.state == STATE_STOPPING
        val failed = internalError != Activity.RESULT_OK && !stoppedByDriver
        val finalState = if (failed) STATE_FAILED else STATE_EXITED
        val finalCode = if (stoppedByDriver) 0 else exitCode
        val error = when {
            stoppedByDriver -> "Stopped by Nomad"
            internalError != Activity.RESULT_OK -> internalMessage.ifBlank {
                "Termux internal error $internalError"
            }
            else -> ""
        }
        persist(
            current.copy(
                state = finalState,
                completedAt = System.currentTimeMillis(),
                exitCode = finalCode,
                error = error,
                stdoutOriginalLength = stdoutOriginal,
                stderrOriginalLength = stderrOriginal,
                truncated = truncated,
            ),
        )
        if (failed) {
            saveSetup(SETUP_FAILED, error)
        } else {
            saveSetup(SETUP_READY, "Termux RUN_COMMAND setup verified")
        }
    }

    @Synchronized
    fun markInterruptedByReboot() {
        preferences().all
            .filterKeys { it.startsWith(TASK_KEY_PREFIX) }
            .values
            .mapNotNull { raw -> runCatching { TaskRecord.fromJson(raw as String) }.getOrNull() }
            .filter { it.state in ACTIVE_STATES }
            .forEach { record ->
                persist(record.failed("Android reboot interrupted the Termux process"))
            }
    }

    private fun finishWithFailure(record: TaskRecord, message: String, kind: String) {
        when (kind) {
            KIND_PROBE -> {
                removeRecord(record.id)
                saveSetup(SETUP_FAILED, message)
            }
            KIND_STOP -> {
                if (record.state !in ACTIVE_STATES) return
                // A missing helper result does not prove that the workload exited.
                persist(record.copy(error = message))
                saveSetup(SETUP_FAILED, message)
            }
            else -> {
                persist(record.failed(message))
                publish()
            }
        }
    }

    private fun TaskRecord.failed(message: String): TaskRecord = copy(
        state = STATE_FAILED,
        completedAt = System.currentTimeMillis(),
        exitCode = 1,
        error = message,
    )

    private fun dispatch(
        taskID: String,
        kind: String,
        command: String,
        arguments: Array<String>,
        workDir: String,
        stdin: String? = null,
    ) {
        val context = requireContext()
        val resultID = UUID.randomUUID()
        val resultIntent = Intent(context, TermuxResultReceiver::class.java)
            .setAction(ACTION_RESULT)
            .setData(Uri.parse("nomad-droid://termux-result/$resultID"))
            .putExtra(EXTRA_TASK_ID, taskID)
            .putExtra(EXTRA_RESULT_KIND, kind)
        val pendingResult = PendingIntent.getBroadcast(
            context,
            resultID.hashCode(),
            resultIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE,
        )
        val commandIntent = baseIntent()
            .putExtra(TermuxContract.EXTRA_COMMAND_PATH, command)
            .putExtra(TermuxContract.EXTRA_ARGUMENTS, arguments)
            .putExtra(TermuxContract.EXTRA_WORKDIR, workDir)
            .putExtra(TermuxContract.EXTRA_BACKGROUND, true)
            .putExtra(TermuxContract.EXTRA_PENDING_INTENT, pendingResult)
        if (stdin != null) commandIntent.putExtra(TermuxContract.EXTRA_STDIN, stdin)
        check(context.startService(commandIntent) != null) { "Termux rejected the RUN_COMMAND service start" }
    }

    private fun baseIntent(): Intent = Intent(TermuxContract.ACTION_RUN_COMMAND)
        .setComponent(ComponentName(TermuxContract.PACKAGE_NAME, TermuxContract.RUN_COMMAND_SERVICE))

    private fun requireAvailable() {
        val state = state()
        require(state.installed) { "Termux is not installed" }
        require(state.permissionGranted) { "Termux RUN_COMMAND permission is not granted" }
        require(state.serviceAvailable) { "Termux RunCommandService is unavailable" }
    }

    private fun saveSetup(setup: String, message: String) {
        val packageUpdate = runCatching {
            requireContext().packageManager.getPackageInfo(TermuxContract.PACKAGE_NAME, 0).lastUpdateTime
        }.getOrDefault(Long.MIN_VALUE)
        preferences().edit()
            .putString(KEY_SETUP_STATE, setup)
            .putString(KEY_SETUP_MESSAGE, message)
            .putLong(KEY_SETUP_PACKAGE_UPDATE, packageUpdate)
            .commitOrThrow()
        publish()
    }

    private fun success(output: String, state: String): JSONObject = JSONObject()
        .put("ok", true)
        .put("exit_code", 0)
        .put("state", state)
        .put("output", output)

    private fun persist(record: TaskRecord) {
        preferences().edit().putString(taskKey(record.id), record.toJson().toString()).commitOrThrow()
    }

    private fun record(taskID: String): TaskRecord? = preferences()
        .getString(taskKey(taskID), null)
        ?.let { runCatching { TaskRecord.fromJson(it) }.getOrNull() }

    private fun removeRecord(taskID: String) {
        preferences().edit().remove(taskKey(taskID)).commitOrThrow()
        stdoutFile(taskID).delete()
        stderrFile(taskID).delete()
    }

    private fun preferences() = requireContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun resultDirectory(): File = File(requireContext().filesDir, "termux-results")

    private fun stdoutFile(taskID: String): File = File(resultDirectory(), "${taskHash(taskID)}.stdout")

    private fun stderrFile(taskID: String): File = File(resultDirectory(), "${taskHash(taskID)}.stderr")

    private fun pidFile(taskID: String): String =
        "${TermuxContract.TERMUX_HOME}/.nomad-droid/tasks/${taskHash(taskID)}.pid"

    private fun taskKey(taskID: String): String = TASK_KEY_PREFIX + taskHash(taskID)

    private fun taskHash(taskID: String): String = MessageDigest.getInstance("SHA-256")
        .digest(taskID.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun isTruncated(original: String, value: String): Boolean =
        original.toLongOrNull()?.let { it > value.length } ?: false

    private fun JSONArray?.toStringList(): List<String> = if (this == null) {
        emptyList()
    } else {
        List(length()) { index -> getString(index) }
    }

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        return keys().asSequence().associateWith { key -> getString(key) }
    }

    private fun android.content.SharedPreferences.Editor.commitOrThrow() {
        if (!commit()) throw IOException("Unable to persist Termux task state")
    }

    private fun requireContext(): Context = checkNotNull(appContext) { "TermuxManager is not initialized" }

    private fun publish() {
        val state = state()
        listeners.forEach { it(state) }
    }

    internal const val ACTION_RESULT = "com.nomad.droid.action.TERMUX_RESULT"
    internal const val EXTRA_TASK_ID = "task_id"
    internal const val EXTRA_RESULT_KIND = "result_kind"

    private const val PREFERENCES = "termux_tasks"
    private const val TASK_KEY_PREFIX = "task."
    private const val KEY_SETUP_STATE = "setup.state"
    private const val KEY_SETUP_MESSAGE = "setup.message"
    private const val KEY_SETUP_PACKAGE_UPDATE = "setup.package_update"
    private const val KIND_COMMAND = "command"
    private const val KIND_STOP = "stop"
    private const val KIND_PROBE = "probe"
    private const val STATE_RUNNING = "running"
    private const val STATE_STOPPING = "stopping"
    private const val STATE_EXITED = "exited"
    private const val STATE_FAILED = "failed"
    private const val STATE_MISSING = "missing"
    private const val SETUP_UNVERIFIED = "unverified"
    private const val SETUP_RUNNING = "running"
    private const val SETUP_READY = "ready"
    private const val SETUP_FAILED = "failed"
    private val ACTIVE_STATES = setOf(STATE_RUNNING, STATE_STOPPING)
}
