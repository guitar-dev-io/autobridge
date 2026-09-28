package dev.autobridge.apps

/**
 * What the Applications picker actually shows, given the search box and the Quick Apps / All apps
 * choice.
 *
 * This was previously inline in the screen and only ever applied the search term: the Quick Apps
 * chip restyled itself but the list underneath never changed, so the control did nothing. Keeping
 * the rule here makes it one testable place instead of a branch buried in view code.
 */
object AppListFilter {
    /**
     * @param quickAppPackages packages currently enabled as Quick Apps.
     * @param quickOnly true when the Quick Apps chip is selected.
     * @param query free text matched against the label and, as a fallback, the package name.
     */
    fun apply(
        apps: List<InstalledApp>,
        quickAppPackages: Set<String>,
        quickOnly: Boolean,
        query: String,
    ): List<InstalledApp> {
        val scoped = if (quickOnly) apps.filter { it.packageName in quickAppPackages } else apps
        val term = query.trim()
        if (term.isEmpty()) return scoped
        return scoped.filter {
            it.label.contains(term, ignoreCase = true) || it.packageName.contains(term, ignoreCase = true)
        }
    }

    /** Message for an empty result, phrased for the reason the list is empty. */
    fun emptyMessage(quickOnly: Boolean, query: String, hasQuickApps: Boolean): String = when {
        query.isNotBlank() -> "No apps match \"${query.trim()}\""
        quickOnly && !hasQuickApps -> "No Quick Apps yet. Open All apps and tap ☆ to add one."
        quickOnly -> "No Quick Apps match this filter."
        else -> "No launchable apps found."
    }
}
