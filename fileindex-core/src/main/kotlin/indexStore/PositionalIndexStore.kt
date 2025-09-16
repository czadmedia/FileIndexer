package org.example.fileindexcore.indexStore

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
 * - Higher memory usage
 * - Slower indexing due to position tracking
 * - More complex data structures
 */
class PositionalIndexStore : IndexStore, PositionalIndexOperations {

    private val positionalInverted: ConcurrentHashMap<String, ConcurrentHashMap<Path, List<Int>>> = ConcurrentHashMap()
    private val positionalFileTokens: ConcurrentHashMap<Path, Map<String, List<Int>>> = ConcurrentHashMap()

    override fun updateFileTokensWithPositions(
        path: Path,
        newTokenPositions: Map<String, List<Int>>,
        oldTokens: Set<String>?
    ) {
        val previousPositions = positionalFileTokens.put(path, newTokenPositions)
        val previousTokens = previousPositions?.keys ?: oldTokens
        val newTokens = newTokenPositions.keys

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
        return fileMap.keys.toSet()
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
        val firstTokenPositions = positionalInverted[tokens[0]] ?: return emptySet()
        val matchingFiles = mutableSetOf<Path>()

        for ((filePath, positions) in firstTokenPositions) {
            // for each position where first token appears
            positionLoop@ for (startPos in positions) {
                // check if subsequent tokens appear at consecutive positions
                for (i in 1 until tokens.size) {
                    val expectedPos = startPos + i
                    val tokenPositions = positionalInverted[tokens[i]]?.get(filePath) ?: break@positionLoop

                    if (expectedPos !in tokenPositions) {
                        continue@positionLoop  // This sequence doesn't work, try next position
                    }
                }

                // complete sequence found
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
        val result = mutableMapOf<String, Set<Path>>()
        for ((token, fileMap) in positionalInverted) {
            result[token] = HashSet(fileMap.keys)
        }
        return result
    }

    override fun clear() {
        positionalInverted.clear()
        positionalFileTokens.clear()
    }
}
