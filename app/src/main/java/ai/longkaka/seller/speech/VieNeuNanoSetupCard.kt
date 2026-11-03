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

@Composable
fun VieNeuNanoSetupCard(sourceText: String) {
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
                        val active = engine ?: return@Button
                        val text = sourceText.trim().ifBlank { "Xin chào. Đây là giọng đọc thử của VieNeu Nano. Giọng kể rõ ràng, tự nhiên và giàu cảm xúc." }
                        busy = true
                        status = "Đang tạo giọng $selectedVoice…"
                        scope.launch {
                            try {
                                val file = withContext(Dispatchers.IO) {
                                    val folder = File(context.getExternalFilesDir(null), "LONGKAKA SELLER AI/audio")
                                    check(folder.exists() || folder.mkdirs()) { "Không tạo được thư mục âm thanh." }
                                    val timings = mutableListOf<VieNeuChunkTiming>()
                                    val wav = active.synthesizeToWav(
                                        text, selectedVoice, File(folder, "vieneu_nano_preview.wav"), steps = 8,
                                        onChunkTiming = { timings += it }
                                    )
                                    val srt = timings.mapIndexed { index, cue ->
                                        val start = cue.startMs.coerceAtLeast(0L)
                                        val end = cue.endMs.coerceAtLeast(start + 1L)
                                        val fmt: (Long) -> String = { ms ->
                                            val h = ms / 3_600_000L; val m = (ms % 3_600_000L) / 60_000L
                                            val s = (ms % 60_000L) / 1_000L; val milli = ms % 1_000L
                                            String.format(java.util.Locale.ROOT, "%02d:%02d:%02d,%03d", h, m, s, milli)
                                        }
                                        "${index + 1}\n${fmt(start)} --> ${fmt(end)}\n${cue.text}\n"
                                    }.joinToString("\n")
                                    File(folder, "vieneu_nano_preview.srt").writeText(srt, Charsets.UTF_8)
                                    wav
                                }
                                output = file
                                chunkTimings = emptyList()
                                player?.release()
                                player = MediaPlayer().apply {
                                    setDataSource(file.absolutePath)
                                    setOnPreparedListener { it.start() }
                                    prepareAsync()
                                }
                                status = "Đã tạo xong âm thanh VieNeu Nano: ${file.name}"
                            } catch (e: Throwable) {
                                status = "Tạo giọng thất bại: ${e.message ?: e.javaClass.simpleName}"
                            } finally { busy = false }
                        }
                    },
                    enabled = !busy && engine != null,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("TẠO VÀ NGHE THỬ GIỌNG Vieneu") }
                output?.let { Text("Đã lưu: ${it.absolutePath}") }
            }
        }
    }
}

