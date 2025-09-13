package org.example.fileindexcore

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Simple inverted index implementation using basic token-to-files mapping.
 * Memory efficient but requires file scanning for sequence queries.
 * 
 * Best for:
 * - Memory-constrained environments
 * - Infrequent sequence queries
 * - Simple use cases where basic search is sufficient
 */
class SimpleIndexStore(
    private val tokenizer: Tokenizer = SimpleWordTokenizer(),
    private val fileProcessor: FileProcessor = TextFileProcessor(tokenizer)
) : IndexStore, SimpleIndexOperations {
    
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
    
    override fun querySequence(tokens: List<String>): Set<Path> {
        if (tokens.isEmpty()) return emptySet()
        if (tokens.size == 1) return query(tokens[0])
        
        // Find candidate files that contain ALL tokens in the sequence
        val candidateFiles = tokens.map { query(it) }.reduceOrNull { acc, paths -> 
            acc.intersect(paths) 
        } ?: return emptySet()
        
        if (candidateFiles.isEmpty()) return emptySet()
        
        // Verify sequence exists in candidate files using file scanning
        return verifySequenceInFiles(candidateFiles, tokens)
    }
    
    /**
     * Verify that the target sequence exists in the candidate files by scanning file content.
     * This is the fallback method used when positional information is not available.
     */
    private fun verifySequenceInFiles(candidateFiles: Set<Path>, targetSequence: List<String>): Set<Path> {
        val verifiedFiles = mutableSetOf<Path>()
        
        for (filePath in candidateFiles) {
            if (fileProcessor.canProcess(filePath)) {
                try {
                    val session = tokenizer.createSession()
                    val sequenceVerifier = SequenceVerifier(targetSequence)
                    var found = false
                    
                    Files.newBufferedReader(filePath).use { reader ->
                        reader.forEachLine { line ->
                            if (!found) {
                                val tokens = session.processText(line)
                                if (sequenceVerifier.processTokens(tokens)) {
                                    verifiedFiles.add(filePath)
                                    found = true
                                }
                            }
                        }
                        
                        if (!found) {
                            val finalTokens = session.finalize()
                            if (sequenceVerifier.processTokens(finalTokens)) {
                                verifiedFiles.add(filePath)
                            }
                        }
                    }
                } catch (e: Exception) {
                    continue // Skip files that can't be read
                }
            }
        }
        return verifiedFiles
    }
    
    override fun getFileTokens(path: Path): Set<String>? {
        return fileTokens[path]
    }
    
    override fun dumpIndex(): Map<String, Set<Path>> {
        // Create truly immutable map using Kotlin's buildMap - no Java interop needed
        return buildMap {
            for ((token, paths) in inverted) {
                put(token, paths.toSet()) // Creates immutable defensive copy
            }
        }
    }
    
    override fun clear() {
        inverted.clear()
        fileTokens.clear()
    }
}

/**
 * Efficient sequence verifier using a state machine approach.
 * Only checks for matches when encountering potential sequence starts.
 * Time complexity: O(tokens) with early termination.
 */
private class SequenceVerifier(private val targetSequence: List<String>) {
    private var matchPosition = 0
    
    fun processTokens(tokens: Sequence<String>): Boolean {
        for (token in tokens) {
            when {
                token == targetSequence[matchPosition] -> {
                    matchPosition++
                    if (matchPosition == targetSequence.size) {
                        return true // Found complete sequence!
                    }
                }
                token == targetSequence[0] -> {
                    matchPosition = 1 // Restart from position 1
                }
                else -> {
                    matchPosition = 0 // Reset to beginning
                }
            }
        }
        return false
    }
}
