package dev.autobridge.desktop.runtime

/**
 * The fontconfig rule written into the Linux runtime so Thai pages are not drawn in the tlwg
 * fonts (Loma and friends) the distribution prefers for Thai, whose shapes look square and out of
 * proportion beside the Latin text. Noto Sans leads for Latin, and [thaiFamily] follows it for Thai.
 */
object GuestFontConfig {
    const val FILE = "/etc/fonts/conf.d/99-autobridge-fonts.conf"
    const val DEFAULT_THAI = "Noto Sans Thai"
    const val LOOPED_THAI = "Noto Looped Thai"

    private fun match(generic: String, vararg families: String) = buildString {
        appendLine("  <match target=\"pattern\">")
        appendLine("    <test qual=\"any\" name=\"family\"><string>$generic</string></test>")
        appendLine("    <edit name=\"family\" mode=\"prepend_first\" binding=\"strong\">")
        families.forEach { appendLine("      <string>$it</string>") }
        appendLine("    </edit>")
        appendLine("  </match>")
    }

    /** Emoji have no glyphs in the text fonts, so every generic family falls back to this one. */
    private fun emoji(generic: String) = buildString {
        appendLine("  <match target=\"pattern\">")
        appendLine("    <test qual=\"any\" name=\"family\"><string>$generic</string></test>")
        appendLine("    <edit name=\"family\" mode=\"append\" binding=\"weak\"><string>Noto Color Emoji</string></edit>")
        appendLine("  </match>")
    }

    fun render(thaiFamily: String = DEFAULT_THAI): String = buildString {
        appendLine("<?xml version=\"1.0\"?>")
        appendLine("<!DOCTYPE fontconfig SYSTEM \"fonts.dtd\">")
        appendLine("<fontconfig>")
        append(match("sans-serif", "Noto Sans", thaiFamily))
        append(match("serif", "Noto Serif", "Noto Serif Thai"))
        listOf("sans-serif", "serif", "monospace").forEach { append(emoji(it)) }
        appendLine("</fontconfig>")
    }
}
