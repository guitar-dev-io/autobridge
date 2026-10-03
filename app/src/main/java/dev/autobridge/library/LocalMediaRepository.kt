package dev.autobridge.library

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import dev.autobridge.R

/**
 * MediaStore-backed on-device library for the Folders, Playlists and Gallery sections.
 *
 * All queries are read-only and return content:// URIs, so playback goes through the same
 * [dev.autobridge.media.MediaSourceResolver] path as remote media and no storage paths are
 * exposed. Callers must hold the matching read permission; [missingPermissions] reports what is
 * still needed instead of throwing.
 */
object LocalMediaRepository {
    /** One playable/viewable on-device item. */
    data class Item(
        val id: Long,
        val title: String,
        /**
         * The second line to show: an artist, or a media-kind label. Display text only — callers
         * deciding *how* to play an item must read [isAudio], never match on this. It used to be
         * both, and a translated label would have routed every audio file to the video player.
         */
        val subtitle: String,
        val uri: String,
        val durationMs: Long = 0L,
        /** True for an audio track: queue it on the MediaSession rather than opening a player. */
        val isAudio: Boolean = false
    )

    /** A bucket of items: a storage folder, a MediaStore playlist, or a gallery album. */
    data class Group(val id: String, val name: String, val count: Int)

    enum class MediaType { AUDIO, VIDEO, IMAGE }

    /** Runtime permissions this device needs for [types], filtered to the ones not yet granted. */
    fun missingPermissions(context: Context, types: Set<MediaType>): List<String> =
        permissionsFor(types).filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

    fun hasPermission(context: Context, types: Set<MediaType>): Boolean =
        missingPermissions(context, types).isEmpty()

    private fun permissionsFor(types: Set<MediaType>): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            types.map {
                when (it) {
                    MediaType.AUDIO -> Manifest.permission.READ_MEDIA_AUDIO
                    MediaType.VIDEO -> Manifest.permission.READ_MEDIA_VIDEO
                    MediaType.IMAGE -> Manifest.permission.READ_MEDIA_IMAGES
                }
            }.distinct()
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    /**
     * Storage folders that contain audio or video, newest-modified first. This is the "Folders"
     * section: a flat bucket list rather than a real directory tree, because MediaStore indexes
     * by bucket and scoped storage makes arbitrary directory walking unavailable.
     */
    fun folders(context: Context): List<Group> {
        val buckets = linkedMapOf<String, Pair<String, Int>>()
        listOf(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to MediaType.AUDIO,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to MediaType.VIDEO
        ).forEach { (collection, type) ->
            if (!hasPermission(context, setOf(type))) return@forEach
            query(
                context,
                collection,
                arrayOf(MediaStore.MediaColumns.BUCKET_ID, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME),
                null,
                null
            ) { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
                val nameColumn =
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idColumn) ?: continue
                    val name = cursor.getString(nameColumn) ?: continue
                    val current = buckets[id]
                    buckets[id] = name to ((current?.second ?: 0) + 1)
                }
            }
        }
        return buckets.map { (id, value) -> Group(id, value.first, value.second) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    /** Audio and video inside one folder bucket. */
    fun folderItems(context: Context, bucketId: String): List<Item> {
        val items = mutableListOf<Item>()
        if (hasPermission(context, setOf(MediaType.AUDIO))) {
            items += mediaItems(context, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, bucketId, true)
        }
        if (hasPermission(context, setOf(MediaType.VIDEO))) {
            items += mediaItems(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, bucketId, false)
        }
        return items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }

    /**
     * MediaStore playlists. The API is deprecated from Android 11 and returns nothing on many
     * devices, so an empty list here is normal rather than an error.
     */
    @Suppress("DEPRECATION")
    fun playlists(context: Context): List<Group> {
        if (!hasPermission(context, setOf(MediaType.AUDIO))) return emptyList()
        val groups = mutableListOf<Group>()
        runCatching {
            query(
                context,
                MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Playlists._ID, MediaStore.Audio.Playlists.NAME),
                null,
                MediaStore.Audio.Playlists.NAME
            ) { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Playlists._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Playlists.NAME)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    groups += Group(id.toString(), cursor.getString(nameColumn) ?: "Playlist $id", 0)
                }
            }
        }
        return groups
    }

    @Suppress("DEPRECATION")
    fun playlistItems(context: Context, playlistId: String): List<Item> {
        val id = playlistId.toLongOrNull() ?: return emptyList()
        val items = mutableListOf<Item>()
        runCatching {
            query(
                context,
                MediaStore.Audio.Playlists.Members.getContentUri("external", id),
                arrayOf(
                    MediaStore.Audio.Playlists.Members.AUDIO_ID,
                    MediaStore.Audio.Playlists.Members.TITLE,
                    MediaStore.Audio.Playlists.Members.ARTIST,
                    MediaStore.Audio.Playlists.Members.DURATION
                ),
                null,
                MediaStore.Audio.Playlists.Members.PLAY_ORDER
            ) { cursor ->
                val idColumn =
                    cursor.getColumnIndexOrThrow(MediaStore.Audio.Playlists.Members.AUDIO_ID)
                val titleColumn =
                    cursor.getColumnIndexOrThrow(MediaStore.Audio.Playlists.Members.TITLE)
                val artistColumn =
                    cursor.getColumnIndexOrThrow(MediaStore.Audio.Playlists.Members.ARTIST)
                val durationColumn =
                    cursor.getColumnIndexOrThrow(MediaStore.Audio.Playlists.Members.DURATION)
                while (cursor.moveToNext()) {
                    val audioId = cursor.getLong(idColumn)
                    items += Item(
                        id = audioId,
                        title = cursor.getString(titleColumn) ?: "Track $audioId",
                        subtitle = cursor.getString(artistColumn).orEmpty(),
                        uri = ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, audioId
                        ).toString(),
                        durationMs = cursor.getLong(durationColumn),
                        isAudio = true
                    )
                }
            }
        }
        return items
    }

    /** Gallery albums: image and video buckets, so the section matches a normal photo app. */
    fun galleryAlbums(context: Context): List<Group> {
        val buckets = linkedMapOf<String, Pair<String, Int>>()
        listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI to MediaType.IMAGE,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to MediaType.VIDEO
        ).forEach { (collection, type) ->
            if (!hasPermission(context, setOf(type))) return@forEach
            query(
                context,
                collection,
                arrayOf(MediaStore.MediaColumns.BUCKET_ID, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME),
                null,
                null
            ) { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
                val nameColumn =
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idColumn) ?: continue
                    val name = cursor.getString(nameColumn) ?: continue
                    val current = buckets[id]
                    buckets[id] = name to ((current?.second ?: 0) + 1)
                }
            }
        }
        return buckets.map { (id, value) -> Group(id, value.first, value.second) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    fun galleryItems(context: Context, bucketId: String): List<Item> {
        val items = mutableListOf<Item>()
        if (hasPermission(context, setOf(MediaType.IMAGE))) {
            items += mediaItems(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, bucketId, false)
        }
        if (hasPermission(context, setOf(MediaType.VIDEO))) {
            items += mediaItems(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, bucketId, false)
        }
        return items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }

    /** Every on-device audio track, used as the flat "All music" fallback when no playlist exists. */
    fun allAudio(context: Context): List<Item> =
        if (hasPermission(context, setOf(MediaType.AUDIO))) {
            mediaItems(context, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, null, true)
        } else {
            emptyList()
        }

    private fun mediaItems(
        context: Context,
        collection: Uri,
        bucketId: String?,
        audio: Boolean
    ): List<Item> {
        val items = mutableListOf<Item>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.TITLE,
            MediaStore.MediaColumns.DURATION
        )
        val selection = bucketId?.let { "${MediaStore.MediaColumns.BUCKET_ID} = ?" }
        query(context, collection, projection, selection, MediaStore.MediaColumns.TITLE, bucketId) { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.TITLE)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn)?.takeIf { it.isNotBlank() }
                    ?: cursor.getString(nameColumn) ?: "Item $id"
                items += Item(
                    id = id,
                    title = title,
                    subtitle = context.getString(
                        if (audio) R.string.media_kind_audio else R.string.media_kind_media
                    ),
                    uri = ContentUris.withAppendedId(collection, id).toString(),
                    durationMs = cursor.getLong(durationColumn),
                    isAudio = audio
                )
            }
        }
        return items
    }

    private fun query(
        context: Context,
        collection: Uri,
        projection: Array<String>,
        selection: String?,
        sortOrder: String?,
        selectionArgument: String? = null,
        read: (android.database.Cursor) -> Unit
    ) {
        runCatching {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgument?.let { arrayOf(it) },
                sortOrder
            )?.use(read)
        }
    }
}
