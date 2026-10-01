# Kế hoạch — Hệ thống thu thập & tìm kiếm tài liệu HUST (Java toàn phần)

> Môn: **Tìm kiếm thông tin** — Bản viết lại toàn bộ bằng Java.
> Bản Python (Scrapy) được giữ ở thư mục `../python-scrapy` để tham chiếu/so sánh.
> Môi trường: **JDK 21 + Maven 3.9.11** (đã có sẵn trên máy).

## 1. Vì sao chọn Java toàn phần

Đề **bắt buộc** dùng **Apache Lucene** (tìm kiếm) và **Apache Tika** (bóc tách văn bản) —
cả hai đều là thư viện Java. Làm toàn Java để:
- Dùng Lucene/Tika "chính chủ", không qua cầu nối (tránh PyLucene khó cài trên Windows).
- Một ngôn ngữ, một build tool (Maven), một runtime → dễ chạy, dễ bảo vệ.

## 2. Công cụ & thư viện

| Thành phần | Thư viện Java | Vai trò |
|---|---|---|
| Crawler | **Jsoup** (+ `java.net.http.HttpClient`) | Tải & phân tích HTML, trích link |
| Crawler (JS) | **Selenium WebDriver** | Chỉ cho trang render bằng JavaScript |
| robots.txt | `crawler-commons` | Đọc & tuân thủ robots.txt |
| Bóc tách tài liệu | **Apache Tika** | Lấy text từ PDF/DOC/DOCX/XLS |
| Tách từ tiếng Việt | **VnCoreNLP** (hoặc CocCoc Tokenizer) | Word segmentation + chuẩn hóa |
| Đánh chỉ mục & tìm kiếm | **Apache Lucene** | Inverted index + xếp hạng BM25 |
| Lưu trữ metadata | **SQLite JDBC** (xerial) | Bảng `documents`, `files` (giống bản Python) |
| Lập lịch định kỳ | **Quartz Scheduler** (hoặc `ScheduledExecutorService`) | Rà soát & cập nhật định kỳ |
| Giao diện (tùy chọn) | **Javalin** / Spring Boot | Trang web tìm kiếm; hoặc CLI |
| Log | SLF4J + Logback | Ghi log |

### Phiên bản đề xuất (Maven)
- Lucene `9.12.x` — ổn định, nhiều tài liệu (Java 21 chạy tốt).
- Tika `2.9.2` — `tika-core` + `tika-parsers-standard-package`.
- Jsoup `1.18.x`, Selenium `4.27.x`, sqlite-jdbc `3.47.x`, crawler-commons `1.4`.
- VnCoreNLP: thêm dạng jar cục bộ + file model (không có sẵn trên Maven Central).

## 3. Kiến trúc

```
                         ┌──────────────────────────────┐
   Seed URLs  ──────────▶│  CRAWLER (Jsoup + Selenium)  │
   *.hust.edu.vn         │  - frontier, robots.txt, delay│
                         └───────────────┬──────────────┘
                                         │ lưu metadata + tải file
                                         ▼
                              ┌────────────────────┐
                              │  SQLite hust.db     │  documents, files
                              │  + thư mục files/   │  (PDF/DOC đã tải)
                              └─────────┬──────────┘
                                        │ đọc
                                        ▼
        ┌───────────────────────────────────────────────────┐
        │  INDEXER                                            │
        │  1. Tika  -> bóc text từ HTML/PDF/DOC               │
        │  2. VnCoreNLP -> tách từ + chuẩn hóa tiếng Việt     │
        │  3. Lucene IndexWriter -> ghi inverted index        │
        └───────────────────────┬───────────────────────────┘
                                 ▼
                       ┌───────────────────┐      ┌──────────────┐
   Truy vấn  ─────────▶│  SEARCH (Lucene)  │─────▶│  Kết quả xếp  │
   (từ khóa)           │  BM25 + highlight │      │  hạng + link  │
                       └───────────────────┘      └──────────────┘

   [SCHEDULER Quartz] ── định kỳ chạy lại CRAWLER + INDEXER (phát hiện bài mới)
```

## 4. Cấu trúc project Maven (một project, nhiều package)

```
java-lucene/
├── pom.xml
├── data/
│   ├── hust.db                  # SQLite
│   ├── files/                   # tài liệu tải về
│   └── index/                   # thư mục Lucene index
├── libs/                        # jar VnCoreNLP + models (thêm tay)
└── src/main/java/vn/hust/ir/
    ├── App.java                 # CLI: crawl | index | search | schedule
    ├── crawler/
    │   ├── HustCrawler.java     # frontier BFS + giới hạn *.hust.edu.vn
    │   ├── RobotsPolicy.java    # crawler-commons
    │   └── JsPageFetcher.java   # Selenium cho trang JS (tùy chọn)
    ├── store/
    │   ├── Db.java              # sqlite-jdbc: documents, files
    │   └── Document.java        # model dữ liệu
    ├── extract/
    │   └── TikaExtractor.java   # bóc text PDF/DOC/HTML
    ├── nlp/
    │   ├── VietnameseAnalyzer.java   # Lucene Analyzer tiếng Việt
    │   └── WordSegmenter.java        # bọc VnCoreNLP
    ├── index/
    │   └── LuceneIndexer.java   # tạo/cập nhật index
    ├── search/
    │   └── LuceneSearcher.java  # BM25 + highlight
    └── schedule/
        └── CrawlJob.java        # Quartz job định kỳ
```

## 5. Cơ chế then chốt

### 5.1 Tách từ & chuẩn hóa tiếng Việt (yêu cầu riêng của đề)
- **Tách từ**: VnCoreNLP biến "trường đại học bách khoa" → "trường_đại_học bách_khoa".
- **Chuẩn hóa**: lowercase, chuẩn hóa Unicode (NFC), bỏ **stopwords** tiếng Việt.
- Gói lại thành `VietnameseAnalyzer` (kế thừa `org.apache.lucene.analysis.Analyzer`)
  để **cả lúc index và lúc query đều xử lý giống nhau** — đây là điểm mấu chốt của IR.

### 5.2 Bóc tách tài liệu (Tika)
- Với mỗi URL tài liệu (PDF/DOC/DOCX/XLS) trong bảng `files`, Tika đọc → text thuần.
- Text này được index cùng bài viết → tìm kiếm được cả nội dung bên trong file.

### 5.3 Đánh chỉ mục & xếp hạng (Lucene)
- Mỗi tài liệu = 1 Lucene `Document` với field: `url`, `title`, `content`, `doc_type`,
  `subdomain`, `published_at`.
- Dùng `BM25Similarity` (mặc định của Lucene) để xếp hạng.
- `Highlighter` để bôi đậm đoạn khớp trong kết quả.

### 5.4 Phát hiện nội dung mới & cập nhật định kỳ
- Crawler lưu `content_hash` (SHA-256) như bản Python → so sánh để biết new/updated.
- `IndexWriter.updateDocument(term(url), doc)` để cập nhật đúng bản ghi (không trùng).
- Quartz chạy lại crawl+index theo lịch (vd hằng ngày) → đáp ứng "cập nhật định kỳ".

## 6. Lộ trình thực hiện

| Bước | Nội dung | Kết quả kiểm chứng |
|---|---|---|
| B1 | `pom.xml` + khung project + `App.java` CLI rỗng | `mvn compile` chạy OK |
| B2 | `store/Db.java` tạo SQLite `documents`, `files` | Tạo được file `hust.db` |
| B3 | `crawler/HustCrawler.java` (Jsoup + robots + delay) | Crawl thử, lưu vài bài vào DB |
| B4 | `extract/TikaExtractor.java` | Bóc được text 1 file PDF mẫu |
| B5 | `nlp/VietnameseAnalyzer.java` (VnCoreNLP + stopwords) | Tách từ đúng câu tiếng Việt |
| B6 | `index/LuceneIndexer.java` | Sinh thư mục `index/`, đếm được số doc |
| B7 | `search/LuceneSearcher.java` (BM25 + highlight) | Gõ từ khóa → ra kết quả xếp hạng |
| B8 | `schedule/CrawlJob.java` (Quartz) | Chạy lại định kỳ, log new/updated |
| B9 | (Tùy chọn) Web UI Javalin | Trang tìm kiếm trên trình duyệt |
| B10 | Báo cáo + demo | Tài liệu nộp |

## 7. Ghi chú
- Có thể **tái dùng dữ liệu** từ bản Python: copy `../python-scrapy/hust_crawler/hust_data.db`
  hoặc để crawler Java tự thu lại từ đầu.
- Giữ nguyên tinh thần **crawl có đạo đức**: robots.txt, delay, User-Agent rõ ràng.

## 8. Trạng thái
- [x] B1 khung Maven (`pom.xml`, `App.java`, `mvn compile` OK)
- [x] B2 SQLite store (`Db.java`, `Document.java`, lệnh `initdb` tạo được `data/hust.db`)
- [x] B3 Crawler (`HustCrawler.java` + `RobotsCache.java`) — test crawl 25 bài OK
- [x] B4 Tika (`TikaExtractor.java`)
- [x] B5 NLP tiếng Việt (`VietnameseSegmenter.java` + `vi-words.txt`, `vi-stopwords.txt`)
- [x] B6 Lucene index (`LuceneIndexer.java`)
- [x] B7 Search BM25 (`LuceneSearcher.java`) — test search OK
- [x] B8 Scheduler (`PeriodicRunner.java`)
- [x] B9 Web UI (`WebServer.java`) — nền sáng, chạy `serve` tại cổng 8080
- [x] B10 Báo cáo (`../BAO_CAO.md`)
