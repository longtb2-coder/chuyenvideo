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
    val prompt = remember(productBrief, currentDraft, analysisFrames.isNotEmpty()) {
        """
Bạn là chuyên gia phân tích video sản phẩm và sáng tạo video bán hàng thương mại điện tử tại Việt Nam.

${if (analysisFrames.isNotEmpty()) "QUAN TRỌNG: Các ảnh đính kèm là những khung hình được trích tuần tự từ video gốc. Hãy quan sát trực tiếp từng ảnh để hiểu sản phẩm, hình dáng, chi tiết, thao tác sử dụng, bối cảnh và diễn biến cảnh quay. Không được chỉ dựa vào OCR hoặc đoán tên sản phẩm. Nếu không nhìn rõ chi tiết nào, hãy nói rõ chưa xác định được." else "LƯU Ý: Hiện chưa lấy được ảnh khung hình từ video. Bạn chỉ có dữ liệu OCR và lời thoại trong phần văn bản bên dưới. Không được giả vờ đã xem hình hoặc tự đoán sản phẩm; hãy nêu rõ điều gì chưa thể xác nhận và viết lời thoại thận trọng dựa trên dữ liệu có căn cứ."}

DỮ LIỆU SẢN PHẨM DO NGƯỜI BÁN CUNG CẤP:
$productBrief

BẢN NHÁP HIỆN TẠI (NẾU CÓ):
$currentDraft

DỮ LIỆU ĐA PHƯƠNG THỨC:
Hãy dùng tất cả ảnh đính kèm cùng dữ liệu văn bản bên trên. Ảnh được sắp theo thứ tự thời gian từ đầu đến cuối video. Tách bạch lời thoại đã nhận diện từ âm thanh với chữ OCR nhìn thấy trên hình; kết hợp hai nguồn nhưng không coi chúng là cùng một nội dung. Nếu transcript bị thiếu hoặc khó hiểu, ghi nhận giới hạn và không tự điền câu còn thiếu. Transcript là văn bản nên không đủ căn cứ để khẳng định cảm xúc hay chất giọng gốc.

NHIỆM VỤ PHÂN TÍCH SẢN PHẨM:
1. Xem lần lượt tất cả ảnh, không bỏ qua ảnh giữa video. Xác định sản phẩm/nhóm sản phẩm, hình dáng, bộ phận, phụ kiện, cách cầm/nắm/lắp đặt/vận hành, bối cảnh sử dụng và trình tự thao tác.
2. Nêu rõ: sản phẩm giải quyết nhu cầu gì; ai có thể cần; tình huống sử dụng nào được hình ảnh chứng minh; điểm khác biệt nào nhìn thấy được; điểm nào chỉ là suy luận và chưa thể xác nhận.
3. Đối chiếu hình ảnh với OCR và lời thoại gốc. Nếu ba nguồn mâu thuẫn, không tự chọn một thông tin làm sự thật; ưu tiên dữ kiện người bán xác nhận và ghi nhận phần chưa rõ.
4. Trước khi viết, tự lập bảng kiểm nội bộ gồm: đặc điểm nhìn thấy, lợi ích có căn cứ, lợi ích chưa đủ căn cứ, thông tin còn thiếu, câu hỏi người mua có thể quan tâm. Không biến suy luận thành cam kết quảng cáo.
5. Dùng phong cách và thời lượng người bán yêu cầu. Viết MỘT kịch bản mới, không dịch nguyên văn: mở đầu cuốn hút 2-3 giây; giới thiệu đúng sản phẩm; nối từng thao tác/đặc điểm nhìn thấy với lợi ích thực tế có căn cứ; nói rõ đối tượng phù hợp; kết thúc bằng CTA tự nhiên.
6. Chỉ dùng giá, ưu đãi, thông số, chất liệu, xuất xứ, chứng nhận, bảo hành, tồn kho và công dụng đặc biệt khi có nguồn xác nhận. Nếu chưa có, bỏ qua hoặc dùng cách diễn đạt trung tính.
7. Lời thoại tiếng Việt tự nhiên, câu ngắn, không đọc kiểu liệt kê máy móc, không lặp ý, phù hợp tốc độ giọng AI và đúng thời lượng.
HÃY TRẢ KẾT QUẢ THEO ĐÚNG 2 PHẦN SAU:
[PHAN_TICH]
- Sản phẩm/nhóm sản phẩm nhận diện được:
- Diễn biến video theo thứ tự cảnh:
- Đặc điểm nhìn thấy và thao tác thực tế:
- Nhu cầu/đối tượng phù hợp:
- Lợi ích có bằng chứng:
- Thông tin chưa xác minh hoặc còn thiếu:
- Gợi ý góc bán hàng mới:
[/PHAN_TICH]
[KICH_BAN]
Chỉ viết lời thoại hoàn chỉnh, tự nhiên, đúng thời lượng, không chèn ghi chú sân khấu.
[/KICH_BAN]
Không bỏ phần phân tích. Không bịa dữ kiện. Nếu thiếu ảnh hoặc transcript, phải nói rõ giới hạn.
        """.trimIndent()
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AI QUAN SÁT SẢN PHẨM & VIẾT LỜI BÁN HÀNG", style = MaterialTheme.typography.titleSmall)
            Text(
                if (analysisFrames.isEmpty()) "Chưa có ảnh cảnh quay. Bạn vẫn có thể xem, sao chép và gửi prompt dạng văn bản; AI sẽ chỉ dựa trên OCR và lời thoại, không giả định đã xem video."
                else "Đã chuẩn bị ${analysisFrames.size} ảnh cảnh quay. Prompt sẽ đi kèm ảnh, lời thoại, chữ OCR và dữ liệu sản phẩm để AI viết lời bán hàng mới.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = prompt,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                maxLines = 8,
                label = { Text("PROMPT GỬI CHATGPT / GEMINI") }
            )
            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Prompt viết lời thoại bán hàng", prompt))
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("SAO CHÉP PROMPT") }
            Button(
                onClick = {
                    val send = if (analysisFrames.isNotEmpty()) {
                        val streams = ArrayList(analysisFrames)
                        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                            type = "image/jpeg"
                            putExtra(Intent.EXTRA_TEXT, prompt)
                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, streams)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            clipData = ClipData.newUri(context.contentResolver, "Ảnh phân tích video", streams.first())
                            streams.drop(1).forEach { clipData?.addItem(ClipData.Item(it)) }
                        }
                    } else {
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, prompt)
                        }
                    }
                    context.startActivity(Intent.createChooser(send, "Gửi prompt sang ChatGPT / Gemini"))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (analysisFrames.isEmpty()) "GỬI PROMPT VĂN BẢN SANG CHATGPT / GEMINI"
                    else "GỬI ${analysisFrames.size} ẢNH + PROMPT SANG AI")
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
                label = { Text("Dán kết quả AI (gồm phân tích và kịch bản)") }
            )
            Button(
                onClick = {\n                    val script = Regex("(?is)\\\\[KICH_BAN\\\\](.*?)\\\\[/KICH_BAN\\\\]").find(answer)?.groupValues?.get(1)?.trim()\n                    onAccept(script?.takeIf { it.isNotBlank() } ?: answer.trim())\n                },
                enabled = answer.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("LẤY RIÊNG KỊCH BẢN ĐỂ TẠO GIỌNG ĐỌC") }
        }
    }
}
