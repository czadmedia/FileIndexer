package org.example.fileindexcore

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class SimpleIndexStoreTest {
    
    private lateinit var tokenizer: Tokenizer
    private lateinit var fileProcessor: FileProcessor
    private lateinit var store: SimpleIndexStore
    
    @BeforeEach
    fun setUp() {
        tokenizer = SimpleWordTokenizer()
        fileProcessor = TextFileProcessor(tokenizer)
        store = SimpleIndexStore(tokenizer, fileProcessor)
    }
    
    @Test
    fun `updateFileTokens stores tokens correctly`() {
        val path = Paths.get("test.txt")
        val tokens = setOf("hello", "world", "test")
        
        store.updateFileTokens(path, tokens)
        
        // Verify tokens are stored
        assertEquals(tokens, store.getFileTokens(path))
        
        // Verify queries work
        assertEquals(setOf(path), store.query("hello"))
        assertEquals(setOf(path), store.query("world"))
        assertEquals(setOf(path), store.query("test"))
        assertEquals(emptySet<Path>(), store.query("missing"))
    }
    
    @Test
    fun `updateFileTokens handles updates correctly`() {
        val path = Paths.get("test.txt")
        
        // Initial tokens
        val initialTokens = setOf("old", "shared")
        store.updateFileTokens(path, initialTokens)
        
        // Update with new tokens
        val updatedTokens = setOf("new", "shared")
        store.updateFileTokens(path, updatedTokens, initialTokens)
        
        // Verify old token is removed
        assertEquals(emptySet<Path>(), store.query("old"))
        
        // Verify new token is added
        assertEquals(setOf(path), store.query("new"))
        
        // Verify shared token remains
        assertEquals(setOf(path), store.query("shared"))
        
        // Verify file tokens are correct
        assertEquals(updatedTokens, store.getFileTokens(path))
    }
    
    @Test
    fun `updateFileTokens handles incremental updates without oldTokens`() {
        val path = Paths.get("test.txt")
        
        // Initial tokens
        store.updateFileTokens(path, setOf("a", "b"))
        
        // Update without providing oldTokens (should detect changes automatically)
        store.updateFileTokens(path, setOf("b", "c"))
        
        // Verify state
        assertEquals(emptySet<Path>(), store.query("a"))
        assertEquals(setOf(path), store.query("b"))
        assertEquals(setOf(path), store.query("c"))
        assertEquals(setOf("b", "c"), store.getFileTokens(path))
    }
    
    @Test
    fun `sequence query with file scanning works correctly`() {
        val file = Files.createTempFile("sequence_test", ".txt")
        Files.write(file, "the quick brown fox jumps over the lazy dog".toByteArray())
        
        try {
            // Process and index file
            val tokens = fileProcessor.processFile(file)!!
            store.updateFileTokens(file, tokens)
            
            // Test consecutive sequences (should be found by scanning)
            assertEquals(setOf(file), store.querySequence(listOf("quick", "brown", "fox")))
            assertEquals(setOf(file), store.querySequence(listOf("fox", "jumps", "over")))
            assertEquals(setOf(file), store.querySequence(listOf("the", "lazy", "dog")))
            
            // Test non-consecutive sequences (should not match)
            assertEquals(emptySet<Path>(), store.querySequence(listOf("quick", "fox")))
            assertEquals(emptySet<Path>(), store.querySequence(listOf("brown", "jumps")))
            
            // Test wrong order
            assertEquals(emptySet<Path>(), store.querySequence(listOf("fox", "brown", "quick")))
            
            // Test single token queries
            assertEquals(setOf(file), store.querySequence(listOf("quick")))
            assertEquals(emptySet<Path>(), store.querySequence(listOf("missing")))
            
            // Test empty sequence
            assertEquals(emptySet<Path>(), store.querySequence(emptyList()))
            
        } finally {
            Files.deleteIfExists(file)
        }
    }
    
    @Test
    fun `sequence query handles multiple files correctly`() {
        val file1 = Files.createTempFile("file1", ".txt")
        val file2 = Files.createTempFile("file2", ".txt")
        val file3 = Files.createTempFile("file3", ".txt")
        
        Files.write(file1, "hello world test sequence".toByteArray())
        Files.write(file2, "hello test world sequence".toByteArray())  // different order
        Files.write(file3, "world hello test sequence".toByteArray())  // different order
        
        try {
            // Index all files
            listOf(file1, file2, file3).forEach { file ->
                val tokens = fileProcessor.processFile(file)!!
                store.updateFileTokens(file, tokens)
            }
            
            // Test sequences that should match specific files
            assertEquals(setOf(file1), store.querySequence(listOf("hello", "world")))
            assertEquals(setOf(file2, file3), store.querySequence(listOf("hello", "test"))) // Both file2 and file3 have consecutive "hello test"
            assertEquals(setOf(file3), store.querySequence(listOf("world", "hello")))
            
            // Test sequences that appear in multiple files 
            // File1: "hello world test sequence" - matches "test sequence"
            // File2: "hello test world sequence" - does NOT match "test sequence" (world is between)
            // File3: "world hello test sequence" - matches "test sequence"
            assertEquals(setOf(file1, file3), store.querySequence(listOf("test", "sequence")))
            
            // Test sequences that don't exist in any file
            assertEquals(emptySet<Path>(), store.querySequence(listOf("missing", "sequence")))
            
        } finally {
            Files.deleteIfExists(file1)
            Files.deleteIfExists(file2)
            Files.deleteIfExists(file3)
        }
    }
    
    @Test
    fun `sequence query with cross-line boundaries`() {
        val file = Files.createTempFile("multiline_test", ".txt")
        val content = """
            first line ends
            second line starts here
            third line final
        """.trimIndent()
        Files.write(file, content.toByteArray())
        
        try {
            val tokens = fileProcessor.processFile(file)!!
            store.updateFileTokens(file, tokens)
            
            // Test sequences across line boundaries
            assertEquals(setOf(file), store.querySequence(listOf("ends", "second")))
            assertEquals(setOf(file), store.querySequence(listOf("here", "third")))
            assertEquals(setOf(file), store.querySequence(listOf("line", "final")))
            
            // Test longer cross-boundary sequences
            assertEquals(setOf(file), store.querySequence(listOf("first", "line", "ends", "second")))
            
        } finally {
            Files.deleteIfExists(file)
        }
    }
    
    @Test
    fun `removeFile removes all token associations`() {
        val path1 = Paths.get("test1.txt")
        val path2 = Paths.get("test2.txt")
        
        // Add tokens to both files
        store.updateFileTokens(path1, setOf("shared", "unique1"))
        store.updateFileTokens(path2, setOf("shared", "unique2"))
        
        // Verify both files are indexed
        assertEquals(setOf(path1, path2), store.query("shared"))
        assertEquals(setOf(path1), store.query("unique1"))
        assertEquals(setOf(path2), store.query("unique2"))
        
        // Remove first file
        val removedTokens = store.removeFile(path1)
        
        // Verify return value
        assertEquals(setOf("shared", "unique1"), removedTokens)
        
        // Verify first file is removed
        assertNull(store.getFileTokens(path1))
        assertEquals(setOf(path2), store.query("shared")) // Only path2 should remain
        assertEquals(emptySet<Path>(), store.query("unique1"))
        assertEquals(setOf(path2), store.query("unique2")) // Should be unaffected
    }
    
    @Test
    fun `removeFile handles non-existent files gracefully`() {
        val nonExistentPath = Paths.get("nonexistent.txt")
        
        val removedTokens = store.removeFile(nonExistentPath)
        
        assertEquals(emptySet<String>(), removedTokens)
        assertNull(store.getFileTokens(nonExistentPath))
    }
    
    @Test
    fun `query returns defensive copy`() {
        val path = Paths.get("test.txt")
        store.updateFileTokens(path, setOf("test"))
        
        val result1 = store.query("test")
        val result2 = store.query("test")
        
        // Should get different instances (defensive copies)
        assertNotSame(result1, result2)
        assertEquals(result1, result2)
        
        // Modifying result should not affect store
        if (result1 is MutableSet) {
            assertThrows<UnsupportedOperationException> {
                result1.clear()
            }
        }
    }
    
    @Test
    fun `dumpIndex returns correct structure`() {
        val file1 = Paths.get("file1.txt")
        val file2 = Paths.get("file2.txt")
        
        store.updateFileTokens(file1, setOf("hello", "world"))
        store.updateFileTokens(file2, setOf("hello", "test"))
        
        val dump = store.dumpIndex()
        
        assertEquals(setOf(file1, file2), dump["hello"])
        assertEquals(setOf(file1), dump["world"])
        assertEquals(setOf(file2), dump["test"])
        assertEquals(3, dump.size)
        
        // Verify it's a defensive copy
        val originalSize = dump.size
        if (dump is MutableMap) {
            assertThrows<UnsupportedOperationException> {
                dump.clear() // This should throw if it's truly immutable
            }
        }
        assertEquals(originalSize, store.dumpIndex().size) // Store should be unchanged
    }
    
    @Test
    fun `clear removes all data`() {
        val path = Paths.get("test.txt")
        store.updateFileTokens(path, setOf("hello", "world"))
        
        // Verify data exists
        assertFalse(store.dumpIndex().isEmpty())
        assertNotNull(store.getFileTokens(path))
        
        store.clear()
        
        // Verify all data is cleared
        assertTrue(store.dumpIndex().isEmpty())
        assertNull(store.getFileTokens(path))
        assertEquals(emptySet<Path>(), store.query("hello"))
    }
    
    @Test
    fun `handles edge cases gracefully`() {
        val path = Paths.get("test.txt")
        
        // Empty token set
        store.updateFileTokens(path, emptySet())
        assertEquals(emptySet<String>(), store.getFileTokens(path))
        
        // Query non-existent tokens
        assertEquals(emptySet<Path>(), store.query("nonexistent"))
        assertEquals(emptySet<Path>(), store.querySequence(listOf("nonexistent")))
        
        // Query with very long sequences
        val longSequence = (1..1000).map { "token$it" }.toList()
        assertEquals(emptySet<Path>(), store.querySequence(longSequence))
        
        // Multiple updates of the same file
        repeat(10) {
            store.updateFileTokens(path, setOf("token$it"))
        }
        assertEquals(setOf("token9"), store.getFileTokens(path))
    }
    
    @Test
    fun `case sensitivity and tokenizer integration`() {
        val file = Files.createTempFile("case_test", ".txt")
        Files.write(file, "Hello WORLD Test".toByteArray())
        
        try {
            val tokens = fileProcessor.processFile(file)!!
            store.updateFileTokens(file, tokens)
            
            // SimpleWordTokenizer normalizes to lowercase
            assertTrue(store.query("hello").contains(file))
            assertTrue(store.query("world").contains(file))
            assertTrue(store.query("test").contains(file))
            
            // Original case should not match (depends on tokenizer)
            assertEquals(emptySet<Path>(), store.query("Hello"))
            assertEquals(emptySet<Path>(), store.query("WORLD"))
            
            // Sequence queries should work with normalized tokens
            assertEquals(setOf(file), store.querySequence(listOf("hello", "world")))
            
        } finally {
            Files.deleteIfExists(file)
        }
    }
    
    @Test
    fun `thread safety test`() {
        val numThreads = 10
        val numOperations = 100
        val latch = CountDownLatch(numThreads)
        val executor = Executors.newFixedThreadPool(numThreads)
        
        try {
            repeat(numThreads) { threadId ->
                executor.submit {
                    try {
                        repeat(numOperations) { opId ->
                            val path = Paths.get("thread${threadId}_op${opId}.txt")
                            val tokens = setOf("thread$threadId", "operation$opId", "shared")
                            
                            store.updateFileTokens(path, tokens)
                            store.query("thread$threadId")
                            store.query("shared")
                            store.getFileTokens(path)
                            
                            if (opId % 10 == 0) {
                                store.removeFile(path)
                            }
                        }
                    } finally {
                        latch.countDown()
                    }
                }
            }
            
            latch.await()
            
            // Verify store is still functional after concurrent operations
            val testPath = Paths.get("final_test.txt")
            store.updateFileTokens(testPath, setOf("final"))
            assertEquals(setOf(testPath), store.query("final"))
            
        } finally {
            executor.shutdown()
        }
    }
    
    @Test
    fun `performance characteristics test`() {
        val numFiles = 1000
        val tokensPerFile = 100
        
        // Index many files
        val startIndex = System.currentTimeMillis()
        repeat(numFiles) { fileId ->
            val path = Paths.get("perf_file_$fileId.txt")
            val tokens = (0 until tokensPerFile).map { "token${it % 50}" }.toSet() // Some overlap
            store.updateFileTokens(path, tokens)
        }
        val indexTime = System.currentTimeMillis() - startIndex
        
        // Test query performance
        val startQuery = System.currentTimeMillis()
        repeat(1000) {
            store.query("token25")
        }
        val queryTime = System.currentTimeMillis() - startQuery
        
        println("Indexing $numFiles files with ~$tokensPerFile tokens each took: ${indexTime}ms")
        println("1000 simple queries took: ${queryTime}ms")
        
        // Verify correctness
        assertTrue(store.query("token25").size > 0)
        
        // Performance should be reasonable (adjust based on hardware)
        assertTrue(indexTime < 5000, "Simple indexing should be very fast")
        assertTrue(queryTime < 1000, "Simple queries should be fast")
    }
    
    @Test
    fun `sequence query performance vs single token queries`() {
        val files = mutableListOf<Path>()
        
        try {
            // Create files with known sequences
            repeat(100) { i ->
                val file = Files.createTempFile("seq_perf_$i", ".txt")
                val content = "start sequence token$i middle sequence end"
                Files.write(file, content.toByteArray())
                files.add(file)
                
                val tokens = fileProcessor.processFile(file)!!
                store.updateFileTokens(file, tokens)
            }
            
            // Measure single token queries
            val singleStart = System.currentTimeMillis()
            repeat(100) {
                store.query("sequence")
            }
            val singleTime = System.currentTimeMillis() - singleStart
            
            // Measure sequence queries (requires file scanning)
            val sequenceStart = System.currentTimeMillis()
            repeat(100) {
                store.querySequence(listOf("start", "sequence"))
            }
            val sequenceTime = System.currentTimeMillis() - sequenceStart
            
            println("100 single token queries took: ${singleTime}ms")
            println("100 sequence queries (with file scanning) took: ${sequenceTime}ms")
            
            // Sequence queries should be slower due to file scanning
            assertTrue(sequenceTime >= singleTime, "Sequence queries should be slower than single token queries")
            
            // But still reasonable
            assertTrue(sequenceTime < 10000, "Sequence queries should complete in reasonable time")
            
        } finally {
            files.forEach { Files.deleteIfExists(it) }
        }
    }
    
    @Test
    fun `integration with file processing edge cases`() {
        // Test with empty file
        val emptyFile = Files.createTempFile("empty", ".txt")
        Files.write(emptyFile, "".toByteArray())
        
        try {
            val tokens = fileProcessor.processFile(emptyFile)!!
            store.updateFileTokens(emptyFile, tokens)
            
            assertEquals(emptySet<String>(), store.getFileTokens(emptyFile))
            
        } finally {
            Files.deleteIfExists(emptyFile)
        }
        
        // Test with whitespace-only file
        val whitespaceFile = Files.createTempFile("whitespace", ".txt")
        Files.write(whitespaceFile, "   \n\t  \r\n  ".toByteArray())
        
        try {
            val tokens = fileProcessor.processFile(whitespaceFile)!!
            store.updateFileTokens(whitespaceFile, tokens)
            
            assertEquals(emptySet<String>(), store.getFileTokens(whitespaceFile))
            
        } finally {
            Files.deleteIfExists(whitespaceFile)
        }
        
        // Test with special characters (depends on tokenizer)
        val specialFile = Files.createTempFile("special", ".txt")
        Files.write(specialFile, "hello@world.com test-case #hashtag".toByteArray())
        
        try {
            val tokens = fileProcessor.processFile(specialFile)!!
            store.updateFileTokens(specialFile, tokens)
            
            // Verify tokenizer behavior
            assertTrue(tokens.isNotEmpty())
            println("Tokens from special characters file: $tokens")
            
        } finally {
            Files.deleteIfExists(specialFile)
        }
    }
}
