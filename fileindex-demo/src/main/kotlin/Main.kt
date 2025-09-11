package org.example.fileindexdemo

import org.example.fileindexcore.FileIndexService
import java.nio.file.Paths

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        // Usage:
        //   ./gradlew run --args="/test"
        //   ./gradlew run --args="--watch /some/dir README.md"
        val watchMode = args.isNotEmpty() && args[0] == "--watch"
        val targetArgs = if (watchMode) args.drop(1) else args.toList()
        val targets = targetArgs.ifEmpty { listOf("dummyDir") }
        val paths = targets.map { Paths.get(it) }

        FileIndexService().use { service ->
            service.index(paths)

            if (watchMode) {
                service.startWatching(paths)
                println("Watching ${targets.joinToString()}.\nType a word to search (Ctrl+D to exit).")

                val bufferedReader = System.`in`.bufferedReader()
                while (true) {
                    print("> ")
                    System.out.flush()

                    val query = bufferedReader.readLine() ?: break

                    val hits = service.query(query.trim())

                    if (hits.isEmpty()) println("No results.")
                    else hits.sortedBy { it.toString() }.forEach { println(" - $it") }
                }
            } else {
                Thread.sleep(300)

                val snapshot = service.dumpIndex().toSortedMap()
                println("\n=== Inverted Index (${snapshot.size} tokens) ===")

                for ((token, files) in snapshot) {
                    val list = files.map { it.toString() }.sorted()
                    println("$token -> ${list.joinToString(prefix = "[", postfix = "]")}")
                }
            }
        }
    }
}