package net.micapum.neopen.app.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InFlightRequestsTest {
    @Test fun simultaneousCallsShareWorkButLaterRefreshesDoNotReuseAResult() = runBlocking {
        withTimeout(5000) {
            val requests = InFlightRequests<String, Int>()
            val release = CompletableDeferred<Unit>()
            var calls = 0
            val first = async(start = CoroutineStart.UNDISPATCHED) { requests.run("server") { calls++; release.await(); 10 } }
            val second = async(start = CoroutineStart.UNDISPATCHED) { requests.run("server") { calls++; 20 } }
            assertEquals(1, calls)
            assertEquals(30, requests.run("different-server") { 30 })
            release.complete(Unit)
            assertEquals(10, first.await()); assertEquals(10, second.await())
            assertEquals(40, requests.run("server") { calls++; 40 })
            assertEquals(2, calls)
        }
    }

    @Test fun cancellingAWaiterDoesNotCancelOwnerAndOwnerCancellationAllowsRetry() = runBlocking {
        withTimeout(5000) {
            val requests = InFlightRequests<String, Int>()
            val release = CompletableDeferred<Unit>()
            val owner = async(start = CoroutineStart.UNDISPATCHED) { requests.run("server") { release.await(); 10 } }
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { requests.run("server") { error("duplicated") } }
            waiter.cancelAndJoin()
            assertTrue(owner.isActive)
            owner.cancelAndJoin()
            assertEquals(20, requests.run("server") { 20 })
            try { owner.await(); error("cancelled owner returned") } catch (_: CancellationException) { }
        }
    }

    @Test fun cancellingTheScreenOwnerDoesNotCancelAnIndependentWorkerWaitingForIt() = runBlocking {
        withTimeout(5000) {
            val requests = InFlightRequests<String, Int>()
            val never = CompletableDeferred<Unit>()
            val screen = async(start = CoroutineStart.UNDISPATCHED) {
                requests.run("server") { never.await(); 1 }
            }
            var workerCalls = 0
            val worker = async(start = CoroutineStart.UNDISPATCHED) {
                requests.run("server") { workerCalls++; 2 }
            }
            assertEquals(0, workerCalls)
            screen.cancelAndJoin()
            assertEquals(2, worker.await())
            assertEquals(1, workerCalls)
            assertEquals(3, requests.run("server") { 3 })
        }
    }
}
