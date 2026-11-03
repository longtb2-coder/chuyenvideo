package ai.longkaka.seller.speech

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import ai.longkaka.seller.subtitle.VietnameseSubtitleCue
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Free on-device Vietnamese TTS. Each spoken cue is saved separately with its original video timestamp. */
class VietnameseSpeechSynthesizer(context: Context) {
    private val appContext = context.applicationContext
    data class SpeechSegment(val startMs: Long, val endMs: Long, val audioFile: File, val text: String)

    suspend fun synthesize(cues: List<VietnameseSubtitleCue>, outputDirectory: File,
        speechRate: Float = 1.0f, pitch: Float = 1.0f): List<SpeechSegment> {
        require(speechRate in 0.75f..1.25f) { "Tốc độ đọc phải từ 0,75x đến 1,25x." }
        require(pitch in 0.8f..1.2f) { "Cao độ giọng đọc không hợp lệ." }
        require(cues.isNotEmpty()) { "Chưa có phụ đề để tạo giọng đọc." }
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) error("Không tạo được thư mục lưu âm thanh.")
        val engine = createEngine()
        try {
            require(engine.isLanguageAvailable(VIETNAMESE) >= TextToSpeech.LANG_AVAILABLE &&
                engine.setLanguage(VIETNAMESE) >= TextToSpeech.LANG_AVAILABLE) {
                "Thiết bị chưa có dữ liệu giọng tiếng Việt. Hãy cài giọng tiếng Việt trong cài đặt chuyển văn bản thành giọng nói."
            }
            engine.setSpeechRate(speechRate)
            engine.setPitch(pitch)
            val segments = mutableListOf<SpeechSegment>()
            cues.sortedBy { it.startMs }.forEachIndexed { index, cue ->
                val text = cue.text.trim()
                if (text.isNotEmpty()) {
                    val file = File(outputDirectory, "vi_voice_${index + 1}.wav")
                    if (file.exists()) file.delete()
                    synthesizeOne(engine, text, file, UUID.randomUUID().toString())
                    check(file.exists() && file.length() > 44L) { "Không tạo được tệp giọng đọc câu ${index + 1}." }
                    segments += SpeechSegment(cue.startMs, cue.endMs, file, text)
                }
            }
            return segments
        } finally {
            engine.stop()
            engine.shutdown()
        }
    }

    private suspend fun createEngine(): TextToSpeech = suspendCancellableCoroutine { continuation ->
        var instance: TextToSpeech? = null
        instance = TextToSpeech(appContext) { status ->
            val engine = instance
            if (!continuation.isActive) engine?.shutdown()
            else if (status == TextToSpeech.SUCCESS && engine != null) continuation.resume(engine)
            else {
                engine?.shutdown()
                continuation.resumeWithException(IllegalStateException("Không khởi tạo được bộ đọc văn bản Android."))
            }
        }
        continuation.invokeOnCancellation { instance?.shutdown() }
    }

    private suspend fun synthesizeOne(engine: TextToSpeech, text: String, file: File, utteranceId: String) =
        suspendCancellableCoroutine<Unit> { continuation ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit
                override fun onDone(id: String?) {
                    if (id == utteranceId && continuation.isActive) continuation.resume(Unit)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    if (id == utteranceId && continuation.isActive)
                        continuation.resumeWithException(IllegalStateException("Lỗi tạo giọng đọc tiếng Việt."))
                }
                override fun onError(id: String?, errorCode: Int) = onError(id)
            })
            val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId) }
            if (engine.synthesizeToFile(text, params, file, utteranceId) == TextToSpeech.ERROR &&
                continuation.isActive) {
                continuation.resumeWithException(IllegalStateException("Không thể tổng hợp câu thoại thành tệp âm thanh."))
            }
            continuation.invokeOnCancellation { engine.stop() }
        }

    companion object { private val VIETNAMESE = Locale("vi", "VN") }
}
