package com.nomad.droid.agent

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class WorkloadTrackerTest {

    @Before
    fun setup() {
        WorkloadTracker.clear()
    }

    @Test
    fun tracksWorkloadLifecycle() {
        WorkloadTracker.recordStart("alloc-1", "Termux Shell", "sh script.sh")
        val active = WorkloadTracker.getWorkloads()

        assertEquals(1, active.size)
        assertEquals("alloc-1", active[0].id)
        assertEquals("Termux Shell", active[0].kind)
        assertEquals("Running", active[0].status)

        WorkloadTracker.recordEnd("alloc-1", "Completed")
        val finished = WorkloadTracker.getWorkloads()
        assertEquals(1, finished.size)
        assertEquals("Completed", finished[0].status)
    }

    @Test
    fun sortsLatestFirst() {
        WorkloadTracker.recordStart("alloc-1", "Android Service", "com.test.app/.Service")
        Thread.sleep(10)
        WorkloadTracker.recordStart("alloc-2", "Termux Shell", "python script.py")

        val list = WorkloadTracker.getWorkloads()
        assertEquals("alloc-2", list[0].id)
        assertEquals("alloc-1", list[1].id)
    }
}
