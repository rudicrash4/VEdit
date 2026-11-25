package id.vedit.editor.data

import id.vedit.editor.domain.Project
import org.junit.Assert.assertEquals
import org.junit.Test

class AutosaverTest {
    private class FakeScheduler : Scheduler {
        class Job(val task: () -> Unit, var cancelled: Boolean = false)
        val jobs = ArrayList<Job>()
        override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
            val j = Job(task)
            jobs += j
            return Cancellable { j.cancelled = true }
        }
        fun runPending() = jobs.filter { !it.cancelled }.forEach { it.cancelled = true; it.task() }
    }

    private fun p(n: String) = Project.create("p", n, 0)

    @Test
    fun burstOfChangesSavesOnlyLatestOnce() {
        val sch = FakeScheduler()
        val saved = ArrayList<String>()
        val a = Autosaver(sch, 800) { saved += it.name }
        a.onChanged(p("1")); a.onChanged(p("2")); a.onChanged(p("3"))
        assertEquals(emptyList<String>(), saved)
        sch.runPending()
        assertEquals(listOf("3"), saved)
    }

    @Test
    fun flushSavesImmediatelyAndOnlyOnce() {
        val sch = FakeScheduler()
        val saved = ArrayList<String>()
        val a = Autosaver(sch, 800) { saved += it.name }
        a.onChanged(p("x"))
        a.flushNow()
        a.flushNow()
        sch.runPending()
        assertEquals(listOf("x"), saved)
    }
}
