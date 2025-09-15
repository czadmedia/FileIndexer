package indexStore

import org.example.fileindexcore.FileProcessor
import org.example.fileindexcore.SimpleWordTokenizer
import org.example.fileindexcore.TextFileProcessor
import org.example.fileindexcore.Tokenizer
import org.example.fileindexcore.indexStore.SimpleIndexStore
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
        
        assertEquals(tokens, store.getFileTokens(path))
        assertEquals(setOf(path), store.query("hello"))
        assertEquals(setOf(path), store.query("world"))
        assertEquals(setOf(path), store.query("test"))
        assertEquals(emptySet<Path>(), store.query("missing"))
    }

    @Test
    fun `updateFileTokens handles updates correctly`() {
        val path = Paths.get("test.txt")

        val initialTokens = setOf("old", "shared")
        store.updateFileTokens(path, initialTokens)
        val updatedTokens = setOf("new", "shared")
        store.updateFileTokens(path, updatedTokens, initialTokens)

        assertEquals(emptySet<Path>(), store.query("old"))
        assertEquals(setOf(path), store.query("new"))
        assertEquals(setOf(path), store.query("shared"))
        assertEquals(updatedTokens, store.getFileTokens(path))
    }

    @Test
    fun `updateFileTokens handles incremental updates without oldTokens`() {
        val path = Paths.get("test.txt")

        store.updateFileTokens(path, setOf("a", "b"))
        store.updateFileTokens(path, setOf("b", "c"))

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
            val tokens = fileProcessor.processFile(file)!!
            store.updateFileTokens(file, tokens)

            assertEquals(setOf(file), store.querySequence(listOf("quick", "brown", "fox")))
            assertEquals(setOf(file), store.querySequence(listOf("fox", "jumps", "over")))
            assertEquals(setOf(file), store.querySequence(listOf("the", "lazy", "dog")))
            assertEquals(emptySet<Path>(), store.querySequence(listOf("quick", "fox")))
            assertEquals(emptySet<Path>(), store.querySequence(listOf("brown", "jumps")))
            assertEquals(emptySet<Path>(), store.querySequence(listOf("fox", "brown", "quick")))
            assertEquals(setOf(file), store.querySequence(listOf("quick")))
            assertEquals(emptySet<Path>(), store.querySequence(listOf("missing")))
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
        Files.write(file2, "hello test world sequence".toByteArray())
        Files.write(file3, "world hello test sequence".toByteArray())

        try {
            listOf(file1, file2, file3).forEach { file ->
                val tokens = fileProcessor.processFile(file)!!
                store.updateFileTokens(file, tokens)
            }

            assertEquals(setOf(file1), store.querySequence(listOf("hello", "world")))
            assertEquals(
                setOf(file2, file3),
                store.querySequence(listOf("hello", "test"))
            )
            assertEquals(setOf(file3), store.querySequence(listOf("world", "hello")))
            assertEquals(setOf(file1, file3), store.querySequence(listOf("test", "sequence")))
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

            assertEquals(setOf(file), store.querySequence(listOf("ends", "second")))
            assertEquals(setOf(file), store.querySequence(listOf("here", "third")))
            assertEquals(setOf(file), store.querySequence(listOf("line", "final")))
            assertEquals(setOf(file), store.querySequence(listOf("first", "line", "ends", "second")))

        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `removeFile removes all token associations`() {
        val path1 = Paths.get("test1.txt")
        val path2 = Paths.get("test2.txt")

        store.updateFileTokens(path1, setOf("shared", "unique1"))
        store.updateFileTokens(path2, setOf("shared", "unique2"))

        assertEquals(setOf(path1, path2), store.query("shared"))
        assertEquals(setOf(path1), store.query("unique1"))
        assertEquals(setOf(path2), store.query("unique2"))

        val removedTokens = store.removeFile(path1)

        assertEquals(setOf("shared", "unique1"), removedTokens)
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

        assertNotSame(result1, result2)
        assertEquals(result1, result2)
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

        val originalSize = dump.size
        if (dump is MutableMap) {
            assertThrows<UnsupportedOperationException> {
                dump.clear()
            }
        }
        assertEquals(originalSize, store.dumpIndex().size)
    }

    @Test
    fun `clear removes all data`() {
        val path = Paths.get("test.txt")
        store.updateFileTokens(path, setOf("hello", "world"))

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

        store.updateFileTokens(path, emptySet())

        assertEquals(emptySet<String>(), store.getFileTokens(path))
        assertEquals(emptySet<Path>(), store.query("nonexistent"))
        assertEquals(emptySet<Path>(), store.querySequence(listOf("nonexistent")))

        val longSequence = (1..1000).map { "token$it" }.toList()

        assertEquals(emptySet<Path>(), store.querySequence(longSequence))

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

            assertTrue(store.query("hello").contains(file))
            assertTrue(store.query("world").contains(file))
            assertTrue(store.query("test").contains(file))

            assertEquals(emptySet<Path>(), store.query("Hello"))
            assertEquals(emptySet<Path>(), store.query("WORLD"))

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

        val startIndex = System.currentTimeMillis()
        repeat(numFiles) { fileId ->
            val path = Paths.get("perf_file_$fileId.txt")
            val tokens = (0 until tokensPerFile).map { "token${it % 50}" }.toSet() // Some overlap
            store.updateFileTokens(path, tokens)
        }
        val indexTime = System.currentTimeMillis() - startIndex
        val startQuery = System.currentTimeMillis()
        repeat(1000) {
            store.query("token25")
        }
        val queryTime = System.currentTimeMillis() - startQuery

        assertTrue(store.query("token25").isNotEmpty())
        assertTrue(indexTime < 5000, "Simple indexing should be very fast")
        assertTrue(queryTime < 1000, "Simple queries should be fast")
    }

    @Test
    fun `sequence query performance vs single token queries`() {
        val files = mutableListOf<Path>()

        try {
            repeat(100) { i ->
                val file = Files.createTempFile("seq_perf_$i", ".txt")
                val content = "start sequence token$i middle sequence end"
                Files.write(file, content.toByteArray())
                files.add(file)

                val tokens = fileProcessor.processFile(file)!!
                store.updateFileTokens(file, tokens)
            }

            val singleStart = System.currentTimeMillis()
            repeat(100) {
                store.query("sequence")
            }
            val singleTime = System.currentTimeMillis() - singleStart

            val sequenceStart = System.currentTimeMillis()
            repeat(100) {
                store.querySequence(listOf("start", "sequence"))
            }
            val sequenceTime = System.currentTimeMillis() - sequenceStart

            assertTrue(sequenceTime >= singleTime, "Sequence queries should be slower than single token queries")
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

        val whitespaceFile = Files.createTempFile("whitespace", ".txt")
        Files.write(whitespaceFile, "   \n\t  \r\n  ".toByteArray())

        try {
            val tokens = fileProcessor.processFile(whitespaceFile)!!
            store.updateFileTokens(whitespaceFile, tokens)

            assertEquals(emptySet<String>(), store.getFileTokens(whitespaceFile))

        } finally {
            Files.deleteIfExists(whitespaceFile)
        }

        val specialFile = Files.createTempFile("special", ".txt")
        Files.write(specialFile, "hello@world.com test-case #hashtag".toByteArray())

        try {
            val tokens = fileProcessor.processFile(specialFile)!!
            store.updateFileTokens(specialFile, tokens)

            assertTrue(tokens.isNotEmpty())

        } finally {
            Files.deleteIfExists(specialFile)
        }
    }
}
