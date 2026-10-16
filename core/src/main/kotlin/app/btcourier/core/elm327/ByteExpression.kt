package app.btcourier.core.elm327

/**
 * A vehicle profile's parameter formula over the bytes of one ELM327 answer, with wican-fw's
 * `expression_parser.c` grammar (commit `a2cf687`):
 *
 * - operands: decimal numbers (`40`, `0.25`, `.2`); `B<n>` the unsigned byte n; `S<n>` byte n as
 *   a signed 8-bit value; `B<n>:<bit>` bit 0..7 of byte n (0 or 1); `[B<a>:B<b>]` bytes a..b as one
 *   unsigned big-endian number and `[S<a>:S<b>]` as a signed one (two's complement in the
 *   firmware's int8, int16, int32 or int64 accumulator by width), at most 8 bytes;
 * - binary operators `+ - * /`, `& | ^`, `<< >>`, left-associative, with precedence
 *   `| ^` < `&` < shifts < `+ -` < `* /`; parentheses;
 * - bitwise operators and shifts work on operands truncated to a 32-bit int, as the C casts do.
 *
 * There are no unary operators and no functions. Byte indexes follow wican-fw `autopid.c`: the
 * bytes of every answer line after its header, concatenated in line order with PCI bytes included
 * (see [Elm327Response]).
 *
 * Unlike the firmware, which reads past its buffer, a byte the answer does not have is a
 * [MissingByteException], and a malformed expression an [IllegalArgumentException] when compiled.
 */
class ByteExpression private constructor(
    /** The expression in reverse Polish order. */
    private val program: List<Token>,
    /** The source text, for messages. */
    val text: String,
) {
    /**
     * Evaluates against [bytes].
     *
     * @throws MissingByteException when the expression reads a byte past the end of [bytes].
     * @throws ArithmeticException on division by zero, which the firmware also rejects.
     */
    fun evaluate(bytes: ByteArray): Double {
        val stack = DoubleArray(program.size)
        var top = 0
        for (token in program) {
            when (token) {
                is Token.Operand -> {
                    stack[top++] = token.value(bytes)
                }

                is Token.Operator -> {
                    val right = stack[--top]
                    val left = stack[--top]
                    stack[top++] = token.apply(left, right)
                }

                Token.Open -> {
                    error("parentheses are resolved at compile time")
                }
            }
        }
        return stack[0]
    }

    override fun toString(): String = text

    private sealed interface Token {
        sealed interface Operand : Token {
            fun value(bytes: ByteArray): Double
        }

        data class Number(
            val number: Double,
        ) : Operand {
            override fun value(bytes: ByteArray) = number
        }

        data class ByteAt(
            val index: Int,
            val signed: Boolean,
            val bit: Int?,
        ) : Operand {
            override fun value(bytes: ByteArray): Double {
                val b = bytes.at(index)
                return when {
                    bit != null -> ((b.toInt() and 0xFF) shr bit and 1).toDouble()
                    signed -> b.toInt().toDouble()
                    else -> (b.toInt() and 0xFF).toDouble()
                }
            }
        }

        data class Range(
            val first: Int,
            val last: Int,
            val signed: Boolean,
        ) : Operand {
            override fun value(bytes: ByteArray): Double {
                var sum = 0L
                for (i in first..last) sum = (sum shl 8) or (bytes.at(i).toLong() and 0xFF)
                if (!signed) return sum.toULong().toDouble()
                // The firmware's accumulators: int8, int16, int32 for 3-4 bytes (so a 3-byte range
                // is never negative), int64 beyond.
                val bits =
                    when (last - first + 1) {
                        1 -> 8
                        2 -> 16
                        3, 4 -> 32
                        else -> 64
                    }
                return (sum shl (64 - bits) shr (64 - bits)).toDouble()
            }
        }

        enum class Operator(
            val symbol: String,
            val precedence: Int,
        ) : Token {
            OR("|", 1),
            XOR("^", 1),
            AND("&", 2),
            SHL("<<", 3),
            SHR(">>", 3),
            PLUS("+", 4),
            MINUS("-", 4),
            TIMES("*", 5),
            DIV("/", 5),
            ;

            fun apply(
                l: Double,
                r: Double,
            ): Double =
                when (this) {
                    PLUS -> l + r
                    MINUS -> l - r
                    TIMES -> l * r
                    DIV -> if (r == 0.0) throw ArithmeticException("division by zero") else l / r
                    AND -> (l.toInt() and r.toInt()).toDouble()
                    OR -> (l.toInt() or r.toInt()).toDouble()
                    XOR -> (l.toInt() xor r.toInt()).toDouble()
                    SHL -> (l.toInt() shl r.toInt()).toDouble()
                    SHR -> (l.toInt() shr r.toInt()).toDouble()
                }
        }

        data object Open : Token
    }

    companion object {
        /** wican-fw rejects ranges wider than its 64-bit accumulator. */
        private const val MAX_RANGE_BYTES = 8

        private val rangeShape = Regex("""\[([BS])(\d+):([BS])(\d+)]""")

        /** Compiles [expression] once, for evaluating it against many answers. */
        fun compile(expression: String): ByteExpression = ByteExpression(Compiler(expression).run(), expression)

        /** Compiles and evaluates [expression] against [bytes]; see [ByteExpression]. */
        fun evaluate(
            expression: String,
            bytes: ByteArray,
        ): Double = compile(expression).evaluate(bytes)

        private fun ByteArray.at(index: Int): Byte =
            if (index < size) this[index] else throw MissingByteException(index, size)

        /** Shunting-yard over the source text, checking that operands and operators alternate. */
        private class Compiler(
            private val text: String,
        ) {
            private val output = mutableListOf<Token>()
            private val operators = ArrayDeque<Token>()
            private var i = 0
            private var expectOperand = true

            fun run(): List<Token> {
                while (i < text.length) {
                    val c = text[i]
                    when {
                        c.isWhitespace() -> i++
                        c == '(' -> openParen()
                        c == ')' -> closeParen()
                        expectOperand -> operand(c)
                        else -> operator()
                    }
                }
                if (expectOperand) fail(if (output.isEmpty()) "empty expression" else "ends with an operator")
                while (operators.isNotEmpty()) {
                    val op = operators.removeLast()
                    if (op == Token.Open) fail("unclosed parenthesis")
                    output += op
                }
                return output
            }

            private fun openParen() {
                if (!expectOperand) fail("parenthesis after an operand")
                operators.addLast(Token.Open)
                i++
            }

            private fun closeParen() {
                if (expectOperand) fail("operand missing before ')'")
                while (operators.lastOrNull().let { it != null && it != Token.Open }) output += operators.removeLast()
                if (operators.removeLastOrNull() != Token.Open) fail("unmatched ')'")
                i++
            }

            private fun operand(c: Char) {
                output +=
                    when {
                        c.isDigit() || c == '.' -> number()
                        c == 'B' || c == 'S' -> byte(signed = c == 'S')
                        c == '[' -> range()
                        else -> fail("operand expected")
                    }
                expectOperand = false
            }

            private fun number(): Token {
                val start = i
                while (i < text.length && (text[i].isDigit() || text[i] == '.')) i++
                val literal = text.substring(start, i)
                return Token.Number(literal.toDoubleOrNull() ?: fail("bad number '$literal'", start))
            }

            private fun byte(signed: Boolean): Token {
                i++
                val index = digits() ?: fail("byte index expected")
                // The bit selector exists for B only; the firmware reads S<n> as a plain signed byte.
                val bit =
                    if (!signed && text.getOrNull(i) == ':') {
                        i++
                        text
                            .getOrNull(i)
                            ?.digitToIntOrNull()
                            ?.takeIf { it < 8 }
                            ?.also { i++ }
                            ?: fail("bit 0-7 expected")
                    } else {
                        null
                    }
                return Token.ByteAt(index, signed, bit)
            }

            private fun range(): Token {
                val match = rangeShape.matchAt(text, i) ?: fail("range [B<a>:B<b>] or [S<a>:S<b>] expected")
                val (kindA, a, kindB, b) = match.destructured
                if (kindA != kindB) fail("range mixes B and S")
                val first = a.toIntOrNull() ?: fail("byte index too large")
                val last = b.toIntOrNull() ?: fail("byte index too large")
                if (last < first || last - first >= MAX_RANGE_BYTES) fail("range must span 1-$MAX_RANGE_BYTES bytes")
                i = match.range.last + 1
                return Token.Range(first, last, signed = kindA == "S")
            }

            private fun digits(): Int? {
                val start = i
                while (i < text.length && text[i].isDigit()) i++
                return text.substring(start, i).toIntOrNull()
            }

            private fun operator() {
                val op =
                    Token.Operator.entries.firstOrNull { text.startsWith(it.symbol, i) }
                        ?: fail("operator expected")
                while (operators.lastOrNull().let { it is Token.Operator && it.precedence >= op.precedence }) {
                    output += operators.removeLast()
                }
                operators.addLast(op)
                i += op.symbol.length
                expectOperand = true
            }

            private fun fail(
                reason: String,
                at: Int = i,
            ): Nothing = throw IllegalArgumentException("$reason at $at in expression '$text'")
        }
    }
}

/** An expression read byte [index] of an answer that has only [available] bytes. */
class MissingByteException(
    val index: Int,
    val available: Int,
) : RuntimeException("byte B$index missing from a $available-byte answer")
