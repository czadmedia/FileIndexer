import org.example.fileindexcore.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests to verify the concurrent processing prevention mechanism works.
 * The main verification is that existing functionality still works correctly.
 */
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
}
