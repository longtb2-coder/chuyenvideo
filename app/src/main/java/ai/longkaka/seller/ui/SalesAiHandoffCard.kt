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

NHIỆM VỤ PHÂN TÍCH SẢN PHẨM:
1. Xem lần lượt tất cả ảnh, không bỏ qua ảnh giữa video. Xác định sản phẩm/nhóm sản phẩm, hình dáng, bộ phận, phụ kiện, cách cầm/nắm/lắp đặt/vận hành, bối cảnh sử dụng và trình tự thao tác.
2. Nêu rõ: sản phẩm giải quyết nhu cầu gì; ai có thể cần; tình huống sử dụng nào được hình ảnh chứng minh; điểm khác biệt nào nhìn thấy được; điểm nào chỉ là suy luận và chưa thể xác nhận.
3. Đối chiếu hình ảnh với OCR và lời thoại gốc. Nếu ba nguồn mâu thuẫn, không tự chọn một thông tin làm sự thật; ưu tiên dữ kiện người bán xác nhận và ghi nhận phần chưa rõ.
4. Trước khi viết, tự lập bảng kiểm nội bộ gồm: đặc điểm nhìn thấy, lợi ích có căn cứ, lợi ích chưa đủ căn cứ, thông tin còn thiếu, câu hỏi người mua có thể quan tâm. Không biến suy luận thành cam kết quảng cáo.
5. Dùng phong cách và thời lượng người bán yêu cầu. Viết MỘT kịch bản mới, không dịch nguyên văn: mở đầu cuốn hút 2-3 giây; giới thiệu đúng sản phẩm; nối từng thao tác/đặc điểm nhìn thấy với lợi ích thực tế có căn cứ; nói rõ đối tượng phù hợp; kết thúc bằng CTA tự nhiên.
6. Chỉ dùng giá, ưu đãi, thông số, chất liệu, xuất xứ, chứng nhận, bảo hành, tồn kho và công dụng đặc biệt khi có nguồn xác nhận. Nếu chưa có, bỏ qua hoặc dùng cách diễn đạt trung tính.
7. Lời thoại tiếng Việt tự nhiên, câu ngắn, không đọc kiểu liệt kê máy móc, không lặp ý, phù hợp tốc độ giọng AI và đúng thời lượng.
Chỉ trả về lời thoại hoàn chỉnh để đưa vào tạo giọng đọc; không thêm tiêu đề, phân tích hay ghi chú.
        """.trimIndent()
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AI QUAN SÁT SẢN PHẨM & VIẾT LỜI BÁN HÀNG", style = MaterialTheme.typography.titleSmall)
            Text(
                if (analysisFrames.isEmpty()) "Hãy phân tích video để tạo ảnh cảnh quay trước. Sau đó gửi prompt kèm ảnh sang ChatGPT/Gemini."
                else "Đã chuẩn bị ${analysisFrames.size} ảnh cảnh quay. Khi gửi sang ChatGPT/Gemini, AI sẽ xem sản phẩm trong ảnh, đối chiếu lời thoại, chữ OCR và thông tin bạn cung cấp rồi viết lời bán hàng mới.",
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
