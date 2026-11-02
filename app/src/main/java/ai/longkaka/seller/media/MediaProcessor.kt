package ai.longkaka.seller.media

/**
 * Provider-neutral contract. Real OCR, speech, translation and render adapters are added
 * behind this boundary so the UI never depends on a particular cloud vendor.
 */
interface MediaProcessor {
    suspend fun process(job: MediaJob, onProgress: suspend (Int, MediaStage) -> Unit): MediaJob
}

class ProcessorNotConfiguredException :
    IllegalStateException("Chưa cấu hình bộ xử lý media. Vui lòng thử lại sau khi dịch vụ được thiết lập.")
