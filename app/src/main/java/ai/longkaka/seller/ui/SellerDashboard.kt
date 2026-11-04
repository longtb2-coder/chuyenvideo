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
import ai.longkaka.seller.ui.BackgroundScanCard
import ai.longkaka.seller.media.VideoImportRepository
import ai.longkaka.seller.media.VideoLibraryRepository
import ai.longkaka.seller.translation.ChineseOverlayTranslator
import ai.longkaka.seller.translation.OnScreenText
import ai.longkaka.seller.translation.ProductCategory
import ai.longkaka.seller.speech.VieNeuNanoSetupCard
import ai.longkaka.seller.speech.vieneu.VieNeuChunkTiming
import java.io.File
import ai.longkaka.seller.subtitle.VietnameseSubtitleTrack
import ai.longkaka.seller.sales.SalesProductBrief
import ai.longkaka.seller.sales.SalesScriptBuilder
import ai.longkaka.seller.sales.SalesCopyStyle
import ai.longkaka.seller.sales.SalesCopyLength
import ai.longkaka.seller.sales.SalesVideoFrameSampler
import ai.longkaka.seller.sales.SalesSpeechTranscriber
import ai.longkaka.seller.sales.SalesSpeechLanguage
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
    val salesFrameSampler = remember(context) { SalesVideoFrameSampler(context.applicationContext) }
    val salesSpeechTranscriber = remember(context) { SalesSpeechTranscriber(context.applicationContext) }
    var salesAnalysisFrames by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var isPreparingSalesFrames by remember { mutableStateOf(false) }
    var salesFrameStatus by remember { mutableStateOf("Chưa trích xuất ảnh phân tích video.") }
    var salesSpeechLanguage by remember { mutableStateOf(SalesSpeechLanguage.CHINESE) }
    var spokenTranscript by remember { mutableStateOf("") }
    var isTranscribingSalesSpeech by remember { mutableStateOf(false) }
    var salesSpeechStatus by remember { mutableStateOf("Chưa nhận diện lời thoại gốc.") }
    var workflowMode by remember { mutableStateOf(SellerWorkflowMode.SALES_VIDEO) }
    var translatedOverlays by remember { mutableStateOf<List<OnScreenText>>(emptyList()) }
    var editedTranslations by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var selectedCategory by remember { mutableStateOf(ProductCategory.AUTO) }
    var scanStatus by remember { mutableStateOf("Chưa quét chữ trên hình.") }
    var scanProgress by remember { mutableIntStateOf(0) }
    var isScanning by remember { mutableStateOf(false) }
    var videoLibrary by remember { mutableStateOf<List<ImportedVideo>>(emptyList()) }
    var sourceUrl by remember { mutableStateOf("") }
    var importedVideo by remember { mutableStateOf<ImportedVideo?>(null) }
    var importStatus by remember { mutableStateOf("Chưa có video được nhập.") }
    var importProgress by remember { mutableIntStateOf(0) }
    var isImporting by remember { mutableStateOf(false) }
    var pendingSubtitleText by remember { mutableStateOf("") }
    var subtitleExportStatus by remember { mutableStateOf("Chưa xuất phụ đề.") }
    var productName by remember { mutableStateOf("") }
    var targetCustomer by remember { mutableStateOf("") }
    var customerProblem by remember { mutableStateOf("") }
    var productBenefits by remember { mutableStateOf("") }
    var verifiedProductFacts by remember { mutableStateOf("") }
    var productOffer by remember { mutableStateOf("") }
    var salesCallToAction by remember { mutableStateOf("") }
    var generatedSalesScript by remember { mutableStateOf("") }
    var narrationFile by remember { mutableStateOf<File?>(null) }
    var narrationTimings by remember { mutableStateOf<List<VieNeuChunkTiming>>(emptyList()) }
    var salesCopyStyle by remember { mutableStateOf(SalesCopyStyle.FRIENDLY) }
    var salesCopyLength by remember { mutableStateOf(SalesCopyLength.STANDARD) }

    val subtitleSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-subrip")
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(pendingSubtitleText) }
                    ?: error("Không mở được tệp để ghi phụ đề.")
            }.onSuccess { subtitleExportStatus = "Đã lưu phụ đề SRT thành công." }
             .onFailure { subtitleExportStatus = it.message ?: "Không thể lưu phụ đề." }
        }
    }

    LaunchedEffect(library) {
        videoLibrary = runCatching { library.getAll() }.getOrDefault(emptyList())
    }

    suspend fun rememberImported(video: ImportedVideo) {
        library.save(video)
        videoLibrary = library.getAll()
        importedVideo = video
        salesAnalysisFrames = emptyList()
        salesFrameStatus = "Video đã thay đổi. Hãy trích xuất lại ảnh phân tích."
        spokenTranscript = ""
        salesSpeechStatus = "Video đã thay đổi. Hãy nhận diện lại lời thoại."
        translatedOverlays = emptyList()
        editedTranslations = emptyMap()
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
                            onClick = {
                                importedVideo = video
                                salesAnalysisFrames = emptyList()
                                salesFrameStatus = "Video đã thay đổi. Hãy trích xuất lại ảnh phân tích."
                                spokenTranscript = ""
                                salesSpeechStatus = "Video đã thay đổi. Hãy nhận diện lại lời thoại."
                                translatedOverlays = emptyList()
                                editedTranslations = emptyMap()
                            },
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
                ) { Text(if (isScanning) "ĐANG QUÉT & DỊCH…" else "QUÉT LẠI CHỮ TRUNG (NẾU CẦN)") }
                if (isScanning) {
                    LinearProgressIndicator(progress = { scanProgress / 100f }, modifier = Modifier.fillMaxWidth())
                }
                Text(scanStatus, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (importedVideo != null) {
                    VideoSubtitleEditor(
                        videoUri = Uri.parse(importedVideo!!.contentUri),
                        overlays = translatedOverlays,
                        editedTexts = editedTranslations,
                        onTextChange = { index, value -> editedTranslations = editedTranslations + (index to value) },
                        salesEditing = workflowMode == SellerWorkflowMode.SALES_VIDEO,
                        narrationFile = if (workflowMode == SellerWorkflowMode.SALES_VIDEO) narrationFile else null,
                        narrationTimings = if (workflowMode == SellerWorkflowMode.SALES_VIDEO) narrationTimings else emptyList()
                    )
                }
                if (workflowMode == SellerWorkflowMode.SALES_VIDEO) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("TRỢ LÝ KỊCH BẢN BÁN HÀNG THÍCH ỨNG", style = MaterialTheme.typography.titleSmall)
                            Text("App sẽ lần lượt xem hình ảnh, nhận diện chữ Trung và nghe lời thoại gốc trước khi gửi dữ liệu cho AI.", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(
                                enabled = !isTranscribingSalesSpeech,
                                onClick = {
                                    val languages = SalesSpeechLanguage.values()
                                    salesSpeechLanguage = languages[(languages.indexOf(salesSpeechLanguage) + 1) % languages.size]
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("NGÔN NGỮ LỜI THOẠI: ${salesSpeechLanguage.label} • CHẠM ĐỂ ĐỔI") }
                            Button(
                                onClick = {
                                    val video = importedVideo
                                    if (video != null && !isPreparingSalesFrames && !isScanning && !isTranscribingSalesSpeech) {
                                        scope.launch {
                                            val videoUri = Uri.parse(video.contentUri)
                                            isPreparingSalesFrames = true
                                            isScanning = true
                                            isTranscribingSalesSpeech = true
                                            salesAnalysisFrames = emptyList()
                                            translatedOverlays = emptyList()
                                            editedTranslations = emptyMap()
                                            spokenTranscript = ""
                                            scanProgress = 0
                                            salesFrameStatus = "Đang trích xuất ảnh cảnh quay…"
                                            scanStatus = "Đang nhận diện và dịch chữ Trung…"
                                            salesSpeechStatus = "Đang chuẩn bị nhận diện lời thoại…"
                                            try {
                                                try {
                                                    salesAnalysisFrames = salesFrameSampler.createStoryboard(videoUri)
                                                    salesFrameStatus = "Đã chuẩn bị ${salesAnalysisFrames.size} ảnh cảnh quay theo thứ tự thời gian."
                                                    if (importedVideo?.contentUri != video.contentUri) return@launch
                                                } catch (error: Throwable) {
                                                    salesFrameStatus = error.message ?: "Không trích xuất được ảnh cảnh quay."
                                                }
                                                if (importedVideo?.contentUri != video.contentUri) return@launch
                                                try {
                                                    translatedOverlays = overlayTranslator.scanAndTranslate(
                                                        videoUri,
                                                        category = selectedCategory,
                                                        onProgress = { scanProgress = it }
                                                    )
                                                    if (importedVideo?.contentUri != video.contentUri) return@launch
                                                    scanStatus = if (translatedOverlays.isEmpty())
                                                        "Không tìm thấy vùng chữ Trung đủ tin cậy."
                                                    else "Đã nhận diện ${translatedOverlays.size} vùng chữ Trung và dịch sang tiếng Việt."
                                                } catch (error: Throwable) {
                                                    scanStatus = error.message ?: "Không nhận diện được chữ trong video."
                                                }
                                                if (importedVideo?.contentUri != video.contentUri) return@launch
                                                try {
                                                    spokenTranscript = salesSpeechTranscriber.transcribe(
                                                        videoUri,
                                                        salesSpeechLanguage,
                                                        onStatus = { salesSpeechStatus = it }
                                                    )
                                                    if (importedVideo?.contentUri != video.contentUri) return@launch
                                                    salesSpeechStatus = if (spokenTranscript.isBlank())
                                                        "Không nhận diện được lời thoại rõ ràng."
                                                    else "Đã nhận diện lời thoại. Bạn có thể sửa văn bản trước khi gửi AI."
                                                } catch (error: Throwable) {
                                                    salesSpeechStatus = error.message ?: "Không thể nhận diện lời thoại."
                                                }
                                            } finally {
                                                isPreparingSalesFrames = false
                                                isScanning = false
                                                isTranscribingSalesSpeech = false
                                            }
                                        }
                                    }
                                },
                                enabled = importedVideo != null && !isPreparingSalesFrames && !isScanning && !isTranscribingSalesSpeech,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    if (isPreparingSalesFrames || isScanning || isTranscribingSalesSpeech)
                                        "ĐANG PHÂN TÍCH TOÀN DIỆN VIDEO…"
                                    else "PHÂN TÍCH TOÀN DIỆN VIDEO (ẢNH + CHỮ + LỜI THOẠI)"
                                )
                            }
                            Text(salesFrameStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(scanStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(salesSpeechStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (isScanning) {
                                LinearProgressIndicator(progress = { scanProgress / 100f }, modifier = Modifier.fillMaxWidth())
                            }
                            if (spokenTranscript.isNotBlank()) {
                                OutlinedTextField(
                                    value = spokenTranscript,
                                    onValueChange = { spokenTranscript = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 4,
                                    label = { Text("Lời thoại gốc đã nhận diện • có thể chỉnh sửa") }
                                )
                            }
                            Text("Chọn cách nói và thời lượng. Kịch bản ưu tiên dữ kiện đã xác nhận; không tự bịa giá, công dụng hay chứng nhận.", style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(productName, { productName = it }, Modifier.fillMaxWidth(), label = { Text("Tên sản phẩm / mã hàng") })
                            OutlinedTextField(targetCustomer, { targetCustomer = it }, Modifier.fillMaxWidth(), label = { Text("Khách hàng mục tiêu") })
                            OutlinedTextField(customerProblem, { customerProblem = it }, Modifier.fillMaxWidth(), label = { Text("Vấn đề khách hàng đang gặp") })
                            OutlinedTextField(productBenefits, { productBenefits = it }, Modifier.fillMaxWidth(), label = { Text("Lợi ích đã xác nhận") })
                            OutlinedTextField(verifiedProductFacts, { verifiedProductFacts = it }, Modifier.fillMaxWidth(), label = { Text("Thông số / chất liệu / xuất xứ đã kiểm chứng") })
                            OutlinedTextField(productOffer, { productOffer = it }, Modifier.fillMaxWidth(), label = { Text("Giá và ưu đãi (nếu có)") })
                            OutlinedTextField(salesCallToAction, { salesCallToAction = it }, Modifier.fillMaxWidth(), label = { Text("Lời kêu gọi hành động") })
                            OutlinedButton(onClick = { val all = SalesCopyStyle.values(); salesCopyStyle = all[(all.indexOf(salesCopyStyle) + 1) % all.size] }, modifier = Modifier.fillMaxWidth()) { Text("CÁCH NÓI: ${salesCopyStyle.label} • CHẠM ĐỂ ĐỔI") }
                            OutlinedButton(onClick = { val all = SalesCopyLength.values(); salesCopyLength = all[(all.indexOf(salesCopyLength) + 1) % all.size] }, modifier = Modifier.fillMaxWidth()) { Text("THỜI LƯỢNG: ${salesCopyLength.label} • CHẠM ĐỂ ĐỔI") }
                            Button(onClick = {
                                generatedSalesScript = SalesScriptBuilder.build(
                                    SalesProductBrief(productName, targetCustomer, customerProblem, productBenefits, verifiedProductFacts, productOffer, salesCallToAction),
                                    translatedOverlays.map { it.sourceText + " → " + it.vietnameseText },
                                    style = salesCopyStyle,
                                    length = salesCopyLength
                                ).asText()
                            }, modifier = Modifier.fillMaxWidth()) { Text("TẠO KỊCH BẢN BÁN HÀNG") }
                            if (generatedSalesScript.isNotBlank()) {
                                OutlinedTextField(generatedSalesScript, { generatedSalesScript = it }, Modifier.fillMaxWidth(), label = { Text("Kịch bản có thể chỉnh sửa") }, minLines = 7)
                                OutlinedButton(onClick = {
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Kịch bản bán hàng", generatedSalesScript))
                                }, modifier = Modifier.fillMaxWidth()) { Text("SAO CHÉP TOÀN BỘ KỊCH BẢN") }
                            }
                            SalesAiHandoffCard(
                                productBrief = listOf(
                                    "Tên sản phẩm: $productName",
                                    "Khách hàng: $targetCustomer",
                                    "Nhu cầu/vấn đề: $customerProblem",
                                    "Lợi ích đã xác nhận: $productBenefits",
                                    "Thông số đã kiểm chứng: $verifiedProductFacts",
                                    "Giá/ưu đãi do người bán cung cấp: $productOffer",
                                    "CTA: $salesCallToAction",
                                    "Ngôn ngữ lời thoại gốc: ${salesSpeechLanguage.label}",
                                    "Lời thoại gốc đã nhận diện: $spokenTranscript",
                                    "Nội dung đã dịch từ video: ${translatedOverlays.mapIndexed { index, item -> editedTranslations[index]?.takeIf { it.isNotBlank() } ?: item.vietnameseText }.filter { it.isNotBlank() }.joinToString(" | ")}"
                                ).joinToString("\n"),
                                currentDraft = generatedSalesScript,
                                analysisFrames = salesAnalysisFrames,
                                onAccept = { generatedSalesScript = it }
                            )
                        }
                    }
                }
                ToolRow(Icons.Rounded.AutoAwesome,
                    if (workflowMode == SellerWorkflowMode.SHORT_FILM) "Xóa chữ Trung / làm mờ đúng vùng chữ" else "Phân tích lợi ích, điểm khác biệt và khách hàng mục tiêu",
                    if (workflowMode == SellerWorkflowMode.SHORT_FILM) "Có thể chỉnh vùng làm mờ và xuất MP4 trong trình biên tập" else "Không tự bịa thông số; phải kiểm chứng nguồn")
                ToolRow(Icons.Rounded.Subtitles, if (workflowMode == SellerWorkflowMode.SHORT_FILM) "Phụ đề tiếng Việt theo vị trí chữ gốc" else "Phụ đề, điểm nổi bật và lời kêu gọi mua hàng", "Căn thời gian và cho phép chỉnh sửa")
                ToolRow(Icons.Rounded.CloudDownload, "Thuyết minh tiếng Việt bằng VieNeu Nano", "Giọng đọc offline, chọn giọng và nghe thử")
                VieNeuNanoSetupCard(
                    sourceText = if (workflowMode == SellerWorkflowMode.SALES_VIDEO && generatedSalesScript.isNotBlank()) generatedSalesScript else translatedOverlays.mapIndexed { index, item -> editedTranslations[index] ?: item.vietnameseText }
                        .filter { it.isNotBlank() }.joinToString("\n"),
                    onNarrationReady = { file, timings -> narrationFile = file; narrationTimings = timings }
                )
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("04  •  THÀNH PHẨM", style = MaterialTheme.typography.titleMedium)
                ToolRow(Icons.Rounded.Movie, if (workflowMode == SellerWorkflowMode.SHORT_FILM) "Dựng phim ngắn thuyết minh tiếng Việt" else "Biên tập video bán hàng", if (workflowMode == SellerWorkflowMode.SHORT_FILM) "Khử chữ • phụ đề • giọng đọc • âm thanh gốc" else "Tỷ lệ dọc 9:16 • lợi ích • giá • CTA")
                Text(
                    if (importedVideo != null) "Trình biên tập đã mở. Nếu OCR không tìm thấy chữ, bạn vẫn có thể tự đặt vùng làm mờ và nhập phụ đề."
                    else "Nhập video để mở trình biên tập, làm mờ vùng chữ và chèn phụ đề.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
