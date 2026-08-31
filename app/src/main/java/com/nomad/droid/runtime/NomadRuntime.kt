package com.nomad.droid.runtime

import android.content.Context
import com.nomad.droid.agent.AgentConfig
import java.io.File

object NomadRuntime {
    @Synchronized
    fun start(context: Context, config: AgentConfig): Int {
        GoBridgeServer.start()

        val root = File(context.filesDir, "nomad").apply { mkdirs() }
        val state = File(root, "state").apply { mkdirs() }
        val alloc = File(root, "alloc").apply { mkdirs() }
        val json = config.toNativeJson(
            stateDir = state.absolutePath,
            allocDir = alloc.absolutePath,
            bridgeSocket = GoBridgeServer.SOCKET_ADDRESS,
        )
        return NomadNative.start(json)
    }

    @Synchronized
    fun stop() {
        NomadNative.stop()
        GoBridgeServer.stop()
    }

    fun status(): String = NomadNative.status()
}

