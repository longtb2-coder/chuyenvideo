package ai.longkaka.seller.media

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Local index only: source videos remain in shared Movies/LONGKAKA SELLER AI.
 * This database never copies, renames or deletes the user's media files.
 */
class VideoLibraryDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_VIDEOS (
                id TEXT PRIMARY KEY NOT NULL,
                display_name TEXT NOT NULL,
                content_uri TEXT NOT NULL UNIQUE,
                mime_type TEXT NOT NULL,
                size_bytes INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL,
                width INTEGER NOT NULL,
                height INTEGER NOT NULL,
                source TEXT NOT NULL,
                imported_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_videos_imported_at ON $TABLE_VIDEOS(imported_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Keep existing user data; add explicit migrations before increasing the version.
    }

    fun save(video: ImportedVideo) {
        val values = ContentValues().apply {
            put("id", video.id)
            put("display_name", video.displayName)
            put("content_uri", video.contentUri)
            put("mime_type", video.mimeType)
            put("size_bytes", video.sizeBytes)
            put("duration_ms", video.durationMs)
            put("width", video.width)
            put("height", video.height)
            put("source", video.source.name)
            put("imported_at", video.importedAt)
        }
        writableDatabase.insertWithOnConflict(TABLE_VIDEOS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getAll(): List<ImportedVideo> {
        val videos = mutableListOf<ImportedVideo>()
        readableDatabase.query(TABLE_VIDEOS, null, null, null, null, null, "imported_at DESC").use { cursor ->
            while (cursor.moveToNext()) {
                videos += ImportedVideo(
                    id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                    displayName = cursor.getString(cursor.getColumnIndexOrThrow("display_name")),
                    contentUri = cursor.getString(cursor.getColumnIndexOrThrow("content_uri")),
                    mimeType = cursor.getString(cursor.getColumnIndexOrThrow("mime_type")),
                    sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow("size_bytes")),
                    durationMs = cursor.getLong(cursor.getColumnIndexOrThrow("duration_ms")),
                    width = cursor.getInt(cursor.getColumnIndexOrThrow("width")),
                    height = cursor.getInt(cursor.getColumnIndexOrThrow("height")),
                    source = runCatching {
                        ImportSource.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("source")))
                    }.getOrDefault(ImportSource.DEVICE),
                    importedAt = cursor.getLong(cursor.getColumnIndexOrThrow("imported_at"))
                )
            }
        }
        return videos
    }

    fun delete(id: String): Int =
        writableDatabase.delete(TABLE_VIDEOS, "id = ?", arrayOf(id))

    companion object {
        private const val DATABASE_NAME = "longkaka_video_library.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_VIDEOS = "imported_videos"
    }
}
