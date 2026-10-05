package dev.autobridge.iptv

import android.content.Context
import androidx.core.content.edit
import dev.autobridge.core.crypto.SecretPrefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persisted IPTV sources, split by [IptvKind] so the TV and Radio grids stay independent.
 *
 * Credentials never touch the disk in the clear. The whole source list is written through
 * [SecretPrefs], so the username, the password and the portal URL that embeds both are encrypted
 * with a key held in the Android Keystore. Three consequences, which together are the credential
 * policy for this app:
 *
 *  - uninstalling removes the key with the app, so the stored credentials are unrecoverable
 *    afterwards even from a copy of this file taken beforehand;
 *  - the file is excluded from both cloud backup and device-to-device transfer
 *    (res/xml/data_extraction_rules.xml), so it never reaches a Google account or another handset;
 *  - a list written by an older build in plaintext is migrated on the first read.
 *
 * They are also never logged. Any new key added here holds credentials too - the seeding marker is
 * the only value in this file written in the clear, and it holds URLs of public playlists only.
 *
 * The public lists [IptvDirectory] marks as defaults are created on first read, so TV and Radio
 * have channels before anything is configured. They are ordinary sources afterwards: editable,
 * and removable for good — see [seedDefaults].
 */
object IptvSourceStore {
    private const val PREFS_NAME = "autobridge_iptv_sources"
    private const val KEY_ITEMS = "sources"
    private const val KEY_SEEDED = "seeded_defaults"

    fun list(context: Context, kind: IptvKind? = null): List<IptvSource> {
        seedDefaults(context)
        val parsed = stored(context)
        return if (kind == null) parsed else parsed.filter { it.kind == kind }
    }

    /**
     * Creates the default public lists that have never been created before.
     *
     * The URLs seeded so far are remembered, not just the fact that seeding ran. That is what makes
     * "delete" mean delete: a removed default is gone from the source list but still recorded as
     * seeded, so no later read brings it back — while a default introduced by a future version,
     * recorded nowhere yet, still arrives. Writing the sources before the marker keeps a crash in
     * between harmless: the next pass sees them already present and only writes the marker.
     */
    @Synchronized
    private fun seedDefaults(context: Context) {
        val prefs = prefs(context)
        val seededUrls = prefs.getStringSet(KEY_SEEDED, null).orEmpty()
        if (IptvDirectory.defaults().all { it.url in seededUrls }) return

        val existing = stored(context)
        val pending = IptvDirectory.pendingDefaults(
            seededUrls = seededUrls,
            existingUrls = existing.map { it.url }.toSet()
        )
        if (pending.isNotEmpty()) {
            persist(context, existing + pending.map { IptvDirectory.toSource(it, newId()) })
        }
        prefs.edit { putStringSet(KEY_SEEDED, seededUrls + IptvDirectory.defaults().map { it.url }) }
    }

    /** The stored sources, without the default-seeding pass [list] performs. */
    private fun stored(context: Context): List<IptvSource> {
        val raw = SecretPrefs.read(prefs(context), PREFS_NAME, KEY_ITEMS)
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index -> decode(array.optJSONObject(index)) }
        }.getOrDefault(emptyList())
    }

    fun find(context: Context, id: String): IptvSource? = list(context).firstOrNull { it.id == id }

    /** Adds a new source, or replaces the existing one with the same id. Returns the stored value. */
    fun save(context: Context, source: IptvSource): IptvSource {
        val current = list(context).toMutableList()
        val index = current.indexOfFirst { it.id == source.id }
        if (index >= 0) current[index] = source else current += source
        persist(context, current)
        IptvCatalog.invalidate(source.id)
        return source
    }

    fun remove(context: Context, id: String) {
        persist(context, list(context).filterNot { it.id == id })
        IptvCatalog.invalidate(id)
    }

    /** Stable id for a freshly entered source; keeps saved entries addressable across restarts. */
    fun newId(): String = "src-" + java.util.UUID.randomUUID().toString().take(8)

    private fun persist(context: Context, sources: List<IptvSource>) {
        val array = JSONArray()
        sources.forEach { source ->
            array.put(
                JSONObject()
                    .put("id", source.id)
                    .put("name", source.name)
                    .put("kind", source.kind.name)
                    .put("type", source.type.name)
                    .put("url", source.url)
                    .put("username", source.username)
                    .put("password", source.password)
            )
        }
        SecretPrefs.write(prefs(context), PREFS_NAME, KEY_ITEMS, array.toString())
    }

    private fun decode(json: JSONObject?): IptvSource? {
        val item = json ?: return null
        val id = item.optString("id").takeIf { it.isNotBlank() } ?: return null
        val url = item.optString("url").takeIf { it.isNotBlank() } ?: return null
        return IptvSource(
            id = id,
            name = item.optString("name").ifBlank { url },
            kind = runCatching { IptvKind.valueOf(item.optString("kind")) }.getOrDefault(IptvKind.TV),
            type = runCatching { IptvSourceType.valueOf(item.optString("type")) }
                .getOrDefault(IptvSourceType.M3U),
            url = url,
            username = item.optString("username"),
            password = item.optString("password")
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
