# Quyết định kỹ thuật: nhận diện lời thoại tiếng Trung offline

Trạng thái: nghiên cứu lựa chọn, CHƯA tích hợp vào APK.

## Mục tiêu
- Nhận diện lời thoại Mandarin trực tiếp trên Android, không gửi video/âm thanh lên máy chủ.
- Không cần API key và không phát sinh phí theo phút.
- Chạy được trên ARM64; có thể tải mô hình riêng sau khi cài app.
- Kết quả phải có câu chữ và mốc thời gian đủ tin cậy để dịch, tạo phụ đề và thay giọng đọc.
- Không được tự bịa phần lời thoại không nhận diện được.

## Lựa chọn đang ưu tiên thử nghiệm: sherpa-onnx + SenseVoice int8
Tài liệu sherpa-onnx xác nhận SenseVoice hỗ trợ Mandarin, Cantonese, English, Japanese, Korean; có API Kotlin/Java và hỗ trợ Android arm64-v8a. Đây là cơ sở phù hợp để làm adapter Android offline. Tham khảo:
- https://k2-fsa.github.io/sherpa/onnx/sense-voice/
- https://k2-fsa.github.io/sherpa/onnx/pretrained_models/

Chưa chốt phiên bản thư viện hoặc mô hình nhúng trong APK. Mô hình sẽ được tải theo yêu cầu, lưu trong vùng dữ liệu ứng dụng và kiểm tra checksum; không đóng gói model lớn vào APK để tránh làm APK nặng và tránh tải lại khi cập nhật.

## Phương án dự phòng: Paraformer tiếng Trung
sherpa-onnx có các mô hình Paraformer Mandarin/Chinese-English offline. Một bản int8 được tài liệu công bố có file model khoảng 227 MB; bản small có dung lượng nhỏ hơn nhưng tài liệu ghi rõ không hỗ trợ timestamp. Vì vậy phải kiểm tra timestamp và chất lượng trên video mẫu trước khi chọn làm mặc định.
- https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-paraformer/

## Điều kiện trước khi bật tính năng trong giao diện
1. Chốt phiên bản sherpa-onnx Android và ABI phù hợp.
2. Tải model vào thư mục riêng, kiểm tra dung lượng trống, checksum, khả năng tiếp tục tải và xóa model lỗi.
3. Dùng MediaExtractor/MediaCodec để giải mã audio thành PCM mono 16 kHz; kiểm thử video có AAC/HE-AAC.
4. Chạy nhận diện theo từng đoạn có chồng lấn; giữ thứ tự và quy đổi timestamp về timeline gốc.
5. Kiểm thử trên chính video mẫu 127 giây; so sánh thủ công transcript, đặc biệt tên sản phẩm, số lượng, đơn vị và câu quảng cáo.
6. Nếu model không trả timestamp đáng tin cậy, không gán timestamp giả; cần bước căn chỉnh riêng hoặc hiển thị transcript để người dùng duyệt trước.
7. Chỉ sau khi đạt kiểm thử mới nối vào WorkManager foreground job.

## Nguyên tắc dịch và dựng
- Tách transcript lời nói khỏi OCR chữ trên màn hình; hai nguồn không được nhập nhằng.
- Bản dịch phải cho phép sửa trước khi tạo giọng đọc.
- Giữ nguyên tên thương hiệu, mã hàng, số đo, giá và thông số kỹ thuật; không tự thêm công dụng.
- Giữ video gốc; xuất bản MP4 mới, giữ tỷ lệ khung hình gốc.
- Mô hình ASR và model giọng đọc là hai gói độc lập, có thể cập nhật mà không xóa thư viện video.

## Giới hạn hiện tại
Đây là quyết định kiến trúc dựa trên tài liệu upstream, chưa phải bằng chứng rằng mô hình đã chạy trên OPPO hoặc đã nhận diện chính xác video mẫu. Không bật nút xử lý thật cho đến khi có test chạy được và kết quả đo được.
