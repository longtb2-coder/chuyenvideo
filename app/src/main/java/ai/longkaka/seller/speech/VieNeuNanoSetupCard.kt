package ai.longkaka.seller.speech

import android.media.MediaPlayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ai.longkaka.seller.speech.vieneu.VieNeuNanoEngine
import ai.longkaka.seller.speech.vieneu.VieNeuNanoModelStore
import ai.longkaka.seller.speech.vieneu.VieNeuChunkTiming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import ai.longkaka.seller.speech.vieneu.VieNeuSynthesisWorker

@Composable
fun VieNeuNanoSetupCard(
    sourceText: String,
    onNarrationReady: (File, List<VieNeuChunkTiming>) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var engine by remember { mutableStateOf<VieNeuNanoEngine?>(null) }
    var voices by remember { mutableStateOf(emptyList<String>()) }
    var selectedVoice by remember { mutableStateOf("Mỹ Duyên") }
    var expanded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf(if (VieNeuNanoModelStore.hasDownloadedFiles(context)) "Đã có dữ liệu VieNeu Nano. Nhấn khởi tạo để dùng offline." else "Tải mô hình VieNeu Nano miễn phí lần đầu, sau đó dùng offline.") }
    var output by remember { mutableStateOf<File?>(null) }
    var chunkTimings by remember { mutableStateOf<List<VieNeuChunkTiming>>(emptyList()) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { player?.release() }
            runCatching { engine?.close() }
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("VieNeu Nano • giọng đọc AI offline")
            Text("Mô hình ONNX + từ điển SEA-G2P. Không cần API trả phí; tải dữ liệu một lần.")
            if (busy) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            Text(status)
            Button(
                onClick = {
                    if (busy || engine != null) return@Button
                    busy = true
                    status = "Đang tải và kiểm tra mô hình VieNeu Nano…"
                    scope.launch {
                        var created: VieNeuNanoEngine? = null
                        try {
                            val dir = withContext(Dispatchers.IO) {
                                VieNeuNanoModelStore.ensureDownloaded(context) { _, _, _, bytes ->
                                    // The downloader reports per-file bytes; progress is indeterminate until a file completes.
                                    if (bytes > 0L) progress = ((bytes % (32L * 1024 * 1024)) * 100L / (32L * 1024 * 1024)).toInt()
                                }
                            }
                            status = "Đã tải mô hình. Đang nạp bốn mạng ONNX và SEA-G2P…"
                            val loaded = withContext(Dispatchers.IO) { VieNeuNanoEngine(dir, threads = 2) }
                            created = loaded
                            engine = loaded
                            voices = loaded.voices.map { it.name }
                            if (selectedVoice !in voices) selectedVoice = voices.firstOrNull { it == "Mỹ Duyên" } ?: voices.firstOrNull().orEmpty()
                            status = "VieNeu Nano sẵn sàng • ${voices.size} giọng mẫu • chạy offline."
                            progress = 100
                        } catch (e: Throwable) {
                            created?.close()
                            status = "Không khởi tạo được VieNeu Nano: ${e.message ?: e.javaClass.simpleName}"
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && engine == null,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "ĐANG TẢI / KHỞI TẠO…" else if (VieNeuNanoModelStore.hasDownloadedFiles(context)) "KHỞI TẠO Vieneu NANO" else "TẢI MÔ HÌNH VÀ KHỞI TẠO") }

            if (voices.isNotEmpty()) {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("Giọng đọc: $selectedVoice") }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    voices.forEach { name -> DropdownMenuItem(text = { Text(name) }, onClick = { selectedVoice = name; expanded = false }) }
                }
                Button(
                    onClick = {
                        if (engine == null) return@Button
                        val text = sourceText.trim().ifBlank { "Xin chào. Đây là giọng đọc thử của VieNeu Nano." }
                        val voiceForJob = selectedVoice
                        busy = true
                        progress = 0
                        status = "Đang đưa tác vụ VieNeu vào chế độ nền…"
                        scope.launch {
                            try {
                                val folder = File(context.filesDir, "LONGKAKA SELLER AI/jobs")
                                withContext(Dispatchers.IO) {
                                    check(folder.exists() || folder.mkdirs()) { "Không tạo được thư mục tác vụ." }
                                }
                                val scriptFile = File(folder, "vieneu_script_${System.currentTimeMillis()}.txt")
                                withContext(Dispatchers.IO) { scriptFile.writeText(text, Charsets.UTF_8) }
                                withContext(Dispatchers.IO) { engine?.close() }
                                engine = null
                                val request = OneTimeWorkRequestBuilder<VieNeuSynthesisWorker>()
                                    .setInputData(workDataOf(
                                        VieNeuSynthesisWorker.KEY_TEXT_FILE to scriptFile.absolutePath,
                                        VieNeuSynthesisWorker.KEY_VOICE to voiceForJob
                                    ))
                                    .build()
                                val workManager = WorkManager.getInstance(context)
                                workManager.enqueueUniqueWork(
                                    "vieneu_synthesis_${request.id}",
                                    ExistingWorkPolicy.KEEP,
                                    request
                                )
                                status = "Đang tạo giọng $voiceForJob ở chế độ nền. Bạn có thể chuyển sang ứng dụng khác."
                                workManager.getWorkInfoByIdFlow(request.id).collect { info ->
                                    if (info != null) {
                                        progress = info.progress.getInt("progress", progress)
                                        status = info.progress.getString("status")
                                            ?: when (info.state) {
                                                androidx.work.WorkInfo.State.ENQUEUED -> "Đã xếp hàng tạo giọng VieNeu."
                                                androidx.work.WorkInfo.State.RUNNING -> "Đang tạo giọng VieNeu ở chế độ nền…"
                                                androidx.work.WorkInfo.State.SUCCEEDED -> "Đã tạo xong giọng VieNeu."
                                                androidx.work.WorkInfo.State.FAILED -> "Tạo giọng thất bại: ${info.outputData.getString(VieNeuSynthesisWorker.KEY_ERROR) ?: "Lỗi không xác định"}"
                                                androidx.work.WorkInfo.State.CANCELLED -> "Tác vụ tạo giọng đã bị hủy."
                                                else -> status
                                            }
                                        if (info.state == androidx.work.WorkInfo.State.SUCCEEDED) {
                                            val path = info.outputData.getString(VieNeuSynthesisWorker.KEY_OUTPUT_FILE)
                                            val file = path?.let(::File)
                                            if (file != null && file.isFile && file.length() > 44L) {
                                                val timings = readVieNeuSrt(File(file.parentFile, "${file.nameWithoutExtension}.srt"))
                                                output = file
                                                chunkTimings = timings
                                                onNarrationReady(file, timings)
                                                player?.release()
                                                player = MediaPlayer().apply {
                                                    setDataSource(file.absolutePath)
                                                    setOnPreparedListener { it.start() }
                                                    prepareAsync()
                                                }
                                                status = "Đã tạo xong âm thanh VieNeu Nano: ${file.name}"
                                            } else status = "Tác vụ hoàn tất nhưng không tìm thấy tệp WAV."
                                        }
                                        if (info.state.isFinished) {
                                            busy = false
                                            if (info.state != androidx.work.WorkInfo.State.SUCCEEDED) scriptFile.delete()
                                        }
                                    }
                                }
                            } catch (e: Throwable) {
                                status = "Không khởi chạy được tác vụ nền: ${e.message ?: e.javaClass.simpleName}"
                                busy = false
                            }
                        }
                    },
                    enabled = !busy && engine != null,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (busy) "ĐANG TẠO GIỌNG NỀN…" else "TẠO GIỌNG VÀ NGHE THỬ") }
                output?.let { Text("Đã lưu: ${it.absolutePath}") }
            }
        }
    }
}


private fun readVieNeuSrt(file: File): List<VieNeuChunkTiming> {
    if (!file.isFile) return emptyList()
    val timestamp = Regex("""(\\d{2}):(\\d{2}):(\\d{2}),(\\d{3})""")
    fun parseTime(value: String): Long {
        val m = timestamp.find(value) ?: return 0L
        val (h, min, sec, ms) = m.destructured
        return h.toLong() * 3_600_000L + min.toLong() * 60_000L + sec.toLong() * 1_000L + ms.toLong()
    }
    return file.readText(Charsets.UTF_8).split(Regex("""\\r?\\n\\s*\\r?\\n""")).mapNotNull { block ->
        val lines = block.lines()
        val range = lines.firstOrNull { it.contains(" --> ") } ?: return@mapNotNull null
        val times = range.split(" --> ", limit = 2)
        if (times.size != 2) return@mapNotNull null
        val text = lines.dropWhile { it != range }.drop(1).joinToString("\\n").trim()
        if (text.isBlank()) null else VieNeuChunkTiming(text, parseTime(times[0]), parseTime(times[1]))
    }
}
