package ai.longkaka.seller.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.Data
import ai.longkaka.seller.R
import ai.longkaka.seller.translation.ChineseOverlayTranslator
import ai.longkaka.seller.translation.ProductCategory
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Foreground, resumable OCR + Chinese-to-Vietnamese translation job.
 * This worker deliberately stops after producing verified translation tracks;
 * it never reports a video export as completed.
 */
class MediaWorkRequest(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val inputUri = inputData.getString(KEY_INPUT_URI)?.takeIf { it.isNotBlank() }
            ?: return Result.failure(errorData("Thiếu video đầu vào."))
        val category = inputData.getString(KEY_CATEGORY)
            ?.let { name -> ProductCategory.values().firstOrNull { it.name == name } }
            ?: ProductCategory.AUTO

        return try {
            setForeground(createForegroundInfo(0, "Đang chuẩn bị quét video…"))
            val translator = ChineseOverlayTranslator(applicationContext)
            val tracks = translator.scanAndTranslate(
                videoUri = Uri.parse(inputUri),
                category = category,
                onProgress = { percent ->
                    setProgressAsync(Data.Builder().putInt(KEY_PROGRESS, percent).build())
                    setForegroundAsync(createForegroundInfo(percent, "Đang nhận diện và dịch chữ Trung…"))
                }
            )
            if (isStopped) return Result.failure(errorData("Tác vụ đã bị hủy."))
            val resultFile = writeResults(tracks)
            Result.success(
                Data.Builder()
                    .putString(KEY_RESULT_FILE, resultFile.absolutePath)
                    .putInt(KEY_RESULT_COUNT, tracks.size)
                    .putString(KEY_STAGE, "TRANSLATION_READY")
                    .build()
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(errorData(error.message ?: "Không thể quét và dịch video."))
        }
    }

    private fun writeResults(tracks: List<ai.longkaka.seller.translation.OnScreenText>): File {
        val array = JSONArray()
        tracks.forEach { track ->
            val observations = JSONArray()
            track.observations.forEach { point ->
                observations.put(JSONObject().apply {
                    put("timeMs", point.timeMs)
                    put("left", point.left)
                    put("top", point.top)
                    put("right", point.right)
                    put("bottom", point.bottom)
                })
            }
            array.put(JSONObject().apply {
                put("sourceText", track.sourceText)
                put("vietnameseText", track.vietnameseText)
                put("timeMs", track.timeMs)
                put("lastTimeMs", track.lastTimeMs)
                put("left", track.left)
                put("top", track.top)
                put("right", track.right)
                put("bottom", track.bottom)
                put("observations", observations)
            })
        }
        val directory = File(applicationContext.filesDir, "scan_results").apply { mkdirs() }
        return File(directory, "scan_${id}.json").apply { writeText(array.toString(), Charsets.UTF_8) }
    }

    private suspend fun createForegroundInfo(progress: Int, message: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Xử lý video", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("LONGKAKA SELLER AI")
            .setContentText(message)
            .setProgress(100, progress.coerceIn(0, 100), progress <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private fun errorData(message: String) =
        Data.Builder().putString(KEY_ERROR, message).build()

    companion object {
        const val KEY_INPUT_URI = "input_uri"
        const val KEY_PROJECT_ID = "project_id"
        const val KEY_CATEGORY = "category"
        const val KEY_PROGRESS = "progress"
        const val KEY_RESULT_FILE = "result_file"
        const val KEY_RESULT_COUNT = "result_count"
        const val KEY_STAGE = "stage"
        const val KEY_ERROR = "error"
        const val CHANNEL_ID = "seller_video_processing"
        private const val NOTIFICATION_ID = 4107
    }
}
