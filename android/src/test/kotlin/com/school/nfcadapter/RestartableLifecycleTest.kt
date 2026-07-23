package com.school.nfcadapter

import com.school.nfcadapter.core.RestartableLifecycle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RestartableLifecycleTest {

    @Test
    fun stopThenStartCreatesFreshPollingAcrossThreeCycles() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val lifecycle = RestartableLifecycle<String>(dispatcher, dispatcher)
        var opens = 0
        var polls = 0
        var closes = 0

        fun start() {
            lifecycle.launchControl {
                lifecycle.replacePendingAttach(0) {
                    opens++
                    lifecycle.launchPolling {
                        polls++
                        awaitCancellation()
                    }
                }
            }
        }

        start()
        runCurrent()
        repeat(3) {
            lifecycle.temporaryStop { closes++ }
            advanceUntilIdle()
            start()
            runCurrent()
        }

        assertEquals(4, opens)
        assertEquals(4, polls)
        assertEquals(3, closes)
    }

    @Test
    fun temporaryStopCancelsPendingAttachAndClearsIt() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val lifecycle = RestartableLifecycle<String>(dispatcher, dispatcher)
        var opens = 0

        lifecycle.launchControl {
            lifecycle.replacePendingAttach(1_000) { opens++ }
        }
        runCurrent()

        lifecycle.temporaryStop {}
        advanceUntilIdle()

        assertEquals(0, opens)
    }

    @Test
    fun attachAfterTemporaryStopCanOpenAndPollAgain() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val lifecycle = RestartableLifecycle<String>(dispatcher, dispatcher)
        var polls = 0

        lifecycle.temporaryStop {}
        lifecycle.launchControl {
            lifecycle.replacePendingAttach(0) {
                lifecycle.launchPolling {
                    polls++
                    awaitCancellation()
                }
            }
        }
        runCurrent()

        assertEquals(1, polls)
    }

    @Test
    fun repeatedStartDoesNotCreateDuplicatePollingWorker() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val lifecycle = RestartableLifecycle<String>(dispatcher, dispatcher)
        var polls = 0

        lifecycle.launchControl {
            assertNotNull(
                lifecycle.launchPolling {
                    polls++
                    awaitCancellation()
                }
            )
            assertNull(
                lifecycle.launchPolling {
                    polls++
                    awaitCancellation()
                }
            )
        }
        runCurrent()

        assertEquals(1, polls)
    }

    @Test
    fun temporaryStopClosesWorkerButRetainsReusableScope() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val lifecycle = RestartableLifecycle<String>(dispatcher, dispatcher)
        var closes = 0
        var polls = 0

        lifecycle.launchControl {
            lifecycle.launchPolling {
                polls++
                awaitCancellation()
            }
        }
        runCurrent()
        lifecycle.temporaryStop { closes++ }
        advanceUntilIdle()

        assertEquals(1, closes)

        lifecycle.launchControl {
            lifecycle.launchPolling {
                polls++
                awaitCancellation()
            }
        }
        runCurrent()
        assertEquals(2, polls)
    }

    @Test
    fun permanentDestroyCleansOnceAndRejectsLaterWork() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val lifecycle = RestartableLifecycle<String>(dispatcher, dispatcher)
        var closes = 0
        var laterWork = 0
        var pollingFinally = 0

        lifecycle.launchControl {
            lifecycle.launchPolling {
                try {
                    awaitCancellation()
                } finally {
                    pollingFinally++
                }
            }
        }
        runCurrent()

        val firstDestroy = lifecycle.destroy { closes++ }
        val secondDestroy = lifecycle.destroy { closes++ }
        advanceUntilIdle()

        assertEquals(firstDestroy, secondDestroy)
        assertEquals(1, closes)
        assertEquals(1, pollingFinally)
        assertNull(lifecycle.launchControl { laterWork++ })
        advanceUntilIdle()
        assertEquals(0, laterWork)
    }

    @Test
    fun laterOpenWaitsUntilTeardownCompletes() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val lifecycle = RestartableLifecycle<String>(dispatcher, dispatcher)
        val allowClose = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        lifecycle.temporaryStop {
            order += "close-start"
            allowClose.await()
            order += "close-end"
        }
        lifecycle.launchControl { order += "open" }
        runCurrent()

        assertEquals(listOf("close-start"), order)
        allowClose.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("close-start", "close-end", "open"), order)
    }

    @Test
    fun pollingReferenceClearsAfterHandledWorkerFailure() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val lifecycle = RestartableLifecycle<String>(dispatcher, dispatcher)

        lifecycle.launchControl {
            lifecycle.launchPolling {
                runCatching { error("simulated polling failure") }
            }
        }
        advanceUntilIdle()

        var restarted = false
        lifecycle.launchControl {
            lifecycle.launchPolling {
                restarted = true
                awaitCancellation()
            }
        }
        runCurrent()
        assertTrue(restarted)
    }

    @Test
    fun temporaryStopClearsSessionReferenceAndAllowsFreshSession() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val lifecycle = RestartableLifecycle<String>(dispatcher, dispatcher)
        val closed = mutableListOf<String>()

        lifecycle.launchControl {
            assertTrue(lifecycle.setActiveSession("first"))
        }
        runCurrent()

        lifecycle.temporaryStop {
            lifecycle.takeActiveSession { true }?.let(closed::add)
        }
        advanceUntilIdle()

        lifecycle.launchControl {
            assertTrue(lifecycle.setActiveSession("second"))
        }
        runCurrent()

        assertEquals(listOf("first"), closed)
        assertEquals("second", lifecycle.takeActiveSession { true })
    }
}
