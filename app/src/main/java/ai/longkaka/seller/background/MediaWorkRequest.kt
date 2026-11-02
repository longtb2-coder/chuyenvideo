package ai.longkaka.seller.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * WorkManager worker foundation for resumable stages.
 * A concrete processor is injected when the media pipeline is wired.
 */
class MediaWorkRequest(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val inputUri = inputData.getString(KEY_INPUT_URI) ?: return Result.failure()
        // Do not pretend work completed: the actual media processor is connected in a later
        // integration step. Keeping this explicit prevents false-success exports.
        return if (inputUri.isBlank()) Result.failure() else Result.failure()
    }

    companion object {
        const val KEY_INPUT_URI = "input_uri"
        const val KEY_PROJECT_ID = "project_id"
    }
}
