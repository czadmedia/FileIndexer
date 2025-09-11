package org.example.fileindexcore

import java.nio.file.Files
import java.nio.file.Path

/**
 * Handles traversal of file system paths to discover files for indexing.
 * Separates directory walking logic from the main indexing service.
 */
interface PathWalker {
    /**
     * Walk the given path and return all paths that should be processed.
     * If path is a file, returns just that file.
     * If path is a directory, returns all paths in the directory tree.
     */
    fun walk(path: Path): Sequence<Path>
}

/**
 * Default implementation that walks directories recursively and returns all paths.
 * This matches the existing behavior of FileIndexService's walk method.
 */
class RecursivePathWalker : PathWalker {
    
    override fun walk(path: Path): Sequence<Path> = sequence {
        when {
            Files.isRegularFile(path) -> yield(path)
            Files.isDirectory(path) -> {
                Files.walk(path).use { pathStream ->
                    for (p in pathStream) {
                        yield(p)
                    }
                }
            }
        }
    }
}
