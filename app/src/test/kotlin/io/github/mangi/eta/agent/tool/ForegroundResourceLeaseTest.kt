package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.tool.ForegroundResourceLease.Resource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundResourceLeaseTest {
    @After
    fun tearDown() {
        listOf("run-a", "run-b").forEach(ForegroundResourceLease::releaseAll)
    }

    @Test
    fun resourcesCoverScreenOperationsAndTheSharedBrowserOnly() {
        assertEquals(Resource.SCREEN, ForegroundResourceLease.resourceFor("tap_element"))
        assertEquals(Resource.SCREEN, ForegroundResourceLease.resourceFor("observe_screen"))
        assertEquals(Resource.SCREEN, ForegroundResourceLease.resourceFor("launch_app"))
        assertEquals(Resource.BROWSER, ForegroundResourceLease.resourceFor("browser_use"))
        assertNull(ForegroundResourceLease.resourceFor("terminal"))
        assertNull(ForegroundResourceLease.resourceFor("fetch_url"))
    }

    @Test
    fun otherRunWaitsWhileHolderIsActiveAndProceedsAfterRelease() {
        assertTrue(ForegroundResourceLease.acquire(Resource.SCREEN, "run-a"))
        assertFalse(ForegroundResourceLease.acquire(Resource.SCREEN, "run-b", waitMs = 50))
        // 同一 run 可重入，不同资源互不影响。
        assertTrue(ForegroundResourceLease.acquire(Resource.SCREEN, "run-a", waitMs = 0))
        assertTrue(ForegroundResourceLease.acquire(Resource.BROWSER, "run-b", waitMs = 0))

        val acquired = AtomicBoolean(false)
        val started = CountDownLatch(1)
        val waiter = thread {
            started.countDown()
            acquired.set(ForegroundResourceLease.acquire(Resource.SCREEN, "run-b", waitMs = 5_000))
        }
        assertTrue(started.await(1, TimeUnit.SECONDS))
        ForegroundResourceLease.releaseAll("run-a")
        waiter.join(2_000)
        assertTrue(acquired.get())
        assertEquals("run-b", ForegroundResourceLease.holderOf(Resource.SCREEN))
    }

    @Test
    fun idleHolderYieldsButBusyHolderDoesNot() {
        var clock = 1_000L
        assertTrue(ForegroundResourceLease.acquire(Resource.SCREEN, "run-a", now = { clock }))
        clock += ForegroundResourceLease.IDLE_RELEASE_MS * 2
        // 工具仍在执行时不因空闲计时被抢占。
        assertFalse(ForegroundResourceLease.acquire(Resource.SCREEN, "run-b", waitMs = 0, now = { clock }))
        ForegroundResourceLease.finish(Resource.SCREEN, "run-a", now = { clock })
        assertFalse(ForegroundResourceLease.acquire(Resource.SCREEN, "run-b", waitMs = 0, now = { clock }))
        clock += ForegroundResourceLease.IDLE_RELEASE_MS
        assertTrue(ForegroundResourceLease.acquire(Resource.SCREEN, "run-b", waitMs = 0, now = { clock }))
    }

    @Test
    fun cancelledWaiterStopsWaitingImmediately() {
        assertTrue(ForegroundResourceLease.acquire(Resource.BROWSER, "run-a"))
        val started = System.nanoTime()
        assertFalse(ForegroundResourceLease.acquire(Resource.BROWSER, "run-b", waitMs = 10_000, isCancelled = { true }))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000)
    }
}
