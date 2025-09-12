import org.example.fileindexcore.SimpleWordTokenizer
import org.example.fileindexcore.TextFileProcessor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files

class FileProcessorTest {

    @Test
    fun `line-by-line processing produces same results as whole-file processing`() {
        // Create a test file with multiple lines
        val testContent = """
            Hello world! This is line one.
            Second line with different WORDS and numbers123.
            Third line: special-characters & symbols!
            
            Empty line above, and more content here.
        """.trimIndent()
        
        val tempFile = Files.createTempFile("line_test", ".txt")
        Files.write(tempFile, testContent.toByteArray())
        
        try {
            val tokenizer = SimpleWordTokenizer()
            val processor = TextFileProcessor(tokenizer)
            
            // Process with our line-by-line implementation
            val lineByLineTokens = processor.processFile(tempFile)
            assertNotNull(lineByLineTokens, "Should successfully process file")
            
            // Compare with what would happen if we tokenized the whole content at once
            val wholeFileTokens = tokenizer.tokens(testContent).toSet()
            
            // Results should be identical
            assertEquals(wholeFileTokens, lineByLineTokens, 
                       "Line-by-line processing should produce same tokens as whole-file processing")
            
            // Verify we got expected content (some sample tokens)
            assertTrue(lineByLineTokens!!.contains("hello"), "Should contain 'hello'")
            assertTrue(lineByLineTokens.contains("world"), "Should contain 'world'") 
            assertTrue(lineByLineTokens.contains("numbers123"), "Should contain 'numbers123'")
            assertTrue(lineByLineTokens.contains("special"), "Should contain 'special'")
            assertTrue(lineByLineTokens.contains("characters"), "Should contain 'characters'")
            
            println("Line-by-line tokens: ${lineByLineTokens.sorted()}")
            
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }
    
    @Test
    fun `handles empty file correctly`() {
        val tempFile = Files.createTempFile("empty_test", ".txt")
        Files.write(tempFile, "".toByteArray())
        
        try {
            val processor = TextFileProcessor(SimpleWordTokenizer())
            val tokens = processor.processFile(tempFile)
            
            assertNotNull(tokens, "Should handle empty file")
            assertTrue(tokens!!.isEmpty(), "Empty file should produce no tokens")
            
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }
    
    @Test
    fun `handles large file simulation`() {
        val tempFile = Files.createTempFile("large_test", ".txt")
        
        try {
            // Simulate a larger file by writing many lines
            Files.newBufferedWriter(tempFile).use { writer ->
                repeat(1000) { lineNumber ->
                    writer.write("Line $lineNumber with some content and words$lineNumber\n")
                }
            }
            
            val processor = TextFileProcessor(SimpleWordTokenizer())
            val tokens = processor.processFile(tempFile)
            
            assertNotNull(tokens, "Should handle larger file")
            assertTrue(tokens!!.isNotEmpty(), "Should extract tokens from large file")
            
            // Should contain tokens from different parts of the file
            assertTrue(tokens.contains("line"), "Should contain 'line'")
            assertTrue(tokens.contains("content"), "Should contain 'content'")
            assertTrue(tokens.contains("words0"), "Should contain 'words0'")
            assertTrue(tokens.contains("words999"), "Should contain 'words999'")
            
            println("Large file produced ${tokens.size} unique tokens")
            
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }
    
    @Test
    fun `handles file with only whitespace and empty lines`() {
        val testContent = "\n\n   \n\t\t\n  \n\n"
        val tempFile = Files.createTempFile("whitespace_test", ".txt")
        Files.write(tempFile, testContent.toByteArray())
        
        try {
            val processor = TextFileProcessor(SimpleWordTokenizer())
            val tokens = processor.processFile(tempFile)
            
            assertNotNull(tokens, "Should handle whitespace-only file")
            assertTrue(tokens!!.isEmpty(), "Whitespace-only file should produce no tokens")
            
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }
}
