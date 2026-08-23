package dev.chirpboard.app.data.search

/**
 * FTS4 MATCH expressions are a query language, not a literal string: quotes, `-`, `*`, `^`, `:`,
 * `(`/`)` and the bare uppercase keywords `AND`/`OR`/`NOT`/`NEAR` all change parsing, and an
 * unbalanced one makes SQLite raise a syntax error mid-flow. Everything the user types therefore
 * goes through [toFtsPrefixMatchQuery], which keeps only word characters and rebuilds a safe
 * prefix query, so no input can reach SQLite as syntax.
 */
object FtsQuery {
    /** Guards against a paste turning into a thousand-term MATCH expression. */
    private const val MAX_TOKENS = 16

    /**
     * Builds a prefix MATCH expression from raw user input: tokens are the maximal runs of
     * letters and digits, lowercased (which also defuses the uppercase-only FTS keywords), each
     * suffixed with `*` and joined by whitespace, i.e. implicit AND. Returns an empty string when
     * the input carries no word characters at all — callers must skip MATCH entirely for that,
     * since `MATCH ''` is itself a syntax error.
     */
    fun toFtsPrefixMatchQuery(query: String): String {
        val tokens = mutableListOf<String>()
        val token = StringBuilder()
        for (character in query) {
            if (character.isLetterOrDigit()) {
                token.append(character)
            } else if (token.isNotEmpty()) {
                tokens.add(token.toString())
                token.clear()
            }
            if (tokens.size == MAX_TOKENS) {
                break
            }
        }
        if (token.isNotEmpty() && tokens.size < MAX_TOKENS) {
            tokens.add(token.toString())
        }
        return tokens.joinToString(separator = " ") { "${it.lowercase()}*" }
    }
}
