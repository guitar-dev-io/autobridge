package dev.autobridge.car

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the one template rule no other check in this project can see.
 *
 * A header action must be icon-only. `ActionsConstraints.ACTIONS_CONSTRAINTS_HEADER`,
 * `ACTIONS_CONSTRAINTS_MULTI_HEADER` and `ACTIONS_CONSTRAINTS_MAP` in androidx.car.app all leave
 * `maxCustomTitles` at 0, and the first two also set `requireActionIcons`, so an action with a
 * title and no icon is invalid in a header or a map action strip.
 *
 * Nothing catches that before a car does. `Header.Builder.addEndHeaderAction` does not validate,
 * `MULTI_HEADER` is referenced by no builder in the client library (it is there for the *host*),
 * and the Desktop Head Unit's 2022 host accepts the template — so every DHU session passed while a
 * real car's host rejected it and threw `IllegalArgumentException: Action list exceeded max number
 * of 0 actions with custom titles` back across the binder, killing the app as it opened a screen.
 * Thirteen call sites across eleven screens had this shape.
 *
 * The scan follows named helpers, not just actions built inline at the call site. `MirrorCarScreen`
 * added its speed readout to the map action strip through a `speedAction()` helper, so the earlier
 * inline-only scan passed while 0.4.3 crashed in the car the moment a valid speed reading arrived
 * and gave that titled action to `setMapActionStrip`.
 *
 * A setter only counts when it sits directly on the `Action.Builder` chain. A title applied inside
 * a lambda - `.apply { if (!mapAction) setTitle(description) }` in `CarVideoScreen` - is
 * conditional, and whether it fires depends on the call site rather than the helper.
 *
 * This is a source scan rather than a model test on purpose: building an `Action` needs a real
 * `CarIcon`, which needs Android, and the failure is a *shape* in the code that is cheap to read
 * directly and expensive to discover in a vehicle.
 */
class HeaderActionConstraintTest {
    private companion object {
        val HEADER_SINKS = listOf("setStartHeaderAction", "addEndHeaderAction", "setMapActionStrip")
        const val ACTION_BUILDER = "Action.Builder()"
        const val STRIP_BUILDER = "ActionStrip.Builder()"
    }

    /**
     * A `fun` or `val` in the same file that builds an `Action`, or a strip of them, that the sink
     * may end up receiving.
     */
    private data class Declaration(val name: String, val body: String)

    private fun carSources(): List<File> {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (!File(dir, "app/src/main/java").isDirectory) {
            dir = dir.parentFile ?: error("cannot locate app/src/main/java from ${System.getProperty("user.dir")}")
        }
        return File(dir, "app/src/main/java/dev/autobridge")
            .walkTopDown().filter { it.extension == "kt" }.toList()
    }

    /** Returns the argument text of each `sink(...)` call, with parentheses balanced. */
    private fun callArguments(source: String, sink: String): List<String> {
        val out = mutableListOf<String>()
        var from = 0
        while (true) {
            val start = source.indexOf("$sink(", from)
            if (start < 0) break
            var i = start + sink.length + 1
            var depth = 1
            while (i < source.length && depth > 0) {
                when (source[i]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                i++
            }
            out += source.substring(start + sink.length + 1, (i - 1).coerceAtLeast(start))
            from = i
        }
        return out
    }

    /**
     * Text of the declaration starting at [start], ending at the first line break that closes every
     * bracket and is not continued by a `.` - which is where a builder chain or a block body ends.
     */
    private fun declarationBody(source: String, start: Int): String {
        var depth = 0
        var i = start
        while (i < source.length) {
            when (source[i]) {
                '(', '{', '[' -> depth++
                ')', '}', ']' -> depth--
                '\n' -> if (depth <= 0 && i > start) {
                    val next = source.drop(i + 1).firstOrNull { !it.isWhitespace() }
                    if (next != '.' && next != ')') return source.substring(start, i)
                }
            }
            i++
        }
        return source.substring(start)
    }

    private fun declarations(source: String): List<Declaration> =
        Regex("""\b(?:fun|val)\s+(\w+)""").findAll(source)
            .map { Declaration(it.groupValues[1], declarationBody(source, it.range.first)) }
            .filter { it.body.contains(ACTION_BUILDER) || it.body.contains(STRIP_BUILDER) }
            .toList()

    /** Setters called directly on the `Action.Builder` chain at [start], ignoring nested lambdas. */
    private fun chainSetters(source: String, start: Int): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var i = start + ACTION_BUILDER.length
        while (i < source.length) {
            when (val c = source[i]) {
                '(', '{', '[' -> depth++
                ')', '}', ']' -> if (depth == 0) return out else depth--
                '.' -> if (depth == 0) {
                    val name = Regex("""^\.(\w+)\s*\(""").find(source.substring(i))?.groupValues?.get(1)
                    if (name == "build") return out
                    if (name != null) out += name
                }
                else -> if (depth == 0 && c == ';') return out
            }
            i++
        }
        return out
    }

    /** One complaint per `Action.Builder` chain in [text] that a header or map strip would reject. */
    private fun violations(text: String): List<String> {
        val out = mutableListOf<String>()
        var from = 0
        while (true) {
            val start = text.indexOf(ACTION_BUILDER, from)
            if (start < 0) return out
            val setters = chainSetters(text, start)
            if (setters.contains("setTitle")) out += "action has a custom title"
            if (!setters.contains("setIcon")) out += "action has no icon"
            from = start + ACTION_BUILDER.length
        }
    }

    /**
     * Every declaration in [declarations] the sink argument [argument] can reach, directly or
     * through another declaration. `setMapActionStrip(mapActions)` names a strip, the strip names
     * the actions, and the action is where the title is - so one hop is not enough.
     */
    private fun reachable(argument: String, declarations: List<Declaration>): List<Declaration> {
        val found = LinkedHashMap<String, Declaration>()
        var frontier = listOf(argument)
        while (frontier.isNotEmpty()) {
            val next = mutableListOf<String>()
            frontier.forEach { text ->
                declarations.forEach { declaration ->
                    if (found.containsKey(declaration.name)) return@forEach
                    if (!Regex("""\b${Regex.escape(declaration.name)}\b""").containsMatchIn(text)) return@forEach
                    found[declaration.name] = declaration
                    next += declaration.body
                }
            }
            frontier = next
        }
        return found.values.toList()
    }

    @Test fun noHeaderActionCarriesACustomTitleOrOmitsItsIcon() {
        val sources = carSources()
        assertTrue("found no Kotlin sources to scan", sources.isNotEmpty())

        val offenders = linkedSetOf<String>()
        sources.forEach { file ->
            val text = file.readText()
            val declarations = declarations(text)
            HEADER_SINKS.forEach { sink ->
                callArguments(text, sink).forEach { argument ->
                    violations(argument).forEach { offenders += "${file.name}: $sink(...) $it" }
                    // An action reaching the sink through a named helper is judged by what that
                    // helper builds - this is the shape that crashed MirrorCarScreen in 0.4.3.
                    reachable(argument, declarations).forEach { declaration ->
                        violations(declaration.body).forEach {
                            offenders += "${file.name}: $sink(...) via ${declaration.name}: $it"
                        }
                    }
                }
            }
        }
        assertTrue(
            "header and map-strip actions must be icon-only (maxCustomTitles=0, requireActionIcons):\n" +
                offenders.joinToString("\n") { "  $it" },
            offenders.isEmpty()
        )
    }
}
