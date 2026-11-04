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
import ai.longkaka.seller.media.VideoExporter
import ai.longkaka.seller.media.RenderBox
import ai.longkaka.seller.speech.vieneu.VieNeuChunkTiming
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private enum class EditorLayer { TRANSLATION, COVER }
private data class EditableOverlay(val left: Float, val top: Float, val width: Float, val height: Float)

@Composable
fun VideoSubtitleEditor(videoUri: Uri, overlays: List<OnScreenText>, editedTexts: Map<Int, String>, onTextChange: (Int, String) -> Unit, salesEditing: Boolean = false, narrationFile: File? = null, narrationTimings: List<VieNeuChunkTiming> = emptyList()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isExporting by remember { mutableStateOf(false) }
    var exportStatus by remember { mutableStateOf("Chưa xuất video.") }
    val exporter = remember(context) { VideoExporter(context.applicationContext) }
    val exoPlayer = remember(videoUri) {
        ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(videoUri)); prepare(); playWhenReady = false }
    }
    DisposableEffect(exoPlayer) { onDispose { exoPlayer.release() } }
    val projectStore = remember(context) { EditorProjectStore(context) }
    val translationDefaults = remember(overlays) { overlays.map { SavedLayerBox(it.left, it.top, (it.right-it.left).coerceAtLeast(.08f), (it.bottom-it.top).coerceAtLeast(.04f)) } }
    val coverDefaults = remember(overlays) { overlays.map { SavedLayerBox((it.left-.01f).coerceAtLeast(0f), (it.top-.01f).coerceAtLeast(0f), (it.right-it.left+.02f).coerceAtMost(1f), (it.bottom-it.top+.02f).coerceAtMost(1f)) } }
    val savedProject = remember(videoUri, overlays) { projectStore.load(videoUri, overlays.size, translationDefaults, coverDefaults) }
    var selected by remember(overlays) { mutableIntStateOf(0) }
    var layer by remember { mutableStateOf(EditorLayer.TRANSLATION) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var translations by remember(overlays) { mutableStateOf((savedProject?.translations ?: translationDefaults).map { EditableOverlay(it.left,it.top,it.width,it.height) }) }
    var covers by remember(overlays) { mutableStateOf((savedProject?.covers ?: coverDefaults).map { EditableOverlay(it.left,it.top,it.width,it.height) }) }
    var coverEnabled by remember(overlays) { mutableStateOf(savedProject?.coverEnabled ?: overlays.map { true }) }
    var coverColor by remember { mutableIntStateOf(savedProject?.coverColor ?: 0xFF111111.toInt()) }
    var coverAlpha by remember { mutableFloatStateOf(savedProject?.coverAlpha ?: 1f) }
    var textSize by remember { mutableFloatStateOf(savedProject?.textSize ?: 16f) }

    LaunchedEffect(videoUri, translations, covers, if (salesEditing) coverEnabled else overlays.map { false }, coverColor, coverAlpha, textSize, editedTexts, narrationFile) {
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
    LaunchedEffect(selected, overlays) { overlays.getOrNull(selected)?.let { exoPlayer.seekTo(it.timeMs.coerceAtLeast(0L)) } }

    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(if (salesEditing) "BIÊN TẬP VIDEO BÁN HÀNG" else "DỊCH PHIM & CHỈNH SỬA PHỤ ĐỀ", style = MaterialTheme.typography.titleMedium)
            if (salesEditing) Text(if (narrationFile != null) "Đã nhận giọng đọc VieNeu: ${narrationFile.name} • ${narrationTimings.size} đoạn căn thời gian." else "Chưa có giọng đọc bán hàng. Hãy tạo giọng VieNeu trước khi xuất video.", style = MaterialTheme.typography.bodySmall)
            Text(if (salesEditing) "Chỉnh nội dung, vị trí và lớp che trong video bán hàng." else "Chỉ sửa bản dịch tiếng Việt và vị trí phụ đề; không thêm nội dung quảng cáo hay thay đổi video gốc.", style = MaterialTheme.typography.bodySmall)
            BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(16f/9f).background(Color.Black, RoundedCornerShape(10.dp))) {
                val bw = constraints.maxWidth.toFloat()
                val bh = constraints.maxHeight.toFloat()
                AndroidView(factory = { c -> PlayerView(c).apply { player = exoPlayer; useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT } },
                    update = { it.player = exoPlayer }, modifier = Modifier.fillMaxSize())
                if (selected in overlays.indices) {
                    if (layer == EditorLayer.COVER) {
                        val cover = covers[selected]
                        if (coverEnabled[selected]) {
                            Box(Modifier.offset { IntOffset((cover.left*bw).roundToInt(), (cover.top*bh).roundToInt()) }
                                .size((cover.width*maxWidth.value).dp, (cover.height*maxHeight.value).dp)
                                .background(Color(coverColor).copy(alpha=coverAlpha), RoundedCornerShape(3.dp))
                                .pointerInput(selected, cover) { detectDragGestures { change, drag ->
                                    change.consume()
                                    covers = covers.toMutableList().also { it[selected] = cover.copy(left=(cover.left+drag.x/bw).coerceIn(0f,1f-cover.width), top=(cover.top+drag.y/bh).coerceIn(0f,1f-cover.height)) }
                                } })
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
                            Text(editedTexts[selected] ?: overlays[selected].vietnameseText, color=Color.White, fontSize=textSize.sp, lineHeight=(textSize*1.15f).sp)
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
            Button(onClick = {
                if (!isExporting) {
                    isExporting = true
                    exportStatus = "Đang kết xuất MP4…"
                    scope.launch {
                        runCatching {
                            exporter.export(videoUri, overlays,
                                translations.map { RenderBox(it.left,it.top,it.width,it.height) },
                                covers.map { RenderBox(it.left,it.top,it.width,it.height) },
                                if (salesEditing) coverEnabled else overlays.map { false }, coverColor, coverAlpha, textSize, editedTexts, narrationFile)
                        }.onSuccess { exportStatus = "ĐÃ XUẤT VIDEO • Movies/LONGKAKA SELLER AI" }
                         .onFailure { exportStatus = "Lỗi xuất video: " + (it.message ?: "Không xác định") }
                        isExporting = false
                    }
                }
            }, enabled = !isExporting, modifier = Modifier.fillMaxWidth()) {
                Text(if (isExporting) "ĐANG XUẤT VIDEO…" else "XUẤT VIDEO MP4 ĐÃ CHỈNH SỬA")
            }
            Text(exportStatus, style = MaterialTheme.typography.bodySmall)
            if (overlays.isNotEmpty()) {
                Text("CÁC ĐOẠN CHỮ ĐÃ NHẬN DIỆN", style=MaterialTheme.typography.labelLarge)
                Column(Modifier.heightIn(max=160.dp).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    overlays.forEachIndexed { index, item ->
                        OutlinedButton(onClick={ selected=index; exoPlayer.seekTo(item.timeMs) }, modifier=Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text("Đoạn ${index+1} • ${clock(item.timeMs)}–${clock(item.lastTimeMs)}")
                                Text(item.sourceText, style=MaterialTheme.typography.bodySmall, maxLines=1)
                            }
                        }
                    }
                }
                OutlinedTextField(value=editedTexts[selected] ?: overlays[selected].vietnameseText,
                    onValueChange={ onTextChange(selected,it) }, modifier=Modifier.fillMaxWidth(), label={ Text("Nội dung dịch tiếng Việt") })
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected=layer==EditorLayer.TRANSLATION,onClick={layer=EditorLayer.TRANSLATION},label={Text("Chỉnh phụ đề")})
                    if (salesEditing) FilterChip(selected=layer==EditorLayer.COVER,onClick={layer=EditorLayer.COVER},label={Text("Khung che")})
                }
                if(salesEditing && layer==EditorLayer.COVER) {
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
                    Text("Độ đậm nền: ${(coverAlpha*100).roundToInt()}%")
                    Slider(value=coverAlpha,onValueChange={coverAlpha=it},valueRange=.1f..1f)
                } else {
                    Text("Chiều rộng vùng dịch: ${(translations[selected].width*100).roundToInt()}%")
                    Slider(value=translations[selected].width,onValueChange={ v -> translations=translations.toMutableList().also { it[selected]=it[selected].copy(width=v.coerceIn(.05f,maxOf(.05f,1f-translations[selected].left))) } },valueRange=.05f..1f)
                    Text("Chiều cao vùng dịch: ${(translations[selected].height*100).roundToInt()}%")
                    Slider(value=translations[selected].height,onValueChange={ v -> translations=translations.toMutableList().also { it[selected]=it[selected].copy(height=v.coerceIn(.03f,maxOf(.03f,1f-translations[selected].top))) } },valueRange=.03f..1f)
                    Text("Cỡ chữ dịch: ${textSize.roundToInt()} sp")
                    Slider(value=textSize,onValueChange={textSize=it},valueRange=10f..28f)
                }
                Text(if (salesEditing) "Kéo lớp trên video để đổi vị trí. Các chỉnh sửa được áp dụng khi xuất MP4." else "Kéo phụ đề trên video để đổi vị trí. Chỉ bản dịch đã sửa được ghi vào MP4; âm thanh và hình ảnh gốc được giữ nguyên.", style=MaterialTheme.typography.bodySmall)
            } else Text("Chưa có đoạn chữ để chỉnh. Hãy quét chữ Trung trước.", style=MaterialTheme.typography.bodySmall)
        }
    }
}
private fun clock(ms: Long): String {
    val s=ms.coerceAtLeast(0L)/1000L
    return "%02d:%02d".format(s/60L,s%60L)
}
