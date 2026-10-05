/*
 * SearchQuery.kt (commonMain)
 * ---------------------------
 * The search expression language shared by the "Search this tree" field
 * and search nodes (`{{search: …}}`, see [SearchNode]):
 *
 *     #work #urgent                  both (a space means AND)
 *     #work AND (#urgent OR #today)  grouping
 *     #work -#done                   NOT, short form
 *     #project AND NOT #archived     NOT, long form
 *     "call anna" OR #phone          exact phrase
 *     #proj*                         any tag starting with #proj
 *     in:/Work/Projects #urgent      another tree (vault-rooted path)
 *     #timeline order:reverse        results in reverse order
 *     #todo is:open                  not done (LBR-24); `is:done` the done ones
 *
 * - `AND`, `OR`, `NOT` are operators only in capitals, so the words "and"
 *   / "or" can still be searched for. NOT binds tightest, then AND, then OR.
 * - A word or phrase matches as a substring of a line's visible text; a
 *   `#tag` matches a whole tag of the line *or of any item above it* (a
 *   tag on a parent counts for its children), `#tag*` a tag prefix.
 * - `in:` (top level, anywhere in the query) replaces the default tree;
 *   `in:/` is the whole vault. Its path is on-disk folder names, like a
 *   vault link's (percent-encoding accepted); quote it to use spaces.
 * - `is:done` matches a done line — its item's whole title struck through,
 *   or an item above it done ([DoneState]; inherited like a tag, across
 *   files); `is:open` is `-is:done`. Both combine with everything.
 * - `order:reverse` (top level, anywhere) lists the results in reverse
 *   order — the deepest, last files first; `order:normal` is the default.
 * - Parsing never fails: a half-typed query (an open parenthesis, a
 *   trailing `OR`) means what it says so far, so results follow typing.
 *
 * Pure: no state, commonMain only. Evaluated per line by `TextIndex.search`.
 */

package se.soderbjorn.lunarbor.data

import se.soderbjorn.lunarbor.platform.toNfc

/**
 * A parsed search expression.
 *
 * @property expr The condition a line must meet, or `null` for a query
 *   with no terms (it matches nothing — the field is "empty").
 * @property scopePath The `in:` tree, vault-relative (`""`: the whole
 *   vault), or `null` to search the default tree.
 * @property reversed `true` when the query says `order:reverse`.
 */
data class SearchQuery(val expr: Expr?, val scopePath: String?, val reversed: Boolean = false) {

    /** `true` when the query has no terms: nothing is searched. */
    val isEmpty: Boolean get() = expr == null

    /**
     * The positive words, phrases and tags (normalized) — what a result
     * list highlights. Terms under a NOT are left out.
     */
    fun highlightTerms(): List<String> {
        val out = ArrayList<String>()
        fun walk(e: Expr) {
            when (e) {
                is Expr.Word -> out += e.text
                is Expr.Phrase -> out += e.text
                is Expr.Tag -> out += "#" + e.name
                is Expr.And -> e.parts.forEach(::walk)
                is Expr.Or -> e.parts.forEach(::walk)
                is Expr.Not -> Unit
                Expr.Done -> Unit
            }
        }
        expr?.let(::walk)
        return out
    }

    /**
     * A condition on one line. Every text here is normalized (NFC,
     * lowercase); a tag's [Tag.name] has no `#`.
     */
    sealed class Expr {
        /**
         * `true` when a line with normalized visible [text] and effective
         * [tags] (its own and its ancestors', normalized, without `#`)
         * meets this condition. [done] is the line's done flag, its own or
         * inherited ([DoneState]; computed at index time by `TextIndex`).
         */
        abstract fun matches(text: String, tags: Set<String>, done: Boolean = false): Boolean

        /** `is:done`: the line is done (`is:open` is its [Not]). */
        object Done : Expr() {
            override fun matches(text: String, tags: Set<String>, done: Boolean) = done
            override fun toString(): String = "Done"
        }

        /** A word: a substring of the line's text. */
        data class Word(val text: String) : Expr() {
            override fun matches(text: String, tags: Set<String>, done: Boolean) = this.text in text
        }

        /** A quoted phrase: a substring of the line's text. */
        data class Phrase(val text: String) : Expr() {
            override fun matches(text: String, tags: Set<String>, done: Boolean) = this.text in text
        }

        /** `#name` (a whole tag) or `#name*` ([prefix]: any tag starting with it). */
        data class Tag(val name: String, val prefix: Boolean) : Expr() {
            override fun matches(text: String, tags: Set<String>, done: Boolean) =
                if (prefix) tags.any { it.startsWith(name) } else name in tags
        }

        /** All of [parts]. */
        data class And(val parts: List<Expr>) : Expr() {
            override fun matches(text: String, tags: Set<String>, done: Boolean) = parts.all { it.matches(text, tags, done) }
        }

        /** Any of [parts]. */
        data class Or(val parts: List<Expr>) : Expr() {
            override fun matches(text: String, tags: Set<String>, done: Boolean) = parts.any { it.matches(text, tags, done) }
        }

        /** Not [part]. */
        data class Not(val part: Expr) : Expr() {
            override fun matches(text: String, tags: Set<String>, done: Boolean) = !part.matches(text, tags, done)
        }
    }

    companion object {
        /**
         * Parses [query] (see the file header for the language). Never
         * throws; whatever cannot be read is ignored.
         */
        fun parse(query: String?): SearchQuery {
            val tokens = lex(query.orEmpty())
            var scope: String? = null
            var reversed = false
            val rest = tokens.filter { t ->
                when (t) {
                    is Token.Scope -> { scope = t.path; false }
                    is Token.Order -> { reversed = t.reversed; false }
                    else -> true
                }
            }
            val parser = Parser(rest)
            val expr = parser.orExpr(nested = false)
            return SearchQuery(expr, scope, reversed)
        }

        /** Normalizes text for matching: NFC, lowercase. */
        fun normalize(text: String): String = text.toNfc().lowercase()

        private sealed class Token {
            object LParen : Token()
            object RParen : Token()
            object And : Token()
            object Or : Token()
            object Not : Token()
            data class Term(val expr: Expr) : Token()
            data class Scope(val path: String) : Token()
            data class Order(val reversed: Boolean) : Token()
        }

        private fun lex(text: String): List<Token> {
            val out = ArrayList<Token>()
            var i = 0
            fun readQuoted(start: Int): Pair<String, Int> {
                val end = text.indexOf('"', start)
                return if (end < 0) text.substring(start) to text.length else text.substring(start, end) to end + 1
            }
            while (i < text.length) {
                val c = text[i]
                when {
                    c.isWhitespace() -> i++
                    c == '(' -> { out += Token.LParen; i++ }
                    c == ')' -> { out += Token.RParen; i++ }
                    c == '"' -> {
                        val (phrase, next) = readQuoted(i + 1)
                        if (phrase.isNotBlank()) out += Token.Term(Expr.Phrase(normalize(phrase)))
                        i = next
                    }
                    // `-` right before a term, a phrase or a group: NOT.
                    c == '-' && i + 1 < text.length && !text[i + 1].isWhitespace() -> { out += Token.Not; i++ }
                    else -> {
                        if (text.startsWith("in:", i)) {
                            val (raw, next) = if (i + 3 < text.length && text[i + 3] == '"') {
                                readQuoted(i + 4)
                            } else {
                                var j = i + 3
                                while (j < text.length && !text[j].isWhitespace() && text[j] != '(' && text[j] != ')') j++
                                text.substring(i + 3, j) to j
                            }
                            out += Token.Scope(scopePathOf(raw))
                            i = next
                            continue
                        }
                        var j = i
                        while (j < text.length && !text[j].isWhitespace() && text[j] != '(' && text[j] != ')' && text[j] != '"') j++
                        val word = text.substring(i, j)
                        i = j
                        out += when (word.lowercase()) {
                            "order:reverse", "order:desc" -> Token.Order(reversed = true)
                            "order:normal", "order:asc" -> Token.Order(reversed = false)
                            "is:done" -> Token.Term(Expr.Done)
                            "is:open" -> Token.Term(Expr.Not(Expr.Done))
                            else -> null
                        } ?: when (word) {
                            "AND" -> Token.And
                            "OR" -> Token.Or
                            "NOT" -> Token.Not
                            else -> Token.Term(termOf(word))
                        }
                    }
                }
            }
            return out
        }

        /** A bare word: a `#tag` (or `#tag*`) when it is one, else a word. */
        private fun termOf(word: String): Expr {
            if (word.length >= 2 && word[0] == '#' && word[1].isLetter()) {
                val prefix = word.endsWith("*")
                val name = word.substring(1).removeSuffix("*")
                if (name.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
                    return Expr.Tag(normalize(name), prefix)
                }
            }
            return Expr.Word(normalize(word))
        }

        /**
         * The vault-relative folder an `in:` path names: leading / trailing
         * slashes dropped, percent-encoding accepted.
         */
        private fun scopePathOf(raw: String): String {
            val path = raw.trim().trim('/')
            if (path.isEmpty()) return ""
            return LunarborLink.parseRooted("/$path") ?: path
        }

        /**
         * Recursive-descent parser, lenient: a missing `)` closes at the
         * end, a stray `)` or a dangling operator is skipped.
         */
        private class Parser(val tokens: List<Token>) {
            var pos = 0

            fun peek(): Token? = tokens.getOrNull(pos)

            /**
             * `a OR b OR …`. [nested]: inside parentheses, where a `)` ends
             * it; at the top level a stray `)` is skipped.
             */
            fun orExpr(nested: Boolean): Expr? {
                val parts = ArrayList<Expr>()
                while (pos < tokens.size) {
                    andExpr()?.let(parts::add)
                    when (peek()) {
                        Token.Or -> pos++
                        Token.RParen -> if (nested) return combine(parts, ::Or) else pos++
                        null -> break
                        else -> Unit
                    }
                }
                return combine(parts, ::Or)
            }

            private fun andExpr(): Expr? {
                val parts = ArrayList<Expr>()
                while (true) {
                    when (peek()) {
                        null, Token.Or, Token.RParen -> break
                        Token.And -> { pos++; continue }
                        else -> unary()?.let(parts::add)
                    }
                }
                return combine(parts, ::And)
            }

            private fun unary(): Expr? {
                return when (val t = peek()) {
                    Token.Not -> {
                        pos++
                        unary()?.let { Expr.Not(it) }
                    }
                    Token.LParen -> {
                        pos++
                        val inner = orExpr(nested = true)
                        if (peek() == Token.RParen) pos++
                        inner
                    }
                    is Token.Term -> { pos++; t.expr }
                    else -> { pos++; null }
                }
            }

            private fun Or(parts: List<Expr>) = Expr.Or(parts)
            private fun And(parts: List<Expr>) = Expr.And(parts)

            private fun combine(parts: List<Expr>, make: (List<Expr>) -> Expr): Expr? = when (parts.size) {
                0 -> null
                1 -> parts[0]
                else -> make(parts)
            }
        }
    }
}
