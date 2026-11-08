package ai.longkaka.seller.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import ai.longkaka.seller.translation.OnScreenText
import ai.longkaka.seller.translation.boundsAt
import ai.longkaka.seller.media.VideoExporter
import ai.longkaka.seller.media.RenderBox
import ai.longkaka.seller.media.VideoRecoveryAdvisor
import ai.longkaka.seller.media.VideoRecoveryAdvice
import ai.longkaka.seller.speech.vieneu.VieNeuChunkTiming
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private enum class EditorLayer { TRANSLATION, COVER, BLUR }
private data class EditableOverlay(val left: Float, val top: Float, val width: Float, val height: Float)

@Composable
fun VideoSubtitleEditor(videoUri: Uri, overlays: List<OnScreenText>, editedTexts: Map<Int, String>, onTextChange: (Int, String) -> Unit, salesEditing: Boolean = false, narrationFile: File? = null, narrationTimings: List<VieNeuChunkTiming> = emptyList(), autoExport: Boolean = false, onAutoExportStarted: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isExporting by remember { mutableStateOf(false) }
    var exportStatus by remember { mutableStateOf("Chưa xuất video.") }
    var exportProgress by remember { mutableIntStateOf(0) }
    var recoveryAdvice by remember { mutableStateOf<VideoRecoveryAdvice?>(null) }
    var showExportReview by remember { mutableStateOf(false) }
    var autoExportStarted by remember(videoUri) { mutableStateOf(false) }
    val exporter = remember(context) { VideoExporter(context.applicationContext) }
    val exoPlayer = remember(videoUri) {
        ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(videoUri)); prepare(); playWhenReady = false }
    }
    DisposableEffect(exoPlayer) { onDispose { exoPlayer.release() } }
    val projectStore = remember(context) { EditorProjectStore(context) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var originalAudioVolume by remember { mutableFloatStateOf(1f) }
    var previousAudioVolume by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(exoPlayer, originalAudioVolume) { exoPlayer.volume = originalAudioVolume.coerceIn(0f, 1f) }
    // A manual full-video region is created when OCR returns no Chinese text.
    val editableOverlays = remember(overlays, durationMs) {
        if (overlays.isNotEmpty()) overlays
        else if (durationMs > 0L) listOf(
            OnScreenText(
                sourceText = "",
                vietnameseText = "",
                timeMs = 0L,
                lastTimeMs = durationMs,
                left = 0.12f,
                top = 0.35f,
                right = 0.88f,
                bottom = 0.65f
            )
        ) else emptyList()
    }
    // Defaults and saved project must use editableOverlays, not raw OCR overlays.
    // Otherwise the manual region has zero-sized editor lists and cannot be exported.
    val translationDefaults = remember(editableOverlays) {
        editableOverlays.map { SavedLayerBox(it.left, it.top,
            (it.right-it.left).coerceAtLeast(.08f), (it.bottom-it.top).coerceAtLeast(.04f)) }
    }
    val coverDefaults = remember(editableOverlays) {
        editableOverlays.map { item ->
            val left = (item.left - .025f).coerceIn(0f, 1f)
            val top = (item.top - .025f).coerceIn(0f, 1f)
            SavedLayerBox(left, top,
                (item.right - item.left + .05f).coerceAtLeast(.05f).coerceAtMost(1f - left),
                (item.bottom - item.top + .05f).coerceAtLeast(.03f).coerceAtMost(1f - top))
        }
    }
    val savedProject = remember(videoUri, editableOverlays) {
        projectStore.load(videoUri, editableOverlays.size, translationDefaults, coverDefaults)
    }
    var translations by remember(editableOverlays) {
        mutableStateOf(savedProject?.translations ?: translationDefaults)
    }
    var covers by remember(editableOverlays) {
        mutableStateOf(savedProject?.covers ?: coverDefaults)
    }
    var selected by remember(editableOverlays) { mutableIntStateOf(0) }
    var layer by remember(editableOverlays, salesEditing) { mutableStateOf(if (editableOverlays.any { it.sourceText.isNotBlank() }) EditorLayer.BLUR else if (salesEditing) EditorLayer.COVER else EditorLayer.BLUR) }
    var coverEnabled by remember(editableOverlays) { mutableStateOf(savedProject?.coverEnabled ?: editableOverlays.map { it.sourceText.isNotBlank() }) }
    var coverColor by remember { mutableIntStateOf(savedProject?.coverColor ?: 0xFF111111.toInt()) }
    var coverAlpha by remember { mutableFloatStateOf(1f) }
    // OCR-detected Chinese regions use quality-first background restoration by default; users can disable it per region.
    var blurEnabled by remember(editableOverlays) { mutableStateOf(editableOverlays.map { it.sourceText.isNotBlank() }) }
    var blurStrength by remember { mutableFloatStateOf(14f) }
    var textSize by remember { mutableFloatStateOf(savedProject?.textSize ?: 16f) }

    LaunchedEffect(videoUri, translations, covers, if (salesEditing) coverEnabled else editableOverlays.map { false }, coverColor, coverAlpha, textSize, editedTexts, narrationFile) {
        projectStore.save(videoUri, SavedEditorProject(
            translations.map { SavedLayerBox(it.left,it.top,it.width,it.height) },
            covers.map { SavedLayerBox(it.left,it.top,it.width,it.height) },
            coverEnabled, coverColor, coverAlpha, textSize, editedTexts
        ))
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            durationMs = exoPlayer.duration.takeIf { it > 0L } ?: 0L
            delay(250)
        }
    }
    LaunchedEffect(selected, editableOverlays) { editableOverlays.getOrNull(selected)?.let { exoPlayer.seekTo(it.timeMs.coerceAtLeast(0L)) } }

    LaunchedEffect(autoExport, narrationFile, durationMs) {
        if (autoExport && narrationFile?.exists() == true && durationMs > 0L && !autoExportStarted) {
            autoExportStarted = true
            onAutoExportStarted()
            showExportReview = false
            if (!isExporting) {
                isExporting = true
                exportProgress = 0
                exportStatus = "Đang tự động phục hồi nền, kiểm tra chữ còn sót và xuất MP4…"
                recoveryAdvice = null
                scope.launch {
                    runCatching {
                        exporter.export(
                            videoUri,
                            editableOverlays,
                            translations.map { RenderBox(it.left, it.top, it.width, it.height) },
                            covers.map { RenderBox(it.left, it.top, it.width, it.height) },
                            if (salesEditing) coverEnabled else editableOverlays.map { false },
                            coverColor, coverAlpha, textSize, editedTexts, narrationFile,
                            blurEnabled = blurEnabled,
                            blurStrength = blurStrength,
                            originalAudioVolume = originalAudioVolume,
                            salesSubtitleTimings = if (salesEditing) narrationTimings else emptyList(),
                            salesSubtitleRegionIndex = selected,
                            onProgress = { exportProgress = it.coerceIn(0, 100) }
                        )
                    }.onSuccess {
                        exportStatus = "ĐÃ XUẤT VIDEO • Movies/LONGKAKA SELLER AI"
                        recoveryAdvice = null
                    }.onFailure {
                        recoveryAdvice = VideoRecoveryAdvisor.diagnose(it)
                        exportStatus = "Lỗi xuất video: " + (it.message ?: "Không xác định")
                    }
                    isExporting = false
                }
            }
        }
    }

    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(if (salesEditing) "BIÊN TẬP VIDEO BÁN HÀNG" else "DỊCH PHIM & CHỈNH SỬA PHỤ ĐỀ", style = MaterialTheme.typography.titleMedium)
            if (editableOverlays.size == 1 && editableOverlays[0].sourceText.isBlank()) Text("Không nhận diện được chữ tự động. Bạn vẫn có thể tự kéo vùng xử lý, bật phục hồi nền hoặc dùng khung che thủ công, rồi nhập phụ đề tiếng Việt cho toàn video.", style = MaterialTheme.typography.bodySmall)
            if (salesEditing) Text(if (narrationFile != null) "Đã nhận giọng đọc VieNeu: ${narrationFile.name} • ${narrationTimings.size} đoạn. Phụ đề xuất video sẽ lấy đúng các câu này và đồng bộ theo thời gian giọng đọc." else "Chưa có giọng đọc bán hàng. Hãy tạo giọng VieNeu trước khi xuất video.", style = MaterialTheme.typography.bodySmall)
            Text(if (salesEditing) "Chỉnh nội dung, vị trí và lớp che trong video bán hàng." else "Chỉ sửa bản dịch tiếng Việt và vị trí phụ đề; không thêm nội dung quảng cáo hay thay đổi video gốc.", style = MaterialTheme.typography.bodySmall)
            BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(16f/9f).background(Color.Black, RoundedCornerShape(10.dp))) {
                val bw = constraints.maxWidth.toFloat()
                val bh = constraints.maxHeight.toFloat()
                AndroidView(factory = { c -> PlayerView(c).apply { player = exoPlayer; useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT } },
                    update = { it.player = exoPlayer }, modifier = Modifier.fillMaxSize())
                if (selected in editableOverlays.indices) {
                    if (layer == EditorLayer.COVER || layer == EditorLayer.BLUR) {
                        val cover = covers[selected]
                        val item = editableOverlays[selected]
                        val observed = item.boundsAt(positionMs)
                        val baseLeft = (item.left - .025f).coerceAtLeast(0f)
                        val baseTop = (item.top - .025f).coerceAtLeast(0f)
                        val baseWidth = (item.right - item.left + .05f).coerceAtLeast(.01f)
                        val baseHeight = (item.bottom - item.top + .05f).coerceAtLeast(.01f)
                        val previewLeft = observed?.let { (it.left - .025f + cover.left - baseLeft).coerceIn(0f, 1f) } ?: cover.left
                        val previewTop = observed?.let { (it.top - .025f + cover.top - baseTop).coerceIn(0f, 1f) } ?: cover.top
                        val previewWidth = observed?.let { ((it.right-it.left+.05f) * (cover.width/baseWidth).coerceIn(.5f,2.5f)).coerceAtMost(1f-previewLeft) } ?: cover.width
                        val previewHeight = observed?.let { ((it.bottom-it.top+.05f) * (cover.height/baseHeight).coerceIn(.5f,2.5f)).coerceAtMost(1f-previewTop) } ?: cover.height
                        if (layer == EditorLayer.BLUR && blurEnabled[selected]) {
                            Box(Modifier.offset { IntOffset((previewLeft*bw).roundToInt(), (previewTop*bh).roundToInt()) }
                                .size((previewWidth*maxWidth.value).dp, (previewHeight*maxHeight.value).dp)
                                .background(Color.White.copy(alpha=.22f), RoundedCornerShape(3.dp))
                                .pointerInput(selected, cover) { detectDragGestures { change, drag ->
                                    change.consume()
                                    covers = covers.toMutableList().also { it[selected] = cover.copy(left=(cover.left+drag.x/bw).coerceIn(0f,1f-cover.width), top=(cover.top+drag.y/bh).coerceIn(0f,1f-cover.height)) }
                                } }, contentAlignment=Alignment.Center) {
                                Text("VÙNG PHỤC HỒI", color=Color.White, fontSize=11.sp)
                            }
                        } else if (layer == EditorLayer.COVER && coverEnabled[selected]) {
                            Box(Modifier.offset { IntOffset((previewLeft*bw).roundToInt(), (previewTop*bh).roundToInt()) }
                                .size((previewWidth*maxWidth.value).dp, (previewHeight*maxHeight.value).dp)
                                .background(Color(coverColor).copy(alpha=coverAlpha), RoundedCornerShape(3.dp))
                                .pointerInput(selected, cover) { detectDragGestures { change, drag ->
                                    change.consume()
                                    covers = covers.toMutableList().also { it[selected] = cover.copy(left=(cover.left+drag.x/bw).coerceIn(0f,1f-cover.width), top=(cover.top+drag.y/bh).coerceIn(0f,1f-cover.height)) }
                                } }, contentAlignment=Alignment.Center) {
                                Text(editedTexts[selected] ?: editableOverlays[selected].vietnameseText,
                                    color=Color.White, fontSize=textSize.sp, lineHeight=(textSize*1.15f).sp,
                                    maxLines=3)
                            }
                        }
                    } else {
                        val tr = translations[selected]
                        Box(Modifier.offset { IntOffset((tr.left*bw).roundToInt(), (tr.top*bh).roundToInt()) }
                            .size((tr.width*maxWidth.value).dp, (tr.height*maxHeight.value).dp)
                            .background(Color.Black.copy(alpha=.18f), RoundedCornerShape(3.dp))
                            .pointerInput(selected, tr) { detectDragGestures { change, drag ->
                                change.consume()
                                translations = translations.toMutableList().also { it[selected] = tr.copy(left=(tr.left+drag.x/bw).coerceIn(0f,1f-tr.width), top=(tr.top+drag.y/bh).coerceIn(0f,1f-tr.height)) }
                            } }, contentAlignment=Alignment.Center) {
                            Text(editedTexts[selected] ?: editableOverlays[selected].vietnameseText, color=Color.White, fontSize=textSize.sp, lineHeight=(textSize*1.15f).sp)
                        }
                    }
                }
            }
            Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick={ if(exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() }) { Text(if(exoPlayer.isPlaying) "TẠM DỪNG" else "PHÁT") }
                Text("${clock(positionMs)} / ${clock(durationMs)}", style=MaterialTheme.typography.labelMedium)
            }
            Slider(value=if(durationMs>0) positionMs.toFloat().coerceIn(0f,durationMs.toFloat()) else 0f,
                onValueChange={ positionMs=it.toLong(); exoPlayer.seekTo(positionMs) }, valueRange=0f..durationMs.coerceAtLeast(1L).toFloat())
            if (salesEditing) {
                Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.SpaceBetween) {
                    Text("ÂM LƯỢNG ÂM THANH GỐC: ${(originalAudioVolume * 100).toInt()}%",
                        style=MaterialTheme.typography.labelLarge)
                    TextButton(onClick = {
                        if (originalAudioVolume > 0f) {
                            previousAudioVolume = originalAudioVolume
                            originalAudioVolume = 0f
                        } else originalAudioVolume = previousAudioVolume.coerceIn(0.05f, 1f)
                    }) { Text(if (originalAudioVolume > 0f) "TẮT TIẾNG" else "BẬT LẠI") }
                }
                Slider(value=originalAudioVolume, onValueChange={ originalAudioVolume=it.coerceIn(0f,1f) },
                    valueRange=0f..1f)
                Text("Mức này được áp dụng cả khi xem trước và khi xuất MP4. Có thể hạ âm thanh gốc để lời thuyết minh nổi bật hơn.",
                    style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(
                onClick = { showExportReview = true },
                enabled = !isExporting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isExporting) "ĐANG XUẤT VIDEO…" else "KIỂM TRA & XUẤT VIDEO MP4")
            }
            if (showExportReview) {
                AlertDialog(
                    onDismissRequest = { showExportReview = false },
                    title = { Text("KIỂM TRA TRƯỚC KHI XUẤT") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text("Chế độ: Tự động có kiểm soát • ưu tiên chất lượng hình ảnh.")
                            Text("Vùng phục hồi nền: ${blurEnabled.count { it }}")
                            Text("Vùng phụ đề/khung thay thế: ${if (salesEditing) coverEnabled.count { it } else editableOverlays.size}")
                            Text("Giọng VieNeu: ${if (narrationFile?.exists() == true) "Đã sẵn sàng" else "Không dùng"}")
                            Text("Quy trình: tìm khung hình sạch gần kề nếu đủ an toàn → OpenCV Telea → Navier‑Stokes dự phòng → quét OCR kiểm tra chữ Trung còn sót.")
                            Text("Video gốc được giữ nguyên. Nếu kiểm tra phát hiện chữ còn sót hoặc phụ đề không đủ chỗ hiển thị, app sẽ dừng và báo vùng cần xử lý.")
                            Text("Bạn nên phát thử vài đoạn có chữ chuyển động trước khi xác nhận xuất.")
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showExportReview = false
                            if (!isExporting) {
                                isExporting = true
                                exportProgress = 0
                                exportStatus = "Đang chuẩn bị xử lý chất lượng cao…"
                                recoveryAdvice = null
                                scope.launch {
                                    runCatching {
                                        exporter.export(
                                            videoUri,
                                            editableOverlays,
                                            translations.map { RenderBox(it.left, it.top, it.width, it.height) },
                                            covers.map { RenderBox(it.left, it.top, it.width, it.height) },
                                            if (salesEditing) coverEnabled else editableOverlays.map { false },
                                            coverColor, coverAlpha, textSize, editedTexts, narrationFile,
                                            blurEnabled = blurEnabled,
                                            blurStrength = blurStrength,
                                            originalAudioVolume = originalAudioVolume,
                                            salesSubtitleTimings = if (salesEditing) narrationTimings else emptyList(),
                                            salesSubtitleRegionIndex = selected,
                                            onProgress = { exportProgress = it.coerceIn(0, 100) }
                                        )
                                    }.onSuccess {
                                        exportStatus = "ĐÃ XUẤT VIDEO • Movies/LONGKAKA SELLER AI"
                                        recoveryAdvice = null
                                    }.onFailure {
                                        recoveryAdvice = VideoRecoveryAdvisor.diagnose(it)
                                        exportStatus = "Lỗi xuất video: " + (it.message ?: "Không xác định")
                                    }
                                    isExporting = false
                                }
                            }
                        }) { Text("XÁC NHẬN XUẤT") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showExportReview = false }) { Text("QUAY LẠI KIỂM TRA") }
                    }
                )
            }
            if (isExporting || exportProgress > 0) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                if (isExporting) "TIẾN ĐỘ XUẤT VIDEO" else "KẾT QUẢ XUẤT VIDEO",
                                style = MaterialTheme.typography.labelLarge
                            )
                            Text(
                                "$exportProgress%",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        LinearProgressIndicator(
                            progress = { exportProgress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            exportStageLabel(exportProgress, isExporting, blurEnabled.any { it } || coverEnabled.any { it }),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Text(
                exportStatus,
                style = MaterialTheme.typography.bodySmall,
                color = if (exportStatus.startsWith("Lỗi")) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
            )
            recoveryAdvice?.let { advice ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("TỰ CHẨN ĐOÁN LỖI: ${advice.title}", style = MaterialTheme.typography.titleSmall)
                        Text(advice.explanation, style = MaterialTheme.typography.bodySmall)
                        Text("Cách xử lý: ${advice.nextStep}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (editableOverlays.isNotEmpty()) {
                Text("CÁC ĐOẠN CHỮ ĐÃ NHẬN DIỆN", style=MaterialTheme.typography.labelLarge)
                Column(Modifier.heightIn(max=160.dp).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    editableOverlays.forEachIndexed { index, item ->
                        OutlinedButton(onClick={ selected=index; exoPlayer.seekTo(item.timeMs) }, modifier=Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text("Vùng chữ ${index+1}", style=MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                OutlinedTextField(value=editedTexts[selected] ?: editableOverlays[selected].vietnameseText,
                    onValueChange={ onTextChange(selected,it) }, modifier=Modifier.fillMaxWidth(), label={ Text("Nội dung dịch tiếng Việt") })
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    if (salesEditing) {
                        FilterChip(selected=layer==EditorLayer.COVER,onClick={layer=EditorLayer.COVER},label={Text("Khung che + phụ đề")})
                    } else {
                        FilterChip(selected=layer==EditorLayer.TRANSLATION,onClick={layer=EditorLayer.TRANSLATION},label={Text("Chỉnh phụ đề")})
                    }
                    FilterChip(selected=layer==EditorLayer.BLUR,onClick={layer=EditorLayer.BLUR},label={Text("Phục hồi nền chữ")})
                }
                if (layer == EditorLayer.BLUR) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(checked=blurEnabled[selected],onCheckedChange={ v -> blurEnabled=blurEnabled.toMutableList().also { it[selected]=v } })
                        Text("Bật tự động phục hồi nền cho đoạn chữ này")
                    }
                    Text("Mức phục hồi chi tiết: ${blurStrength.roundToInt()}")
                    Slider(value=blurStrength,onValueChange={blurStrength=it},valueRange=3f..28f)
                    Text("Ưu tiên khôi phục điểm ảnh nền thay vì phủ một hình chữ nhật mờ. App tự chọn khung hình lân cận khi đủ an toàn; nếu không, dùng OpenCV cục bộ. Vùng phục hồi bám theo vị trí chữ đã nhận diện.")
                } else if(salesEditing && layer==EditorLayer.COVER) {
                    Text("Chiều rộng khung: ${(covers[selected].width*100).roundToInt()}%")
                    Slider(value=covers[selected].width,onValueChange={ v -> covers=covers.toMutableList().also { it[selected]=it[selected].copy(width=v.coerceIn(.05f,maxOf(.05f,1f-covers[selected].left))) } },valueRange=.05f..1f)
                    Text("Chiều cao khung: ${(covers[selected].height*100).roundToInt()}%")
                    Slider(value=covers[selected].height,onValueChange={ v -> covers=covers.toMutableList().also { it[selected]=it[selected].copy(height=v.coerceIn(.03f,maxOf(.03f,1f-covers[selected].top))) } },valueRange=.03f..1f)
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(checked=coverEnabled[selected],onCheckedChange={ v -> coverEnabled=coverEnabled.toMutableList().also { it[selected]=v } })
                        Text("Bật khung che chữ gốc")
                    }
                    Text("Màu nền khung che")
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        listOf(0xFF111111.toInt(),0xFFFFFFFF.toInt(),0xFF777777.toInt(),0xFFB58A55.toInt()).forEach { color ->
                            Surface(modifier=Modifier.size(34.dp),shape=RoundedCornerShape(8.dp),color=Color(color),
                                border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outline),onClick={coverColor=color}) {}
                        }
                    }
                    Text("Cỡ phụ đề trên khung: ${textSize.roundToInt()} sp")
                    Slider(value=textSize,onValueChange={textSize=it},valueRange=10f..28f)
                } else if (!salesEditing) {
                    Text("Chiều rộng vùng dịch: ${(translations[selected].width*100).roundToInt()}%")
                    Slider(value=translations[selected].width,onValueChange={ v -> translations=translations.toMutableList().also { it[selected]=it[selected].copy(width=v.coerceIn(.05f,maxOf(.05f,1f-translations[selected].left))) } },valueRange=.05f..1f)
                    Text("Chiều cao vùng dịch: ${(translations[selected].height*100).roundToInt()}%")
                    Slider(value=translations[selected].height,onValueChange={ v -> translations=translations.toMutableList().also { it[selected]=it[selected].copy(height=v.coerceIn(.03f,maxOf(.03f,1f-translations[selected].top))) } },valueRange=.03f..1f)
                    Text("Cỡ chữ dịch: ${textSize.roundToInt()} sp")
                    Slider(value=textSize,onValueChange={textSize=it},valueRange=10f..28f)
                }
                Text(if (layer == EditorLayer.BLUR) "Kéo vùng phục hồi để chỉnh vị trí. Khi bật, app phục hồi nền chữ rồi chèn phụ đề tiếng Việt lên đúng vùng đó." else if (salesEditing) "Kéo lớp trên video để đổi vị trí. Các chỉnh sửa được áp dụng khi xuất MP4." else "Kéo phụ đề trên video để đổi vị trí. Chỉ bản dịch đã sửa được ghi vào MP4; âm thanh và hình ảnh gốc được giữ nguyên.", style=MaterialTheme.typography.bodySmall)
            } else Text("Chưa có đoạn chữ để chỉnh. Hãy quét chữ Trung trước.", style=MaterialTheme.typography.bodySmall)
        }
    }
}
private fun clock(ms: Long): String {
    val s=ms.coerceAtLeast(0L)/1000L
    return "%02d:%02d".format(s/60L,s%60L)
}


private fun exportStageLabel(progress: Int, running: Boolean, twoPass: Boolean): String = when {
    !running && progress >= 100 -> "Đã kiểm tra tệp MP4 và lưu vào Movies/LONGKAKA SELLER AI."
    progress < 5 -> "Đang chuẩn bị dữ liệu và vùng xử lý…"
    twoPass && progress < 45 -> "Đang xử lý hình ảnh, làm sạch chữ gốc…"
    twoPass && progress < 50 -> "Đang kiểm tra video trung gian…"
    progress < 95 -> "Đang kết xuất hình ảnh, phụ đề và âm thanh…"
    progress < 100 -> "Đang kiểm tra MP4 và lưu vào điện thoại…"
    else -> "Hoàn tất."
}
