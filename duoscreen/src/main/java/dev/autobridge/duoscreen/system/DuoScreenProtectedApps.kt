package dev.autobridge.duoscreen.system

/**
 * Apps known to keep their picture off any screen but the phone's own.
 *
 * They mark their windows secure (FLAG_SECURE) and decode their video for a protected output only
 * (Widevine), and Android draws neither onto a VirtualDisplay, which is what a pane is. Duo Screen
 * cannot change that — creating a secure display takes a permission only the system holds — so the
 * pane shows black while the sound may still play. This list is what lets the picker and the pane
 * say so instead of leaving the driver with an unexplained black square.
 *
 * Known streaming apps only, by package; an app missing here simply gets no warning. Pure, so the
 * lookup is a JVM test.
 */
object DuoScreenProtectedApps {
    private val PACKAGES = setOf(
        "com.netflix.mediaclient",
        "com.disney.disneyplus",
        "in.startv.hotstar",
        "com.amazon.avod.thirdpartyclient",
        "com.hbo.hbonow",
        "com.wbd.stream",
        "com.apple.atve.androidtv.appletv",
        "com.vuclip.viu",
        "com.iqiyi.i18n",
        "com.tencent.qqlivei18n",
    )

    fun isProtected(packageName: String?): Boolean = packageName != null && packageName in PACKAGES
}
