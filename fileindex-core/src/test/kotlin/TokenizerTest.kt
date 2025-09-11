import org.example.fileindexcore.SimpleWordTokenizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TokenizerTest {
    @Test
    fun `empty string yields no tokens`() {
        val tokens = SimpleWordTokenizer().tokens("").toList()

        assertTrue(tokens.isEmpty())
    }

    @Test
    fun `whitespace-only yields no tokens`() {
        val tokens = SimpleWordTokenizer().tokens("   \t \n" +
                " ").toList()

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
}