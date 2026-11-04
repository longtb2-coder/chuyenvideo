package ai.longkaka.seller.sales

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.concurrent.atomics.ExperimentalAtomicApi

enum class SalesSpeechLanguage(val label: String, val locale: Locale) {
    CHINESE("Tiếng Trung", Locale.forLanguageTag("cmn-Hans-CN")),
    ENGLISH("Tiếng Anh", Locale.US),
    VIETNAMESE("Tiếng Việt", Locale.forLanguageTag("vi-VN"))
}

/**
 * Extracts the compressed audio track locally and transcribes it with ML Kit's
 * on-device Basic speech recognizer. No audio is uploaded to a remote server.
 */
class SalesSpeechTranscriber(private val context: Context) {
    @OptIn(ExperimentalAtomicApi::class)
    suspend fun transcribe(
        videoUri: Uri,
        language: SalesSpeechLanguage,
        onStatus: (String) -> Unit = {}
    ): String {
        val audioFile = withContext(Dispatchers.IO) { extractAudioTrack(videoUri) }
        val options = speechRecognizerOptions {
            locale = language.locale
            preferredMode = SpeechRecognizerOptions.Mode.MODE_BASIC
        }
        val recognizer = SpeechRecognition.getClient(options)
        try {
            onStatus("Đang kiểm tra khả năng nhận diện lời thoại trên điện thoại…")
            var status = recognizer.checkStatus()
            if (status == FeatureStatus.DOWNLOADABLE) {
                onStatus("Đang tải mô hình nhận diện giọng nói lần đầu. Cần kết nối Internet…")
                recognizer.download().collect { download ->
                    when (download) {
                        is DownloadStatus.DownloadStarted ->
                            onStatus("Đang tải mô hình nhận diện giọng nói…")
                        is DownloadStatus.DownloadProgress ->
                            onStatus("Đang tải mô hình nhận diện giọng nói…")
                        is DownloadStatus.DownloadFailed ->
                            throw download.e
                        is DownloadStatus.DownloadCompleted ->
                            onStatus("Đã chuẩn bị mô hình. Đang nhận diện lời thoại…")
                        else -> Unit
                    }
                }
                status = recognizer.checkStatus()
            }
            when (status) {
                FeatureStatus.AVAILABLE -> Unit
                FeatureStatus.DOWNLOADING -> error("Mô hình nhận diện đang được tải. Hãy thử lại sau ít phút.")
                FeatureStatus.UNAVAILABLE -> error(
                    "Thiết bị hoặc dịch vụ Google hiện chưa hỗ trợ nhận diện lời thoại offline cho ngôn ngữ này."
                )
                else -> error("Không thể chuẩn bị mô hình nhận diện lời thoại (mã $status).")
            }

            onStatus("Đang nghe và chuyển lời thoại gốc thành văn bản…")
            val audioSource = AudioSource.fromFile(audioFile)
            val request = speechRecognizerRequest { this.audioSource = audioSource }
            val finalTexts = mutableListOf<String>()
            try {
                recognizer.startRecognition(request).collect { response ->
                    when (response) {
                        is SpeechRecognizerResponse.PartialTextResponse ->
                            onStatus("Đang nhận diện lời thoại…")
                        is SpeechRecognizerResponse.FinalTextResponse ->
                            response.text.trim().takeIf { it.isNotBlank() }?.let(finalTexts::add)
                        is SpeechRecognizerResponse.ErrorResponse -> throw response.e
                        else -> Unit
                    }
                }
            } finally {
                audioSource.close()
            }
            return finalTexts.distinct().joinToString("\n").trim()
        } finally {
            recognizer.close()
            audioFile.delete()
        }
    }

    private fun extractAudioTrack(videoUri: Uri): File {
        val folder = File(context.cacheDir, "sales_speech").apply {
            deleteRecursively()
            mkdirs()
        }
        val output = File(folder, "source_audio.m4a")
        val descriptor = context.contentResolver.openFileDescriptor(videoUri, "r")
            ?: error("Không mở được video để trích xuất âm thanh.")
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            extractor.setDataSource(descriptor.fileDescriptor)
            val audioTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: error("Video này không có track âm thanh.")
            val format = extractor.getTrackFormat(audioTrack)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            require(mime == "audio/mp4a-latm") {
                "Định dạng âm thanh $mime chưa được hỗ trợ trích xuất an toàn. Hãy thử video MP4/AAC."
            }
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outputTrack = muxer.addTrack(format)
            muxer.start()
            muxerStarted = true
            extractor.selectTrack(audioTrack)
            val maxInputSize = runCatching {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            }.getOrDefault(1024 * 1024).coerceIn(64 * 1024, 4 * 1024 * 1024)
            val buffer = java.nio.ByteBuffer.allocate(maxInputSize)
            val info = MediaCodec.BufferInfo()
            while (true) {
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                info.offset = 0
                info.size = sampleSize
                info.presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(outputTrack, buffer, info)
                if (!extractor.advance()) break
            }
            muxer.stop()
            muxerStarted = false
            check(output.exists() && output.length() > 44L) {
                "Không trích xuất được âm thanh từ video."
            }
            return output
        } catch (error: Throwable) {
            output.delete()
            throw error
        } finally {
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            extractor.release()
            descriptor.close()
        }
    }
}
