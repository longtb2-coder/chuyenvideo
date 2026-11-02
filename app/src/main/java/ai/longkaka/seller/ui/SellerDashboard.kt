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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SellerDashboard() {
    var sourceUrl by remember { mutableStateOf("") }
    var selectedVideo by remember { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        selectedVideo = uri
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
                Text("01  •  NGUỒN VIDEO", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = sourceUrl, onValueChange = { sourceUrl = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Dán link video (nếu nguồn hỗ trợ)") },
                    placeholder = { Text("TikTok / Facebook / Douyin / Shopee") }
                )
                OutlinedButton(onClick = { picker.launch(arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth()) {
                    androidx.compose.material3.Icon(Icons.Rounded.UploadFile, contentDescription = null)
                    Text(if (selectedVideo == null) "Chọn video trong điện thoại" else "Đã chọn video")
                }
                Text("Nhập link có thể bị giới hạn bởi nền tảng. Chọn video từ máy là phương án dự phòng ổn định nhất.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("02  •  XỬ LÝ AI", style = MaterialTheme.typography.titleMedium)
                ToolRow(Icons.Rounded.Translate, "Nhận diện & dịch tiếng Trung", "OCR, lời thoại và nội dung sản phẩm")
                ToolRow(Icons.Rounded.AutoAwesome, "Làm sạch chữ trên video", "Làm mờ lớp chữ được phép chỉnh sửa")
                ToolRow(Icons.Rounded.Subtitles, "Tạo phụ đề tiếng Việt", "Căn thời gian và cho phép chỉnh sửa")
                ToolRow(Icons.Rounded.CloudDownload, "Thay giọng đọc tiếng Việt", "Chọn giọng và nghe thử trước khi ghép")
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("03  •  THÀNH PHẨM", style = MaterialTheme.typography.titleMedium)
                ToolRow(Icons.Rounded.Movie, "Biên tập video bán hàng", "Tỷ lệ dọc 9:16 • phụ đề • giá • CTA")
                Button(onClick = { /* Pipeline is wired in the media foundation phase. */ },
                    enabled = selectedVideo != null || sourceUrl.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()) {
                    Text("TẠO VIDEO BÁN HÀNG")
                }
                Text("Bản nền giao diện: các công cụ AI sẽ được bật khi từng bộ xử lý và dịch vụ được tích hợp, kiểm thử.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(10.dp))
    }
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
