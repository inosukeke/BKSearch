# Lộ trình phát triển dự án cả kỳ — Hệ thống tìm kiếm tài liệu HUST

> Quy mô: đồ án môn học 1 học kỳ, **4 thành viên CNTT**.
> Nền tảng: **bản Java/Lucene** (giữ `python-scrapy` làm tham chiếu để so sánh crawler).
> Mục tiêu: áp dụng **12 chương môn Tìm kiếm thông tin** vào một hệ thống thật.

---

## A. Hiện trạng (đã có)

Crawler Jsoup + robots.txt · Apache Tika · tách từ tiếng Việt (Maximum Matching) ·
Lucene inverted index · BM25 · phát hiện new/updated (SHA-256) · scheduler · web UI có phân trang.

→ Đã phủ **một phần** Ch 1,2,4,7,10. Phần còn lại là không gian để 4 người phát triển.

## B. Ánh xạ 12 chương → tính năng cần thêm

| Chương | Nội dung bài học | Tính năng áp dụng vào project |
|---|---|---|
| **1. Boolean** | AND/OR/NOT, cụm từ, proximity, ranked retrieval | Chế độ **tìm kiếm Boolean** (Lucene `BooleanQuery`), cụm từ `"..."`, toán tử khoảng cách |
| **2. Từ vựng & so khớp mềm** | tokenization, positional index, skip pointer, wildcard, sửa lỗi chính tả | **Positional/phrase query**, **wildcard** (`*`), **"Did you mean?"** (Levenshtein + Jaccard k-gram) |
| **3. Xây & nén chỉ mục** | BSBI/SPIMI, MapReduce, Heap's/Zipf's law, d-gaps, VB/Gamma | **Phân tích thống kê** từ vựng (vẽ Luật Heap & Zipf trên dữ liệu HUST); báo cáo kích thước/nén chỉ mục của Lucene |
| **4. VSM & tf-idf** | vec-tơ, cosine, SMART, top-K heap | **Bộ xếp hạng VSM/tf-idf** (`ClassicSimilarity`) để **so sánh với BM25** |
| **5. Đánh giá** | Precision/Recall/F1, P@k, MAP, nDCG, A/B | **Bộ công cụ đánh giá** + tự xây **test collection** (truy vấn + nhãn phù hợp) |
| **6. Nâng chất lượng** | Rocchio, pseudo-feedback, mở rộng truy vấn, LSI/SVD | **Phản hồi liên quan** (Rocchio), **mở rộng truy vấn** bằng từ đồng xuất hiện |
| **7. Xác suất** | BIM, **BM25**, Language Model, smoothing | Thêm **Language Model** (`LMDirichletSimilarity`) → so sánh 3 mô hình BM25/VSM/LM |
| **8. Phân lớp & phân cụm** | Naïve Bayes, kNN, SVM, K-Means, HAC | **Phân loại tài liệu** (tin tức/tuyển sinh/thông báo...) + **lọc theo nhãn**; **gom cụm kết quả** |
| **9. Web search** | ý định truy vấn, web spam, near-duplicate (Shingles, MinHash, LSH) | **Phát hiện trùng lặp gần** (Shingles + MinHash) để khử trang trùng |
| **10. Crawler & chỉ mục phân tán** | Mercator frontier, DNS cache, phân tán | **Nâng cấp crawler**: hàng đợi Mercator (ưu tiên + politeness theo host), đa luồng, Selenium cho trang JS |
| **11. Phân tích liên kết** | **PageRank**, HITS, anchor text | Dựng **đồ thị web** `*.hust.edu.vn` → tính **PageRank**, dùng anchor text, trộn điểm liên kết vào xếp hạng |
| **12. Xu hướng** | QA, tích hợp DBMS/IR, đa phương tiện | (Mở rộng/điểm cộng) **Hỏi đáp factoid** đơn giản, hoặc thử Elasticsearch/Solr (đều xây trên Lucene) |

## C. Chia việc cho 4 thành viên

Mỗi người "chủ" một mảng nhưng cùng dùng chung lõi (crawler + Lucene index + web UI).

### Thành viên 1 — Crawler & Web (Ch 9, 10, 11)
- Hàng đợi **Mercator** (ưu tiên + politeness mỗi host bằng min-heap), crawler **đa luồng**.
- **Selenium** cho trang JavaScript.
- Dựng **đồ thị liên kết** → tính **PageRank** (+ HITS), lưu điểm vào mỗi trang.
- **Near-duplicate**: Shingles + MinHash + LSH để khử trùng.

### Thành viên 2 — Mô hình xếp hạng (Ch 1, 2, 4, 7)
- Nhiều bộ xếp hạng: **BM25**, **VSM/tf-idf** (ClassicSimilarity), **Language Model** (Dirichlet/JM) — cho phép **chuyển đổi & so sánh**.
- **Boolean query** (AND/OR/NOT), **phrase/proximity**, **wildcard**.
- Trộn điểm **PageRank** (từ TV1) vào công thức xếp hạng cuối.

### Thành viên 3 — Chất lượng truy vấn & NLP tiếng Việt (Ch 2, 6)
- **Sửa lỗi / "Did you mean?"** (Levenshtein + Jaccard k-gram), **gợi ý tự động** (autocomplete).
- **Mở rộng truy vấn**: Rocchio + pseudo-relevance feedback, từ đồng xuất hiện.
- Nâng cấp **tách từ tiếng Việt** (tích hợp **VnCoreNLP**), mở rộng từ điển + stopwords, chuẩn hóa Unicode TCVN 6909.

### Thành viên 4 — Đánh giá, Phân lớp & Phân cụm (Ch 5, 8, 3)
- **Bộ đánh giá**: tự xây test collection (bộ truy vấn + nhãn phù hợp), tính **P/R/F1, P@k, MAP, nDCG**.
- **Phân loại tài liệu** (Naïve Bayes) thành danh mục → **bộ lọc theo nhãn** trên web UI.
- **Gom cụm** kết quả tìm kiếm (K-Means) kiểu Yippy.
- **Phân tích thống kê** (Ch 3): vẽ Luật Heap & Zipf, báo cáo kích thước chỉ mục.

> Việc chung: thống nhất **git repo**, chuẩn code, và **báo cáo + demo** cuối kỳ.

## D. Lịch trình gợi ý (~12–14 tuần)

| Tuần | Mốc |
|---|---|
| 1–2 | Lập repo git chung, chia nhánh, chốt module; refactor lõi hiện có cho dễ mở rộng |
| 3–5 | TV1: Mercator + đa luồng · TV2: VSM + LM · TV3: did-you-mean · TV4: khung đánh giá |
| 6–8 | TV1: PageRank + đồ thị · TV2: Boolean/phrase · TV3: mở rộng truy vấn · TV4: phân lớp |
| 9–10 | TV1: near-duplicate · TV2: trộn PageRank vào ranking · TV3: VnCoreNLP · TV4: phân cụm + metrics |
| 11–12 | Hoàn thiện **web UI** (facet theo nhãn, did-you-mean, chọn mô hình xếp hạng), chạy **đánh giá** đầy đủ |
| 13–14 | Viết **báo cáo**, chuẩn bị **demo + bảo vệ** |

## E. Nguyên tắc kỹ thuật khi mở rộng

- **Git**: mỗi thành viên một nhánh tính năng, review chéo trước khi merge.
- **Kiến trúc module hóa**: lõi (crawler/index/search) tách rời để 4 người làm song song ít đụng nhau.
- **Giao diện xếp hạng pluggable**: dùng cơ chế `Similarity` của Lucene để đổi BM25/VSM/LM dễ dàng.
- **Elasticsearch/Solr** (tùy chọn nâng cao): đều xây trên Lucene, nếu muốn demo phân tán Ch 10.
- **Luôn giữ**: crawl có đạo đức (robots.txt, delay), tách từ nhất quán giữa index & query.

## F. Ưu tiên nếu thiếu thời gian (MVP → điểm cao)

1. **Bắt buộc/điểm nền**: đa mô hình xếp hạng (BM25/VSM/LM) + **bộ đánh giá** (Ch 4,5,7) — vì đây là lõi IR.
2. **Điểm cộng mạnh**: **PageRank** (Ch 11) + **did-you-mean** (Ch 2) + **phân lớp/lọc nhãn** (Ch 8).
3. **Điểm cộng nâng cao**: near-duplicate (Ch 9), Mercator đa luồng (Ch 10), VnCoreNLP, QA (Ch 12).
