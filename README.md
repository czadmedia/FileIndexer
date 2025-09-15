# FileIndexer

A high-performance file indexing and search library for Kotlin/Java applications.

## Features

- **🚀 Fast Indexing**: Efficient inverted index with positional support
- **🔍 Advanced Search**: Token queries, phrase search, and proximity search
- **⚡ High Performance**: Optimized data structures and algorithms
- **🔒 Thread Safe**: Production-ready concurrent indexing and querying
- **📁 File Processing**: Line-by-line processing to handle large files
- **🎯 Flexible**: Pluggable tokenizers and file processors
- **✅ Guaranteed Fresh Results**: All queries return up-to-date results automatically
- **🔄 Async by Design**: CompletableFuture-based API for non-blocking operations

## Quick Start

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("org.example:fileindex-core:0.1.0")
}
```

### Gradle (Groovy)

```groovy
dependencies {
    implementation 'org.example:fileindex-core:0.1.0'
}
```

### Maven

```xml

<dependency>
    <groupId>org.example</groupId>
    <artifactId>fileindex-core</artifactId>
    <version>0.1.0</version>
</dependency>
```

## Usage

### Basic Usage (Async with Guaranteed Up-to-Date Results)

```kotlin
// Create a file indexing service
val service = FileIndexService()

// Index a directory (async)
service.index(listOf(Paths.get("path/to/documents")))

// Search for files - returns CompletableFuture with guaranteed up-to-date results
service.query("kotlin").thenAccept { files ->
    println("Found ${files.size} files containing 'kotlin'")
}

// Phrase search - also async with guaranteed results
service.querySequence("file indexing").thenAccept { results ->
    println("Found ${results.size} files with exact phrase")
}

// If you need to block until results are ready:
val files = service.query("kotlin").get() // Blocks until indexing completes
println("Up-to-date results: ${files.size} files")
```

### Advanced Usage

```kotlin
// Custom tokenizer and positional indexing
val tokenizer = SimpleWordTokenizer()
val fileProcessor = TextFileProcessor(tokenizer)
val positionalIndex = PositionalIndexStore(tokenizer, fileProcessor)

val service = FileIndexService(
    indexStore = positionalIndex,
    tokenizer = tokenizer,
    fileProcessor = fileProcessor
)

// Enable file system watching for automatic re-indexing
service.startWatching(listOf(Paths.get("documents/")))

// Chain multiple async operations
service.index(listOf(Paths.get("documents/")))
service.query("kotlin")
    .thenCompose { kotlinFiles -> 
        if (kotlinFiles.isNotEmpty()) {
            service.querySequence("kotlin spring")
        } else {
            CompletableFuture.completedFuture(emptySet())
        }
    }
    .thenAccept { springKotlinFiles ->
        println("Found ${springKotlinFiles.size} Kotlin Spring files")
    }
```

### Manual Lifecycle Management

```kotlin
// For long-running applications, manage lifecycle manually
val service = FileIndexService()

try {
    service.index(listOf(Paths.get("documents/")))
    service.startWatching(listOf(Paths.get("documents/")))

    // Application continues running...
    val results = service.query("search term")

} finally {
    service.close() // Always clean up resources
}

// Or use automatic resource management:
FileIndexService().use { service ->
    service.index(listOf(Paths.get("documents/")))
    // Automatically closed when block exits
}
```

## API Reference

### Core Classes

- **`FileIndexService`** - Main service for indexing and querying
- **`SimpleIndexStore`** - Basic inverted index implementation
- **`PositionalIndexStore`** - Advanced index with position tracking
- **`TextFileProcessor`** - Default file processing implementation
- **`SimpleWordTokenizer`** - Basic word tokenization

### Key Methods

- **`indexPath(path)`** - Index files in a directory
- **`query(token)`** - Find files containing a token
- **`querySequence(phrase)`** - Find files with exact phrase
- **`startWatching()`** - Enable automatic file monitoring
- **`clear()`** - Clear the entire index