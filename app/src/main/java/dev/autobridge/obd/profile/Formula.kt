package dev.autobridge.obd.profile

/**
 * A small arithmetic formula over the data bytes of an answer, such as `(A*256+B)/4`.
 *
 * Vehicle profiles are text that people share, so the formula language is deliberately tiny and
 * cannot do anything but arithmetic: numbers, the byte variables `A` to `H` (the first to the
 * eighth data byte), `+ - * /`, brackets and a leading minus. There are no functions, no names to
 * look up and no loops, and the text length and nesting are capped, so a profile from anywhere can
 * be parsed without trusting it. Anything else is a parse failure, never a guess.
 */
class Formula private constructor(private val root: Node, val source: String, val bytesNeeded: Int) {

    /** How many variables (`A`, `B`...) the formula uses; for bytes this is how many are needed. */
    val variables: Int get() = bytesNeeded


    /** The value for [data]; null when the data is too short or the result is not a finite number. */
    fun evaluate(data: IntArray): Float? = evaluate(DoubleArray(data.size) { data[it].toDouble() })

    /** The same over decimal values: how a derived gauge combines the readings of other gauges. */
    fun evaluate(values: DoubleArray): Float? {
        if (values.size < bytesNeeded) return null
        val v = root.eval(values)
        return if (v.isNaN() || v.isInfinite()) null else v.toFloat()
    }

    private sealed interface Node {
        fun eval(data: DoubleArray): Double
    }

    private class Num(val value: Double) : Node {
        override fun eval(data: DoubleArray) = value
    }

    private class Var(val index: Int) : Node {
        override fun eval(data: DoubleArray) = data[index]
    }

    private class Neg(val inner: Node) : Node {
        override fun eval(data: DoubleArray) = -inner.eval(data)
    }

    private class Bin(val op: Char, val left: Node, val right: Node) : Node {
        override fun eval(data: DoubleArray): Double {
            val a = left.eval(data)
            val b = right.eval(data)
            return when (op) {
                '+' -> a + b
                '-' -> a - b
                '*' -> a * b
                else -> if (b == 0.0) Double.NaN else a / b
            }
        }
    }

    private class Parser(private val text: String) {
        private var pos = 0
        var maxVar = -1
            private set
        private var depth = 0

        fun parse(): Node? {
            val node = expression() ?: return null
            skipSpaces()
            return if (pos == text.length) node else null
        }

        private fun skipSpaces() { while (pos < text.length && text[pos] == ' ') pos++ }

        private fun expression(): Node? {
            var left = term() ?: return null
            while (true) {
                skipSpaces()
                val op = text.getOrNull(pos)
                if (op != '+' && op != '-') return left
                pos++
                left = Bin(op, left, term() ?: return null)
            }
        }

        private fun term(): Node? {
            var left = factor() ?: return null
            while (true) {
                skipSpaces()
                val op = text.getOrNull(pos)
                if (op != '*' && op != '/') return left
                pos++
                left = Bin(op, left, factor() ?: return null)
            }
        }

        private fun factor(): Node? {
            skipSpaces()
            if (++depth > MAX_DEPTH) return null
            try {
                val c = text.getOrNull(pos) ?: return null
                return when {
                    c == '-' -> { pos++; Neg(factor() ?: return null) }
                    c == '(' -> {
                        pos++
                        val inner = expression() ?: return null
                        skipSpaces()
                        if (text.getOrNull(pos) != ')') return null
                        pos++
                        inner
                    }
                    c in 'A'..'H' -> {
                        pos++
                        maxVar = maxOf(maxVar, c - 'A')
                        Var(c - 'A')
                    }
                    c.isDigit() || c == '.' -> number()
                    else -> null
                }
            } finally {
                depth--
            }
        }

        private fun number(): Node? {
            val start = pos
            while (pos < text.length && (text[pos].isDigit() || text[pos] == '.')) pos++
            return text.substring(start, pos).toDoubleOrNull()?.let { Num(it) }
        }
    }

    companion object {
        const val MAX_LENGTH = 120
        private const val MAX_DEPTH = 24

        /** The parsed formula, or null when [text] is empty, too long, or not in the language. */
        fun parse(text: String): Formula? {
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed.length > MAX_LENGTH) return null
            val parser = Parser(trimmed)
            val node = parser.parse() ?: return null
            return Formula(node, trimmed, parser.maxVar + 1)
        }
    }
}
