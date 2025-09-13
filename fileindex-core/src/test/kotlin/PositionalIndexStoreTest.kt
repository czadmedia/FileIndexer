import org.example.fileindexcore.FileProcessor
import org.example.fileindexcore.PositionalIndexStore
import org.example.fileindexcore.SimpleWordTokenizer
import org.example.fileindexcore.TextFileProcessor
import org.example.fileindexcore.Tokenizer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class PositionalIndexStoreTest {
    
    private lateinit var tokenizer: Tokenizer
    private lateinit var fileProcessor: FileProcessor
    private lateinit var store: PositionalIndexStore
    
    @BeforeEach
    fun setUp() {
        tokenizer = SimpleWordTokenizer()
        fileProcessor = TextFileProcessor(tokenizer)
        store = PositionalIndexStore(tokenizer, fileProcessor)
    }
    
    @Test
    fun `updateFileTokensWithPositions stores positions correctly`() {
        val path = Paths.get("test.txt")
        val tokenPositions = mapOf(
            "hello" to listOf(0, 3),
            "world" to listOf(1, 4),
            "test" to listOf(2)
        )
        
        store.updateFileTokensWithPositions(path, tokenPositions)
        
        // Verify positions are stored correctly
        assertEquals(listOf(0, 3), store.getTokenPositions("hello", path))
        assertEquals(listOf(1, 4), store.getTokenPositions("world", path))
        assertEquals(listOf(2), store.getTokenPositions("test", path))
        
        // Verify file tokens
        assertEquals(setOf("hello", "world", "test"), store.getFileTokens(path))
        
        // Verify queries
        assertEquals(setOf(path), store.query("hello"))
        assertEquals(setOf(path), store.query("world"))
        assertEquals(setOf(path), store.query("test"))
    }
    
    @Test
    fun `updateFileTokensWithPositions handles updates correctly`() {
        val path = Paths.get("test.txt")
        
        // Initial state
        val initialPositions = mapOf(
            "old" to listOf(0),
            "shared" to listOf(1)
        )
        store.updateFileTokensWithPositions(path, initialPositions)
        
        // Update with new positions
        val updatedPositions = mapOf(
            "new" to listOf(0),
            "shared" to listOf(1, 2) // Position changed
        )
        store.updateFileTokensWithPositions(path, updatedPositions, setOf("old", "shared"))
        
        // Verify old token is removed
        assertNull(store.getTokenPositions("old", path))
        assertEquals(emptySet<Path>(), store.query("old"))
        
        // Verify new token is added
        assertEquals(listOf(0), store.getTokenPositions("new", path))
        assertEquals(setOf(path), store.query("new"))
        
        // Verify shared token is updated
        assertEquals(listOf(1, 2), store.getTokenPositions("shared", path))
        
        // Verify file tokens are correct
        assertEquals(setOf("new", "shared"), store.getFileTokens(path))
    }
    
    @Test
    fun `arithmetic sequence query works correctly`() {
        val path = Paths.get("test.txt")
        val tokenPositions = mapOf(
            "the" to listOf(0, 6),
            "quick" to listOf(1),
            "brown" to listOf(2),
            "fox" to listOf(3),
            "jumps" to listOf(4),
            "over" to listOf(5),
            "lazy" to listOf(7),
            "dog" to listOf(8)
        )
        
        store.updateFileTokensWithPositions(path, tokenPositions)
        
        // Test consecutive sequences
        assertEquals(setOf(path), store.querySequence(listOf("quick", "brown", "fox")))
        assertEquals(setOf(path), store.querySequence(listOf("fox", "jumps", "over")))
        assertEquals(setOf(path), store.querySequence(listOf("the", "lazy", "dog")))
        
        // Test non-consecutive sequences (should not match)
        assertEquals(emptySet<Path>(), store.querySequence(listOf("quick", "fox")))
        assertEquals(emptySet<Path>(), store.querySequence(listOf("brown", "jumps")))
        
        // Test sequences that don't exist
        assertEquals(emptySet<Path>(), store.querySequence(listOf("fox", "brown", "quick")))
        assertEquals(emptySet<Path>(), store.querySequence(listOf("nonexistent", "token")))
        
        // Test single token queries
        assertEquals(setOf(path), store.querySequence(listOf("quick")))
        assertEquals(emptySet<Path>(), store.querySequence(listOf("missing")))
        
        // Test empty sequence
        assertEquals(emptySet<Path>(), store.querySequence(emptyList()))
    }
    
    @Test
    fun `sequence query handles multiple files correctly`() {
        val file1 = Paths.get("file1.txt")
        val file2 = Paths.get("file2.txt")
        val file3 = Paths.get("file3.txt")
        
        // File 1: "hello world test"
        store.updateFileTokensWithPositions(file1, mapOf(
            "hello" to listOf(0),
            "world" to listOf(1),
            "test" to listOf(2)
        ))
        
        // File 2: "hello test world" (different order)
        store.updateFileTokensWithPositions(file2, mapOf(
            "hello" to listOf(0),
            "test" to listOf(1),
            "world" to listOf(2)
        ))
        
        // File 3: "world hello test" (different order)
        store.updateFileTokensWithPositions(file3, mapOf(
            "world" to listOf(0),
            "hello" to listOf(1),
            "test" to listOf(2)
        ))
        
        // Test sequence queries
        assertEquals(setOf(file1), store.querySequence(listOf("hello", "world")))
        assertEquals(setOf(file2, file3), store.querySequence(listOf("hello", "test"))) // Both file2 and file3 have consecutive "hello test"
        assertEquals(setOf(file3), store.querySequence(listOf("world", "hello")))
        assertEquals(setOf(file1), store.querySequence(listOf("world", "test")))
        assertEquals(setOf(file2), store.querySequence(listOf("test", "world")))
        
        // Test sequence that spans multiple positions
        assertEquals(setOf(file1), store.querySequence(listOf("hello", "world", "test")))
        assertEquals(setOf(file2), store.querySequence(listOf("hello", "test", "world"))) // File2 has this exact sequence
    }
    
    @Test
    fun `queryProximity finds tokens within distance`() {
        val path = Paths.get("test.txt")
        val tokenPositions = mapOf(
            "database" to listOf(0),
            "connection" to listOf(1),
            "error" to listOf(3),
            "timeout" to listOf(5),
            "retry" to listOf(6)
        )
        
        store.updateFileTokensWithPositions(path, tokenPositions)
        
        // Test proximity within distance
        assertEquals(setOf(path), store.queryProximity(listOf("database", "connection"), 1))
        assertEquals(setOf(path), store.queryProximity(listOf("database", "error"), 3))
        assertEquals(setOf(path), store.queryProximity(listOf("timeout", "retry"), 1))
        assertEquals(setOf(path), store.queryProximity(listOf("connection", "timeout"), 4))
        
        // Test proximity beyond distance
        assertEquals(emptySet<Path>(), store.queryProximity(listOf("database", "timeout"), 3))
        assertEquals(emptySet<Path>(), store.queryProximity(listOf("database", "retry"), 5))
        
        // Test with single token
        assertEquals(setOf(path), store.queryProximity(listOf("database"), 10))
        
        // Test with empty list
        assertEquals(emptySet<Path>(), store.queryProximity(emptyList(), 5))
        
        // Test with non-existent tokens
        assertEquals(emptySet<Path>(), store.queryProximity(listOf("missing", "token"), 10))
    }
    
    @Test
    fun `removeFile removes all token associations`() {
        val path = Paths.get("test.txt")
        val tokenPositions = mapOf(
            "hello" to listOf(0),
            "world" to listOf(1),
            "test" to listOf(2)
        )
        
        store.updateFileTokensWithPositions(path, tokenPositions)
        
        // Verify tokens are present
        assertEquals(setOf("hello", "world", "test"), store.getFileTokens(path))
        assertEquals(setOf(path), store.query("hello"))
        
        // Remove file
        val removedTokens = store.removeFile(path)
        
        // Verify return value
        assertEquals(setOf("hello", "world", "test"), removedTokens)
        
        // Verify file is removed
        assertNull(store.getFileTokens(path))
        assertEquals(emptySet<Path>(), store.query("hello"))
        assertEquals(emptySet<Path>(), store.query("world"))
        assertEquals(emptySet<Path>(), store.query("test"))
        
        // Verify positional queries don't work
        assertNull(store.getTokenPositions("hello", path))
        assertEquals(emptySet<Path>(), store.querySequence(listOf("hello", "world")))
    }
    
    @Test
    fun `removeFile handles non-existent files gracefully`() {
        val nonExistentPath = Paths.get("nonexistent.txt")
        
        val removedTokens = store.removeFile(nonExistentPath)
        
        assertEquals(emptySet<String>(), removedTokens)
        assertNull(store.getFileTokens(nonExistentPath))
    }
    
    @Test
    fun `dumpIndex returns correct structure`() {
        val file1 = Paths.get("file1.txt")
        val file2 = Paths.get("file2.txt")
        
        store.updateFileTokensWithPositions(file1, mapOf(
            "hello" to listOf(0),
            "world" to listOf(1)
        ))
        
        store.updateFileTokensWithPositions(file2, mapOf(
            "hello" to listOf(0),
            "test" to listOf(1)
        ))
        
        val dump = store.dumpIndex()
        
        assertEquals(setOf(file1, file2), dump["hello"])
        assertEquals(setOf(file1), dump["world"])
        assertEquals(setOf(file2), dump["test"])
        assertEquals(3, dump.size)
    }
    
    @Test
    fun `dumpPositionalIndex returns detailed position information`() {
        val file1 = Paths.get("file1.txt")
        val file2 = Paths.get("file2.txt")
        
        store.updateFileTokensWithPositions(file1, mapOf(
            "hello" to listOf(0, 3),
            "world" to listOf(1)
        ))
        
        store.updateFileTokensWithPositions(file2, mapOf(
            "hello" to listOf(0),
            "test" to listOf(1, 2)
        ))
        
        val positionalDump = store.dumpPositionalIndex()
        
        assertEquals(listOf(0, 3), positionalDump["hello"]?.get(file1))
        assertEquals(listOf(0), positionalDump["hello"]?.get(file2))
        assertEquals(listOf(1), positionalDump["world"]?.get(file1))
        assertEquals(listOf(1, 2), positionalDump["test"]?.get(file2))
    }
    
    @Test
    fun `clear removes all data`() {
        val path = Paths.get("test.txt")
        store.updateFileTokensWithPositions(path, mapOf(
            "hello" to listOf(0),
            "world" to listOf(1)
        ))
        
        // Verify data exists
        assertFalse(store.dumpIndex().isEmpty())
        assertNotNull(store.getFileTokens(path))
        
        store.clear()
        
        // Verify all data is cleared
        assertTrue(store.dumpIndex().isEmpty())
        assertTrue(store.dumpPositionalIndex().isEmpty())
        assertNull(store.getFileTokens(path))
        assertEquals(emptySet<Path>(), store.query("hello"))
        assertNull(store.getTokenPositions("hello", path))
    }
    
    @Test
    fun `handles edge cases gracefully`() {
        val path = Paths.get("test.txt")
        
        // Empty token positions
        store.updateFileTokensWithPositions(path, emptyMap())
        assertEquals(emptySet<String>(), store.getFileTokens(path))
        
        // Token with empty positions list
        store.updateFileTokensWithPositions(path, mapOf("token" to emptyList()))
        assertEquals(setOf("token"), store.getFileTokens(path))
        assertEquals(emptyList<Int>(), store.getTokenPositions("token", path))
        
        // Non-existent token queries
        assertNull(store.getTokenPositions("nonexistent", path))
        assertEquals(emptySet<Path>(), store.query("nonexistent"))
        
        // Query with very long sequences
        val longSequence = (1..1000).map { "token$it" }.toList()
        assertEquals(emptySet<Path>(), store.querySequence(longSequence))
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
                            val tokenPositions = mapOf(
                                "thread$threadId" to listOf(0),
                                "operation$opId" to listOf(1)
                            )
                            
                            store.updateFileTokensWithPositions(path, tokenPositions)
                            store.query("thread$threadId")
                            store.querySequence(listOf("thread$threadId", "operation$opId"))
                            store.getTokenPositions("thread$threadId", path)
                            
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
            store.updateFileTokensWithPositions(testPath, mapOf("final" to listOf(0)))
            assertEquals(setOf(testPath), store.query("final"))
            
        } finally {
            executor.shutdown()
        }
    }
    
    @Test
    fun `performance characteristics test`() {
        val numFiles = 100
        val tokensPerFile = 1000
        
        // Index many files
        val startIndex = System.currentTimeMillis()
        repeat(numFiles) { fileId ->
            val path = Paths.get("perf_file_$fileId.txt")
            val positions = (0 until tokensPerFile).associate { pos ->
                "token${pos % 100}" to listOfNotNull(pos) // Some tokens appear multiple times
            }
            store.updateFileTokensWithPositions(path, positions)
        }
        val indexTime = System.currentTimeMillis() - startIndex
        
        // Test query performance
        val startQuery = System.currentTimeMillis()
        repeat(100) {
            store.query("token50")
            store.querySequence(listOf("token1", "token2"))
            store.queryProximity(listOf("token10", "token20"), 15)
        }
        val queryTime = System.currentTimeMillis() - startQuery
        
        println("Indexing $numFiles files with $tokensPerFile tokens each took: ${indexTime}ms")
        println("100 mixed queries took: ${queryTime}ms")
        
        // Verify correctness
        assertEquals(numFiles, store.query("token50").size)
        assertTrue(store.querySequence(listOf("token1", "token2")).isNotEmpty())
        
        // Performance should be reasonable (adjust based on hardware)
        assertTrue(indexTime < 10000, "Indexing should complete in reasonable time")
        assertTrue(queryTime < 1000, "Queries should be fast with positional index")
    }
    
    @Test
    fun `integration with real file processing`() {
        val file = Files.createTempFile("positional_test", ".txt")
        Files.write(file, "the quick brown fox jumps over the lazy dog".toByteArray())
        
        try {
            // Process real file
            val tokenPositions = fileProcessor.processFileWithPositions(file)
            assertNotNull(tokenPositions)
            
            store.updateFileTokensWithPositions(file, tokenPositions!!)
            
            // Test queries on real content
            assertTrue(store.query("quick").contains(file))
            assertTrue(store.querySequence(listOf("quick", "brown")).contains(file))
            assertTrue(store.querySequence(listOf("the", "lazy", "dog")).contains(file))
            
            // Test positions
            assertNotNull(store.getTokenPositions("quick", file))
            assertNotNull(store.getTokenPositions("fox", file))
            
            // Test proximity
            assertTrue(store.queryProximity(listOf("quick", "fox"), 5).contains(file))
            
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
