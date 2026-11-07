package ai.longkaka.seller.speech.vieneu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ai.longkaka.seller.R
import java.io.File
import java.util.Locale

/**
 * Durable VieNeu synthesis job. WorkManager owns this task, not a Compose screen coroutine.
 * The WAV is written atomically by VieNeuNanoEngine and the matching SRT is saved beside it.
 */
class VieNeuSynthesisWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_TEXT_FILE = "text_file"
        const val KEY_VOICE = "voice"
        const val KEY_OUTPUT_FILE = "output_file"
        const val KEY_ERROR = "error"
        private const val CHANNEL_ID = "vieneu_synthesis"
        private const val NOTIFICATION_ID = 74021
    }

    override suspend fun doWork(): Result {
        val textFile = inputData.getString(KEY_TEXT_FILE)?.let(::File)
            ?: return Result.failure(workDataOf(KEY_ERROR to "Thiếu tệp kịch bản."))
        val voice = inputData.getString(KEY_VOICE).orEmpty()
        if (!textFile.isFile || textFile.length() == 0L || voice.isBlank()) {
            return Result.failure(workDataOf(KEY_ERROR to "Kịch bản hoặc giọng đọc không hợp lệ."))
        }
        val folder = File(applicationContext.getExternalFilesDir(null), "LONGKAKA SELLER AI/audio")
        if (!folder.exists() && !folder.mkdirs()) {
            return Result.failure(workDataOf(KEY_ERROR to "Không tạo được thư mục âm thanh."))
        }
        val output = File(folder, "seller_narration_${System.currentTimeMillis()}.wav")
        createChannel()
        setForeground(foreground("Đang chuẩn bị VieNeu Nano", 0, 100))
        var engine: VieNeuNanoEngine? = null
        return try {
            val directory = VieNeuNanoModelStore.ensureDownloaded(applicationContext) { name, index, count, _ ->
                val percent = ((index - 1) * 100 / count).coerceIn(0, 95)
                kotlinx.coroutines.runBlocking {
                    setProgress(workDataOf("progress" to percent, "status" to "Kiểm tra mô hình: $name"))
                    setForeground(foreground("Kiểm tra mô hình: $name", percent, 100))
                }
            }
            if (isStopped) return Result.failure(workDataOf(KEY_ERROR to "Tác vụ đã bị dừng."))
            engine = VieNeuNanoEngine(directory, threads = 2)
            val timings = mutableListOf<VieNeuChunkTiming>()
            val script = textFile.readText(Charsets.UTF_8)
            engine.synthesizeToWav(
                script, voice, output, steps = 8,
                onProgress = { done, total ->
                    val percent = (done * 100 / total).coerceIn(0, 100)
                    kotlinx.coroutines.runBlocking {
                        setProgress(workDataOf("progress" to percent, "status" to "Đang tạo giọng $voice: $done/$total đoạn"))
                        setForeground(foreground("Đang tạo giọng $voice: $done/$total đoạn", percent, 100))
                    }
                },
                onChunkTiming = { timings += it }
            )
            val srt = timings.mapIndexed { index, cue ->
                "${index + 1}\n${formatTime(cue.startMs)} --> ${formatTime(cue.endMs)}\n${cue.text}\n"
            }.joinToString("\n")
            File(folder, "${output.nameWithoutExtension}.srt").writeText(srt, Charsets.UTF_8)
            textFile.delete()
            setProgress(workDataOf("progress" to 100, "status" to "Đã tạo xong giọng đọc."))
            Result.success(workDataOf(KEY_OUTPUT_FILE to output.absolutePath))
        } catch (error: Throwable) {
            output.delete()
            Result.failure(workDataOf(KEY_ERROR to (error.message ?: error.javaClass.simpleName)))
        } finally {
            runCatching { engine?.close() }
        }
    }

    private fun formatTime(ms: Long): String {
        val value = ms.coerceAtLeast(0L)
        val h = value / 3_600_000L
        val m = (value % 3_600_000L) / 60_000L
        val s = (value % 60_000L) / 1_000L
        val milli = value % 1_000L
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", h, m, s, milli)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Tạo giọng VieNeu", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun foreground(message: String, progress: Int, max: Int): ForegroundInfo {
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("LONGKAKA SELLER AI • VieNeu Nano")
            .setContentText(message)
            .setOnlyAlertOnce(true)
            .setOngoing(progress < max)
            .setProgress(max, progress, false)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(NOTIFICATION_ID, notification)
    }
}
