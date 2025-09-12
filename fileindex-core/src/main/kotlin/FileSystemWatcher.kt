package org.example.fileindexcore

import java.io.IOException
import java.nio.file.*
import java.nio.file.StandardWatchEventKinds.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Event types for file system changes.
 */
sealed class FileSystemEvent {
    abstract val path: Path
    
    data class Created(override val path: Path) : FileSystemEvent()
    data class Modified(override val path: Path) : FileSystemEvent()
    data class Deleted(override val path: Path) : FileSystemEvent()
}

/**
 * Callback interface for handling file system events.
 */
fun interface FileSystemEventHandler {
    /**
     * Handle a file system event.
     */
    fun onEvent(event: FileSystemEvent)
}

/**
 * Manages file system watching for a set of paths.
 */
interface FileSystemWatcher : AutoCloseable {
    /**
     * Start watching the specified paths for changes.
     */
    fun startWatching(paths: List<Path>, handler: FileSystemEventHandler)
    
    /**
     * Stop watching and clean up resources.
     */
    fun stopWatching()
    
    /**
     * Check if the watcher is currently running.
     */
    fun isWatching(): Boolean
}

/**
 * Implementation using Java's WatchService API.
 * Matches the existing behavior of FileIndexService's watch functionality.
 */
class JavaFileSystemWatcher : FileSystemWatcher {
    private var watchService: WatchService? = null
    private val watcherRunning = AtomicBoolean(false)
    private var watcherThread: Thread? = null
    private val keyToDir = ConcurrentHashMap<WatchKey, Path>()
    private var eventHandler: FileSystemEventHandler? = null
    
    override fun startWatching(paths: List<Path>, handler: FileSystemEventHandler) {
        if (watcherRunning.get()) {
            throw IllegalStateException("Watcher is already running")
        }
        
        ensureWatchService()
        eventHandler = handler
        
        paths.forEach { path ->
            if (Files.isDirectory(path)) {
                registerAll(path)
            } else {
                registerParentOfFile(path)
            }
        }
        
        if (watcherRunning.compareAndSet(false, true)) {
            watcherThread = Thread(this::watchLoop, "FileSystemWatcher").apply {
                isDaemon = true
                start()
            }
        }
    }
    
    override fun stopWatching() {
        watcherRunning.set(false)
        watcherThread?.interrupt()
    }
    
    override fun isWatching(): Boolean = watcherRunning.get()
    
    override fun close() {
        stopWatching()
        try {
            watchService?.close()
        } catch (_: IOException) {
            // Ignore close errors
        }
        eventHandler = null
    }
    
    private fun ensureWatchService() {
        if (watchService == null) {
            watchService = FileSystems.getDefault().newWatchService()
        }
    }
    
    private fun register(dir: Path) {
        if (!Files.isDirectory(dir)) return
        val ws = watchService ?: return
        
        val key = dir.register(ws, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
        keyToDir[key] = dir
        
        println("📁 Registered: ${dir.toAbsolutePath()}")
    }
    
    private fun registerAll(start: Path) {
        Files.walk(start).use { paths ->
            paths.filter { Files.isDirectory(it) }
                .forEach { register(it) }
        }
    }
    
    private fun registerParentOfFile(file: Path) {
        val parent = file.parent ?: return
        register(parent)
    }
    
    private fun watchLoop() {
        val ws = watchService ?: return
        val handler = eventHandler ?: return
        
        while (watcherRunning.get()) {
            val key = try {
                ws.take()
            } catch (_: InterruptedException) {
                break
            } catch (_: ClosedWatchServiceException) {
                break
            }
            
            val directory = keyToDir[key]
            if (directory == null) {
                key.reset()
                continue
            }
            
            for (event in key.pollEvents()) {
                val kind = event.kind()
                
                if (kind == OVERFLOW) continue
                
                @Suppress("UNCHECKED_CAST")
                val e = event as WatchEvent<Path>
                val child = directory.resolve(e.context())
                
                val fsEvent = when (kind) {
                    ENTRY_CREATE -> {
                        // If a directory was created, register it for watching
                        if (Files.isDirectory(child)) {
                            registerAll(child)
                        }
                        FileSystemEvent.Created(child)
                    }
                    ENTRY_MODIFY -> FileSystemEvent.Modified(child)
                    ENTRY_DELETE -> FileSystemEvent.Deleted(child)
                    else -> continue
                }
                
                // Notify the handler
                try {
                    handler.onEvent(fsEvent)
                } catch (e: Exception) {
                    // Log error but continue processing events
                    println("Error handling file system event: ${e.message}")
                }
            }
            
            val valid = key.reset()
            if (!valid) {
                keyToDir.remove(key)
            }
        }
    }
}
