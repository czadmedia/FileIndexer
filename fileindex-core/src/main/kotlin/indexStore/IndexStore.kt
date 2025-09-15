package org.example.fileindexcore.indexStore

import java.nio.file.Path

/**
 * Core read operations for inverted index storage.
 * All index store implementations support these query operations.
 */
interface IndexStore {
    /**
     * Query for files containing a specific token.
     */
    fun query(token: String): Set<Path>
    
    /**
     * Query for files containing a sequence of tokens in their exact order.
     * This is the core phrase search functionality.
     */
    fun querySequence(tokens: List<String>): Set<Path>
    
    /**
     * Get all tokens associated with a specific file.
     */
    fun getFileTokens(path: Path): Set<String>?
    
    /**
     * Get a complete dump of the current index state.
     */
    fun dumpIndex(): Map<String, Set<Path>>
    
    /**
     * Remove all tokens associated with a file from the index.
     */
    fun removeFile(path: Path): Set<String>
    
    /**
     * Clear all data from the index.
     */
    fun clear()
}

/**
 * Sealed interface that defines the different types of index operations.
 * Used to ensure exhaustive when clauses and type safety.
 */
sealed interface IndexOperations

/**
 * Operations for simple token-based indexing.
 * Used by implementations that work with token sets without positional information.
 */
interface SimpleIndexOperations : IndexOperations {
    /**
     * Update the index when a file's tokens change.
     * This is the natural method for simple token-based indexing.
     */
    fun updateFileTokens(path: Path, newTokens: Set<String>, oldTokens: Set<String>? = null)
}

/**
 * Operations for positional indexing.
 * Used by implementations that track exact token positions for advanced queries.
 * Includes both read and write operations specific to positional indexing.
 */
interface PositionalIndexOperations : IndexOperations {
    /**
     * Update the index with full positional information.
     * This is the natural method for positional indexing.
     */
    fun updateFileTokensWithPositions(path: Path, newTokenPositions: Map<String, List<Int>>, oldTokens: Set<String>? = null)
}