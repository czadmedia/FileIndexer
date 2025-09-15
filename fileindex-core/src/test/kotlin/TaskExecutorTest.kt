import org.example.fileindexcore.FileProcessor
import org.example.fileindexcore.ThreadPoolTaskExecutor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class TaskExecutorTest {

    @Test
    fun `concurrent processing mechanism does not break basic functionality`() {
        val tempFile = Files.createTempFile("functionality_test", ".txt")
        Files.write(tempFile, "test content".toByteArray())
        val mockFileProcessor = object : FileProcessor {
            override fun processFile(path: Path): Set<String> {
                return setOf("test", "content")
            }

            override fun processFileWithPositions(path: Path): Map<String, List<Int>> {
                return mapOf("test" to listOf(0), "content" to listOf(1))
            }

            override fun canProcess(path: Path): Boolean = Files.exists(path)
        }

        ThreadPoolTaskExecutor(2).use { executor ->
            var indexingCompleted = false

            executor.scheduleIndex(tempFile, mockFileProcessor) {
                indexingCompleted = true
            }

            Thread.sleep(500)
            assertTrue(indexingCompleted, "Indexing should complete successfully")
        }

        Files.deleteIfExists(tempFile)
    }

    @Test
    fun `multiple file processing works correctly`() {
        val file1 = Files.createTempFile("multi1", ".txt")
        val file2 = Files.createTempFile("multi2", ".txt")
        Files.write(file1, "content1".toByteArray())
        Files.write(file2, "content2".toByteArray())

        val mockFileProcessor = object : FileProcessor {
            override fun processFile(path: Path): Set<String> = setOf("content")
            override fun processFileWithPositions(path: Path): Map<String, List<Int>> {
                return mapOf("content" to listOf(0))
            }

            override fun canProcess(path: Path): Boolean = Files.exists(path)
        }

        ThreadPoolTaskExecutor(2).use { executor ->
            var completed1 = false
            var completed2 = false

            executor.scheduleIndex(file1, mockFileProcessor) { completed1 = true }
            executor.scheduleIndex(file2, mockFileProcessor) { completed2 = true }

            Thread.sleep(300)

            assertTrue(completed1, "File 1 should be processed")
            assertTrue(completed2, "File 2 should be processed")
        }

        Files.deleteIfExists(file1)
        Files.deleteIfExists(file2)
    }

    @Test
    fun `rapid scheduling of same file does not crash`() {
        val tempFile = Files.createTempFile("rapid_test", ".txt")
        Files.write(tempFile, "content".toByteArray())

        val mockFileProcessor = object : FileProcessor {
            override fun processFile(path: Path): Set<String> {
                Thread.sleep(10)
                return setOf("content")
            }

            override fun processFileWithPositions(path: Path): Map<String, List<Int>> {
                Thread.sleep(10)
                return mapOf("content" to listOf(0))
            }

            override fun canProcess(path: Path): Boolean = Files.exists(path)
        }

        ThreadPoolTaskExecutor(2).use { executor ->
            repeat(5) {
                executor.scheduleIndex(tempFile, mockFileProcessor) {}
            }
            Thread.sleep(500)
            assertTrue(true, "Rapid scheduling should not cause crashes")
        }

        Files.deleteIfExists(tempFile)
    }

    @Test
    fun `getCompletionFuture returns completed future when no work is pending`() {
        ThreadPoolTaskExecutor().use { executor ->
            val future = executor.getCompletionFuture()

            assertTrue(future.isDone, "Future should be completed when no work is pending")
            assertDoesNotThrow { future.get(100, TimeUnit.MILLISECONDS) }
        }
    }

    @Test
    fun `getCompletionFuture returns active future when work is pending`() {
        val tempFile = Files.createTempFile("pending_test", ".txt")
        Files.write(tempFile, "content".toByteArray())

        val mockFileProcessor = createMockFileProcessor()

        ThreadPoolTaskExecutor().use { executor ->
            val workStarted = AtomicBoolean(false)
            val allowCompletion = AtomicBoolean(false)

            executor.scheduleIndex(tempFile, mockFileProcessor) {
                workStarted.set(true)
                while (!allowCompletion.get()) {
                    Thread.sleep(10) // Wait for signal
                }
            }

            while (!workStarted.get()) {
                Thread.sleep(10)
            }

            val future = executor.getCompletionFuture()
            assertFalse(future.isDone, "Future should not be completed while work is pending")

            allowCompletion.set(true)

            assertDoesNotThrow { future.get(1000, TimeUnit.MILLISECONDS) }
            assertTrue(future.isDone, "Future should be completed after work finishes")
        }

        Files.deleteIfExists(tempFile)
    }

    @Test
    fun `first file creates new batch`() {
        val tempFile = Files.createTempFile("batch_test", ".txt")
        Files.write(tempFile, "content".toByteArray())

        val mockFileProcessor = createMockFileProcessor()

        ThreadPoolTaskExecutor().use { executor ->
            val future1 = executor.getCompletionFuture()
            assertTrue(future1.isDone, "Initial state should have completed future")

            executor.scheduleIndex(tempFile, mockFileProcessor) {}

            val future2 = executor.getCompletionFuture()
            assertNotSame(future1, future2, "New batch should create new future")

            future2.get(1000, TimeUnit.MILLISECONDS)
        }

        Files.deleteIfExists(tempFile)
    }

    @Test
    fun `subsequent files join existing batch`() {
        val file1 = Files.createTempFile("batch1", ".txt")
        val file2 = Files.createTempFile("batch2", ".txt")
        Files.write(file1, "content1".toByteArray())
        Files.write(file2, "content2".toByteArray())

        val allowCompletion = AtomicBoolean(false)
        val mockFileProcessor = object : FileProcessor {
            override fun processFile(path: Path): Set<String> = setOf("content")
            override fun processFileWithPositions(path: Path): Map<String, List<Int>> {
                return mapOf("content" to listOf(0))
            }

            override fun canProcess(path: Path): Boolean = Files.exists(path)
        }

        ThreadPoolTaskExecutor().use { executor ->
            executor.scheduleIndex(file1, mockFileProcessor) {
                while (!allowCompletion.get()) {
                    Thread.sleep(10)
                }
            }
            val batchFuture = executor.getCompletionFuture()

            Thread.sleep(50)

            executor.scheduleIndex(file2, mockFileProcessor) {
                // This one can complete immediately
            }
            val sameBatchFuture = executor.getCompletionFuture()

            assertSame(batchFuture, sameBatchFuture, "Second file should join existing batch")

            allowCompletion.set(true)

            batchFuture.get(1000, TimeUnit.MILLISECONDS)
            assertTrue(sameBatchFuture.isDone, "Same future should be completed")
        }

        Files.deleteIfExists(file1)
        Files.deleteIfExists(file2)
    }

    @Test
    fun `batch completes only when all files are processed`() {
        val file1 = Files.createTempFile("complete1", ".txt")
        val file2 = Files.createTempFile("complete2", ".txt")
        Files.write(file1, "content1".toByteArray())
        Files.write(file2, "content2".toByteArray())

        ThreadPoolTaskExecutor().use { executor ->
            val file1Complete = AtomicBoolean(false)
            val file2Complete = AtomicBoolean(false)
            val allowFile2 = AtomicBoolean(false)

            val mockFileProcessor = object : FileProcessor {
                override fun processFile(path: Path): Set<String> = setOf("content")
                override fun processFileWithPositions(path: Path): Map<String, List<Int>> {
                    return mapOf("content" to listOf(0))
                }

                override fun canProcess(path: Path): Boolean = Files.exists(path)
            }

            executor.scheduleIndex(file1, mockFileProcessor) {
                file1Complete.set(true)
            }

            executor.scheduleIndex(file2, mockFileProcessor) {
                while (!allowFile2.get()) {
                    Thread.sleep(10)
                }
                file2Complete.set(true)
            }

            val batchFuture = executor.getCompletionFuture()

            // Wait for file1 to complete
            while (!file1Complete.get()) {
                Thread.sleep(10)
            }

            assertFalse(batchFuture.isDone, "Batch should not complete until all files are done")

            allowFile2.set(true)

            batchFuture.get(1000, TimeUnit.MILLISECONDS)
            assertTrue(file2Complete.get(), "File2 should be completed")
        }

        Files.deleteIfExists(file1)
        Files.deleteIfExists(file2)
    }

    @Test
    fun `multiple threads can schedule work concurrently`() {
        val numThreads = 5
        val filesPerThread = 3
        val files = mutableListOf<Path>()

        // Create test files
        repeat(numThreads * filesPerThread) { i ->
            val file = Files.createTempFile("thread_test_$i", ".txt")
            Files.write(file, "content$i".toByteArray())
            files.add(file)
        }

        val mockFileProcessor = createMockFileProcessor()
        val completedCount = AtomicInteger(0)

        ThreadPoolTaskExecutor().use { executor ->
            val threads = (0 until numThreads).map { threadIndex ->
                thread {
                    repeat(filesPerThread) { fileIndex ->
                        val fileIdx = threadIndex * filesPerThread + fileIndex
                        executor.scheduleIndex(files[fileIdx], mockFileProcessor) {
                            completedCount.incrementAndGet()
                        }
                    }
                }
            }

            threads.forEach { it.join() }

            val batchFuture = executor.getCompletionFuture()
            batchFuture.get(2000, TimeUnit.MILLISECONDS)

            assertEquals(
                numThreads * filesPerThread, completedCount.get(),
                "All scheduled files should be processed"
            )
        }

        // Cleanup
        files.forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun `reprocessing files stay in batch until truly complete`() {
        val tempFile = Files.createTempFile("reprocess_test", ".txt")
        Files.write(tempFile, "content".toByteArray())

        val processCount = AtomicInteger(0)

        ThreadPoolTaskExecutor().use { executor ->
            val mockFileProcessor = object : FileProcessor {
                override fun processFile(path: Path): Set<String> = setOf("content")
                override fun processFileWithPositions(path: Path): Map<String, List<Int>> {
                    return mapOf("content" to listOf(0))
                }

                override fun canProcess(path: Path): Boolean = Files.exists(path)
            }

            executor.scheduleIndex(tempFile, mockFileProcessor) {
                processCount.incrementAndGet()
            }

            executor.scheduleIndex(tempFile, mockFileProcessor) {
                processCount.incrementAndGet()
            }

            val batchFuture = executor.getCompletionFuture()
            batchFuture.get(2000, TimeUnit.MILLISECONDS)

            assertEquals(2, processCount.get(), "Both original and reprocessed work should complete")
        }

        Files.deleteIfExists(tempFile)
    }

    @Test
    fun `files that cannot be processed are ignored`() {
        val validFile = Files.createTempFile("valid", ".txt")
        val invalidFile = Files.createTempFile("invalid", ".txt")
        Files.write(validFile, "content".toByteArray())
        Files.deleteIfExists(invalidFile) // Make it invalid

        val processedCount = AtomicInteger(0)

        val mockFileProcessor = object : FileProcessor {
            override fun processFile(path: Path): Set<String> = setOf("content")
            override fun processFileWithPositions(path: Path): Map<String, List<Int>> {
                return mapOf("content" to listOf(0))
            }

            override fun canProcess(path: Path): Boolean = Files.exists(path)
        }

        ThreadPoolTaskExecutor().use { executor ->
            executor.scheduleIndex(validFile, mockFileProcessor) {
                processedCount.incrementAndGet()
            }

            executor.scheduleIndex(invalidFile, mockFileProcessor) {
                processedCount.incrementAndGet()
            }

            val batchFuture = executor.getCompletionFuture()
            batchFuture.get(1000, TimeUnit.MILLISECONDS)

            assertEquals(1, processedCount.get(), "Only valid file should be processed")
        }

        Files.deleteIfExists(validFile)
    }

    private fun createMockFileProcessor(): FileProcessor {
        return object : FileProcessor {
            override fun processFile(path: Path): Set<String> = setOf("content")
            override fun processFileWithPositions(path: Path): Map<String, List<Int>> {
                return mapOf("content" to listOf(0))
            }

            override fun canProcess(path: Path): Boolean = Files.exists(path)
        }
    }
}
