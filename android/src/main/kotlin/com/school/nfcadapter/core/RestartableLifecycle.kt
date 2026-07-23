package com.school.nfcadapter.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns the restartable coroutine lifetime used by ConnectionManager.
 *
 * Temporary stop invalidates queued attach work and drains polling while
 * preserving [scope]. Permanent destroy performs the same cleanup and then
 * cancels the scope exactly once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class RestartableLifecycle<Session : Any>(
    controlDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val pollingDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val scope = CoroutineScope(SupervisorJob() + controlDispatcher)
    private val stateLock = Any()

    private var generation = 0L
    private var destroyed = false
    private var teardownBarrier: Job? = null
    private var destroyJob: Job? = null
    private var pendingAttach: Job? = null
    private var pollingJob: Job? = null
    private var activeSession: Session? = null

    fun activeSession(): Session? = synchronized(stateLock) { activeSession }

    fun setActiveSession(session: Session): Boolean = synchronized(stateLock) {
        if (destroyed || activeSession != null) return false
        activeSession = session
        true
    }

    fun takeActiveSession(predicate: (Session) -> Boolean): Session? =
        synchronized(stateLock) {
            val current = activeSession ?: return null
            if (!predicate(current)) return null
            activeSession = null
            current
        }

    fun launchControl(block: suspend () -> Unit): Job? {
        val snapshot = synchronized(stateLock) {
            if (destroyed) return null
            generation to teardownBarrier
        }
        return scope.launch {
            snapshot.second?.join()
            if (isCurrent(snapshot.first)) block()
        }
    }

    /** Replace the current debounce job. Called from the serialized control path. */
    fun replacePendingAttach(delayMs: Long, block: suspend () -> Unit): Job? {
        val token = synchronized(stateLock) {
            if (destroyed) return null
            generation
        }
        val previous = synchronized(stateLock) {
            pendingAttach.also { pendingAttach = null }
        }
        previous?.cancel()

        lateinit var next: Job
        next = scope.launch(start = CoroutineStart.LAZY) {
            try {
                delay(delayMs)
                if (isCurrent(token)) block()
            } finally {
                synchronized(stateLock) {
                    if (pendingAttach === next) pendingAttach = null
                }
            }
        }
        synchronized(stateLock) {
            if (destroyed || generation != token) {
                next.cancel()
                return null
            }
            pendingAttach = next
        }
        next.start()
        return next
    }

    suspend fun cancelPendingAttach() {
        val job = synchronized(stateLock) {
            pendingAttach.also { pendingAttach = null }
        }
        if (job != null && job !== currentCoroutineContext()[Job]) {
            job.cancelAndJoin()
        }
    }

    /** Starts at most one polling worker for the current session. */
    fun launchPolling(block: suspend () -> Unit): Job? {
        lateinit var next: Job
        synchronized(stateLock) {
            if (destroyed || pollingJob?.isActive == true) return null
            next = scope.launch(pollingDispatcher, start = CoroutineStart.LAZY) { block() }
            pollingJob = next
            next.invokeOnCompletion {
                synchronized(stateLock) {
                    if (pollingJob === next) pollingJob = null
                }
            }
        }
        next.start()
        return next
    }

    /**
     * Cancel polling, close resources to unblock bounded USB transfers, then
     * wait for the worker to finish. The self-join guard protects failure paths.
     */
    suspend fun stopPolling(closeResources: suspend () -> Unit) {
        val job = synchronized(stateLock) {
            pollingJob.also { pollingJob = null }
        }
        job?.cancel()
        try {
            closeResources()
        } finally {
            if (job != null && job !== currentCoroutineContext()[Job]) job.join()
        }
    }

    fun temporaryStop(closeResources: suspend () -> Unit): Job =
        createTeardown(permanent = false, closeResources)

    fun destroy(closeResources: suspend () -> Unit): Job =
        createTeardown(permanent = true, closeResources)

    private fun createTeardown(
        permanent: Boolean,
        closeResources: suspend () -> Unit
    ): Job {
        lateinit var teardown: Job
        synchronized(stateLock) {
            if (destroyed) return destroyJob ?: CompletableDeferred(Unit)
            if (permanent) destroyed = true
            generation++
            val previous = teardownBarrier
            teardown = scope.launch(start = CoroutineStart.LAZY) {
                previous?.join()
                cancelPendingAttach()
                stopPolling(closeResources)
            }
            teardownBarrier = teardown
            if (permanent) destroyJob = teardown
        }
        if (permanent) {
            teardown.invokeOnCompletion { scope.cancel() }
        }
        teardown.start()
        return teardown
    }

    private fun isCurrent(token: Long): Boolean =
        synchronized(stateLock) { !destroyed && generation == token }
}
