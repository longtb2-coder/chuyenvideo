package ai.longkaka.seller.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ai.longkaka.seller.media.ImportedVideo
import ai.longkaka.seller.media.VideoImportRepository
import ai.longkaka.seller.media.VideoLibraryRepository
import ai.longkaka.seller.translation.ChineseOverlayTranslator
import ai.longkaka.seller.translation.OnScreenText
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun SellerDashboard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importer = remember(context) { VideoImportRepository(context.applicationContext) }
    val library = remember(context) { VideoLibraryRepository(context.applicationContext) }
    val overlayTranslator = remember(context) { ChineseOverlayTranslator(context.applicationContext) }
    var translatedOverlays by remember { mutableStateOf<List<OnScreenText>>(emptyList()) }
    var scanStatus by remember { mutableStateOf("Chưa quét chữ trên hình.") }
    var scanProgress by remember { mutableIntStateOf(0) }
    var isScanning by remember { mutableStateOf(false) }
    var videoLibrary by remember { mutableStateOf<List<ImportedVideo>>(emptyList()) }
    var sourceUrl by remember { mutableStateOf("") }
    var importedVideo by remember { mutableStateOf<ImportedVideo?>(null) }
    var importStatus by remember { mutableStateOf("Chưa có video được nhập.") }
    var importProgress by remember { mutableIntStateOf(0) }
    var isImporting by remember { mutableStateOf(false) }

    LaunchedEffect(library) {
        videoLibrary = runCatching { library.getAll() }.getOrDefault(emptyList())
    }

    suspend fun rememberImported(video: ImportedVideo) {
        library.save(video)
        videoLibrary = library.getAll()
        importedVideo = video
    }

    fun showError(error: Throwable) {
        importStatus = error.message ?: "Không thể nhận video. Vui lòng thử lại."
        isImporting = false
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null && !isImporting) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            scope.launch {
                isImporting = true
                importProgress = 0
                importStatus = "Đang lưu video trực tiếp vào thư mục Movies/LONGKAKA SELLER AI trên điện thoại…"
                try {
                    val result = importer.importFromUri(uri) { importProgress = it }
                    rememberImported(result)
                    importProgress = 100
                    importStatus = "Đã nhận video từ điện thoại thành công."
                } catch (error: Throwable) {
                    showError(error)
                } finally {
                    isImporting = false
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()) {
            Column {
                Text("LONGKAKA", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                Text("SELLER AI STUDIO", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            }
            Text("● SẴN SÀNG", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
        }
        Text("Biến video sản phẩm thành video bán hàng Việt", style = MaterialTheme.typography.headlineSmall)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("01  •  NHẬP VÀ TIẾP NHẬN VIDEO", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = sourceUrl, onValueChange = { sourceUrl = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Dán link tải video trực tiếp (HTTPS)") },
                    placeholder = { Text("Link tệp MP4 / MOV / WEBM…") },
                    enabled = !isImporting
                )
                Button(
                    onClick = {
                        scope.launch {
                            isImporting = true
                            importProgress = 0
                            importStatus = "Đang kiểm tra link và tải video…"
                            try {
                                val result = importer.importFromDirectLink(sourceUrl) { importProgress = it }
                                rememberImported(result)
                                importProgress = 100
                                importStatus = "Đã nhận video từ đường link thành công."
                            } catch (error: Throwable) {
                                showError(error)
                            } finally {
                                isImporting = false
                            }
                        }
                    },
                    enabled = sourceUrl.isNotBlank() && !isImporting,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("TẢI VIDEO VỀ ĐIỆN THOẠI") }
                OutlinedButton(
                    onClick = { picker.launch(arrayOf("video/*")) },
                    enabled = !isImporting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    androidx.compose.material3.Icon(Icons.Rounded.UploadFile, contentDescription = null)
                    Text("Chọn video từ điện thoại")
                }
                if (isImporting) {
                    if (importProgress < 0) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { importProgress / 100f }, modifier = Modifier.fillMaxWidth())
                }
                Text(importStatus, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                importedVideo?.let { video ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(video.displayName, style = MaterialTheme.typography.titleSmall)
                            Text("Thời lượng: ${formatDuration(video.durationMs)}  •  ${video.width} × ${video.height}",
                                style = MaterialTheme.typography.bodySmall)
                            Text("Dung lượng: ${formatBytes(video.sizeBytes)}",
                                style = MaterialTheme.typography.bodySmall)
                            Text("Đã lưu tại Movies/LONGKAKA SELLER AI trong bộ nhớ điện thoại",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                Text(
                    "Video nhập từ máy hoặc tải từ link trực tiếp sẽ được lưu vào thư mục Movies/LONGKAKA SELLER AI để dễ tìm trong ứng dụng Tệp/Thư viện. Không giới hạn cứng dung lượng; việc tải phụ thuộc dung lượng trống thực tế của điện thoại. Link trang TikTok/Facebook/Shopee/Douyin thường không phải link tệp trực tiếp và có thể cần đăng nhập hoặc bị nền tảng giới hạn. Khi đó, hãy lưu video bằng chức năng hợp lệ của nền tảng rồi chọn tệp ở đây. Không xóa watermark hay dấu bản quyền.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("02  •  THƯ VIỆN VIDEO ĐÃ NHẬP", style = MaterialTheme.typography.titleMedium)
                Text("Video được lưu trên điện thoại và danh sách vẫn còn khi mở lại ứng dụng.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (videoLibrary.isEmpty()) {
                    Text("Chưa có video nào trong thư viện.", style = MaterialTheme.typography.bodySmall)
                } else {
                    videoLibrary.forEach { video ->
                        OutlinedButton(
                            onClick = { importedVideo = video },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(video.displayName, style = MaterialTheme.typography.titleSmall)
                                Text("${formatDuration(video.durationMs)} • ${video.width} × ${video.height} • ${formatBytes(video.sizeBytes)}",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("03  •  XỬ LÝ AI", style = MaterialTheme.typography.titleMedium)
                ToolRow(Icons.Rounded.Translate, "Nhận diện & dịch chữ Trung trên hình", "OCR theo từng mốc video + dịch Việt ngoại tuyến")
                Button(
                    onClick = {
                        val video = importedVideo
                        if (video != null && !isScanning) {
                            scope.launch {
                                isScanning = true
                                scanProgress = 0
                                scanStatus = "Đang quét chữ Trung trên các khung hình…"
                                translatedOverlays = emptyList()
                                try {
                                    translatedOverlays = overlayTranslator.scanAndTranslate(
                                        Uri.parse(video.contentUri),
                                        onProgress = { scanProgress = it }
                                    )
                                    scanStatus = if (translatedOverlays.isEmpty()) {
                                        "Không tìm thấy đoạn chữ Trung đủ tin cậy trong video."
                                    } else {
                                        "Đã nhận diện và dịch ${translatedOverlays.size} vùng chữ. Hãy kiểm tra bản dịch."
                                    }
                                } catch (error: Throwable) {
                                    scanStatus = error.message ?: "Quét chữ thất bại."
                                } finally {
                                    isScanning = false
                                }
                            }
                        }
                    },
                    enabled = importedVideo != null && !isScanning,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (isScanning) "ĐANG QUÉT & DỊCH…" else "QUÉT CHỮ TRUNG VÀ DỊCH SANG TIẾNG VIỆT") }
                if (isScanning) {
                    LinearProgressIndicator(progress = { scanProgress / 100f }, modifier = Modifier.fillMaxWidth())
                }
                Text(scanStatus, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                translatedOverlays.forEachIndexed { index, item ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Vùng chữ ${index + 1} • ${formatDuration(item.timeMs)}",
                                style = MaterialTheme.typography.labelSmall)
                            Text("Gốc: ${item.sourceText}", style = MaterialTheme.typography.bodySmall)
                            Text("Tiếng Việt: ${item.vietnameseText}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                ToolRow(Icons.Rounded.AutoAwesome, "Làm sạch chữ trên video", "Bước phục hồi nền và chèn chữ Việt đang được tích hợp")
                ToolRow(Icons.Rounded.Subtitles, "Tạo phụ đề tiếng Việt", "Căn thời gian và cho phép chỉnh sửa")
                ToolRow(Icons.Rounded.CloudDownload, "Thay giọng đọc tiếng Việt", "Chọn giọng và nghe thử trước khi ghép")
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("04  •  THÀNH PHẨM", style = MaterialTheme.typography.titleMedium)
                ToolRow(Icons.Rounded.Movie, "Biên tập video bán hàng", "Tỷ lệ dọc 9:16 • phụ đề • giá • CTA")
                Button(onClick = { /* Export pipeline is not connected yet. */ },
                    enabled = importedVideo != null && !isImporting,
                    modifier = Modifier.fillMaxWidth()) {
                    Text("TẠO VIDEO BÁN HÀNG")
                }
                Text("Hiện đã có bước nhận và lưu video. Các bộ xử lý AI và xuất video sẽ chỉ bật sau khi được tích hợp và kiểm thử.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    return String.format(Locale.getDefault(), "%02d:%02d:%02d",
        totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60)
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
    else -> "$bytes B"
}

@Composable
private fun ToolRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        androidx.compose.material3.Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
