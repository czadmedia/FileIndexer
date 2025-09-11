package org.example.fileindexcore

import java.io.IOException
import java.nio.file.*
import java.nio.file.StandardWatchEventKinds.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thread-safe file index with optional filesystem watching. Holds an inverted index
 * (token -> files) from the provided paths. Allows for concurrent queries. Reacts to
 * CREATE/MODIFY/DELETE events in watched paths.
 */
class FileIndexService(
    private val tokenizer: Tokenizer = SimpleWordTokenizer(),
    threadCount: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(2),
    private val indexStore: IndexStore = ConcurrentIndexStore(),
    private val fileProcessor: FileProcessor = TextFileProcessor(tokenizer),
    private val pathWalker: PathWalker = RecursivePathWalker()
) : AutoCloseable {

    private val pool: ExecutorService = Executors.newFixedThreadPool(threadCount)
    private var watchService: WatchService? = null
    private val watcherRunning = AtomicBoolean(false)
    private var watcherThread: Thread? = null

    private val keyToDir = ConcurrentHashMap<WatchKey, Path>()

    fun index(paths: List<Path>) {
        println("Index roots: " + paths.map { it.toAbsolutePath() })
        paths.forEach { path ->
            for (p in pathWalker.walk(path)) {
                scheduleIndex(p)
            }
        }
    }

    fun startWatching(paths: List<Path>) {
        println("Start Watching roots: " + paths.map { it.toAbsolutePath() })
        ensureWatchService()

        paths.forEach { path ->
            if (Files.isDirectory(path)) registerAll(path) else registerParentOfFile(
                path
            )
        }

        if (watcherRunning.compareAndSet(false, true)) {
            watcherThread = Thread(this::watchLoop, "FileIndexService-Watcher").apply {
                isDaemon = true; start()
            }
        }
    }

    fun query(input: String): Set<Path> {
        if (input.isBlank()) return emptySet()

        val norm = tokenizer.normalizeSingleToken(input)
        return indexStore.query(norm)
    }

    fun dumpIndex(): Map<String, Set<Path>> = indexStore.dumpIndex()

    override fun close() {
        watcherRunning.set(false)
        try {
            watchService?.close()
        } catch (_: IOException) {
        }
        watcherThread?.interrupt()
        pool.shutdownNow()
    }

    private fun scheduleIndex(path: Path) {
        if (!fileProcessor.canProcess(path)) return
        pool.submit { safeIndex(path) }
    }

    private fun safeIndex(path: Path) = try {
        indexFile(path)
    } catch (_: Throwable) {
    }

    private fun indexFile(path: Path) {
        val newTokens = fileProcessor.processFile(path)
        if (newTokens == null) {
            // File cannot be processed, remove it from index
            removeFile(path)
            return
        }

        val oldTokens = indexStore.getFileTokens(path)
        indexStore.updateFileTokens(path, newTokens, oldTokens)
    }

    private fun removeFile(path: Path) {
        indexStore.removeFile(path)
    }


    private fun ensureWatchService() {
        if (watchService == null) watchService = FileSystems.getDefault().newWatchService()
    }

    private fun register(dir: Path) {
        if (!Files.isDirectory(dir)) return
        val ws = watchService ?: return

        val key = dir.register(ws, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
        keyToDir[key] = dir

        println("📁 Registered: ${dir.toAbsolutePath()}")
    }

    private fun registerAll(start: Path) {
        Files.walk(start)
            .use { paths -> paths.filter { Files.isDirectory(it) }.forEach { register(it) } }
    }

    private fun registerParentOfFile(file: Path) {
        val parent = file.parent ?: return
        register(parent)
    }

    private fun watchLoop() {
        val ws = watchService ?: return
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
                key.reset(); continue
            }

            for (event in key.pollEvents()) {
                val kind = event.kind()

                if (kind == OVERFLOW) continue

                @Suppress("UNCHECKED_CAST")
                val e = event as WatchEvent<Path>
                val child = directory.resolve(e.context())

                when (kind) {
                    ENTRY_CREATE -> {
                        if (Files.isDirectory(child)) {
                            registerAll(child)
                            Files.walk(child).use { pathStream ->
                                pathStream.filter { Files.isRegularFile(it) }
                                    .forEach { scheduleIndex(it) }
                            }
                        } else scheduleIndex(child)
                    }
                    ENTRY_MODIFY -> scheduleIndex(child)
                    ENTRY_DELETE -> removeFile(child)
                }
            }

            val valid = key.reset()
            if (!valid) keyToDir.remove(key)
        }
    }
}