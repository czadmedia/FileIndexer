package org.example.fileindexcore

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import java.util.logging.Logger
import java.util.logging.Level

/**
 * Handles reading and tokenizing files for indexing.
 * Separates file I/O concerns from the main indexing logic.
 */
interface FileProcessor {
    /**
     * Process a file and return its tokens, or null if the file cannot be processed.
     * Returns null for files that don't exist, aren't regular files, or have processing errors.
     */
    fun processFile(path: Path): Set<String>?
    
    /**
     * Check if a file can be processed (exists and is a regular file).
     */
    fun canProcess(path: Path): Boolean
}

/**
 * Default implementation that reads text files and tokenizes their content.
 * Uses the provided tokenizer to extract tokens from file content.
 */
class TextFileProcessor(
    private val tokenizer: Tokenizer
) : FileProcessor {
    
    private val logger = Logger.getLogger(TextFileProcessor::class.java.name)
    
    override fun processFile(path: Path): Set<String>? {
        if (!canProcess(path)) return null
        
        return try {
            val tokens = mutableSetOf<String>()
            val session = tokenizer.createSession()
            
            Files.newBufferedReader(path).use { reader ->
                reader.forEachLine { line ->
                    session.processText(line).forEach { token ->
                        tokens.add(token)
                    }
                }
            }
            
            // Finalize to get any remaining tokens
            session.finalize().forEach { token ->
                tokens.add(token)
            }
            
            tokens
        } catch (e: Throwable) {
            logger.log(Level.WARNING, "Failed to process file: $path", e)
            null
        }
    }
    
    override fun canProcess(path: Path): Boolean {
        return Files.exists(path) && path.isRegularFile()
    }
}
