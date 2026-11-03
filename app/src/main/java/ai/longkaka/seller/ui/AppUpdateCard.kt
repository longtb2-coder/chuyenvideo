package ai.longkaka.seller.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ai.longkaka.seller.update.AppRelease
import ai.longkaka.seller.update.AppUpdateRepository
import ai.longkaka.seller.update.UpdateCheckResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AppUpdateCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { AppUpdateRepository() }
    val currentVersion = remember(context) {
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.1.0"
        }.getOrDefault("0.1.0")
    }
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Phiên bản hiện tại: ${currentVersion}. Chạm để kiểm tra bản mới.") }
    var release by remember { mutableStateOf<AppRelease?>(null) }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            androidx.compose.material3.Icon(Icons.Rounded.SystemUpdate, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary)
            Text("05  •  CẬP NHẬT ỨNG DỤNG", style = MaterialTheme.typography.titleMedium)
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (checking) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Button(
                onClick = {
                    scope.launch {
                        checking = true
                        release = null
                        status = "Đang kiểm tra phiên bản mới…"
                        try {
                            when (val result = withContext(Dispatchers.IO) { repository.check(currentVersion) }) {
                                is UpdateCheckResult.Available -> {
                                    release = result.release
                                    status = "Có phiên bản mới: ${result.release.tagName}. ${result.release.title}"
                                }
                                is UpdateCheckResult.UpToDate -> status = "Bạn đang dùng phiên bản mới nhất (${result.currentVersion})."
                                UpdateCheckResult.NoRelease -> status = "Chưa có bản cập nhật chính thức được phát hành. Khi có bản mới, mục này sẽ thông báo."
                            }
                        } catch (error: Throwable) {
                            status = "Không kiểm tra được. Hãy kiểm tra kết nối Internet rồi thử lại. ${error.message.orEmpty()}"
                        } finally {
                            checking = false
                        }
                    }
                },
                enabled = !checking,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (checking) "ĐANG KIỂM TRA…" else "KIỂM TRA CẬP NHẬT") }
            release?.let { latest ->
                if (latest.notes.isNotBlank()) {
                    Text(latest.notes, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = {
                        val target = latest.apkUrl ?: latest.pageUrl
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (latest.apkUrl != null) "TẢI APK BẢN MỚI" else "MỞ TRANG PHÁT HÀNH") }
            }
            Text("Cập nhật được kiểm tra từ GitHub Releases chính thức của dự án. Không tải hoặc cài APK từ nguồn lạ.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
