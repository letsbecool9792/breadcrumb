package com.lbc.breadcrumb.data

/**
 * Turns what the user typed into an FTS MATCH expression. Pure, so it is
 * covered by JVM tests.
 */
object FtsQuery {

    /** Anything that is not a letter or a digit. */
    private val SEPARATORS = Regex("""[^\p{L}\p{Nd}]+""")

    /**
     * Every word becomes a quoted prefix term, and all of them must match:
     * `qualcomm intern` → `"qualcomm*" "intern*"`.
     *
     * Prefix, so a word matches while it is still being typed, and "intern"
     * finds "internship". Quoted, so FTS syntax in the input -- `OR`, `NEAR`,
     * `-`, a stray `"` -- is searched for as words rather than parsed, and can
     * never make the query invalid.
     *
     * Words are split the way the unicode61 tokenizer splits them: letters and
     * digits make up a word, anything else separates words. So "Naru's" is
     * `"Naru*" "s*"`, which matches what the index made of "Naru's".
     *
     * @return null when the input has no words in it, i.e. nothing to search for.
     */
    fun matchExpression(input: String): String? {
        val words = input.split(SEPARATORS).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        return words.joinToString(" ") { "\"$it*\"" }
    }
}
