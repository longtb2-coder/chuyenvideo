package ai.longkaka.seller.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ai.longkaka.seller.media.ImportedVideo
import ai.longkaka.seller.ui.BackgroundScanCard
import ai.longkaka.seller.media.VideoImportRepository
import ai.longkaka.seller.media.VideoLibraryRepository
import ai.longkaka.seller.translation.ChineseOverlayTranslator
import ai.longkaka.seller.translation.OnScreenText
import ai.longkaka.seller.translation.ProductCategory
import ai.longkaka.seller.processing.VideoFrameRepairPreview
import ai.longkaka.seller.processing.TextRemovalPlanner
import ai.longkaka.seller.speech.VieNeuNanoSetupCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.util.Locale

enum class SellerWorkflowMode { SHORT_FILM, SALES_VIDEO }

@Composable
fun SellerDashboard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importer = remember(context) { VideoImportRepository(context.applicationContext) }
    val library = remember(context) { VideoLibraryRepository(context.applicationContext) }
    val overlayTranslator = remember(context) { ChineseOverlayTranslator(context.applicationContext) }
    var workflowMode by remember { mutableStateOf(SellerWorkflowMode.SALES_VIDEO) }
    var translatedOverlays by remember { mutableStateOf<List<OnScreenText>>(emptyList()) }
    var editedTranslations by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var selectedCategory by remember { mutableStateOf(ProductCategory.AUTO) }
    var selectedRemovalIndices by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var scanStatus by remember { mutableStateOf("Chưa quét chữ trên hình.") }
    var scanProgress by remember { mutableIntStateOf(0) }
    var isScanning by remember { mutableStateOf(false) }
    var repairPreview by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var repairStatus by remember { mutableStateOf("Chưa tạo bản xem trước phục hồi.") }
    var isRepairing by remember { mutableStateOf(false) }
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
        Text("Chọn quy trình làm video", style = MaterialTheme.typography.headlineSmall)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { workflowMode = SellerWorkflowMode.SHORT_FILM }, modifier = Modifier.weight(1f)) {
                        Text(if (workflowMode == SellerWorkflowMode.SHORT_FILM) "✓ PHIM NGẮN" else "PHIM NGẮN")
                    }
                    OutlinedButton(onClick = { workflowMode = SellerWorkflowMode.SALES_VIDEO }, modifier = Modifier.weight(1f)) {
                        Text(if (workflowMode == SellerWorkflowMode.SALES_VIDEO) "✓ BÁN HÀNG" else "VIDEO BÁN HÀNG")
                    }
                }
                Text(
                    if (workflowMode == SellerWorkflowMode.SHORT_FILM)
                        "Phát hiện chữ Trung → xóa/phục hồi hoặc làm mờ đúng vùng → dịch tự nhiên → phụ đề và thuyết minh đồng bộ."
                    else
                        "Phân tích hình ảnh, chữ và lời thoại → xác định sản phẩm → kiểm chứng thông tin bên ngoài → viết kịch bản bán hàng → dựng video tiếng Việt.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
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
                Text(if (workflowMode == SellerWorkflowMode.SHORT_FILM) "03  •  XỬ LÝ PHIM NGẮN" else "03  •  PHÂN TÍCH SẢN PHẨM & BÁN HÀNG", style = MaterialTheme.typography.titleMedium)
                ToolRow(Icons.Rounded.Translate,
                    if (workflowMode == SellerWorkflowMode.SHORT_FILM) "Nhận diện chữ Trung và dịch lời thoại trên hình" else "Trích xuất nội dung video và thông tin sản phẩm",
                    if (workflowMode == SellerWorkflowMode.SHORT_FILM) "Giữ ngữ cảnh phim và mốc thời gian" else "Kết hợp OCR, hình ảnh sản phẩm và lời nói")
                OutlinedButton(
                    onClick = {
                        val categories = ProductCategory.values()
                        val next = (categories.indexOf(selectedCategory) + 1) % categories.size
                        selectedCategory = categories[next]
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("NGÀNH HÀNG: ${selectedCategory.label}  •  CHẠM ĐỂ ĐỔI") }
                Text("Chọn đúng ngành hàng giúp tránh dịch máy móc. Tên thương hiệu, mã sản phẩm và thông số vẫn cần bạn kiểm tra ở bản xem trước.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    onClick = {
                        val video = importedVideo
                        if (video != null && !isScanning) {
                            scope.launch {
                                isScanning = true
                                scanProgress = 0
                                scanStatus = "Đang quét chữ Trung trên các khung hình…"
                                translatedOverlays = emptyList()
                                editedTranslations = emptyMap()
                                selectedRemovalIndices = emptySet()
                                try {
                                    translatedOverlays = overlayTranslator.scanAndTranslate(
                                        Uri.parse(video.contentUri),
                                        category = selectedCategory,
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
                            Text("Vùng chữ ${index + 1} • ${formatDuration(item.timeMs)} – ${formatDuration(item.lastTimeMs)}",
                                style = MaterialTheme.typography.labelSmall)
                            Text("Gốc: ${item.sourceText}", style = MaterialTheme.typography.bodySmall)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = index in selectedRemovalIndices,
                                    onCheckedChange = { checked ->
                                        selectedRemovalIndices = if (checked) selectedRemovalIndices + index else selectedRemovalIndices - index
                                    }
                                )
                                Text("Chọn vùng này để thử làm sạch chữ", style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedTextField(
                                value = editedTranslations[index] ?: item.vietnameseText,
                                onValueChange = { value -> editedTranslations = editedTranslations + (index to value) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Bản dịch tiếng Việt (có thể sửa)") }
                            )
                        }
                    }
                }
                if (translatedOverlays.isNotEmpty()) {
                    Text("Chỉ chọn những chữ phủ do video thêm vào. Không chọn logo, nhãn hiệu, thông số in trên bao bì nếu muốn giữ nguyên.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(
                        onClick = {
                            val video = importedVideo
                            val selectedItems = selectedRemovalIndices.sorted().mapNotNull { translatedOverlays.getOrNull(it) }
                            val previewTimeMs = selectedItems.firstOrNull()?.timeMs
                            // Build masks from the selected tracks at this exact timestamp.
                            val previewRegions = if (previewTimeMs == null) emptyList() else {
                                TextRemovalPlanner.regionsAt(translatedOverlays, selectedRemovalIndices, previewTimeMs)
                            }
                            if (video != null && previewTimeMs != null && previewRegions.isNotEmpty() && !isRepairing) {
                                scope.launch {
                                    isRepairing = true
                                    repairStatus = "Đang phục hồi ${previewRegions.size} vùng chữ tại ${formatDuration(previewTimeMs)}…"
                                    try {
                                        val bitmap = withContext(Dispatchers.Default) {
                                            VideoFrameRepairPreview.create(context, Uri.parse(video.contentUri), previewTimeMs, previewRegions)
                                        }
                                        repairPreview?.recycle()
                                        repairPreview = bitmap
                                        repairStatus = "Đã xem trước ${previewRegions.size} vùng đang xuất hiện tại mốc này. Đây chưa phải xử lý toàn video."
                                    } catch (error: Throwable) {
                                        repairStatus = error.message ?: "Không thể phục hồi khung hình này."
                                    } finally {
                                        isRepairing = false
                                    }
                                }
                            }
                        },
                        enabled = !isRepairing && importedVideo != null && selectedRemovalIndices.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (isRepairing) "ĐANG PHỤC HỒI KHUNG HÌNH…" else "THỬ XÓA VÙNG ĐÃ CHỌN TRÊN 1 KHUNG HÌNH") }
                    Text(repairStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    repairPreview?.let { bitmap ->
                        Image(bitmap.asImageBitmap(), contentDescription = "Khung hình sau phục hồi chữ",
                            modifier = Modifier.fillMaxWidth().height(220.dp))
                    }
                }
                ToolRow(Icons.Rounded.AutoAwesome,
                    if (workflowMode == SellerWorkflowMode.SHORT_FILM) "Xóa chữ Trung / làm mờ đúng vùng chữ" else "Phân tích lợi ích, điểm khác biệt và khách hàng mục tiêu",
                    if (workflowMode == SellerWorkflowMode.SHORT_FILM) "Hiện mới xem trước phục hồi một khung hình; chưa xuất toàn video" else "Không tự bịa thông số; phải kiểm chứng nguồn")
                ToolRow(Icons.Rounded.Subtitles, if (workflowMode == SellerWorkflowMode.SHORT_FILM) "Phụ đề tiếng Việt theo vị trí chữ gốc" else "Phụ đề, điểm nổi bật và lời kêu gọi mua hàng", "Căn thời gian và cho phép chỉnh sửa")
                ToolRow(Icons.Rounded.CloudDownload, "Thuyết minh tiếng Việt bằng VieNeu Nano", "Giọng đọc offline, chọn giọng và nghe thử")
                VieNeuNanoSetupCard(
                    sourceText = translatedOverlays.mapIndexed { index, item -> editedTranslations[index] ?: item.vietnameseText }
                        .filter { it.isNotBlank() }.joinToString("\n")
                )
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("04  •  THÀNH PHẨM", style = MaterialTheme.typography.titleMedium)
                ToolRow(Icons.Rounded.Movie, if (workflowMode == SellerWorkflowMode.SHORT_FILM) "Dựng phim ngắn thuyết minh tiếng Việt" else "Biên tập video bán hàng", if (workflowMode == SellerWorkflowMode.SHORT_FILM) "Khử chữ • phụ đề • giọng đọc • âm thanh gốc" else "Tỷ lệ dọc 9:16 • lợi ích • giá • CTA")
                Button(onClick = { /* Export pipeline is not connected yet. */ },
                    enabled = importedVideo != null && !isImporting,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(if (workflowMode == SellerWorkflowMode.SHORT_FILM) "XUẤT PHIM NGẮN TIẾNG VIỆT" else "TẠO VIDEO BÁN HÀNG")
                }
                Text("Hiện đã có bước nhận và lưu video. Các bộ xử lý AI và xuất video sẽ chỉ bật sau khi được tích hợp và kiểm thử.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        BackgroundScanCard(videoUri = importedVideo?.let { Uri.parse(it.contentUri) }, category = selectedCategory)
        AppUpdateCard()
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
