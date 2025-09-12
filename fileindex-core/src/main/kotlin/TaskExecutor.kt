package org.example.fileindexcore

import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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
    
    override fun submit(task: () -> Unit) {
        pool.submit(task)
    }
    
    override fun scheduleIndex(path: Path, fileProcessor: FileProcessor, indexOperation: (Path) -> Unit) {
        if (!fileProcessor.canProcess(path)) return
        pool.submit {
            safeIndex(path, indexOperation)
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
