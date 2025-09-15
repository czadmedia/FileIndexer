package indexStore

import org.example.fileindexcore.FileProcessor
import org.example.fileindexcore.indexStore.PositionalIndexStore
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
        store = PositionalIndexStore()
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

        assertEquals(setOf("hello", "world", "test"), store.getFileTokens(path))
        assertEquals(setOf(path), store.query("hello"))
        assertEquals(setOf(path), store.query("world"))
        assertEquals(setOf(path), store.query("test"))
    }

    @Test
    fun `updateFileTokensWithPositions handles updates correctly`() {
        val path = Paths.get("test.txt")
        val initialPositions = mapOf(
            "old" to listOf(0),
            "shared" to listOf(1)
        )
        store.updateFileTokensWithPositions(path, initialPositions)

        val updatedPositions = mapOf(
            "new" to listOf(0),
            "shared" to listOf(1, 2) // Position changed
        )
        store.updateFileTokensWithPositions(path, updatedPositions, setOf("old", "shared"))

        assertEquals(emptySet<Path>(), store.query("old"))
        assertEquals(setOf(path), store.query("new"))
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

        assertEquals(setOf(path), store.querySequence(listOf("quick", "brown", "fox")))
        assertEquals(setOf(path), store.querySequence(listOf("fox", "jumps", "over")))
        assertEquals(setOf(path), store.querySequence(listOf("the", "lazy", "dog")))

        assertEquals(emptySet<Path>(), store.querySequence(listOf("quick", "fox")))
        assertEquals(emptySet<Path>(), store.querySequence(listOf("brown", "jumps")))

        assertEquals(emptySet<Path>(), store.querySequence(listOf("fox", "brown", "quick")))
        assertEquals(emptySet<Path>(), store.querySequence(listOf("nonexistent", "token")))

        assertEquals(setOf(path), store.querySequence(listOf("quick")))
        assertEquals(emptySet<Path>(), store.querySequence(listOf("missing")))

        assertEquals(emptySet<Path>(), store.querySequence(emptyList()))
    }

    @Test
    fun `sequence query handles multiple files correctly`() {
        val file1 = Paths.get("file1.txt")
        val file2 = Paths.get("file2.txt")
        val file3 = Paths.get("file3.txt")

        store.updateFileTokensWithPositions(
            file1, mapOf(
                "hello" to listOf(0),
                "world" to listOf(1),
                "test" to listOf(2)
            )
        )
        store.updateFileTokensWithPositions(
            file2, mapOf(
                "hello" to listOf(0),
                "test" to listOf(1),
                "world" to listOf(2)
            )
        )
        store.updateFileTokensWithPositions(
            file3, mapOf(
                "world" to listOf(0),
                "hello" to listOf(1),
                "test" to listOf(2)
            )
        )

        assertEquals(setOf(file1), store.querySequence(listOf("hello", "world")))
        assertEquals(
            setOf(file2, file3),
            store.querySequence(listOf("hello", "test"))
        )
        assertEquals(setOf(file3), store.querySequence(listOf("world", "hello")))
        assertEquals(setOf(file1), store.querySequence(listOf("world", "test")))
        assertEquals(setOf(file2), store.querySequence(listOf("test", "world")))

        assertEquals(setOf(file1), store.querySequence(listOf("hello", "world", "test")))
        assertEquals(
            setOf(file2),
            store.querySequence(listOf("hello", "test", "world"))
        )
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

        assertEquals(setOf("hello", "world", "test"), store.getFileTokens(path))
        assertEquals(setOf(path), store.query("hello"))

        val removedTokens = store.removeFile(path)

        assertEquals(setOf("hello", "world", "test"), removedTokens)

        assertNull(store.getFileTokens(path))
        assertEquals(emptySet<Path>(), store.query("hello"))
        assertEquals(emptySet<Path>(), store.query("world"))
        assertEquals(emptySet<Path>(), store.query("test"))

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

        store.updateFileTokensWithPositions(
            file1, mapOf(
                "hello" to listOf(0),
                "world" to listOf(1)
            )
        )
        store.updateFileTokensWithPositions(
            file2, mapOf(
                "hello" to listOf(0),
                "test" to listOf(1)
            )
        )

        val dump = store.dumpIndex()
        assertEquals(setOf(file1, file2), dump["hello"])
        assertEquals(setOf(file1), dump["world"])
        assertEquals(setOf(file2), dump["test"])
        assertEquals(3, dump.size)
    }

    @Test
    fun `clear removes all data`() {
        val path = Paths.get("test.txt")
        store.updateFileTokensWithPositions(
            path, mapOf(
                "hello" to listOf(0),
                "world" to listOf(1)
            )
        )

        assertFalse(store.dumpIndex().isEmpty())
        assertNotNull(store.getFileTokens(path))

        store.clear()

        assertTrue(store.dumpIndex().isEmpty())
        assertNull(store.getFileTokens(path))
        assertEquals(emptySet<Path>(), store.query("hello"))
    }

    @Test
    fun `handles edge cases gracefully`() {
        val path = Paths.get("test.txt")

        store.updateFileTokensWithPositions(path, emptyMap())
        assertEquals(emptySet<String>(), store.getFileTokens(path))

        store.updateFileTokensWithPositions(path, mapOf("token" to emptyList()))
        assertEquals(setOf("token"), store.getFileTokens(path))
        assertEquals(emptySet<Path>(), store.query("nonexistent"))

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

        val startIndex = System.currentTimeMillis()
        repeat(numFiles) { fileId ->
            val path = Paths.get("perf_file_$fileId.txt")
            val positions = (0 until tokensPerFile).associate { pos ->
                "token${pos % 100}" to listOfNotNull(pos) // Some tokens appear multiple times
            }
            store.updateFileTokensWithPositions(path, positions)
        }
        val indexTime = System.currentTimeMillis() - startIndex
        val startQuery = System.currentTimeMillis()
        repeat(100) {
            store.query("token50")
            store.querySequence(listOf("token1", "token2"))
        }
        val queryTime = System.currentTimeMillis() - startQuery

        assertEquals(numFiles, store.query("token50").size)
        assertTrue(store.querySequence(listOf("token1", "token2")).isNotEmpty())

        assertTrue(indexTime < 10000, "Indexing should complete in reasonable time")
        assertTrue(queryTime < 1000, "Queries should be fast with positional index")
    }

    @Test
    fun `integration with real file processing`() {
        val file = Files.createTempFile("positional_test", ".txt")
        Files.write(file, "the quick brown fox jumps over the lazy dog".toByteArray())

        try {
            val tokenPositions = fileProcessor.processFileWithPositions(file)
            assertNotNull(tokenPositions)

            store.updateFileTokensWithPositions(file, tokenPositions!!)
            
            assertTrue(store.query("quick").contains(file))
            assertTrue(store.querySequence(listOf("quick", "brown")).contains(file))
            assertTrue(store.querySequence(listOf("the", "lazy", "dog")).contains(file))

        } finally {
            Files.deleteIfExists(file)
        }
    }
}
