# BKSearch — Báo cáo hệ thống tìm kiếm thông tin tài liệu HUST

Môn: Tìm kiếm thông tin (Information Retrieval) — HUST.
Hệ thống thu thập, lập chỉ mục và tìm kiếm tài liệu từ `hust.edu.vn` với nhiều mô hình xếp hạng
(BM25, VSM tf-idf, LM Dirichlet, vector k-NN, hybrid RRF, cross-encoder rerank) cùng các tín hiệu
chất lượng (PageRank, near-duplicate, mở rộng truy vấn, phân loại + facet) và giám sát/đo tải.

> **Ghi chú về phạm vi xác minh.** Toàn bộ **mã nguồn + unit test** chạy xanh trên CI/cloud
> (`mvn test`, `pytest`). Các số đo **eval** và **p95** trong báo cáo đo ở **phiên LOCAL** có đủ
> Docker + OpenSearch + model thật; p95 **phụ thuộc phần cứng** nên cần đo lại trên môi trường mục tiêu.

---

## 1. Kiến trúc tổng thể

```
                 ┌──────────────┐   links/docs/files   ┌─────────────┐
   hust.edu.vn ─▶│  Crawler     │─────────────────────▶│  SQLite     │
                 │  (Mercator,  │                       │ (documents, │
                 │  đa luồng)   │                       │  files,     │
                 └──────────────┘                       │  links)     │
                                                        └──────┬──────┘
                                              migrate (+embed)  │
                                                                ▼
   ┌──────────────┐    vector/rerank    ┌──────────────────────────────┐
   │  Embedding    │◀──────────────────▶│         OpenSearch           │
   │  Service      │                     │  documents / _vsm / _lm      │
   │  (FastAPI)    │                     │  BM25 · tf-idf · LMDirichlet │
   └──────────────┘                     │  knn_vector(768) · facets    │
                                         └──────────────┬───────────────┘
                                                        │
                        ┌───────────────────────────────▼───────────────┐
                        │        Query Service (Javalin, :7070)          │
   người dùng ─────────▶│  /api/search /api/suggest /healthz /metrics /  │
                        │  ranker=bm25|vsm|lm|vector|hybrid (+rerank)    │
                        │  PageRank · dedup collapse · query expansion   │
                        │  facet lọc category/doc_type/subdomain         │
                        └───────────────┬───────────────────────────────┘
                                        │ /metrics (Prometheus)
                              ┌─────────▼─────────┐     ┌──────────┐
                              │   Prometheus      │────▶│ Grafana  │
                              └───────────────────┘     └──────────┘
```

- **Lõi Java/Lucene** (`java-lucene/`, package `vn.hust.ir`): crawler, migrate, query service, các
  thuật toán IR. Đóng gói fat-jar (`hust-search.jar`).
- **Embedding Service** (`embedding-service/`, Python FastAPI): bi-encoder tiếng Việt (dim 768) +
  cross-encoder rerank; nạp model lazy, thread-safe, cache LRU.
- **OpenSearch 2.17.1**: 3 index song song cho 3 mô hình từ khóa + `knn_vector` HNSW cosine cho vector.
- **PostgreSQL/Redis**: metadata + cache (hạ tầng).
- **Monitoring**: Prometheus scrape `/metrics`, Grafana dashboard.

Phân tầng lỗi Query Service: cú pháp truy vấn sai → **400**; OpenSearch/Embedding chết → **502**
(không bao giờ 500 cho lỗi người dùng).

---

## 2. Thu thập dữ liệu (Crawler)

Hai chế độ cùng tôn trọng robots.txt + lịch sự theo host:

- **`crawl`** — BFS 1 luồng (Phase 0/1), đơn giản, đủ cho corpus nhỏ.
- **`crawl-mt`** — **Mercator đa luồng (S4.1)**:
  - **Frontier 2 lớp** (`Frontier`): tập URL đã thấy (khử trùng) + hàng đợi FIFO theo từng host;
    min-heap host theo `nextAllowedTime`.
  - **Lịch sự (G4):** mỗi host chỉ do **1 worker** giữ tại một thời điểm (≤ 1 request đồng thời/host);
    sau khi lấy URL của host tại `t`, host bị khoá tới `t + delay` → khoảng cách giữa hai request bắt
    đầu tới cùng host luôn **≥ delay**. robots.txt kiểm qua `RobotsCache`.
  - **Tách I/O:** interface `PageFetcher` (`JsoupFetcher` cho HTML tĩnh; điểm mở rộng cho fetcher
    Selenium/headless xử lý trang JS) — nhờ đó crawler **unit-test được không cần mạng** (fetcher giả).
  - **Đồ thị liên kết:** ghi cạnh trang→trang + anchor vào bảng `links` (phục vụ PageRank S3.1).
  - Ghi SQLite đồng bộ (một kết nối), mạng chạy song song.

Lệnh: `java -jar hust-search.jar crawl-mt [maxPages] [maxDepth]`
(env `CRAWL_THREADS`, `CRAWL_DELAY_MS`).

---

## 3. Lập chỉ mục & mô hình xếp hạng

- **Tách từ tiếng Việt (G3):** `VietnameseAnalyzer` sinh field `*_seg` (từ ghép nối bằng `_`),
  analyzer `vi_seg`. Dùng cho mọi truy vấn từ khóa + anchor + phân loại.
- **3 index song song:** `documents` (BM25), `documents_vsm` (scripted tf-idf), `documents_lm`
  (LMDirichlet). `ranker=` chọn index tương ứng.
- **Vector:** field `embedding` (`knn_vector` 768, HNSW cosine) sinh khi `migrate --embed`.
- **Hybrid:** RRF trộn hạng BM25 + vector. **Rerank:** cross-encoder trên top-K ứng viên.
- Mỗi hit mang `score_type` (bm25/vsm/lm/cosine/rrf/cross-encoder) để biết thang điểm.

---

## 4. Thuật toán IR nâng cao (Phase 3)

| Mã | Thuật toán | Điểm chính | Mặc định |
|---|---|---|---|
| **S3.1** | **PageRank** | Power iteration, damping 0.85, xử lý dangling, hội tụ L1 < tol; anchor text gom theo đích → field tìm kiếm `anchor_text_seg`. Trộn ranking bằng `function_score` (`field_value_factor` trên `pagerank`, `boost_mode=sum`, `modifier=ln1p`). | `PAGERANK_WEIGHT=0` (TẮT) |
| **S3.2** | **Near-duplicate** | MinHash (FNV-1a + hàm băm `(a·x+b) mod (2⁶¹−1)`) + LSH banding + union-find; canonical = url nhỏ nhất; ghi `dup_group`. Gộp kết quả bằng `collapse`. | `DEDUP_COLLAPSE` off |
| **S3.3** | **Mở rộng truy vấn** | Từ điển đồng nghĩa (`vi-synonyms.txt`) + Rocchio PRF (rút token nổi bật từ top-K lượt mồi) → thêm nhánh `should`. | `QUERY_EXPAND_*` off |
| **S3.4** | **Phân loại + facet** | Naïve Bayes đa thức (Laplace) 5 lớp; đánh giá P/R/F1 + macro-F1 leave-one-out; facet `terms` + lọc `category/doc_type/subdomain`. | ghi `category` khi chạy `classify` |

Mọi tính năng Phase 3 **mặc định TẮT** → không đổi hành vi Phase 1/2 tới khi bật qua env.

---

## 5. Đánh giá chất lượng (eval)

Bộ đánh giá `eval-run` tính **nDCG@k, MAP, Recall** trên bộ truy vấn + qrels.

### Kết quả S2.6 (k=10, 35 truy vấn, corpus 500 trang HUST) — đo LOCAL

| Cấu hình | nDCG@10 | MAP | p95 warm (top_k=30) |
|---|---|---|---|
| **BM25 / VSM** | **0.760** | **0.657** | ~115 ms |
| LM (Dirichlet) | 0.515 | 0.380 | ~55 ms |
| Vector (k-NN) | 0.275 | 0.140 | ~240 ms |
| Hybrid (RRF) | 0.579 | 0.405 | ~500 ms |
| Hybrid + rerank | 0.371 | 0.230 | ~7400 ms* |

\* Đo khi reranker còn `max_length=512` (trước fix F2 `RERANK_MAX_LENGTH=256` + `RERANK_TOP_K=30`);
cần đo lại sau fix.

**Kết luận trung thực:** trên corpus 500 trang + truy vấn điều hướng, **BM25/VSM mạnh nhất**;
vector/hybrid không cải thiện và cross-encoder rerank còn tệ hơn + rất chậm trên CPU → tín hiệu
từ khóa đã đủ mạnh ở quy mô này. (Dense/rerank kỳ vọng có giá trị hơn ở corpus lớn + truy vấn ngữ nghĩa.)

> qrels hiện là bản AI gán first-pass (570 nhãn, `eval/qrels/pool-to-label.tsv`) → cần người soát lại;
> sửa cột `rel` rồi `pool_to_qrels.py` + `eval-run` để cập nhật bảng.

---

## 6. Giám sát (Monitoring, S4.2)

- Query Service xuất **`GET /metrics`** ở định dạng Prometheus (không phụ thuộc thư viện ngoài):
  - `bksearch_http_requests_total{path,status}` — suy ra **QPS** + tỉ lệ lỗi.
  - `bksearch_http_request_duration_seconds` (histogram) — suy ra **p50/p95/p99** bằng
    `histogram_quantile`.
  - `bksearch_http_in_flight` (gauge), `bksearch_uptime_seconds`.
- **Prometheus** (`deploy/monitoring/prometheus.yml`) scrape mỗi 5s; **Grafana** tự nạp datasource +
  dashboard *"BKSearch — Query Service"* (QPS theo path, p95/p50, in-flight, tỉ lệ lỗi, uptime).
- Bật: `cd deploy && docker compose --profile monitoring up -d` → Grafana `:3000` (admin/admin),
  Prometheus `:9090`. OpenSearch Dashboards `:5601` cho giám sát index.

---

## 7. Đo tải (Load test, S4.3)

Hai kịch bản **k6** (`deploy/loadtest/`) với ngưỡng pass/fail nhúng sẵn theo **G7**:

| Kịch bản | Mục tiêu p95 | QPS |
|---|---|---|
| `keyword.js` (BM25/VSM) | < 200 ms | 30 (tới 50) |
| `hybrid.js` (hybrid+rerank) | < 800 ms | 20 |

```bash
k6 run -e BASE=http://localhost:7070 deploy/loadtest/keyword.js
k6 run -e BASE=http://localhost:7070 deploy/loadtest/hybrid.js
```

Bật `--profile monitoring` để xem p95/QPS realtime trên Grafana khi bắn tải. Số đo thực tế (máy,
corpus, QPS, p95 đạt được) điền vào mục này sau phiên LOCAL. Rerank CPU rất chậm → cần hạ
`RERANK_TOP_K`/`RERANK_MAX_LENGTH` hoặc GPU để đạt ngưỡng hybrid.

---

## 8. Demo (chạy bằng Docker Compose)

```bash
# 1) Hạ tầng + embedding
cd deploy && cp .env.example .env && docker compose up -d

# 2) (Tuỳ chọn) monitoring
docker compose --profile monitoring up -d

# 3) Nạp dữ liệu + chỉ mục + tín hiệu Phase 3
bash demo-up.sh          # orchestrate: build jar → migrate → tạo 3 index → pagerank/dedupe/classify

# 4) Query Service
cd ../java-lucene
java -jar target/hust-search.jar serve-api 7070
#   mở http://localhost:7070  (UI: chọn ranker, facet, did-you-mean)
```

Bật tín hiệu Phase 3 khi `serve-api` (ví dụ): `PAGERANK_WEIGHT=2 DEDUP_COLLAPSE=1
QUERY_EXPAND_SYN=1 java -jar target/hust-search.jar serve-api 7070`.

---

## 9. Ràng buộc & quyết định kỹ thuật đáng chú ý

- **HTTP/1.1 bắt buộc** (F1/F6): JDK HttpClient mặc định HTTP/2 → ép 1.1 tránh h2c upgrade với
  uvicorn/OpenSearch.
- **Thread-safety Embedding** (F3): nạp model lazy + `threading.Lock`, cache LRU khoá.
- **Phân trang trong pool hữu hạn** (F4): nhánh vector/hybrid/rerank báo `total_candidates` +
  `total_matched`.
- **Mặc định an toàn:** mọi tính năng Phase 3 TẮT; `dup_group=url` cho mọi doc nên `collapse` an toàn.
- **Crawler testable:** tách `PageFetcher` để unit-test politeness/scope/đồ thị không cần mạng.

## 10. Trạng thái & việc còn lại

- Code + unit test: Phase 0→4 **xanh** (`mvn test`, `pytest`).
- Cần phiên LOCAL: re-crawl (`crawl-mt`, sinh `links`) → migrate → tạo index → `pagerank`/`dedupe`/
  `classify` → bật cờ, đo eval, chạy k6 (điền số p95 thực), xem Grafana.
- Nợ: soát qrels thật; mở rộng tập train phân loại từ corpus thật; (tuỳ chọn) fetcher Selenium thật
  cho trang JS.
