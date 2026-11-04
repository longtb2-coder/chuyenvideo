package ai.longkaka.seller.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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

Nhiệm vụ:
1. Phân tích nội dung đã dịch từ video để nhận ra sản phẩm, công dụng được thể hiện, điểm nổi bật và nhóm khách hàng có thể phù hợp.
2. Viết MỘT kịch bản bán hàng mới, không dịch nguyên văn: mở đầu gây chú ý trong 2-3 giây, giới thiệu sản phẩm dễ hiểu, giải thích lợi ích gắn với nhu cầu, nêu lý do đáng cân nhắc mua và kết thúc bằng lời kêu gọi hành động tự nhiên.
3. Nếu tên/mã sản phẩm đủ rõ, hãy tra cứu thêm bằng nguồn chính thức hoặc gian hàng đáng tin cậy nếu có thể; ưu tiên xác minh thông số, chất liệu, cách dùng và chính sách. Phân biệt rõ dữ kiện đã xác minh với điều chưa chắc chắn.
4. Không tự bịa công dụng, thông số, chứng nhận, xuất xứ, giá, giảm giá, tồn kho hoặc cam kết. Nếu thiếu dữ liệu, dùng cách nói trung tính hoặc hỏi người bán bổ sung.
5. Viết đúng thời lượng yêu cầu, tiếng Việt nói tự nhiên, câu ngắn, dễ đọc bằng giọng AI.
Chỉ trả về lời thoại hoàn chỉnh để đưa vào tạo giọng đọc; không thêm phân tích hay ghi chú.
        """.trimIndent()
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
