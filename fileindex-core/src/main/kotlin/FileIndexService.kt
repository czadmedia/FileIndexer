package org.example.fileindexcore

import java.nio.file.*

/**
 * Thread-safe file index with optional filesystem watching. Holds an inverted index
 * (token -> files) from the provided paths. Allows for concurrent queries. Reacts to
 * CREATE/MODIFY/DELETE events in watched paths.
 */
class FileIndexService(
    private val tokenizer: Tokenizer = SimpleWordTokenizer(),
    private val indexStore: IndexStore = ConcurrentIndexStore(),
    private val fileProcessor: FileProcessor = TextFileProcessor(tokenizer),
    private val pathWalker: PathWalker = RecursivePathWalker(),
    private val fileSystemWatcher: FileSystemWatcher = JavaFileSystemWatcher(),
    private val taskExecutor: TaskExecutor = ThreadPoolTaskExecutor()
) : AutoCloseable {

    fun index(paths: List<Path>) {
        println("Index roots: " + paths.map { it.toAbsolutePath() })
        paths.forEach { path ->
            for (p in pathWalker.walk(path)) {
                taskExecutor.scheduleIndex(p, fileProcessor, this::indexFile)
            }
        }
    }

    fun startWatching(paths: List<Path>) {
        println("Start Watching roots: " + paths.map { it.toAbsolutePath() })
        fileSystemWatcher.startWatching(paths, this::handleFileSystemEvent)
    }

    fun query(input: String): Set<Path> {
        if (input.isBlank()) return emptySet()

        val norm = tokenizer.normalizeSingleToken(input)
        return indexStore.query(norm)
    }

    fun dumpIndex(): Map<String, Set<Path>> = indexStore.dumpIndex()

    override fun close() {
        fileSystemWatcher.close()
        taskExecutor.close()
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

    private fun handleFileSystemEvent(event: FileSystemEvent) {
        when (event) {
            is FileSystemEvent.Created -> {
                if (Files.isDirectory(event.path)) {
                    // Index all files in the newly created directory
                    for (p in pathWalker.walk(event.path)) {
                        taskExecutor.scheduleIndex(p, fileProcessor, this::indexFile)
                    }
                } else {
                    taskExecutor.scheduleIndex(event.path, fileProcessor, this::indexFile)
                }
            }
            is FileSystemEvent.Modified -> {
                taskExecutor.scheduleIndex(event.path, fileProcessor, this::indexFile)
            }
            is FileSystemEvent.Deleted -> {
                removeFile(event.path)
            }
        }
    }

}