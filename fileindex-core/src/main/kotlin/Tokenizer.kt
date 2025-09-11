package org.example.fileindexcore

fun interface Tokenizer {
    fun tokens(text: String): Sequence<String>
    fun normalizeSingleToken(token: String): String =
        token.lowercase().trim()
}

class SimpleWordTokenizer : Tokenizer {
    private val split = Regex("[^\\p{L}\\p{N}]+")
    override fun tokens(text: String): Sequence<String> = sequence {
        if (text.isEmpty()) return@sequence
        for (token in text.split(split)) {
            if (token.isBlank()) continue

            val normalizedToken = normalizeSingleToken(token)
            if (normalizedToken.isNotEmpty()) yield(normalizedToken)
        }
    }
}

class RegexTokenizer(
    private val pattern: Regex = Regex("[A-Za-z\\d]+")
) : Tokenizer {
    override fun tokens(text: String): Sequence<String> = sequence {
        pattern.findAll(text).forEach { yield(it.value.lowercase()) }
    }
}