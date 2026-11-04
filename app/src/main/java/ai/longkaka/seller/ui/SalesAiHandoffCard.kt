package ai.longkaka.seller.ui

import android.content.ClipData
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
 * Shares the extracted video storyboard and factual context with the user's own AI app.
 * No API key, automated login, private endpoint, or scraping is used.
 */
@Composable
fun SalesAiHandoffCard(
    productBrief: String,
    currentDraft: String,
    analysisFrames: List<Uri> = emptyList(),
    onAccept: (String) -> Unit
) {
    val context = LocalContext.current
    var answer by remember { mutableStateOf("") }
    val prompt = remember(productBrief, currentDraft) {
        """
Bạn là chuyên gia phân tích video sản phẩm và sáng tạo video bán hàng thương mại điện tử tại Việt Nam.

QUAN TRỌNG: Các ảnh đính kèm là những khung hình được trích tuần tự từ video gốc. Hãy quan sát trực tiếp từng ảnh để hiểu sản phẩm, hình dáng, chi tiết, thao tác sử dụng, bối cảnh và diễn biến cảnh quay. Không được chỉ dựa vào OCR hoặc đoán tên sản phẩm. Nếu không nhìn rõ chi tiết nào, hãy nói rõ chưa xác định được.

DỮ LIỆU SẢN PHẨM DO NGƯỜI BÁN CUNG CẤP:
$productBrief

BẢN NHÁP HIỆN TẠI (NẾU CÓ):
$currentDraft

DỮ LIỆU ĐA PHƯƠNG THỨC:
Hãy dùng tất cả ảnh đính kèm cùng dữ liệu văn bản bên trên. Ảnh được sắp theo thứ tự thời gian từ đầu đến cuối video. Tách bạch lời thoại đã nhận diện từ âm thanh với chữ OCR nhìn thấy trên hình; kết hợp hai nguồn nhưng không coi chúng là cùng một nội dung. Nếu transcript bị thiếu hoặc khó hiểu, ghi nhận giới hạn và không tự điền câu còn thiếu. Transcript là văn bản nên không đủ căn cứ để khẳng định cảm xúc hay chất giọng gốc.

NHIỆM VỤ:
1. Phân tích nội dung trực quan: xác định sản phẩm khi đủ căn cứ, mô tả cảnh, thao tác, chi tiết nhìn thấy và điểm nào thực sự được video chứng minh. Phân biệt quan sát trực tiếp với suy luận.
2. Phân tích transcript lời thoại gốc cùng OCR: xác định ý giới thiệu, cách dùng, điểm bán hàng và thông tin cần kiểm chứng. Không nhầm lời nói với chữ xuất hiện trên hình.
3. Kết hợp hình ảnh, transcript, OCR và thông tin người bán để hiểu đầy đủ nội dung giới thiệu sản phẩm.
4. Viết MỘT kịch bản bán hàng mới, không dịch nguyên văn: mở đầu gây chú ý trong 2-3 giây, giới thiệu sản phẩm dễ hiểu, giải thích lợi ích gắn với nhu cầu, nêu lý do đáng cân nhắc mua và kết thúc bằng lời kêu gọi hành động tự nhiên.
5. Nếu tên/mã sản phẩm đủ rõ, có thể tra cứu nguồn chính thức hoặc gian hàng đáng tin cậy để xác minh thông số, chất liệu, cách dùng và chính sách. Phân biệt dữ kiện đã xác minh với điều chưa chắc chắn.
6. Không tự bịa công dụng, thông số, chứng nhận, xuất xứ, giá, giảm giá, tồn kho hoặc cam kết. Nếu thiếu dữ liệu, dùng cách nói trung tính.
7. Viết đúng thời lượng yêu cầu, tiếng Việt nói tự nhiên, câu ngắn, dễ đọc bằng giọng AI.
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
                if (analysisFrames.isEmpty()) "Hãy phân tích video để tạo ảnh cảnh quay trước. Sau đó gửi prompt kèm ảnh sang ChatGPT/Gemini."
                else "Đã chuẩn bị ${analysisFrames.size} ảnh theo thứ tự thời gian. AI sẽ nhận ảnh thật, lời thoại đã nhận diện, chữ OCR và dữ liệu sản phẩm để viết lại lời thoại.",
                style = MaterialTheme.typography.bodySmall
            )
            Button(
                onClick = {
                    val streams = ArrayList(analysisFrames)
                    val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "image/jpeg"
                        putExtra(Intent.EXTRA_TEXT, prompt)
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, streams)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        if (streams.isNotEmpty()) {
                            clipData = ClipData.newUri(context.contentResolver, "Ảnh phân tích video", streams.first())
                            streams.drop(1).forEach { clipData?.addItem(ClipData.Item(it)) }
                        }
                    }
                    context.startActivity(Intent.createChooser(send, "Gửi ảnh phân tích sang ChatGPT / Gemini"))
                },
                enabled = analysisFrames.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("GỬI ${analysisFrames.size} ẢNH CẢNH QUAY + PROMPT SANG AI")
            }
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/")))
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("MỞ CHATGPT") }
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://gemini.google.com/")))
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("MỞ GEMINI") }
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
