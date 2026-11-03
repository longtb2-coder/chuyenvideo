package ai.longkaka.seller.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Uses the user's own ChatGPT/Gemini app or browser session.
 * No API key, automated login, private endpoint, or scraping is used.
 */
@Composable
fun SalesAiHandoffCard(
    productBrief: String,
    currentDraft: String,
    onAccept: (String) -> Unit
) {
    val context = LocalContext.current
    var answer by remember { mutableStateOf("") }
    val prompt = remember(productBrief, currentDraft) {
        """
Bạn là chuyên gia sáng tạo video bán hàng thương mại điện tử tại Việt Nam.
Hãy viết lời thoại bán hàng tự nhiên, thuyết phục, đúng thời lượng video.
Dữ liệu sản phẩm do người bán cung cấp:
$productBrief

Bản nháp hiện tại (nếu có):
$currentDraft

Yêu cầu:
- Mở đầu thu hút trong 2-3 giây, nêu đúng nhu cầu khách hàng.
- Nêu lợi ích dựa trên dữ kiện được cung cấp; tuyệt đối không tự bịa công dụng, thông số, chứng nhận, giá, giảm giá hay cam kết.
- Ngôn ngữ nói tự nhiên, câu ngắn, dễ đọc bằng giọng AI tiếng Việt.
- Có lời kêu gọi hành động rõ ràng nhưng không gây hiểu lầm.
- Chỉ trả về lời thoại hoàn chỉnh, không thêm phân tích hay ghi chú.
        """.trimIndent()
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AI VIẾT KỊCH BẢN • KHÔNG CẦN API", style = MaterialTheme.typography.titleSmall)
            Text(
                "Gửi prompt sang ChatGPT hoặc Gemini bằng ứng dụng/trình duyệt và tài khoản của bạn. Sau đó dán câu trả lời vào ô bên dưới. Không cần nhập API key.",
                style = MaterialTheme.typography.bodySmall
            )
            Button(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, prompt)
                }
                context.startActivity(Intent.createChooser(send, "Gửi yêu cầu tới ChatGPT hoặc Gemini"))
            }, modifier = Modifier.fillMaxWidth()) {
                Text("GỬI PROMPT SANG CHATGPT / GEMINI")
            }
            OutlinedButton(onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/"))
                context.startActivity(intent)
            }, modifier = Modifier.fillMaxWidth()) { Text("MỞ CHATGPT") }
            OutlinedButton(onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://gemini.google.com/"))
                context.startActivity(intent)
            }, modifier = Modifier.fillMaxWidth()) { Text("MỞ GEMINI") }
            OutlinedTextField(
                value = answer,
                onValueChange = { answer = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 5,
                label = { Text("Dán lời thoại ChatGPT/Gemini trả về") }
            )
            Button(
                onClick = { onAccept(answer.trim()) },
                enabled = answer.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("DÙNG LỜI THOẠI NÀY ĐỂ TẠO GIỌNG ĐỌC") }
        }
    }
}
