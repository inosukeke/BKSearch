# Tìm hiểu công nghệ dùng trong hệ thống thu thập & tìm kiếm tài liệu HUST

> Tài liệu này giải thích **khái niệm + cách hoạt động** của từng công nghệ, kèm ví dụ,
> để đọc/học và trả lời khi bảo vệ. Đọc theo thứ tự đường đi của dữ liệu:
> **Thu thập → Bóc tách → Xử lý tiếng Việt → Lập chỉ mục → Tìm kiếm**.

---

## 0. Bức tranh tổng thể: Information Retrieval (IR) là gì?

**Tìm kiếm thông tin (IR)** là việc tìm các tài liệu phù hợp với nhu cầu (truy vấn) của
người dùng trong một kho tài liệu lớn. Một hệ thống IR cơ bản gồm 3 giai đoạn:

1. **Thu thập & tiền xử lý** (crawl, bóc text, tách từ, chuẩn hóa).
2. **Lập chỉ mục (indexing)**: biến văn bản thành cấu trúc tra cứu nhanh (inverted index).
3. **Truy vấn & xếp hạng (retrieval & ranking)**: nhận từ khóa, trả tài liệu sắp theo độ liên quan.

Đường đi dữ liệu trong đồ án:

```
Trang web *.hust.edu.vn
   │  (1) Crawler: Jsoup + robots.txt
   ▼
Văn bản + link tài liệu  ──►  (2) Tika bóc text từ PDF/DOC
   │
   ▼  (3) Tách từ + chuẩn hóa tiếng Việt
Chuỗi token
   │
   ▼  (4) Lucene: inverted index
Chỉ mục
   │
   ▼  (5) Truy vấn → BM25 → kết quả xếp hạng
Người dùng
```

---

## 1. Thu thập dữ liệu (Web Crawling)

### 1.1 Crawler là gì?
Một **web crawler** (bot thu thập) tự động tải trang web, đọc nội dung, rồi đi theo các
liên kết để tải tiếp — giống việc lần theo các sợi dây nối các trang.

Cơ chế cốt lõi:
- **Frontier (hàng đợi URL)**: danh sách URL chờ tải.
- **Visited set**: tập URL đã tải, tránh lặp vô hạn.
- **Duyệt BFS/DFS**: đồ án dùng **BFS (theo chiều rộng)** — tải hết các link ở tầng gần
  trước rồi mới đi sâu; kiểm soát độ sâu (`maxDepth`) dễ hơn.

### 1.2 Jsoup — thư viện phân tích HTML (Java)
- Jsoup tải trang HTML và cho phép **truy vấn phần tử bằng CSS selector** (giống jQuery).
- Ví dụ trích tiêu đề và mọi liên kết:
  ```java
  Document doc = Jsoup.connect(url).userAgent("...").get();
  String title = doc.title();                 // <title>
  for (Element a : doc.select("a[href]")) {   // mọi thẻ <a> có href
      String link = a.absUrl("href");         // URL tuyệt đối
  }
  ```
- Jsoup **không chạy JavaScript**. Với trang render bằng JS (nội dung nạp động), phải dùng
  **Selenium/Puppeteer** (điều khiển trình duyệt thật). Đồ án chưa cần vì trang HUST chủ yếu
  là HTML tĩnh.

### 1.3 Crawl có đạo đức & robots.txt
Mỗi website có file `robots.txt` (ví dụ `https://hust.edu.vn/robots.txt`) khai báo phần nào
bot được/không được tải. Crawler lịch sự phải:
- **Tuân thủ robots.txt** — đồ án dùng thư viện **crawler-commons** để đọc & kiểm tra.
- **Đặt độ trễ** giữa các request (`DOWNLOAD_DELAY`, ở đây 1 giây) để không làm quá tải
  máy chủ.
- Khai báo **User-Agent** rõ ràng để chủ website biết ai đang truy cập.

> Vì sao quan trọng: crawl quá nhanh có thể bị coi là tấn công (DoS) và bị chặn IP.

---

## 2. Bóc tách văn bản từ tài liệu — Apache Tika

### 2.1 Vấn đề
Tài liệu trên web không chỉ là HTML mà còn PDF, Word (.doc/.docx), Excel (.xls/.xlsx)...
Mỗi định dạng lưu trữ khác nhau; muốn tìm kiếm được **nội dung bên trong** thì phải trích
ra text thuần.

### 2.2 Apache Tika là gì?
- Bộ công cụ Java **tự nhận diện định dạng** và **trích xuất nội dung + metadata** từ hơn
  1000 loại tệp. Bên trong nó gọi các thư viện chuyên biệt (PDFBox cho PDF, POI cho Office...).
- Dùng cực đơn giản:
  ```java
  Tika tika = new Tika();
  String text = tika.parseToString(inputStream); // ra text thuần, bất kể PDF hay DOCX
  ```
- Trong đồ án: mỗi URL tài liệu (bảng `files`) được tải về luồng rồi đưa qua Tika, text thu
  được đem đi lập chỉ mục cùng bài viết → **tìm kiếm được cả nội dung file PDF/DOC**.

### 2.3 Khái niệm cần nhớ
- **Content detection**: Tika đoán định dạng dựa trên phần mở rộng + "magic bytes" (chữ ký
  đầu tệp), không tin mỗi đuôi file.
- **Parser**: mỗi định dạng có một parser; `AutoDetectParser` tự chọn parser phù hợp.

---

## 3. Tách từ và chuẩn hóa tiếng Việt (trọng tâm của đề)

### 3.1 Vì sao tiếng Việt khó?
Tiếng Việt viết **rời từng âm tiết** bằng dấu cách, nhưng một **từ** có nghĩa có thể gồm
nhiều âm tiết:
- "đại học" = 1 từ (university) = 2 âm tiết.
- "nghiên cứu khoa học" = 2 từ: "nghiên cứu" + "khoa học".

Máy không tự biết ranh giới từ. Nếu chỉ cắt theo dấu cách (như tiếng Anh), sẽ hiểu sai.

### 3.2 Ba bước xử lý trong đồ án (`VietnameseSegmenter`)

**Bước 1 — Chuẩn hóa (normalization):**
- **Unicode NFC**: tiếng Việt có thể gõ 2 kiểu byte khác nhau nhưng nhìn giống hệt (ví dụ
  "ế" = 1 ký tự, hoặc "e" + dấu mũ + dấu sắc rời). NFC gộp về **một dạng chuẩn** để so khớp
  đúng.
- **Viết thường** (lowercase) + **gộp khoảng trắng** + **bỏ dấu câu**.

**Bước 2 — Tách từ bằng Maximum Matching (khớp dài nhất):**
Thuật toán kinh điển, dựa trên **từ điển từ ghép**:
- Duyệt câu từ trái sang phải.
- Tại mỗi vị trí, thử ghép **chuỗi âm tiết dài nhất** có trong từ điển.
- Nếu khớp → gộp thành một token (nối bằng `_`); nếu không → lấy 1 âm tiết rồi đi tiếp.

Ví dụ với "trường đại học bách khoa":
```
trường           → không có "trường đại..." trong từ điển → token "trường"
đại học          → có trong từ điển → token "đại_học"
bách khoa        → có trong từ điển → token "bách_khoa"
Kết quả: [trường] [đại_học] [bách_khoa]
```

**Bước 3 — Loại bỏ stopwords:**
Bỏ các từ quá phổ biến, ít giá trị phân biệt ("của", "và", "là", "các"...). Giúp chỉ mục
gọn và kết quả liên quan hơn.

> **Nguyên tắc vàng:** truy vấn phải qua **cùng một** bộ xử lý như lúc lập chỉ mục. Nếu index
> lưu "đại_học" mà truy vấn lại để "đại học" thì không khớp. Đồ án đảm bảo điều này bằng cách
> gọi chung `VietnameseSegmenter` ở cả hai nơi.

### 3.3 So sánh các giải pháp tách từ (phần "tìm hiểu giải pháp")
| Giải pháp | Ý tưởng | Ưu | Nhược |
|---|---|---|---|
| Cắt theo dấu cách / ICU | mỗi âm tiết là 1 token | đơn giản, cài nhanh | không nhận từ ghép → kém chính xác |
| **Maximum Matching + từ điển** (đồ án) | khớp cụm dài nhất trong từ điển | kinh điển, chạy offline, dễ giải thích | phụ thuộc độ phủ từ điển; nhập nhằng dài-ngắn |
| Học máy (VnCoreNLP, CocCoc, underthesea) | mô hình học từ dữ liệu gán nhãn | chính xác nhất | nặng, cần mô hình + tài nguyên |

---

## 4. Lập chỉ mục & tìm kiếm — Apache Lucene

### 4.1 Apache Lucene là gì?
Thư viện tìm kiếm toàn văn (full-text) mã nguồn mở bằng Java — **nền tảng của Elasticsearch
và Apache Solr**. Nó lo phần "trái tim" của IR: lập chỉ mục và xếp hạng.

### 4.2 Inverted index (chỉ mục ngược) — khái niệm cốt lõi
Thay vì với mỗi tài liệu liệt kê các từ (index xuôi), inverted index làm ngược lại: với mỗi
**từ**, lưu danh sách **tài liệu chứa nó** (gọi là *posting list*).

```
"tuyển_sinh" → [doc3, doc7, doc10, ...]
"học_bổng"   → [doc7, doc22, ...]
```
Nhờ đó, tìm "tuyển sinh" chỉ việc tra thẳng danh sách, cực nhanh — không phải quét từng
tài liệu.

### 4.3 Analyzer — bộ xử lý văn bản
- **Analyzer** quyết định cắt văn bản thành token thế nào trước khi vào index.
- Lucene có sẵn nhiều analyzer (StandardAnalyzer, ICUAnalyzer...). Đồ án **tự tách từ tiếng
  Việt trước**, rồi dùng `WhitespaceAnalyzer` (chỉ cắt theo dấu cách) cho phần đã xử lý.
- Lưu ý: "ICU" chỉ là **một analyzer** con của Lucene, KHÔNG phải thứ thay thế Lucene. Lucene
  luôn được dùng; analyzer chỉ là bộ phận tách token bên trong.

### 4.4 Xếp hạng: TF-IDF và BM25
Sau khi tìm được tài liệu chứa từ khóa, cần **xếp hạng** theo độ liên quan.

**TF-IDF** — trực giác nền tảng:
- **TF (Term Frequency)**: từ khóa xuất hiện càng nhiều trong tài liệu → càng liên quan.
- **IDF (Inverse Document Frequency)**: từ càng **hiếm** trên toàn kho → càng có giá trị
  phân biệt (ví dụ "Bách khoa" quý hơn "trường").
- Điểm ≈ TF × IDF.

**BM25** — bản nâng cấp của TF-IDF, là **mặc định của Lucene** (đồ án dùng). Cải tiến:
- **Bão hòa TF**: xuất hiện 100 lần không tốt gấp 100 lần xuất hiện 1 lần (giảm dần).
- **Chuẩn hóa độ dài tài liệu**: tài liệu dài tự nhiên chứa nhiều từ, BM25 bù trừ để không
  thiên vị tài liệu dài.

Trong đồ án, tìm trên 2 trường `title` (trọng số 2.0 — khớp ở tiêu đề quan trọng hơn) và
`content` (1.0).

### 4.5 Truy vấn (Query)
- `MultiFieldQueryParser` phân tích chuỗi truy vấn (đã tách từ) thành câu truy vấn Lucene
  trên nhiều trường.
- `IndexSearcher.search(query, N)` trả về **top N** tài liệu điểm cao nhất (`TopDocs`).
- **Phân trang**: lấy `page × pageSize` kết quả rồi cắt lấy đúng trang; `totalHits` cho biết
  tổng số kết quả để tính số trang.

---

## 5. Lưu trữ metadata — SQLite

- **SQLite** là CSDL quan hệ **nhúng** (không cần server, chỉ một file `.db`), rất hợp cho
  ứng dụng đơn lẻ.
- Đồ án dùng 2 bảng:
  - `documents`: bài viết (url, title, content, content_hash, status...).
  - `files`: URL tài liệu tìm thấy (chỉ lưu link, không tải file → nhẹ).
- Vai trò: là **điểm giao dữ liệu** giữa crawler (ghi) và indexer (đọc).

---

## 6. Phát hiện nội dung mới — hàm băm SHA-256

- **Hàm băm (hash)** biến một chuỗi bất kỳ thành một "dấu vân tay" cố định. Nội dung đổi dù
  1 ký tự → hash đổi hoàn toàn.
- Cơ chế:
  - Chưa có URL trong DB → **new**.
  - Có URL, hash **khác** bản cũ → **updated** (nội dung đã thay đổi).
  - Hash **giống** → **unchanged** (bỏ qua, khỏi cập nhật).
- Nhờ đó hệ thống chỉ xử lý phần thật sự mới/đổi khi crawl lại.

---

## 7. Cập nhật định kỳ — Scheduling

- Yêu cầu "rà soát và cập nhật định kỳ" nghĩa là **tự động chạy lại** crawl + index theo
  chu kỳ (ví dụ hằng ngày) để bắt bài mới.
- Đồ án dùng `ScheduledExecutorService` của Java (đơn giản, ổn định). Lựa chọn khác:
  **Quartz** (lịch dạng cron mạnh hơn), hoặc **Windows Task Scheduler / cron** ở mức hệ điều
  hành.
- Khác với "resume crawl": cập nhật định kỳ **cố ý ghé lại** URL cũ để so hash phát hiện thay
  đổi.

---

## 8. Đánh giá chất lượng tìm kiếm (kiến thức mở rộng)

Các độ đo IR thường gặp (nên biết để bảo vệ):
- **Precision (độ chính xác)**: trong kết quả trả về, bao nhiêu % thực sự liên quan.
- **Recall (độ bao phủ)**: trong tất cả tài liệu liên quan, hệ thống lấy được bao nhiêu %.
- **F1**: trung bình điều hòa của Precision và Recall.
- **MAP, nDCG**: đánh giá **thứ tự xếp hạng** (kết quả tốt có nằm ở trên không).

---

## 9. Ôn nhanh — câu hỏi hay bị hỏi khi bảo vệ

1. **Inverted index là gì, vì sao nhanh?** → Ánh xạ từ → danh sách tài liệu; tra thẳng thay
   vì quét toàn bộ.
2. **Khác nhau TF-IDF và BM25?** → BM25 thêm bão hòa TF và chuẩn hóa độ dài tài liệu.
3. **Vì sao phải tách từ tiếng Việt?** → Vì từ ghép nhiều âm tiết; cắt theo dấu cách sẽ hiểu
   sai ("đại", "học" thay vì "đại_học").
4. **Tại sao truy vấn và index phải cùng analyzer?** → Để token khớp nhau; nếu khác thì không
   tìm ra dù nội dung có.
5. **Tika để làm gì?** → Bóc text từ PDF/DOC để tìm kiếm được nội dung bên trong tài liệu.
6. **Làm sao phát hiện bài mới/đổi?** → So sánh hash SHA-256 của nội dung.
7. **Crawl có đạo đức là gì?** → Tuân thủ robots.txt, đặt delay, khai báo User-Agent.
8. **Lucene có phải search engine hoàn chỉnh không?** → Là *thư viện*; Elasticsearch/Solr là
   sản phẩm hoàn chỉnh xây trên Lucene.

---

## 10. Đọc thêm
- Manning, Raghavan, Schütze — *Introduction to Information Retrieval* (sách kinh điển, miễn phí online).
- Tài liệu chính thức: Apache Lucene, Apache Tika, Jsoup.
- VnCoreNLP, underthesea — công cụ NLP tiếng Việt (nếu muốn nâng cấp tách từ).
