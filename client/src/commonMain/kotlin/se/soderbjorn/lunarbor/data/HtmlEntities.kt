/*
 * HtmlEntities.kt (commonMain)
 * ----------------------------
 * HTML character references as CommonMark renders them: `&amp;` → `&`,
 * `&#228;` → `ä`, `&#x2014;` → `—`. Used by [InlineMarkdownTokenizer] so
 * the editor shows the character a reference stands for (text pasted
 * from HTML, transcripts and exports often carry `&amp;`, `&quot;`, …),
 * while the file keeps the reference exactly as written.
 *
 * Covers decimal and hex numeric references and the named references
 * that turn up in practice (not the full HTML5 table); anything unknown
 * stays literal text.
 *
 * Pure — no I/O. commonMain only.
 */

package se.soderbjorn.lunarbor.data

/**
 * Decoding of HTML character references.
 *
 * ### Callers
 * - `InlineMarkdownTokenizer`'s parser, for display.
 * - [InlineMarkdownTokenizer.entityStartBefore], so Backspace deletes a
 *   whole reference.
 */
object HtmlEntities {

    /** Longest reference body considered (between `&` and `;`). */
    private const val MAX_BODY: Int = 32

    private val named: Map<String, String> = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "shy" to "­", "copy" to "©", "reg" to "®", "trade" to "™",
        "hellip" to "…", "mdash" to "—", "ndash" to "–", "minus" to "−",
        "lsquo" to "‘", "rsquo" to "’", "sbquo" to "‚", "ldquo" to "“", "rdquo" to "”", "bdquo" to "„",
        "laquo" to "«", "raquo" to "»", "lsaquo" to "‹", "rsaquo" to "›",
        "bull" to "•", "middot" to "·", "deg" to "°", "plusmn" to "±", "times" to "×", "divide" to "÷",
        "euro" to "€", "pound" to "£", "yen" to "¥", "cent" to "¢", "curren" to "¤",
        "sect" to "§", "para" to "¶", "dagger" to "†", "Dagger" to "‡", "permil" to "‰", "prime" to "′",
        "larr" to "←", "rarr" to "→", "uarr" to "↑", "darr" to "↓", "harr" to "↔",
        "lArr" to "⇐", "rArr" to "⇒", "hArr" to "⇔",
        "ne" to "≠", "le" to "≤", "ge" to "≥", "asymp" to "≈", "infin" to "∞", "check" to "✓",
        "frac12" to "½", "frac14" to "¼", "frac34" to "¾", "sup1" to "¹", "sup2" to "²", "sup3" to "³",
        "micro" to "µ", "iexcl" to "¡", "iquest" to "¿", "ordf" to "ª", "ordm" to "º", "szlig" to "ß",
        "aring" to "å", "Aring" to "Å", "auml" to "ä", "Auml" to "Ä", "ouml" to "ö", "Ouml" to "Ö",
        "uuml" to "ü", "Uuml" to "Ü", "euml" to "ë", "iuml" to "ï", "aelig" to "æ", "AElig" to "Æ",
        "oslash" to "ø", "Oslash" to "Ø", "eacute" to "é", "Eacute" to "É", "egrave" to "è", "Egrave" to "È",
        "ecirc" to "ê", "aacute" to "á", "Aacute" to "Á", "agrave" to "à", "acirc" to "â", "atilde" to "ã",
        "iacute" to "í", "igrave" to "ì", "icirc" to "î", "oacute" to "ó", "ograve" to "ò", "ocirc" to "ô",
        "otilde" to "õ", "uacute" to "ú", "ugrave" to "ù", "ucirc" to "û", "ccedil" to "ç", "Ccedil" to "Ç",
        "ntilde" to "ñ", "Ntilde" to "Ñ", "yacute" to "ý", "yuml" to "ÿ", "thorn" to "þ", "eth" to "ð",
    )

    /**
     * The reference starting with the `&` at [start] in [text], if any.
     *
     * @return The decoded text and the exclusive end (one past `;`), or
     *   `null` when [start] does not open a known, well-formed reference.
     */
    fun decodeAt(text: String, start: Int): Pair<String, Int>? {
        if (start >= text.length || text[start] != '&') return null
        val limit = minOf(text.length, start + 2 + MAX_BODY)
        var semi = -1
        for (i in start + 1 until limit) {
            val c = text[i]
            if (c == ';') { semi = i; break }
            if (!(c.isLetterOrDigit() || c == '#')) return null
        }
        if (semi <= start + 1) return null
        val decoded = decodeBody(text.substring(start + 1, semi)) ?: return null
        return decoded to semi + 1
    }

    /** Decodes a reference body (`amp`, `#228`, `#x2014`), or `null` when unknown. */
    private fun decodeBody(body: String): String? {
        if (!body.startsWith("#")) return named[body]
        val hex = body.length > 1 && (body[1] == 'x' || body[1] == 'X')
        val digits = body.substring(if (hex) 2 else 1)
        if (digits.isEmpty() || digits.length > (if (hex) 6 else 7)) return null
        if (!digits.all { if (hex) it.isDigit() || it.lowercaseChar() in 'a'..'f' else it.isDigit() }) return null
        val code = digits.toInt(if (hex) 16 else 10)
        // CommonMark: 0, surrogates and out-of-range become U+FFFD.
        if (code == 0 || code in 0xD800..0xDFFF || code > 0x10FFFF) return "�"
        return codePointToString(code)
    }

    private fun codePointToString(code: Int): String {
        if (code < 0x10000) return code.toChar().toString()
        val v = code - 0x10000
        return charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
    }
}
