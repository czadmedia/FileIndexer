import org.example.fileindexcore.FileIndexService
import org.example.fileindexcore.SimpleWordTokenizer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class FileIndexServiceTest {

    @Test
    fun `indexes basic directory and returns matching file`() {
        val root = tmpDir()
        val path = root.resolve("a.txt")

        write(path, "kotlin")

        FileIndexService(SimpleWordTokenizer()).use { service ->
            service.index(listOf(root))

            awaitTrue {
                val hits = service.query("kotlin")
                hits.contains(path)
            }
        }
    }

    @Test
    fun `indexes basic directory and returns matching multiple files`() {
        val root = tmpDir()
        val path1 = root.resolve("a.txt")
        val path2 = root.resolve("b.md")

        write(path1, "kotlin ketchup")
        write(path2, "kotlin world")

        FileIndexService(SimpleWordTokenizer()).use { service ->
            service.index(listOf(root))

            awaitTrue {
                val hits = service.query("kotlin")
                hits.contains(path1) && hits.contains(path2)
            }
        }
    }

    @Test
    fun `reindexing a single file updates map`() {
        val root = tmpDir()
        val path = root.resolve("doc.txt")
        val alpha = "alpha"
        val bravo = "bravo"
        val charlie = "charlie"

        write(path, "$alpha $bravo")
        FileIndexService(SimpleWordTokenizer()).use { service ->
            service.index(listOf(root))
            awaitTrue { service.query(alpha).contains(path) && service.query(bravo).contains(path) }
            write(path, "$alpha $charlie")
            service.index(listOf(path))

            awaitTrue {
                bravo !in service.dumpIndex().keys || !service.query(bravo).contains(path)
            }
            awaitTrue { service.query(charlie).contains(path) }

            assertFalse(service.query(bravo).contains(path))
            assertTrue(service.query(alpha).contains(path))
        }
    }

    @Test
    fun `duplicate tokens in a single file do not create duplicate file entries`() {
        val dir = tmpDir()
        val path = dir.resolve("repeat.txt")
        val echo = "echo"

        write(path, "$echo ".repeat(5))

        FileIndexService(SimpleWordTokenizer()).use { service ->
            service.index(listOf(path))

            awaitTrue { service.query(echo).count { it == path } == 1 }
        }
    }

    @Test
    fun `tokens disappear when a file is removed from a watched directory`() {
        val dir = tmpDir()
        val path = dir.resolve("doc.txt")
        val alpha = "alpha"

        write(path, alpha)

        FileIndexService(SimpleWordTokenizer()).use { service ->
            service.startWatching(listOf(dir))
            service.index(listOf(dir))

            awaitTrue { service.query(alpha).contains(path) }

            Files.deleteIfExists(path)

            awaitTrue(watcherTimeout) {
                !service.query(alpha).contains(path)
            }
        }
    }

    @Test
    fun `tokens appear when a new file is created in a watched directory`() {
        val dir = tmpDir()
        val path = dir.resolve("new.txt")
        val alpha = "alpha"

        FileIndexService(SimpleWordTokenizer()).use { service ->
            service.startWatching(listOf(dir))
            service.index(listOf(dir))

            assertTrue(service.query(alpha).isEmpty())

            write(path, alpha)

            awaitTrue(watcherTimeout) {
                service.query(alpha).contains(path)
            }
        }
    }

    private fun tmpDir(): Path = Files.createTempDirectory("file_index_service_test_")

    private fun write(p: Path, text: String) {
        Files.createDirectories(p.parent)
        Files.write(p, text.toByteArray(),
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
    }

    private fun awaitTrue(timeoutMs: Long = 2000, stepMs: Long = 20, predicate: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (predicate()) return
            Thread.sleep(stepMs)
        }
        fail("Condition not met within ${timeoutMs}ms")
    }
}

private const val watcherTimeout: Long = 20_000