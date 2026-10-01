# libs/ — thư viện ngoài Maven Central (VnCoreNLP)

Mặc định hệ thống tách từ bằng **Maximum Matching** (không cần gì ở đây).

Để bật **VnCoreNLP** (chất lượng tách từ cao hơn — định hướng thiết kế S0.4):

1. Tải `VnCoreNLP-1.2.jar` và thư mục `models/` từ repo chính thức
   <https://github.com/vncorenlp/VnCoreNLP> (Apache-2.0).
2. Đặt vào đây:
   ```
   java-lucene/libs/VnCoreNLP-1.2.jar
   java-lucene/libs/models/wordsegmenter/...
   ```
3. Thêm jar vào classpath khi chạy và bật backend:
   ```bash
   java -Dbksearch.segmenter=vncorenlp -cp "target/hust-search.jar;libs/VnCoreNLP-1.2.jar" vn.hust.ir.App ...
   ```
   (hoặc `-Dbksearch.segmenter=auto` để tự lùi về Maximum Matching nếu thiếu).

> `jar` và `models/` KHÔNG commit vào git (đã loại trong `.gitignore`), vì dung lượng lớn
> và giấy phép đi kèm. Chỉ giữ file README này để hướng dẫn.

Lớp liên quan: `vn.hust.ir.nlp.VietnameseAnalyzer` (điểm vào chung, ràng buộc G3),
`VnCoreNlpSegmenter` (nạp VnCoreNLP qua reflection), `VietnameseSegmenter` (Maximum Matching).
