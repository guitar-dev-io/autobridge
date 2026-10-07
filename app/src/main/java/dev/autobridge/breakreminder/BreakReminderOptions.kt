package dev.autobridge.breakreminder

/** The choices for how often to be told to take a break, pure so they are tested on the JVM. */
object BreakReminderOptions {
    /** Hours between reminders on offer; 0 (not listed) is off. */
    val HOURS = listOf(1, 2, 3, 4)

    /** [hours] as a delay in milliseconds, or null when it is off or not one of the choices. */
    fun intervalMs(hours: Int): Long? = if (hours in HOURS) hours * 60L * 60 * 1000 else null

    /** What is stored, forced to a choice or to off (0): a stray value never makes a silly timer. */
    fun sanitize(hours: Int): Int = if (hours in HOURS) hours else 0
}
