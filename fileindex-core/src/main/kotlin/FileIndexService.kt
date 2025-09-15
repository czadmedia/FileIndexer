package org.example.fileindexcore

import org.example.fileindexcore.indexStore.IndexStore
import org.example.fileindexcore.indexStore.PositionalIndexOperations
import org.example.fileindexcore.indexStore.PositionalIndexStore
import org.example.fileindexcore.indexStore.SimpleIndexOperations
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

/**
 * Thread-safe file index with optional filesystem watching. Holds an inverted index
 * (token -> files) from the provided paths. Allows for concurrent queries. Reacts to
 * CREATE/MODIFY/DELETE events in watched paths.
 */
class FileIndexService(
    private val tokenizer: Tokenizer = SimpleWordTokenizer(),
    private val fileProcessor: FileProcessor = TextFileProcessor(tokenizer),
    private val indexStore: IndexStore = PositionalIndexStore(),
    private val pathWalker: PathWalker = RecursivePathWalker(),
    private val fileSystemWatcher: FileSystemWatcher = JavaFileSystemWatcher(),
    private val taskExecutor: TaskExecutor = ThreadPoolTaskExecutor()
) : AutoCloseable {

    private val logger = Logger.getLogger(FileIndexService::class.java.name)

    fun index(paths: List<Path>) {
        logger.info("Indexing roots: ${paths.map { it.toAbsolutePath() }}")
        paths.forEach { path ->
            for (p in pathWalker.walk(path)) {
                taskExecutor.scheduleIndex(p, fileProcessor, this::indexFile)
            }
        }
    }

    fun startWatching(paths: List<Path>) {
        logger.info("Starting file system watching for roots: ${paths.map { it.toAbsolutePath() }}")
        fileSystemWatcher.startWatching(paths, this::handleFileSystemEvent)
    }

    /**
     * Query for files containing a specific token with guaranteed up-to-date results.
     * Returns a CompletableFuture that completes when indexing finishes and query is executed.
     */
    fun query(input: String): CompletableFuture<Set<Path>> {
        if (input.isBlank()) {
            return CompletableFuture.completedFuture(emptySet())
        }

        return taskExecutor.getCompletionFuture().thenApply {
            val norm = tokenizer.normalizeSingleToken(input)
            indexStore.query(norm)
        }
    }

    /**
     * Query for files containing the specified sequence of tokens in exact order.
     * Performs phrase search with guaranteed up-to-date results.
     */
    fun querySequence(phrase: String): CompletableFuture<Set<Path>> {
        if (phrase.isBlank()) {
            return CompletableFuture.completedFuture(emptySet())
        }

        return taskExecutor.getCompletionFuture().thenApply {
            // Tokenize the input phrase to get the sequence
            val tokens = tokenizer.tokens(phrase).toList()
            if (tokens.isEmpty()) emptySet()
            else indexStore.querySequence(tokens)
        }
    }

    /**
     * Query for files containing the specified sequence of tokens in exact order.
     * Direct token sequence version with guaranteed up-to-date results.
     */
    fun querySequence(tokens: List<String>): CompletableFuture<Set<Path>> {
        if (tokens.isEmpty()) {
            return CompletableFuture.completedFuture(emptySet())
        }

        return taskExecutor.getCompletionFuture().thenApply {
            // Normalize tokens using the tokenizer's normalization
            val normalizedTokens = tokens.map { tokenizer.normalizeSingleToken(it) }
            indexStore.querySequence(normalizedTokens)
        }
    }

    fun dumpIndex(): Map<String, Set<Path>> = indexStore.dumpIndex()

    private fun indexFile(path: Path) {
        when (indexStore) {
            is PositionalIndexOperations -> {
                val newTokenPositions = fileProcessor.processFileWithPositions(path)
                if (newTokenPositions == null) {
                    indexStore.removeFile(path)
                    return
                }
                val oldTokens = indexStore.getFileTokens(path)
                indexStore.updateFileTokensWithPositions(path, newTokenPositions, oldTokens)
            }

            is SimpleIndexOperations -> {
                val newTokens = fileProcessor.processFile(path)
                if (newTokens == null) {
                    indexStore.removeFile(path)
                    return
                }
                val oldTokens = indexStore.getFileTokens(path)
                indexStore.updateFileTokens(path, newTokens, oldTokens)
            }
        }
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
                indexStore.removeFile(event.path)
            }
        }
    }

    override fun close() {
        fileSystemWatcher.close()
        taskExecutor.close()
    }
}