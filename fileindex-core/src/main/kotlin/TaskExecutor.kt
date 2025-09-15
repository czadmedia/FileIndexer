package org.example.fileindexcore

import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
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
     * Shutdown the executor and clean up resources.
     */
    fun shutdown()
}

/**
 * Default implementation using a fixed thread pool.
 * Matches the existing behavior of FileIndexService's thread management.
 */
class ThreadPoolTaskExecutor(
    threadCount: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
) : TaskExecutor {

    private val logger = Logger.getLogger(ThreadPoolTaskExecutor::class.java.name)
    private val pool: ExecutorService = Executors.newFixedThreadPool(threadCount)

    private val filesBeingProcessed = ConcurrentHashMap.newKeySet<Path>()
    private val filesNeedingReprocessing = ConcurrentHashMap<Path, Pair<FileProcessor, (Path) -> Unit>>()

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

        pool.submit {
            safeIndex(path, indexOperation)

            filesBeingProcessed.remove(path)

            val reprocessInfo = filesNeedingReprocessing.remove(path)
            if (reprocessInfo != null) {
                logger.log(Level.FINE, "Reprocessing file with latest changes: $path")
                scheduleIndex(path, reprocessInfo.first, reprocessInfo.second)
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
