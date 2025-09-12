package org.example.fileindexcore

/**
 * Interface for tokenizing text into individual tokens.
 * Supports both simple stateless tokenization and advanced stateful tokenization.
 */
interface Tokenizer {
    /**
     * Tokenize a piece of text into a sequence of normalized tokens.
     * This method should be stateless for simple tokenizers.
     */
    fun tokens(text: String): Sequence<String>
    
    /**
     * Normalize a single token (lowercase, trim, etc.).
     * Can be overridden for custom normalization strategies.
     */
    fun normalizeSingleToken(token: String): String = token.lowercase().trim()
    
    /**
     * Create a tokenization session for stateful/streaming tokenization.
     * Simple tokenizers can return a basic session that just calls tokens().
     */
    fun createSession(): TokenizationSession = SimpleTokenizationSession(this)
}

/**
 * Represents a tokenization session that can maintain state across multiple text chunks.
 * Useful for processing files line-by-line while maintaining context.
 */
interface TokenizationSession {
    /**
     * Process a chunk of text and return any complete tokens found.
     * May hold partial tokens in internal state for combination with future chunks.
     */
    fun processText(text: String): Sequence<String>
    
    /**
     * Signal the end of input and return any remaining tokens.
     * Should be called after all text has been processed.
     */
    fun finalize(): Sequence<String>
}

/**
 * Basic session implementation that delegates to the stateless tokenizer.
 * Used by simple tokenizers that don't need state management.
 */
private class SimpleTokenizationSession(private val tokenizer: Tokenizer) : TokenizationSession {
    override fun processText(text: String): Sequence<String> = tokenizer.tokens(text)
    override fun finalize(): Sequence<String> = emptySequence()
}

class SimpleWordTokenizer : Tokenizer {
    private val split = Regex("[^\\p{L}\\p{N}]+")
    override fun tokens(text: String): Sequence<String> = sequence {
        if (text.isEmpty()) return@sequence
        for (token in text.split(split)) {
            if (token.isBlank()) continue

            val normalizedToken = normalizeSingleToken(token)
            if (normalizedToken.isNotEmpty()) yield(normalizedToken)
        }
    }
}