package org.example.fileindexcore

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe storage for an inverted index mapping tokens to file paths.
 * Provides atomic operations for maintaining token-to-files relationships.
 */
interface IndexStore {
    /**
     * Update the index when a file's tokens change.
     * Removes old tokens that are no longer in the file and adds new ones.
     */
    fun updateFileTokens(path: Path, newTokens: Set<String>, oldTokens: Set<String>? = null)
    
    /**
     * Remove all tokens associated with a file from the index.
     */
    fun removeFile(path: Path): Set<String>
    
    /**
     * Query for files containing the specified token.
     */
    fun query(token: String): Set<Path>
    
    /**
     * Get all tokens currently associated with a file.
     */
    fun getFileTokens(path: Path): Set<String>?
    
    /**
     * Get a snapshot of the entire index for debugging/inspection.
     */
    fun dumpIndex(): Map<String, Set<Path>>
    
    /**
     * Clear all data from the index.
     */
    fun clear()
}

/**
 * Concurrent implementation of InvertedIndexStore using ConcurrentHashMap.
 * This matches the existing behavior of FileIndexService's internal storage.
 */
class ConcurrentIndexStore : IndexStore {
    private val inverted: ConcurrentHashMap<String, MutableSet<Path>> = ConcurrentHashMap()
    private val fileTokens: ConcurrentHashMap<Path, Set<String>> = ConcurrentHashMap()
    
    override fun updateFileTokens(path: Path, newTokens: Set<String>, oldTokens: Set<String>?) {
        val previousTokens = fileTokens.put(path, newTokens) ?: oldTokens
        
        // Remove old tokens that are no longer present
        if (previousTokens != null) {
            for (token in previousTokens - newTokens) {
                inverted[token]?.let { paths ->
                    paths.remove(path)
                    if (paths.isEmpty()) {
                        inverted.remove(token, paths)
                    }
                }
            }
        }
        
        // Add new tokens that weren't present before
        for (token in newTokens - (previousTokens ?: emptySet())) {
            val paths = inverted.computeIfAbsent(token) { ConcurrentHashMap.newKeySet() }
            paths.add(path)
        }
    }
    
    override fun removeFile(path: Path): Set<String> {
        val tokens = fileTokens.remove(path) ?: return emptySet()
        
        for (token in tokens) {
            inverted[token]?.let { paths ->
                paths.remove(path)
                if (paths.isEmpty()) {
                    inverted.remove(token, paths)
                }
            }
        }
        
        return tokens
    }
    
    override fun query(token: String): Set<Path> {
        val paths = inverted[token] ?: return emptySet()
        return paths.toSet() // Return a copy to avoid concurrent modification
    }
    
    override fun getFileTokens(path: Path): Set<String>? {
        return fileTokens[path]
    }
    
    override fun dumpIndex(): Map<String, Set<Path>> {
        // Create a snapshot by copying all entries to avoid concurrent modification
        val result = mutableMapOf<String, Set<Path>>()
        for ((token, paths) in inverted) {
            result[token] = HashSet(paths) // Create defensive copy
        }
        return result
    }
    
    override fun clear() {
        inverted.clear()
        fileTokens.clear()
    }
}
