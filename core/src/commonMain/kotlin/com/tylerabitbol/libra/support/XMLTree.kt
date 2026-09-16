package com.tylerabitbol.libra.support

/**
 * A minimal XML tree.
 *
 * Swift built this on `XMLParser`, whose delegate is a stateful callback API;
 * building a tree once and walking it keeps the extraction readable and
 * testable, which matters more here than the allocation it costs on a document
 * of a few kilobytes.
 *
 * Kotlin Multiplatform has no XML parser in the standard library, and
 * `PLAN.md §6` allows a hand-walk in place of `xmlutil` — the only XML this
 * app reads is an SEC ownership form, a few kilobytes of plain elements with
 * no namespaces to resolve, no DTD to honour and no schema to validate.
 * Pulling in a parser for that would be a dependency carried for one file.
 */
class XMLTree(
    val name: String,
    /**
     * Kept because Form 4 links a transaction to its footnote by `id`, and
     * dropping attributes loses that link entirely.
     */
    val attributes: Map<String, String> = emptyMap(),
) {
    var text: String = ""
        internal set

    val children: MutableList<XMLTree> = mutableListOf()

    /** The first direct child with this name. */
    fun first(name: String): XMLTree? = children.firstOrNull { it.name == name }

    /**
     * The text of a named child, unwrapping the `<value>` element the SEC
     * wraps most fields in.
     */
    fun value(name: String): String? = first(name)?.valueOrText()

    /** This element's own text, or its `<value>` child's. */
    fun valueOrText(): String? {
        first("value")?.let { wrapped ->
            val inner = wrapped.text.trim()
            if (inner.isNotEmpty()) return inner
        }
        return text.trim().ifEmpty { null }
    }

    fun valueDouble(): Double? = valueOrText()?.trim()?.toDoubleOrNull()

    fun descendants(name: String): List<XMLTree> = descendants { it.name == name }

    fun descendants(matches: (XMLTree) -> Boolean): List<XMLTree> {
        val found = mutableListOf<XMLTree>()
        fun walk(node: XMLTree) {
            if (matches(node)) found.add(node)
            node.children.forEach(::walk)
        }
        walk(this)
        return found
    }

    companion object {
        /** Returns null when the document is malformed. */
        fun parse(document: String): XMLTree? {
            var root: XMLTree? = null
            val stack = ArrayDeque<XMLTree>()
            var index = 0
            val length = document.length

            while (index < length) {
                val open = document.indexOf('<', index)
                if (open < 0) break

                if (open > index) {
                    stack.lastOrNull()?.let { it.text += unescape(document.substring(index, open)) }
                }

                // Declarations, comments and CDATA carry nothing this app reads.
                if (document.startsWith("<!--", open)) {
                    val close = document.indexOf("-->", open)
                    if (close < 0) return null
                    index = close + 3
                    continue
                }
                if (document.startsWith("<![CDATA[", open)) {
                    val close = document.indexOf("]]>", open)
                    if (close < 0) return null
                    stack.lastOrNull()?.let { it.text += document.substring(open + 9, close) }
                    index = close + 3
                    continue
                }
                if (document.startsWith("<?", open) || document.startsWith("<!", open)) {
                    val close = document.indexOf('>', open)
                    if (close < 0) return null
                    index = close + 1
                    continue
                }

                val close = document.indexOf('>', open)
                if (close < 0) return null
                val inner = document.substring(open + 1, close).trim()

                when {
                    inner.startsWith("/") -> {
                        if (stack.isEmpty()) return null
                        stack.removeLast()
                    }
                    inner.endsWith("/") -> {
                        val node = element(inner.dropLast(1).trim())
                        if (root == null) root = node
                        stack.lastOrNull()?.children?.add(node)
                    }
                    else -> {
                        val node = element(inner)
                        if (root == null) root = node
                        stack.lastOrNull()?.children?.add(node)
                        stack.addLast(node)
                    }
                }
                index = close + 1
            }

            // An unclosed element means the document was truncated, which is
            // exactly the case the caller needs to hear about rather than
            // silently reading half a filing.
            return if (stack.isEmpty()) root else null
        }

        private fun element(inner: String): XMLTree {
            val name = inner.takeWhile { !it.isWhitespace() }
            // Namespace prefixes are dropped: the SEC's ownership documents use
            // none, and a filing agent that adds one should not change which
            // elements are found.
            val local = name.substringAfterLast(':')
            return XMLTree(local, parseAttributes(inner.drop(name.length)))
        }

        private fun parseAttributes(source: String): Map<String, String> {
            if (source.isBlank()) return emptyMap()
            val attributes = mutableMapOf<String, String>()
            var index = 0
            while (index < source.length) {
                while (index < source.length && source[index].isWhitespace()) index++
                val equals = source.indexOf('=', index)
                if (equals < 0) break
                val key = source.substring(index, equals).trim().substringAfterLast(':')
                var cursor = equals + 1
                while (cursor < source.length && source[cursor].isWhitespace()) cursor++
                if (cursor >= source.length) break
                val quote = source[cursor]
                if (quote != '"' && quote != '\'') break
                val end = source.indexOf(quote, cursor + 1)
                if (end < 0) break
                attributes[key] = unescape(source.substring(cursor + 1, end))
                index = end + 1
            }
            return attributes
        }

        private fun unescape(raw: String): String {
            if (!raw.contains('&')) return raw
            return raw
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                // Last, so an escaped ampersand cannot re-trigger the others.
                .replace("&amp;", "&")
        }
    }
}
