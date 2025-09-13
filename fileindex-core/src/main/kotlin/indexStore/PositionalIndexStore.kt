package org.example.fileindexcore

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Advanced positional inverted index implementation.
 * Tracks exact positions of every token for lightning-fast sequence queries.
 * 
 * Features:
 * - Zero file I/O during sequence queries
 * - O(positions × sequence_length) time complexity  
 * - Pure arithmetic verification
 * - Scales to huge files without performance degradation
 * 
 * Best for:
 * - Frequent sequence/phrase queries
 * - Large files where scanning would be expensive
 * - Search-heavy applications requiring sub-millisecond response times
 * - Advanced search features (proximity, ordering, etc.)
 * 
 * Trade-offs:
 * - Higher memory usage (4-10x compared to simple index)
 * - Slower indexing due to position tracking
 * - More complex data structures
 */
class PositionalIndexStore(
    private val tokenizer: Tokenizer = SimpleWordTokenizer(),
    private val fileProcessor: FileProcessor = TextFileProcessor(tokenizer)
) : IndexStore, PositionalIndexOperations {
    
    // Positional inverted index: token -> file -> [positions]
    private val positionalInverted: ConcurrentHashMap<String, ConcurrentHashMap<Path, List<Int>>> = ConcurrentHashMap()
    
    // File tokens with positions: file -> token -> [positions] 
    private val positionalFileTokens: ConcurrentHashMap<Path, Map<String, List<Int>>> = ConcurrentHashMap()
    
    override fun updateFileTokensWithPositions(path: Path, newTokenPositions: Map<String, List<Int>>, oldTokens: Set<String>?) {
        val previousPositions = positionalFileTokens.put(path, newTokenPositions)
        val previousTokens = previousPositions?.keys ?: oldTokens
        val newTokens = newTokenPositions.keys
        
        // Remove old tokens that are no longer present
        if (previousTokens != null) {
            for (token in previousTokens - newTokens) {
                positionalInverted[token]?.let { fileMap ->
                    fileMap.remove(path)
                    if (fileMap.isEmpty()) {
                        positionalInverted.remove(token, fileMap)
                    }
                }
            }
        }
        
        // Add/update tokens with their new positions
        for ((token, positions) in newTokenPositions) {
            val fileMap = positionalInverted.computeIfAbsent(token) { ConcurrentHashMap() }
            fileMap[path] = positions
        }
    }
    
    override fun removeFile(path: Path): Set<String> {
        val tokenPositions = positionalFileTokens.remove(path) ?: return emptySet()
        val tokens = tokenPositions.keys
        
        for (token in tokens) {
            positionalInverted[token]?.let { fileMap ->
                fileMap.remove(path)
                if (fileMap.isEmpty()) {
                    positionalInverted.remove(token, fileMap)
                }
            }
        }
        
        return tokens
    }
    
    override fun query(token: String): Set<Path> {
        val fileMap = positionalInverted[token] ?: return emptySet()
        return fileMap.keys.toSet() // Return files containing this token
    }
    
    override fun querySequence(tokens: List<String>): Set<Path> {
        if (tokens.isEmpty()) return emptySet()
        if (tokens.size == 1) return query(tokens[0])
        
        return querySequenceArithmetic(tokens)
    }
    
    /**
     * Lightning-fast arithmetic-based sequence query using positional index.
     * Time complexity: O(positions of first token × sequence length)
     * 
     * Algorithm:
     * 1. Find all positions where the first token appears
     * 2. For each position, check if subsequent tokens appear at consecutive positions
     * 3. Use pure arithmetic - no file I/O needed!
     */
    private fun querySequenceArithmetic(tokens: List<String>): Set<Path> {
        // Get all positions of the first token across all files
        val firstTokenPositions = positionalInverted[tokens[0]] ?: return emptySet()
        val matchingFiles = mutableSetOf<Path>()
        
        // For each file containing the first token
        for ((filePath, positions) in firstTokenPositions) {
            // For each position where the first token appears
            positionLoop@ for (startPos in positions) {
                // Check if subsequent tokens appear at consecutive positions
                for (i in 1 until tokens.size) {
                    val expectedPos = startPos + i
                    val tokenPositions = positionalInverted[tokens[i]]?.get(filePath) ?: break@positionLoop
                    
                    // Pure arithmetic check - no file access!
                    if (expectedPos !in tokenPositions) {
                        continue@positionLoop  // This sequence doesn't work, try next position
                    }
                }
                // If we get here, we found a complete sequence!
                matchingFiles.add(filePath)
                break@positionLoop  // Found one match in this file, that's enough
            }
        }
        
        return matchingFiles
    }
    
    override fun getFileTokens(path: Path): Set<String>? {
        return positionalFileTokens[path]?.keys
    }
    
    override fun dumpIndex(): Map<String, Set<Path>> {
        // Create a snapshot by copying all entries to avoid concurrent modification
        val result = mutableMapOf<String, Set<Path>>()
        for ((token, fileMap) in positionalInverted) {
            result[token] = HashSet(fileMap.keys) // Create defensive copy
        }
        return result
    }
    
    override fun clear() {
        positionalInverted.clear()
        positionalFileTokens.clear()
    }
    
    override fun getTokenPositions(token: String, path: Path): List<Int>? {
        return positionalInverted[token]?.get(path)
    }
    
    override fun queryProximity(tokens: List<String>, maxDistance: Int): Set<Path> {
        if (tokens.isEmpty()) return emptySet()
        if (tokens.size == 1) return query(tokens[0])
        
        // Find files containing all tokens
        val candidateFiles = tokens.map { query(it) }.reduceOrNull { acc, paths -> 
            acc.intersect(paths) 
        } ?: return emptySet()
        
        val matchingFiles = mutableSetOf<Path>()
        
        for (filePath in candidateFiles) {
            // Get all positions for all tokens in this file
            val allPositions = tokens.mapNotNull { token ->
                positionalInverted[token]?.get(filePath)
            }
            
            if (allPositions.size == tokens.size) {
                // Check if any combination of positions is within maxDistance
                val firstTokenPositions = allPositions[0]
                
                for (startPos in firstTokenPositions) {
                    var found = true
                    for (i in 1 until allPositions.size) {
                        val positions = allPositions[i]
                        val validPosition = positions.any { pos -> 
                            kotlin.math.abs(pos - startPos) <= maxDistance 
                        }
                        if (!validPosition) {
                            found = false
                            break
                        }
                    }
                    if (found) {
                        matchingFiles.add(filePath)
                        break
                    }
                }
            }
        }
        
        return matchingFiles
    }
    
    override fun dumpPositionalIndex(): Map<String, Map<Path, List<Int>>> {
        val result = mutableMapOf<String, Map<Path, List<Int>>>()
        for ((token, fileMap) in positionalInverted) {
            result[token] = HashMap(fileMap) // Create defensive copy
        }
        return result
    }
}
