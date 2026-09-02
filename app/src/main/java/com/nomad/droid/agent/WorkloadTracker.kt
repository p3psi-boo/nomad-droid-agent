package com.nomad.droid.agent

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

object WorkloadTracker {
    data class Workload(
        val id: String,
        val kind: String, // "Android Service" or "Termux Shell"
        val target: String,
        val status: String, // "Running", "Completed", "Failed", "Stopped"
        val startedAt: Long,
        val completedAt: Long = 0,
    )

    private val activeWorkloads = ConcurrentHashMap<String, Workload>()
    private val listeners = CopyOnWriteArraySet<(List<Workload>) -> Unit>()

    fun addListener(listener: (List<Workload>) -> Unit) {
        listeners += listener
        listener(getWorkloads())
    }

    fun removeListener(listener: (List<Workload>) -> Unit) {
        listeners -= listener
    }

    fun recordStart(id: String, kind: String, target: String) {
        val item = Workload(
            id = id,
            kind = kind,
            target = target,
            status = "Running",
            startedAt = System.currentTimeMillis(),
        )
        activeWorkloads[id] = item
        publish()
    }

    fun recordEnd(id: String, status: String) {
        val existing = activeWorkloads[id] ?: return
        activeWorkloads[id] = existing.copy(
            status = status,
            completedAt = System.currentTimeMillis(),
        )
        publish()
    }

    fun getWorkloads(): List<Workload> = activeWorkloads.values.toList().sortedByDescending { it.startedAt }

    fun clear() {
        activeWorkloads.clear()
        publish()
    }

    private fun publish() {
        val list = getWorkloads()
        listeners.forEach { it(list) }
    }
}
