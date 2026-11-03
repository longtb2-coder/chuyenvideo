package ai.longkaka.seller.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.livedata.observeAsState
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import ai.longkaka.seller.background.MediaWorkRequest
import ai.longkaka.seller.translation.ProductCategory
import android.net.Uri
import androidx.compose.ui.platform.LocalContext

@Composable
fun BackgroundScanCard(videoUri: Uri?, category: ProductCategory) {
    val context = LocalContext.current
    val workManager = WorkManager.getInstance(context)
    val workName = videoUri?.toString()?.hashCode()?.let { "seller-scan-$it" } ?: "seller-scan-none"
    val workInfos by workManager.getWorkInfosForUniqueWorkLiveData(workName).observeAsState()
    val work = workInfos?.firstOrNull()
    val progress = work?.progress?.getInt(MediaWorkRequest.KEY_PROGRESS, 0) ?: 0
    val isActive = work?.state == WorkInfo.State.RUNNING || work?.state == WorkInfo.State.ENQUEUED

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("QUÉT & DỊCH CHẠY NỀN", style = MaterialTheme.typography.titleMedium)
            Text(
                "Tác vụ chạy bằng dịch vụ nền có thông báo tiến độ; bạn có thể chuyển sang ứng dụng khác trong lúc xử lý.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = {
                    val uri = videoUri ?: return@Button
                    val input = Data.Builder()
                        .putString(MediaWorkRequest.KEY_INPUT_URI, uri.toString())
                        .putString(MediaWorkRequest.KEY_CATEGORY, category.name)
                        .build()
                    val request = OneTimeWorkRequestBuilder<MediaWorkRequest>()
                        .setInputData(input)
                        .addTag("seller-video-scan")
                        .build()
                    workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, request)
                },
                enabled = videoUri != null && !isActive,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (isActive) "ĐANG XỬ LÝ Ở NỀN…" else "BẮT ĐẦU QUÉT & DỊCH Ở NỀN") }

            if (isActive) {
                LinearProgressIndicator(
                    progress = { (progress.coerceIn(0, 100)) / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Tiến độ: $progress%", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    onClick = { workManager.cancelUniqueWork(workName) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("HỦY TÁC VỤ") }
            } else if (work?.state == WorkInfo.State.SUCCEEDED) {
                val count = work.outputData.getInt(MediaWorkRequest.KEY_RESULT_COUNT, 0)
                Text(
                    "Đã quét xong $count vùng chữ. Kết quả JSON đã lưu trong vùng dữ liệu riêng của ứng dụng.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            } else if (work?.state == WorkInfo.State.FAILED) {
                Text(
                    work.outputData.getString(MediaWorkRequest.KEY_ERROR) ?: "Quét nền thất bại.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else if (work?.state == WorkInfo.State.CANCELLED) {
                Text("Tác vụ đã được hủy.", style = MaterialTheme.typography.bodySmall)
            } else if (videoUri == null) {
                Text("Hãy nhập hoặc chọn video trước.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
