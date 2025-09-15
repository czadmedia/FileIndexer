package org.example.fileindexcore

import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger
import java.util.logging.Level

/**
 * Handles task execution and thread management for file indexing operations.
 */
interface TaskExecutor : AutoCloseable {
    /**
     * Submit a task for execution.
     */
    fun submit(task: () -> Unit)

    /**
     * Schedule indexing of a file if it can be processed.
     */
    fun scheduleIndex(path: Path, fileProcessor: FileProcessor, indexOperation: (Path) -> Unit)

    /**
     * Get a CompletableFuture that completes when all currently scheduled work finishes.
     * Returns a completed future if no work is currently pending.
     */
    fun getCompletionFuture(): CompletableFuture<Void>

    /**
     * Shutdown the executor and clean up resources.
     */
    fun shutdown()
}

/**
 * Thread-safe implementation using a fixed thread pool with explicit file tracking.
 * Uses concurrent sets to track pending files for better visibility and debugging.
 * Provides CompletableFuture-based coordination for batch completion.
 */
class ThreadPoolTaskExecutor(
    threadCount: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
) : TaskExecutor {

    private val logger = Logger.getLogger(ThreadPoolTaskExecutor::class.java.name)
    private val pool: ExecutorService = Executors.newFixedThreadPool(threadCount)

    private val filesBeingProcessed = ConcurrentHashMap.newKeySet<Path>()
    private val filesNeedingReprocessing = ConcurrentHashMap<Path, Pair<FileProcessor, (Path) -> Unit>>()

    private val pendingFiles = ConcurrentHashMap.newKeySet<Path>()
    private val currentBatch = AtomicReference<CompletableFuture<Void>>(
        CompletableFuture.completedFuture(null)
    )

    private val batchLock = Object()

    override fun submit(task: () -> Unit) {
        pool.submit(task)
    }

    override fun scheduleIndex(path: Path, fileProcessor: FileProcessor, indexOperation: (Path) -> Unit) {
        if (!fileProcessor.canProcess(path)) return

        if (!filesBeingProcessed.add(path)) {
            filesNeedingReprocessing[path] = Pair(fileProcessor, indexOperation)
            logger.log(Level.FINE, "Queueing file for reprocessing after current processing completes: $path")
            return
        }

        val batchFuture = synchronized(batchLock) {
            pendingFiles.add(path)
            if (pendingFiles.size == 1) {
                // First file in new batch - create new completion future
                val newBatch = CompletableFuture<Void>()
                currentBatch.set(newBatch)
                newBatch
            } else {
                currentBatch.get()
            }
        }

        pool.submit {
            try {
                safeIndex(path, indexOperation)
            } finally {
                filesBeingProcessed.remove(path)

                val reprocessInfo = filesNeedingReprocessing.remove(path)
                if (reprocessInfo != null) {
                    scheduleIndex(path, reprocessInfo.first, reprocessInfo.second)
                } else {
                    synchronized(batchLock) {
                        pendingFiles.remove(path)

                        if (pendingFiles.isEmpty()) {
                            batchFuture.complete(null)
                        }
                    }
                }
            }
        }
    }

    override fun getCompletionFuture(): CompletableFuture<Void> {
        return synchronized(batchLock) {
            if (pendingFiles.isNotEmpty()) {
                currentBatch.get()
            } else {
                CompletableFuture.completedFuture(null)
            }
        }
    }

    override fun shutdown() {
        pool.shutdownNow()
    }

    override fun close() {
        shutdown()
    }

    private fun safeIndex(path: Path, indexOperation: (Path) -> Unit) {
        try {
            indexOperation(path)
        } catch (e: Throwable) {
            logger.log(Level.WARNING, "Error processing file for indexing: $path", e)
        }
    }
}
