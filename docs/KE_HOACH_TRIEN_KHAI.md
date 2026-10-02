# Kế hoạch triển khai chi tiết theo step

> Nguồn yêu cầu: [`THIET_KE_HE_THONG.md`](THIET_KE_HE_THONG.md).
> Mỗi step có **điều kiện PASS** kiểm chứng được. Không chuyển step khi chưa PASS.
> Quy ước mã step: `S<phase>.<thứ tự>`. Ưu tiên: ⭐⭐⭐ bắt buộc · ⭐⭐ nên có · ⭐ điểm cộng.

---

## 0. Ràng buộc toàn cục (áp dụng mọi step)

- **G1 — Tái lập:** toàn hệ chạy bằng `docker compose up` trên máy local; không phụ thuộc dịch vụ cloud trả phí.
- **G2 — Mã nguồn mở:** chỉ dùng thành phần OSS (OpenSearch, Postgres, Redis...), không license SSPL bắt buộc.
- **G3 — Nhất quán tách từ:** văn bản và truy vấn PHẢI qua **cùng một** bộ tách từ/chuẩn hóa tiếng Việt. Vi phạm là lỗi chặn.
- **G4 — Crawl có đạo đức:** tuân thủ robots.txt, có delay, User-Agent rõ ràng.
- **G5 — Git:** commit mỗi step với thông điệp rõ ràng, có thể lần ngược được.
  - *Giai đoạn 1 người (hiện tại):* commit/push thẳng `main` cho gọn.
  - *Khi vào nhóm nhiều người:* chuyển sang mỗi step 1 nhánh tính năng, merge khi PASS + review chéo, không commit thẳng `main`.
- **G6 — Kiểm thử:** mỗi step có ít nhất 1 test tự động hoặc script kiểm chứng tái lập được điều kiện PASS.
- **G7 — Hiệu năng:** tôn trọng ngân sách độ trễ p95 (<200ms từ khóa, <800ms hybrid+rerank) khi đo ở Phase cuối.

**Definition of Ready (để bắt đầu một step):** phụ thuộc đã PASS · dữ liệu/mô hình cần thiết sẵn sàng · tiêu chí PASS được hiểu rõ.
**Definition of Done (chung):** code + test xanh · chạy được qua `docker compose` · tài liệu hóa ngắn trong README · điều kiện PASS đạt và có bằng chứng (log/ảnh/số đo).

---

## PHASE 0 — Hạ tầng & di trú dữ liệu (⭐⭐⭐, ~3 p-w)

### S0.1 — Khởi tạo cấu trúc dự án đa module
- **Mục tiêu:** bộ khung thư mục cho nhiều dịch vụ.
- **Mô tả:** tạo cấu trúc: `search-core/` (Java: crawler, index, query), `embedding-service/` (Python FastAPI), `deploy/` (docker-compose, cấu hình), `eval/` (bộ đánh giá), `web/` (UI). Thiết lập Maven (giữ `vn.hust.ir`), `.gitignore`, README gốc.
- **Ràng buộc:** không phá bản Java cũ — chuyển code hiện có vào `search-core` và vẫn build được.
- **Phụ thuộc:** —
- **Đầu ra:** cây thư mục + `mvn -q compile` OK + README mô tả module.
- **PASS:** `mvn -q -f search-core compile` thành công; cấu trúc thư mục đúng như thiết kế.
- **Ước lượng:** 0.5 p-w.

### S0.2 — docker-compose hạ tầng nền
- **Mục tiêu:** dựng OpenSearch + Dashboards + PostgreSQL + Redis bằng 1 lệnh.
- **Mô tả:** viết `deploy/docker-compose.yml`: OpenSearch 1 node (tắt security cho dev, giới hạn heap ~1g), OpenSearch Dashboards, PostgreSQL 16, Redis 7. Khai báo volume bền, healthcheck, mạng nội bộ.
- **Ràng buộc (G1):** chạy được trên máy ≤ 16GB RAM; heap OpenSearch cấu hình qua env; cổng không trùng.
- **Phụ thuộc:** S0.1.
- **Đầu ra:** `docker-compose.yml` + file `.env` mẫu.
- **PASS:** `docker compose up -d` → cả 4 service `healthy`; `curl localhost:9200` trả thông tin cluster; mở được Dashboards.
- **Ước lượng:** 0.5 p-w.

### S0.3 — Thiết kế index mapping OpenSearch + schema PostgreSQL
- **Mục tiêu:** định nghĩa cấu trúc lưu trữ.
- **Mô tả:** index `documents` với field: `url` (keyword), `title`, `content` (text, analyzer TV), `title_seg`, `content_seg` (text, whitespace — đã tách từ), `doc_type`, `subdomain` (keyword), `published_at` (date), `pagerank` (float), `embedding` (knn_vector, dims theo model, HNSW) — field vector thêm ở Phase 2 nhưng khai báo mapping trước. PostgreSQL: bảng `documents` (metadata + content_hash), `files`, `crawl_log`.
- **Ràng buộc:** `url` là khóa định danh (dùng cho upsert chống trùng); dims vector phải khớp model chọn ở S2.1.
- **Phụ thuộc:** S0.2.
- **Đầu ra:** file mapping JSON + SQL migration.
- **PASS:** tạo index thành công (`PUT /documents`); chèn 1 doc mẫu + truy vấn lại được; bảng Postgres tạo xong.
- **Ước lượng:** 0.5 p-w.

### S0.4 — Analyzer tiếng Việt (tách từ khi ingest)
- **Mục tiêu:** chuẩn hóa + tách từ TV nhất quán.
- **Mô tả:** module `nlp` sinh `content_seg`/`title_seg` bằng **VnCoreNLP** (NFC, lowercase, bỏ stopwords). OpenSearch dùng `whitespace` analyzer trên field `*_seg`. Giữ bản Maximum Matching cũ làm fallback.
- **Ràng buộc (G3):** cùng hàm tách từ phải được gọi ở cả ingest và query (đóng gói 1 lớp dùng chung).
- **Phụ thuộc:** S0.3.
- **Đầu ra:** lớp `VietnameseAnalyzer` + unit test.
- **PASS:** test: "Trường Đại học Bách khoa" → `trường đại_học bách_khoa`; gọi từ cả ingest & query cho ra chuỗi giống nhau.
- **Ước lượng:** 1 p-w (tích hợp VnCoreNLP là phần khó — xem rủi ro R2).

### S0.5 — Di trú dữ liệu hiện có sang OpenSearch
- **Mục tiêu:** đưa corpus cũ (SQLite) vào hệ mới.
- **Mô tả:** script ETL đọc `hust_data.db` → tách từ (S0.4) → bulk index OpenSearch + ghi metadata Postgres.
- **Ràng buộc:** idempotent (chạy lại không nhân đôi, upsert theo `url`); bulk theo lô ≥ 500 doc.
- **Phụ thuộc:** S0.4.
- **Đầu ra:** script `migrate` + log số doc.
- **PASS:** số doc trong OpenSearch = số doc hợp lệ trong SQLite; chạy script 2 lần → số doc không đổi.
- **Ước lượng:** 0.5 p-w.

### S0.6 — Smoke test nền
- **PASS tổng Phase 0:** `docker compose up` → migrate → `GET /documents/_search?q=...` (BM25 có tách từ TV) trả kết quả đúng tiếng Việt; Dashboards thấy index.

---

## PHASE 1 — Lõi truy xuất + Đánh giá (⭐⭐⭐, ~5 p-w)

### S1.1 — Query Service skeleton (Javalin) + hợp đồng API
- **Mục tiêu:** dịch vụ truy vấn + REST contract ổn định.
- **Mô tả:** Javalin app; định nghĩa API: `GET /api/search?q=&page=&ranker=`, `GET /api/suggest?q=`, `GET /healthz`. Chuẩn JSON response (query, total, page, results[], took_ms).
- **Ràng buộc:** response có `took_ms` để đo trễ; contract versioned (ghi tài liệu).
- **Phụ thuộc:** S0.6.
- **Đầu ra:** service chạy + tài liệu API.
- **PASS:** `/healthz` 200; `/api/search` trả JSON đúng schema (dù rỗng).
- **Ước lượng:** 0.5 p-w.

### S1.2 — BM25 qua OpenSearch (tách từ query nhất quán)
- **Mô tả:** nối OpenSearch client; `/api/search` chạy BM25 trên `title_seg`(^2)+`content_seg`; query qua cùng analyzer (G3); highlight + phân trang.
- **Ràng buộc (G3):** query tách từ giống ingest; phân trang chuẩn (from/size).
- **Phụ thuộc:** S1.1.
- **Đầu ra:** endpoint BM25 hoạt động.
- **PASS:** tìm "tuyển sinh" trả kết quả liên quan, có highlight, `took_ms` ghi nhận; truy vấn "đại học" khớp tài liệu chứa "đại_học".
- **Ước lượng:** 1 p-w.

### S1.3 — Đa mô hình xếp hạng: BM25 / VSM / LM
- **Mô tả:** tham số `ranker=bm25|vsm|lm`. Cấu hình `similarity` (BM25, ClassicSimilarity/tf-idf, LMDirichlet) theo index/field; hoặc dùng nhiều index song song.
- **Ràng buộc:** đổi ranker không cần reindex toàn bộ (ưu tiên multi-field similarity hoặc index nhỏ song song).
- **Phụ thuộc:** S1.2.
- **Đầu ra:** 3 mô hình chọn được qua tham số.
- **PASS:** cùng 1 truy vấn, 3 ranker cho **thứ hạng khác nhau** và đều trả kết quả; log rõ ranker đang dùng.
- **Ước lượng:** 1 p-w.

### S1.4 — Xử lý truy vấn: phrase + Boolean
- **Mô tả:** hỗ trợ cụm `"..."`, toán tử AND/OR/NOT, proximity.
- **Ràng buộc:** cú pháp sai → báo lỗi thân thiện, không 500.
- **Phụ thuộc:** S1.2.
- **PASS:** `"tuyển sinh" AND thạc sĩ` trả tài liệu chứa đúng cụm; truy vấn lỗi cú pháp trả 400 có thông báo.
- **Ước lượng:** 0.5 p-w.

### S1.5 — Did-you-mean (sửa lỗi chính tả)
- **Mô tả:** `/api/suggest` dùng term suggester OpenSearch hoặc Levenshtein + Jaccard k-gram trên từ vựng.
- **Ràng buộc:** chỉ gợi ý khi độ tương đồng vượt ngưỡng; không gợi ý linh tinh.
- **Phụ thuộc:** S1.2.
- **PASS:** gõ "tuyen sih" → gợi ý "tuyển sinh"; gõ đúng → không gợi ý thừa.
- **Ước lượng:** 0.5 p-w.

### S1.6 — Nâng cấp Web UI
- **Mô tả:** thêm chọn ranker, hiển thị did-you-mean, highlight, phân trang (đã có), chỗ dành cho facet.
- **Ràng buộc:** nền sáng, đơn giản (giữ phong cách hiện tại); responsive cơ bản.
- **Phụ thuộc:** S1.3, S1.5.
- **PASS:** người dùng tìm, đổi ranker, bấm gợi ý sửa lỗi, chuyển trang — tất cả hoạt động trên trình duyệt.
- **Ước lượng:** 0.5 p-w.

### S1.7 — Bộ đánh giá + test collection
- **Mục tiêu:** đo chất lượng khách quan.
- **Mô tả:** thu thập ~30–50 truy vấn tiêu biểu; tạo **qrels** (nhãn phù hợp) bằng pooling top-k các ranker + nhóm tự gán. Script `eval` tính **P@k, Recall@k, F1, MAP, MRR, nDCG@k**, xuất bảng + biểu đồ P/R.
- **Ràng buộc:** qrels lưu định dạng chuẩn (giống TREC); script tái lập được.
- **Phụ thuộc:** S1.3.
- **Đầu ra:** `eval/qrels`, `eval/queries`, script + báo cáo số.
- **PASS:** chạy `eval` ra bảng chỉ số cho 3 ranker; số liệu tái lập giữa 2 lần chạy.
- **Ước lượng:** 1 p-w.

### S1.8 — PASS tổng Phase 1
- **PASS:** có **bảng so sánh BM25/VSM/LM** theo nDCG@10 & MAP trên test collection; UI dùng được đủ tính năng Phase 1.

---

## PHASE 2 — Semantic hybrid + Rerank (⭐⭐⭐, ~5 p-w)

### S2.1 — Embedding Service (FastAPI, bi-encoder TV)
- **Mô tả:** service Python `POST /embed` (nhận list text → list vector), nạp model bi-encoder TV (thử `bkai-foundation-models/vietnamese-bi-encoder`). Dockerize.
- **Ràng buộc:** dims vector cố định & khớp mapping S0.3; hỗ trợ batch; CPU chạy được (không bắt buộc GPU).
- **Phụ thuộc:** S0.3.
- **Đầu ra:** service + healthcheck.
- **PASS:** `POST /embed` 2 câu gần nghĩa → cosine cao hơn 2 câu khác nghĩa; thời gian/embed chấp nhận được (batch 32).
- **Ước lượng:** 1 p-w.

### S2.2 — Sinh embedding khi ingest + k-NN field
- **Mô tả:** ETL gọi Embedding Service cho mỗi doc → ghi field `embedding` (HNSW) vào OpenSearch.
- **Ràng buộc:** batch hóa gọi embed; lỗi 1 doc không chặn cả lô; reindex không trùng (upsert theo url).
- **Phụ thuộc:** S2.1, S0.5.
- **PASS:** ≥ 95% doc có vector; `GET` 1 doc thấy `embedding` đúng dims.
- **Ước lượng:** 1 p-w.

### S2.3 — Vector search (k-NN)
- **Mô tả:** endpoint tìm theo vector: truy vấn → embed → k-NN query OpenSearch.
- **PASS:** truy vấn ngữ nghĩa (vd "học phí kỹ sư") trả tài liệu liên quan kể cả khi không trùng từ khóa chính xác.
- **Phụ thuộc:** S2.2.
- **Ước lượng:** 0.5 p-w.

### S2.4 — Hybrid fusion (RRF)
- **Mô tả:** hợp nhất thứ hạng BM25 + vector bằng **Reciprocal Rank Fusion**.
- **Ràng buộc:** không cần chuẩn hóa thang điểm; tham số k của RRF cấu hình được.
- **Phụ thuộc:** S1.2, S2.3.
- **PASS:** hybrid trả kết quả hợp lý; trên test collection nDCG@10 **≥** max(BM25, vector).
- **Ước lượng:** 0.5 p-w.

### S2.5 — Cross-encoder rerank
- **Mô tả:** service `POST /rerank` (query + list doc → điểm); áp cho **top-K** (mặc định 30) sau hybrid.
- **Ràng buộc (G7):** chỉ rerank top-K (mặc định `RERANK_TOP_K=30`); cross-encoder truncate input ở
  `RERANK_MAX_LENGTH=256` token; có cache LRU; đo p95.
- **Phụ thuộc:** S2.4.
- **PASS:** hybrid+rerank nDCG@10 & MAP **> BM25** (có số đo); p95 < 800ms khi rerank top-K.
  - *Ghi chú (F2):* đo local CPU cho thấy top_k=50 **không truncate** → p95 ~10.3s (VƯỢT); hạ
    top_k=30 + `RERANK_MAX_LENGTH=256` → p95 ~540ms (ĐẠT). **p95 phụ thuộc phần cứng** (CPU/GPU) —
    số trên đo trên CPU local, cần đo lại trên môi trường mục tiêu.
- **Ước lượng:** 1 p-w.

### S2.6 — PASS tổng Phase 2
- **PASS:** bảng đo **BM25 vs vector vs hybrid vs hybrid+rerank**; hybrid+rerank thắng BM25 về nDCG@10/MAP; đạt ngưỡng p95.

---

## PHASE 3 — Chất lượng & tín hiệu (⭐⭐, ~4 p-w)

### S3.1 — PageRank + anchor text
- **Mô tả:** dựng đồ thị liên kết `*.hust.edu.vn` từ dữ liệu crawl → tính **PageRank** (power iteration) → ghi field `pagerank`; trộn vào xếp hạng bằng `function_score`.
- **Ràng buộc:** PageRank tính offline theo lô; trọng số trộn cấu hình được; đo tác động bằng eval.
- **Phụ thuộc:** S0.5.
- **PASS:** PageRank hội tụ (thay đổi < ε); bật/tắt tín hiệu thấy thứ hạng đổi; eval không giảm nDCG.
- **Ước lượng:** 1.5 p-w.

### S3.2 — Near-duplicate (MinHash/LSH)
- **Mô tả:** tính shingles + MinHash khi ingest, dùng LSH gom nhóm trùng → đánh dấu/loại bản trùng.
- **Ràng buộc:** ngưỡng Jaccard cấu hình; không loại nhầm tài liệu khác nhau.
- **Phụ thuộc:** S0.5.
- **PASS:** chèn 2 bản gần trùng → hệ phát hiện & chỉ giữ 1 trong kết quả; tỉ lệ trùng trong top-20 giảm rõ.
- **Ước lượng:** 1 p-w.

### S3.3 — Mở rộng truy vấn (synonym + Rocchio)
- **Mô tả:** từ điển đồng nghĩa + pseudo-relevance feedback (Rocchio) mở rộng truy vấn.
- **Ràng buộc:** mở rộng có giới hạn số từ; có thể tắt để so sánh.
- **Phụ thuộc:** S1.7 (để đo).
- **PASS:** trên eval, bật mở rộng tăng Recall@10 mà không giảm nhiều Precision; đo được.
- **Ước lượng:** 0.5 p-w.

### S3.4 — Phân loại tài liệu + facet
- **Mô tả:** phân loại (Naïve Bayes) thành danh mục (tin tức/tuyển sinh/thông báo/đào tạo...) → field `category`; UI lọc theo facet.
- **Ràng buộc:** có tập train nhỏ gán nhãn; báo cáo độ chính xác phân lớp (P/R/F1).
- **Phụ thuộc:** S1.6.
- **PASS:** ≥ 80% doc được gán nhãn hợp lý (trên tập test nhỏ); facet lọc đúng trên UI.
- **Ước lượng:** 1 p-w.

---

## PHASE 4 — Crawler quy mô + Monitoring + Hoàn thiện (⭐⭐, ~4 p-w)

### S4.1 — Crawler Mercator đa luồng + Selenium
- **Mô tả:** frontier 2 lớp (hàng đợi ưu tiên + hàng đợi theo host, min-heap lịch truy cập) → politeness + freshness; đa luồng; Selenium cho trang JS.
- **Ràng buộc (G4):** tuân thủ robots.txt, delay theo host, không vượt N luồng/host.
- **Phụ thuộc:** S0.5.
- **PASS:** crawl ≥ 100k trang không vi phạm politeness (log khoảng cách request/host ≥ ngưỡng); trang JS lấy được nội dung.
- **Ước lượng:** 2 p-w.

### S4.2 — Monitoring
- **Mô tả:** Prometheus thu metric Query Service (QPS, latency), Grafana dashboard; dùng OpenSearch Dashboards cho index.
- **Phụ thuộc:** S1.1.
- **PASS:** Grafana hiển thị QPS & p95 theo thời gian thực khi bắn tải.
- **Ước lượng:** 1 p-w.

### S4.3 — Load test đạt mục tiêu p95
- **Mô tả:** script bắn tải (k6/JMeter) cho 2 kịch bản: từ khóa & hybrid+rerank.
- **Phụ thuộc:** S2.6, S4.2.
- **PASS (G7):** p95 từ khóa < 200ms; p95 hybrid+rerank < 800ms ở ~20–50 QPS trên corpus mục tiêu.
- **Ước lượng:** 0.5 p-w.

### S4.4 — Báo cáo + demo
- **PASS:** báo cáo đầy đủ (kiến trúc, thuật toán, số đo eval, so sánh mô hình, monitoring) + bản demo chạy bằng `docker compose up`.
- **Ước lượng:** 0.5 p-w.

---

## Sơ đồ phụ thuộc (rút gọn)

```
S0.1→S0.2→S0.3→S0.4→S0.5→S0.6
                         │
        ┌────────────────┼───────────────┐
        ▼                ▼                ▼
   S1.1→S1.2→S1.3→S1.7  (S3.1 PageRank)  (S3.2 near-dup)
        │     │
        │     └→S1.4, S1.5→S1.6→(Phase1 PASS)
        ▼
   S2.1→S2.2→S2.3→S2.4→S2.5→(Phase2 PASS)
                                   │
                        S3.3, S3.4 (cần eval S1.7)
                                   │
                   S4.1, S4.2→S4.3→S4.4 (hoàn thiện)
```

## Thứ tự ưu tiên nếu thiếu thời gian
1. **Bắt buộc:** Phase 0 → Phase 1 (lõi + đánh giá) → Phase 2 (hybrid + rerank).
2. **Điểm cộng mạnh:** S3.1 PageRank, S3.4 facet, S1.5 did-you-mean.
3. **Điểm cộng nâng cao:** S3.2 near-dup, S4.1 Mercator, S4.2–4.3 monitoring/load test.

## Bản đồ rủi ro → step (xem chi tiết trong THIET_KE_HE_THONG.md mục 6)
- R2 (VnCoreNLP khó tích hợp) → S0.4: fallback tách từ offline + whitespace analyzer.
- R4 (embedding TV yếu) → S2.1/S2.5: thử nhiều model, luôn có rerank, BM25 là baseline.
- R5 (rerank chậm) → S2.5/S4.3: rerank top-50 + cache, đo & cắt K.
- R1 (RAM) → S0.2: 1 node, giới hạn heap, corpus 100k.
