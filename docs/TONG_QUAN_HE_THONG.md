# BKSearch — Tổng quan hệ thống (cho thành viên mới)

> Mục đích: đọc xong biết **hệ thống gồm những thành phần gì, mỗi thành phần làm gì, nằm ở đâu** —
> đủ để nhận một phần và tự đào sâu khi cần sửa/nâng cấp/mở rộng. KHÔNG mô tả chi tiết cách hoạt động
> (xem code + các doc chuyên sâu ở cuối).

BKSearch là hệ thống **thu thập — lập chỉ mục — tìm kiếm** tài liệu từ `*.hust.edu.vn`, gồm nhiều mô hình
xếp hạng (BM25/VSM/LM, vector, hybrid, rerank) và các tín hiệu chất lượng (PageRank, khử trùng, phân loại).
Chạy hoàn toàn **local bằng Docker Compose**.

---

## 1. Bản đồ kiến trúc (luồng dữ liệu)

```
  *.hust.edu.vn
       │  (1) OFFLINE — thu thập & dựng chỉ mục
       ▼
  CRAWLER ──► SQLite (thô) ──► MIGRATE ──► OpenSearch (3 index) ◄── TÍN HIỆU: PageRank / Dedup / Classify
   │                               │             ▲
   │                               │             │ (gọi khi ingest)
   └► bảng links (đồ thị)          └► PostgreSQL  └── EMBEDDING SERVICE (Python, sinh vector)

       │  (2) ONLINE — phục vụ truy vấn
       ▼
  UI web ──► QUERY SERVICE ──► SEARCH ENGINE ──► OpenSearch (BM25/VSM/LM/vector/hybrid)
                   │                   └────────► EMBEDDING SERVICE (vector + rerank)
                   └► /metrics ──► PROMETHEUS ──► GRAFANA
```

---

## 2. Thành phần & nhiệm vụ

### A. Thu thập dữ liệu (crawler) — `java-lucene/.../crawler`
| Thành phần | Làm gì |
|---|---|
| `MercatorCrawler` | Crawler đa luồng chính: duyệt `*.hust.edu.vn`, lưu bài viết + link tài liệu + đồ thị link |
| `Frontier` | Hàng đợi URL 2 lớp, đảm bảo **lịch sự** (1 request/host, có delay) |
| `PageFetcher` (interface) | Trừu tượng hoá bước tải + phân tích 1 trang |
| ├ `JsoupFetcher` | Tải HTML **tĩnh** (nhanh) |
| ├ `SeleniumFetcher` | Tải trang **JavaScript** bằng headless Chrome |
| └ `HybridFetcher` | Tự động: Jsoup trước, fallback Selenium khi trang "mỏng" |
| `Fetchers` | Chọn fetcher theo env `CRAWL_FETCHER=jsoup\|selenium\|auto` |
| `RobotsCache` | Đọc & tuân thủ `robots.txt` |
| `HustCrawler` | Crawler BFS 1 luồng (bản cũ, dùng cho scheduler định kỳ) |

### B. Xử lý & lưu trữ dữ liệu
| Thành phần | Vị trí | Làm gì |
|---|---|---|
| `TikaExtractor` | `.../extract` | Bóc text từ file PDF/DOC/XLS/PPT |
| `VietnameseAnalyzer` | `.../nlp` | Tách từ + chuẩn hoá tiếng Việt (dùng chung cho ingest & query) |
| `Db`, `Document` | `.../store` | Lưu bài viết/tài liệu/đồ thị link vào **SQLite** (phát hiện nội dung mới qua hash) |
| `Migrator`, `OpenSearchClient` | `.../migrate` | Đưa dữ liệu SQLite → **OpenSearch** (+ sinh embedding khi ingest) |
| `PostgresMetaStore` | `.../migrate` | Ghi metadata vào **PostgreSQL** |
| `PeriodicRunner` | `.../schedule` | Chạy lại crawl + index **định kỳ** (cập nhật nội dung mới) |

### C. Tín hiệu chất lượng (chạy sau khi migrate)
| Thành phần | Vị trí | Làm gì |
|---|---|---|
| `PageRank`, `LinkGraph` | `.../linkgraph` | Tính PageRank từ đồ thị link (lệnh `pagerank`) |
| `NearDuplicateDetector` (MinHash/LSH) | `.../dedup` | Phát hiện trang gần trùng (lệnh `dedupe`) |
| `NaiveBayes`, `DocumentClassifier` | `.../classify` | Phân loại danh mục tài liệu (lệnh `classify`) |

### D. Phục vụ truy vấn (online) — `java-lucene/.../query`
| Thành phần | Làm gì |
|---|---|
| `QueryService` | Máy chủ web (Javalin): REST API `/api/search`, `/api/suggest`, `/metrics` + Web UI |
| `SearchEngine` | Lõi truy xuất: chạy BM25/VSM/LM, vector, hybrid, rerank, facet, lọc |
| `QueryParser` | Phân tích cú pháp truy vấn (cụm từ, Boolean AND/OR/NOT) |
| `Ranker` | Chọn mô hình xếp hạng / index đích |
| `RrfFusion` | Hợp nhất kết quả BM25 + vector (hybrid) |
| `Reranker` | Sắp xếp lại top-K bằng cross-encoder |
| `SpellChecker` | Gợi ý "did-you-mean" |
| `SynonymDictionary`, `Rocchio`, `ExpansionOptions` | Mở rộng truy vấn (đồng nghĩa + phản hồi liên quan) |
| `SearchResponse`, `SearchHit` | Cấu trúc kết quả trả về |

### E. Embedding Service — `embedding-service/` (Python, FastAPI)
| Thành phần | Làm gì |
|---|---|
| `main.py` | API `/embed`, `/rerank`, `/healthz` |
| `embedder.py` | Sinh vector (bi-encoder tiếng Việt, 768 chiều) |
| `reranker.py` | Chấm điểm lại cặp (truy vấn, tài liệu) bằng cross-encoder |

### F. Giám sát (monitoring) — `java-lucene/.../metrics` + `deploy/monitoring`
| Thành phần | Làm gì |
|---|---|
| `Metrics` | Thu thập số liệu, xuất định dạng Prometheus qua `/metrics` |
| Prometheus | Thu thập (scrape) số liệu định kỳ |
| Grafana | Dashboard QPS / độ trễ p95 / lỗi realtime |

### G. Đánh giá & kiểm thử tải — `eval/` + `deploy/loadtest`
| Thành phần | Làm gì |
|---|---|
| `EvalRunner` (`.../eval`) + `run-eval.sh` | Đo nDCG@k / MAP / MRR cho từng mô hình xếp hạng |
| `build_pool.py`, `pool_to_qrels.py` | Dựng tập đánh giá (pool → gán nhãn → qrels) |
| `loadtest/keyword.js`, `hybrid.js` | Kịch bản k6 đo p95 dưới tải |

### H. Thành phần cũ (legacy, giữ để tham chiếu) — `.../search`, `.../index`
`LuceneSearcher`, `WebServer`, `LuceneIndexer`: bản tìm kiếm **Lucene nhúng** nguyên gốc (trước khi
chuyển sang OpenSearch). Giữ lại để so sánh "tự viết vs OpenSearch" trong báo cáo và cho scheduler.

---

## 3. Hạ tầng (chạy bằng Docker) — `deploy/`
| Dịch vụ | Cổng | Vai trò |
|---|---|---|
| OpenSearch | 9200 | Lưu chỉ mục + tìm kiếm (BM25 + k-NN) |
| OpenSearch Dashboards | 5601 | Xem/soi index |
| PostgreSQL | 5432 | Metadata tài liệu |
| Redis | 6379 | Cache (dự phòng mở rộng) |
| Embedding Service | 8000 | Vector + rerank |
| Query Service | 7070 | API + Web UI |
| Prometheus / Grafana | 9090 / 3000 | Giám sát (bật bằng `--profile monitoring`) |

**Điểm vào duy nhất để chạy cả hệ:** `java-lucene/.../App.java` (CLI: `crawl-mt`, `migrate`, `pagerank`,
`dedupe`, `classify`, `serve-api`, `eval-run`...) và `deploy/docker-compose.yml`. Xem nhanh `deploy/README.md`
và `deploy/demo-up.sh`.

---

## 4. Muốn sửa/mở rộng phần nào thì vào đâu
| Nhu cầu | Bắt đầu từ |
|---|---|
| Thêm/đổi cách crawl (vd trang JS khác) | `crawler/PageFetcher` + các fetcher, `MercatorCrawler` |
| Thêm mô hình xếp hạng mới | `query/Ranker` + `query/SearchEngine` + mapping index |
| Chỉnh mở rộng truy vấn (đồng nghĩa, PRF) | `query/SynonymDictionary`, `query/Rocchio`, `resources/vi-synonyms.txt` |
| Thêm danh mục phân loại | `resources/category-train.tsv` + `classify/*` |
| Đổi giao diện | Web UI (chuỗi `UI_PAGE` trong `query/QueryService.java`) |
| Thêm số liệu giám sát | `metrics/Metrics` + dashboard Grafana |
| Đổi mapping index (thêm field) | `deploy/opensearch/*.mapping.json` ⚠️ phải **xoá+tạo lại index** khi đổi (xem HANDOFF) |

---

## 5. Đọc sâu hơn
- `HANDOFF.md` — trạng thái hiện tại + các lưu ý/bug đã gặp (đọc trước khi code tiếp).
- `BAO_CAO.md` — báo cáo đầy đủ + kết quả đo (eval, load test).
- `API_QUERY_SERVICE.md` — hợp đồng REST của Query Service.
- `THIET_KE_HE_THONG.md` — thiết kế & lý do lựa chọn kiến trúc.
- `TIM_HIEU_CONG_NGHE.md` — giải thích khái niệm công nghệ (để học/bảo vệ).
