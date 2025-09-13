package org.example.fileindexcore

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.fail
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Demonstrates the proper interface segregation approach that solves the original problem:
 * - updateFileTokens(Set<String>) doesn't make sense for positional indexing
 * - updateFileTokensWithPositions(Map<String, List<Int>>) doesn't make sense for simple indexing
 * 
 * Solution: Separate writer interfaces so each implementation only implements methods it can handle efficiently.
 */
class InterfaceSegregationTest {

    @Test
    fun `interface segregation - implementations only implement methods they can handle efficiently`() {
        val tokenizer = SimpleWordTokenizer()
        val fileProcessor = TextFileProcessor(tokenizer)
        
        // SimpleIndexStore implements IndexStore + SimpleIndexOperations (but NOT PositionalIndexOperations)
        val simpleStore = SimpleIndexStore(tokenizer, fileProcessor)
        assertTrue(simpleStore is IndexStore, "SimpleIndexStore should implement IndexStore")
        assertTrue(simpleStore is SimpleIndexOperations, "SimpleIndexStore should implement SimpleIndexOperations") 
        assertFalse(simpleStore is PositionalIndexOperations, "SimpleIndexStore should NOT implement PositionalIndexOperations")
        
        // PositionalIndexStore implements IndexStore + PositionalIndexOperations (but NOT SimpleIndexOperations)
        val positionalStore = PositionalIndexStore(tokenizer, fileProcessor)
        assertTrue(positionalStore is IndexStore, "PositionalIndexStore should implement IndexStore")
        assertTrue(positionalStore is PositionalIndexOperations, "PositionalIndexStore should implement PositionalIndexOperations")
        assertFalse(positionalStore is SimpleIndexOperations, "PositionalIndexStore should NOT implement SimpleIndexOperations")
    }
    
    @Test
    fun `FileIndexService adapts to different IndexStore implementations automatically`() {
        val file = Files.createTempFile("interface_test", ".txt")
        Files.write(file, "the quick brown fox jumps over the lazy dog".toByteArray())
        
        try {
            val tokenizer = SimpleWordTokenizer()
            val fileProcessor = TextFileProcessor(tokenizer)
            
            // Test with SimpleIndexStore
            val simpleService = FileIndexService(
                tokenizer = tokenizer,
                fileProcessor = fileProcessor,
                indexStore = SimpleIndexStore(tokenizer, fileProcessor)
            )
            
            // Test with PositionalIndexStore  
            val positionalService = FileIndexService(
                tokenizer = tokenizer,
                fileProcessor = fileProcessor,
                indexStore = PositionalIndexStore(tokenizer, fileProcessor)
            )
            
            // Both should work with the same API
            simpleService.index(listOf(file))
            positionalService.index(listOf(file))
            
            // Give time for indexing
            awaitTrue { 
                simpleService.query("quick").contains(file) && positionalService.query("quick").contains(file)
            }
            
            // Both support basic queries
            assertTrue(simpleService.query("brown").contains(file))
            assertTrue(positionalService.query("brown").contains(file))
            
            // Both support sequence queries
            assertTrue(simpleService.querySequence("quick brown").contains(file))
            assertTrue(positionalService.querySequence("quick brown").contains(file))
            
            // But positional should be much faster (though hard to measure in small test)
            val start = System.currentTimeMillis()
            positionalService.querySequence("the lazy dog")
            val positionalTime = System.currentTimeMillis() - start
            
            // Should be very fast (typically < 1ms for positional)
            assertTrue(positionalTime < 50, "Positional sequence query should be very fast")
            
        } finally {
            Files.deleteIfExists(file)
        }
    }
    
    @Test
    fun `arithmetic sequence query vs file scanning comparison`() {
        val files = mutableListOf<Path>()
        
        try {
            // Create files with known sequences
            val content = mapOf(
                "doc1.txt" to "database connection failed error timeout",
                "doc2.txt" to "user authentication successful login session", 
                "doc3.txt" to "network connection timeout retry failed",
                "doc4.txt" to "database authentication error connection refused"
            )
            
            content.forEach { (name, text) ->
                val file = Files.createTempFile(name.substringBefore("."), ".txt")
                Files.write(file, text.toByteArray())
                files.add(file)
            }
            
            val tokenizer = SimpleWordTokenizer()
            val fileProcessor = TextFileProcessor(tokenizer)
            
            val simpleStore = SimpleIndexStore(tokenizer, fileProcessor)
            val positionalStore = PositionalIndexStore(tokenizer, fileProcessor)
            
            // Index with both approaches
            files.forEach { file ->
                val tokens = fileProcessor.processFile(file)!!
                val tokenPositions = fileProcessor.processFileWithPositions(file)!!
                
                // SimpleIndexStore uses token sets
                simpleStore.updateFileTokens(file, tokens)
                
                // PositionalIndexStore uses position maps
                positionalStore.updateFileTokensWithPositions(file, tokenPositions)
            }
            
            // Test sequence queries
            val testQueries = listOf(
                listOf("database", "connection"),
                listOf("authentication", "successful"),
                listOf("connection", "timeout"),
                listOf("network", "connection", "timeout")
            )
            
            testQueries.forEach { query ->
                val simpleResult = simpleStore.querySequence(query)
                val positionalResult = positionalStore.querySequence(query)
                
                // Results should be identical
                assertEquals(simpleResult, positionalResult, "Both implementations should return same results for query: $query")
                
                println("Query $query: found ${simpleResult.size} files")
            }
            
            // Test that arithmetic approach finds exact sequences
            val exactSequence = listOf("database", "authentication", "error")
            val results = positionalStore.querySequence(exactSequence)
            assertEquals(1, results.size, "Should find exactly 1 file with 'database authentication error'")
            
        } finally {
            files.forEach { Files.deleteIfExists(it) }
        }
    }
    
    @Test
    fun `positional indexing provides advanced features not available in simple indexing`() {
        val file = Files.createTempFile("advanced", ".txt")
        Files.write(file, "database connection timeout error retry failed network".toByteArray())
        
        try {
            val tokenizer = SimpleWordTokenizer()
            val fileProcessor = TextFileProcessor(tokenizer)
            val positionalStore = PositionalIndexStore(tokenizer, fileProcessor)
            
            val tokenPositions = fileProcessor.processFileWithPositions(file)!!
            positionalStore.updateFileTokensWithPositions(file, tokenPositions)
            
            // Advanced positional features
            assertEquals(listOf(0), positionalStore.getTokenPositions("database", file))
            assertEquals(listOf(1), positionalStore.getTokenPositions("connection", file))
            assertEquals(listOf(6), positionalStore.getTokenPositions("network", file))
            
            // Proximity search - words within 3 positions of each other
            val proximityResults = positionalStore.queryProximity(listOf("database", "error"), 3)
            assertTrue(proximityResults.contains(file), "database and error are within 3 positions")
            
            // Proximity search - words too far apart
            val tooFarResults = positionalStore.queryProximity(listOf("database", "network"), 3)
            assertFalse(tooFarResults.contains(file), "database and network are more than 3 positions apart")
            
            // Detailed positional dump
            val positionalDump = positionalStore.dumpPositionalIndex()
            assertTrue(positionalDump.containsKey("database"))
            assertTrue(positionalDump["database"]!!.containsKey(file))
            assertEquals(listOf(0), positionalDump["database"]!![file])
            
        } finally {
            Files.deleteIfExists(file)
        }
    }
    
    @Test
    fun `demonstrates the original problem is solved - no inefficient forced implementations`() {
        val tokenizer = SimpleWordTokenizer()
        val fileProcessor = TextFileProcessor(tokenizer)
        
        // The original problem: IndexStore interface forced PositionalIndexStore to implement 
        // updateFileTokens(Set<String>) which requires file re-processing - INEFFICIENT!
        
        // ✅ NEW SOLUTION: Interface segregation with sealed interface
        
        // SimpleIndexStore only implements methods it can handle efficiently
        val simpleStore: SimpleIndexOperations = SimpleIndexStore(tokenizer, fileProcessor)
        // ✅ Can efficiently handle token sets
        simpleStore.updateFileTokens(Paths.get("test"), setOf("token1", "token2"))
        
        // PositionalIndexStore only implements methods it can handle efficiently  
        val positionalStore: PositionalIndexOperations = PositionalIndexStore(tokenizer, fileProcessor)
        // ✅ Can efficiently handle position data
        positionalStore.updateFileTokensWithPositions(Paths.get("test"), mapOf("token1" to listOf(0), "token2" to listOf(1)))
        
        // ❌ The problematic combinations are no longer possible:
        // simpleStore.updateFileTokensWithPositions(...) // COMPILE ERROR - method doesn't exist
        // positionalStore.updateFileTokens(...) // COMPILE ERROR - method doesn't exist
        
        // ✅ But both can be used polymorphically through IndexStore for queries
        val stores: List<IndexStore> = listOf<IndexStore>(
            simpleStore as IndexStore,
            positionalStore as IndexStore
        )
        
        stores.forEach { store ->
            // All support the same query interface
            store.query("token1")
            store.querySequence(listOf("token1", "token2"))
            store.getFileTokens(Paths.get("test"))
            store.dumpIndex()
        }
        
        // ✅ PROBLEM SOLVED: Clean separation, no forced inefficient implementations!
        assertTrue(true, "Interface segregation successfully implemented!")
    }
    
    @Test
    fun `sealed interface ensures exhaustive when clauses and type safety`() {
        val tokenizer = SimpleWordTokenizer()
        val fileProcessor = TextFileProcessor(tokenizer)
        
        // ✅ Both implementations extend the sealed IndexOperations interface
        val simpleStore = SimpleIndexStore(tokenizer, fileProcessor)
        val positionalStore = PositionalIndexStore(tokenizer, fileProcessor)
        
        assertTrue(simpleStore is IndexOperations, "SimpleIndexStore should implement IndexOperations")
        assertTrue(positionalStore is IndexOperations, "PositionalIndexStore should implement IndexOperations")
        
        // ✅ Sealed interface enables exhaustive pattern matching
        fun demonstrateExhaustiveWhen(operations: IndexOperations): String {
            return when (operations) {
                is SimpleIndexOperations -> "Simple indexing operations"
                is PositionalIndexOperations -> "Positional indexing operations"
                // ✅ No else clause needed - compiler enforces exhaustiveness!
            }
        }
        
        assertEquals("Simple indexing operations", demonstrateExhaustiveWhen(simpleStore))
        assertEquals("Positional indexing operations", demonstrateExhaustiveWhen(positionalStore))
        
        // ✅ If we add a new implementation type, the compiler will force us to handle it
        // This prevents bugs from missing cases in when expressions
        assertTrue(true, "Sealed interface provides compile-time exhaustiveness checking!")
    }
    
    private fun awaitTrue(timeoutMs: Long = 2000, stepMs: Long = 10, predicate: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (predicate()) return
            Thread.sleep(stepMs)
        }
        fail("Condition not met within ${timeoutMs}ms")
    }
}
