import org.example.fileindexcore.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TokenizerTest {
    @Test
    fun `empty string yields no tokens`() {
        val tokens = SimpleWordTokenizer().tokens("").toList()

        assertTrue(tokens.isEmpty())
    }

    @Test
    fun `whitespace-only yields no tokens`() {
        val tokens = SimpleWordTokenizer().tokens(
            "   \t \n" +
                    " "
        ).toList()

        assertTrue(tokens.isEmpty())
    }

    @Test
    fun `basic splitting on punctuation and spaces works properly`() {
        val tokens = SimpleWordTokenizer().tokens("Hello, world! 123\tfoo-bar").toList()

        assertEquals(listOf("hello", "world", "123", "foo", "bar"), tokens)
    }

    @Test
    fun `lowercases tokens`() {
        val tokens = SimpleWordTokenizer().tokens("HeLLo WoRLD").toList()

        assertEquals(listOf("hello", "world"), tokens)
    }

    @Test
    fun `multiple separators collapse works properly`() {
        val tokens = SimpleWordTokenizer().tokens("one---two,,,three   four").toList()

        assertEquals(listOf("one", "two", "three", "four"), tokens)
    }

    @Test
    fun `unicode letters and digits are preserved`() {
        val tokens = SimpleWordTokenizer().tokens("Żółć jest zolta 123 абв").toList()

        assertEquals(listOf("żółć", "jest", "zolta", "123", "абв"), tokens)
    }

    @Test
    fun `treats numbers as tokens`() {
        val tokens = SimpleWordTokenizer().tokens("version 2.0 build_1234").toList()

        assertEquals(listOf("version", "2", "0", "build", "1234"), tokens)
    }

    @Test
    fun `ignores leading and trailing separators`() {
        val tokens = SimpleWordTokenizer().tokens(",,; hello ;;,").toList()

        assertEquals(listOf("hello"), tokens)
    }

    @Test
    fun `ignores consecutive delimiters around empty fragments`() {
        val tokens = SimpleWordTokenizer().tokens("a,, , ,b").toList()

        assertEquals(listOf("a", "b"), tokens)
    }

    @Test
    fun `simple tokenizer works as before - backward compatibility`() {
        val tokenizer = SimpleWordTokenizer()

        val text = "Hello world! This is a test."
        val tokens = tokenizer.tokens(text).toSet()

        assertEquals(setOf("hello", "world", "this", "is", "a", "test"), tokens)
    }

    @Test
    fun `simple tokenizer works with session API`() {
        val tokenizer = SimpleWordTokenizer()
        val session = tokenizer.createSession()

        val tokens = mutableSetOf<String>()
        tokens.addAll(session.processText("Hello world!"))
        tokens.addAll(session.processText("This is a test."))
        tokens.addAll(session.finalize())

        assertEquals(setOf("hello", "world", "this", "is", "a", "test"), tokens)
    }

    @Test
    fun `custom stateful tokenizer - word boundary preservation`() {
        val tokenizer = WordBoundaryPreservingTokenizer()
        val session = tokenizer.createSession()
        val tokens = mutableSetOf<String>()
        
        tokens.addAll(session.processText("This is a hyphen-"))
        tokens.addAll(session.processText("ated word example."))
        tokens.addAll(session.processText("Another brok"))
        tokens.addAll(session.processText("en word here."))
        tokens.addAll(session.finalize())

        assertTrue(tokens.contains("hyphenated"), "Should combine hyphenated words")
        assertTrue(tokens.contains("broken"), "Should combine broken words")
        assertTrue(tokens.contains("this"), "Should include normal words")
    }

    @Test
    fun `custom regex tokenizer with different pattern`() {
        val emailTokenizer = EmailTokenizer()

        val text = "Contact us at support@example.com or admin@test.org for help."
        val tokens = emailTokenizer.tokens(text).toSet()

        assertEquals(setOf("support@example.com", "admin@test.org"), tokens)
    }

    @Test
    fun `custom normalization strategy`() {
        val uppercaseTokenizer = UppercaseTokenizer()

        val text = "Hello World Test"
        val tokens = uppercaseTokenizer.tokens(text).toSet()

        assertEquals(setOf("HELLO", "WORLD", "TEST"), tokens)
    }
}

/**
 * Example of a stateful tokenizer that preserves word boundaries across chunks.
 * Demonstrates how the session-based API enables complex tokenization strategies.
 */
private class WordBoundaryPreservingTokenizer : Tokenizer {
    override fun tokens(text: String): Sequence<String> =
        SimpleWordTokenizer().tokens(text)

    override fun createSession(): TokenizationSession =
        WordBoundarySession()

    private class WordBoundarySession : TokenizationSession {
        private var pendingWord: String? = null
        private val baseTokenizer = SimpleWordTokenizer()

        override fun processText(text: String): Sequence<String> {
            if (text.isEmpty()) return emptySequence()

            var processedText = text
            val tokens = mutableListOf<String>()

            // Handle pending word from previous chunk
            pendingWord?.let { pending ->
                // Simple heuristic: if text starts with letter and pending ends with letter or hyphen
                if (text.first().isLetter() && (pending.last().isLetter() || pending.last() == '-')) {
                    val firstWordEnd = text.indexOfFirst { !it.isLetter() }
                    if (firstWordEnd > 0) {
                        val combinedWord = if (pending.endsWith('-')) {
                            pending.dropLast(1) + text.take(firstWordEnd)
                        } else {
                            pending + text.take(firstWordEnd)
                        }
                        tokens.addAll(baseTokenizer.tokens(combinedWord))
                        processedText = text.substring(firstWordEnd)
                    }
                } else {
                    // Can't combine, add pending as-is
                    tokens.addAll(baseTokenizer.tokens(pending))
                }
                pendingWord = null
            }

            // Process main text
            val mainTokens = baseTokenizer.tokens(processedText).toList()

            // Check if text ends with potential partial word
            if (processedText.isNotEmpty() && processedText.last().isLetter()) {
                val lastWordStart = processedText.indexOfLast { !it.isLetter() } + 1
                if (lastWordStart < processedText.length) {
                    pendingWord = processedText.substring(lastWordStart)
                    // Remove the last token as it might be incomplete
                    tokens.addAll(mainTokens.dropLast(1))
                } else {
                    tokens.addAll(mainTokens)
                }
            } else if (processedText.endsWith('-')) {
                // Handle hyphenated words
                val lastWordStart = processedText.dropLast(1).indexOfLast { !it.isLetter() } + 1
                if (lastWordStart < processedText.length) {
                    pendingWord = processedText.substring(lastWordStart)
                    tokens.addAll(mainTokens.dropLast(1))
                } else {
                    tokens.addAll(mainTokens)
                }
            } else {
                tokens.addAll(mainTokens)
            }

            return tokens.asSequence()
        }

        override fun finalize(): Sequence<String> {
            return pendingWord?.let { baseTokenizer.tokens(it) } ?: emptySequence()
        }
    }
}

/**
 * Example tokenizer that extracts only email addresses.
 */
private class EmailTokenizer : Tokenizer {
    private val emailPattern = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")

    override fun tokens(text: String): Sequence<String> = sequence {
        emailPattern.findAll(text).forEach { match ->
            yield(match.value.lowercase())
        }
    }

    override fun normalizeSingleToken(token: String): String = token.lowercase()
}

/**
 * Example tokenizer with custom normalization (uppercase instead of lowercase).
 */
private class UppercaseTokenizer : Tokenizer {
    private val wordPattern = Regex("[A-Za-z]+")

    override fun tokens(text: String): Sequence<String> = sequence {
        wordPattern.findAll(text).forEach { match ->
            yield(normalizeSingleToken(match.value))
        }
    }

    override fun normalizeSingleToken(token: String): String = token.uppercase().trim()
}