package com.nomad.droid.runtime

import android.net.Credentials
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import com.nomad.droid.shizuku.ShizukuManager
import com.nomad.droid.termux.TermuxManager
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

object GoBridgeServer {
    const val SOCKET_ADDRESS = "@nomad_droid_bridge"
    private const val ABSTRACT_SOCKET_NAME = "nomad_droid_bridge"

    private val acceptExecutor = Executors.newSingleThreadExecutor()
    private val clientExecutor = Executors.newCachedThreadPool()
    private var server: LocalServerSocket? = null

    @Synchronized
    fun start() {
        if (server != null) return
        val localServer = LocalServerSocket(ABSTRACT_SOCKET_NAME)
        server = localServer
        acceptExecutor.execute {
            while (server === localServer) {
                val socket = runCatching { localServer.accept() }.getOrNull() ?: break
                clientExecutor.execute { handleClient(socket) }
            }
        }
    }

    @Synchronized
    fun stop() {
        val current = server ?: return
        server = null
        runCatching { current.close() }
    }

    private fun handleClient(socket: LocalSocket) {
        socket.use { client ->
            val credentials: Credentials = runCatching { client.peerCredentials }.getOrNull() ?: return
            if (credentials.uid != Process.myUid()) return

            val requestLine = client.inputStream.bufferedReader().use { it.readLine() } ?: return
            val response = runCatching { dispatch(JSONObject(requestLine)) }
                .getOrElse { failure(it.message ?: it.javaClass.simpleName) }
            client.outputStream.bufferedWriter().use {
                it.write(response.toString())
                it.newLine()
            }
        }
    }

    private fun dispatch(request: JSONObject): JSONObject {
        val action = request.getString("action")
        when (action) {
            "termux_status" -> return TermuxManager.statusJson()
            "termux_start" -> return TermuxManager.start(request)
            "termux_stop" -> return TermuxManager.stop(request)
            "termux_inspect" -> return TermuxManager.inspect(request.getString("task_id"))
            "termux_result" -> return TermuxManager.result(request.getString("task_id"))
            "termux_destroy" -> return TermuxManager.destroy(request.getString("task_id"))
        }

        val broker = ShizukuManager.broker() ?: return failure("Shizuku broker is unavailable")
        val result = when (action) {
            "capabilities" -> broker.capabilities
            "install_package" -> {
                val path = request.getString("apk_path")
                ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).use { apk ->
                    broker.installPackage(
                        apk,
                        request.getString("sha256"),
                        request.optBoolean("replace", true),
                    )
                }
            }
            "inspect_package" -> broker.inspectPackage(request.getString("package"))
            "inspect_service" -> broker.inspectService(
                request.getString("package"),
                request.getString("component"),
            )
            "start_service" -> broker.startService(
                request.getString("package"),
                request.getString("component"),
            )
            "stop_service" -> broker.stopService(
                request.getString("package"),
                request.getString("component"),
            )
            "force_stop" -> broker.forceStopPackage(request.getString("package"))
            else -> return failure("Unsupported bridge action: $action")
        }
        return result.toJson()
    }

    private fun Bundle.toJson(): JSONObject = JSONObject().also { json ->
        keySet().forEach { key ->
            val value = when (key) {
                "ok", "running", "inspected", "install_package", "start_service", "force_stop" ->
                    getBoolean(key)
                "exit_code", "uid" -> getInt(key)
                else -> getString(key)
            }
            json.put(key, value)
        }
    }

    private fun failure(message: String): JSONObject = JSONObject()
        .put("ok", false)
        .put("exit_code", 1)
        .put("output", message)
}
