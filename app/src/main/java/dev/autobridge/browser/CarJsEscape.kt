package dev.autobridge.browser

import androidx.annotation.VisibleForTesting

/**
 * Escapes a value for safe embedding inside a single-quoted JS string literal.
 *
 * This is the only place [CarWebRenderer] turns the raw keyboard buffer into a JS string, so the
 * escaping invariant lives here and is regression-pinned by a JVM test ([CarWebRendererJsEscapeTest])
 * without needing a WebView. The two separator arms are the distinct Unicode line separator
 * (U+2028) and paragraph separator (U+2029); JS treats both as line terminators inside a string
 * literal, so both must be escaped and a later edit must not collapse them into one.
 */
internal object CarJsEscape {
    @VisibleForTesting
    fun singleQuoted(input: String): String = buildString {
        for (c in input) {
            when (c) {
                '\\' -> append("\\\\")
                '\'' -> append("\\'")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                '<' -> append("\\x3C")
                else -> append(c)
            }
        }
    }
}
