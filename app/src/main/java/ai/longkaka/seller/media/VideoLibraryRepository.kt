package ai.longkaka.seller.media

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Coroutine facade for the persistent on-device video-library index. */
class VideoLibraryRepository(context: Context) {
    private val database = VideoLibraryDatabase(context.applicationContext)

    suspend fun save(video: ImportedVideo) = withContext(Dispatchers.IO) {
        database.save(video)
    }

    suspend fun getAll(): List<ImportedVideo> = withContext(Dispatchers.IO) {
        database.getAll()
    }

    /** Removes an entry from the library only; it deliberately does not delete the media file. */
    suspend fun forgetFromLibrary(id: String) = withContext(Dispatchers.IO) {
        database.delete(id)
    }

    fun close() = database.close()
}
