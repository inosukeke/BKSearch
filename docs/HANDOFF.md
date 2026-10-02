# HANDOFF — trạng thái dự án cho phiên code kế tiếp

> Cập nhật sau khi hoàn tất **Phase 1**. Đọc file này + `KE_HOACH_TRIEN_KHAI.md` là đủ để tiếp tục.

## Đã xong
- **Phase 0** (hạ tầng + di trú): Docker Compose (OpenSearch 2.17.1 + Dashboards + Postgres 16 + Redis 7),
  mapping index `documents` (có `embedding` knn_vector **dim=768**, HNSW lucene cosinesimil — khai báo sẵn cho Phase 2),
  schema Postgres, analyzer tiếng Việt dùng chung, di trú 500 doc SQLite→OpenSearch (idempotent).
- **Phase 1** (lõi truy xuất + đánh giá): Query Service Javalin, BM25/VSM/LM, phrase+Boolean, did-you-mean, Web UI, bộ đánh giá.

## Quy ước & kiến trúc (giữ nhất quán)
- Lõi Java ở `java-lucene/` (package `vn.hust.ir`). KHÔNG đổi tên thư mục.
- **Tách từ (G3 — BẮT BUỘC):** mọi nơi (ingest + mọi nhánh query) PHẢI dùng `vn.hust.ir.nlp.VietnameseAnalyzer.get()`.
  Field `*_seg` chứa token đã ghép (`đại_học`); analyzer index là `vi_seg` (whitespace+lowercase).
- **OpenSearch client:** `vn.hust.ir.migrate.OpenSearchClient` (JDK HttpClient + Jackson) — `_bulk/_count/_refresh/_search`.
  Tái dùng/mở rộng, KHÔNG kéo client nặng.
- **Đa ranker (S1.3):** OpenSearch 2.x đã bỏ `classic` → VSM dùng **scripted tf-idf**; LM dùng LMDirichlet.
  3 index: `documents` (BM25), `documents_vsm`, `documents_lm`. Đổi ranker = đổi index đích
  (`vn.hust.ir.query.Ranker`). Dựng 2 index song song: `deploy/opensearch/create-ranker-indices.sh` (`_reindex`).
- **Phân tầng lỗi REST:** lỗi cú pháp DSL/QueryParseException → 400; index thiếu/5xx → 502.
- **UI:** escape nội dung, chỉ cho `<em>` highlight (chống XSS).
- **Git:** luồng cloud→local: cloud code nhánh `feature/phaseN` → local test+review → merge `main`.
  Commit trailer: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.
- **Windows/encoding:** dùng UTF-8; curl đọc file JSON qua stdin (`--data-binary @- < file`) để tránh lỗi path Unicode;
  pgjdbc ép tz UTF/UTC.

## Lệnh chạy (LOCAL, cần Docker + corpus)
```bash
cd deploy && docker compose -p bksearch up -d && cd ..
bash deploy/opensearch/apply-mapping.sh
(cd java-lucene && mvn -q -DskipTests package && java -jar target/hust-search.jar migrate)
bash deploy/opensearch/create-ranker-indices.sh
(cd java-lucene && java -jar target/hust-search.jar serve-api 7070)   # /api/search, /api/suggest, /healthz, UI /
mvn test   # (trong java-lucene) — 38 test
```

## Giới hạn môi trường cloud (quan trọng cho phiên code cloud)
- Clone sạch: **KHÔNG có `java-lucene/data/hust.db`** (gitignore) và **không có Docker/OpenSearch sống**.
- → Trên cloud: viết code + unit test KHÔNG cần OpenSearch. Verify tích hợp (index, embedding, hybrid) để phiên LOCAL.

## Đang chờ / nợ kỹ thuật
- **qrels thật:** user tự gán nhãn. Đã sinh `eval/qrels/pool-to-label.tsv` (570 cặp). Quy trình: `eval/build_pool.py` → điền `rel` → `eval/pool_to_qrels.py` → `qrels.txt` → `eval/run-eval.sh` (hoàn tất bảng so sánh S1.8).
- F9 (Phase 0): rà lại stopwords hơi mạnh (người/việc/số/phần/làm) khi tinh chỉnh chất lượng.

## PHASE 2 (semantic hybrid + rerank), S2.1→S2.6 — ĐÃ VERIFY LOCAL + MERGE FIX REVIEW
- S2.1 Embedding Service (Python FastAPI, bi-encoder TV, dims **768** khớp mapping) trong `embedding-service/`.
- S2.2 sinh embedding khi ingest → ghi field `embedding` (k-NN). S2.3 vector search. S2.4 hybrid RRF. S2.5 cross-encoder rerank.
- Query Service (`vn.hust.ir.query`) có đủ nhánh `ranker=bm25|vsm|lm|vector|hybrid` + `rerank=1`. `mvn test` xanh (58 test).
- **Chạy thật LOCAL (2026-10-02):** stack `docker compose -p bksearch up -d` (kèm `embedding`, model thật `fake:false`);
  `migrate --embed` → 500/500 doc có vector; 3 index đủ. serve-api + eval-run --embed chạy được cả 4 pipeline.

### Kết quả S2.6 (k=10, 35 truy vấn, `EMBED_URL` bật) — xem `eval/results/eval-report.txt`
| config | nDCG@10 | MAP | p95 warm (top_k=30) |
|---|---|---|---|
| bm25 / vsm | **0.760** | **0.657** | ~115 ms |
| lm | 0.515 | 0.380 | ~55 ms |
| vector | 0.275 | 0.140 | ~240 ms |
| hybrid | 0.579 | 0.405 | ~500 ms |
| hybrid+rerank | 0.371 | 0.230 | **~7400 ms** |

- **Kết luận trung thực:** trên corpus 500 trang HUST + truy vấn điều hướng, **BM25/VSM mạnh nhất**; vector/hybrid KHÔNG cải thiện,
  cross-encoder rerank còn TỆ hơn hybrid và rất chậm (CPU). → tín hiệu từ khóa đã đủ mạnh; dense+rerank chưa đáng ở quy mô này.
- ⚠️ **Lưu ý về bảng p95 trên:** đo KHI reranker còn hardcode `max_length=512` (trước fix F2). Sau khi merge F2
  (`RERANK_MAX_LENGTH=256` + `RERANK_TOP_K=30`) p95 rerank kỳ vọng giảm mạnh → **cần đo lại** nếu còn quan tâm rerank.

### Chỉnh sau review (Phase 2) — ĐÃ MERGE VÀO MAIN
- **F1/F6:** `EmbeddingClient` & `OpenSearchClient` ép **HTTP/1.1** (uvicorn/h11 không h2c upgrade;
  JDK HttpClient mặc định HTTP/2). Có test khẳng định không gửi header `Upgrade: h2c`.
- **F2 (p95 rerank):** hạ `RERANK_TOP_K` mặc định **50→30**; cross-encoder truncate ở env
  `RERANK_MAX_LENGTH` (mặc định **256**) — GIẢI QUYẾT nợ "reranker hardcode max_length=512". p95
  **PHỤ THUỘC PHẦN CỨNG** (CPU/GPU) — đo lại trên môi trường mục tiêu.
- **F3:** nạp model lazy (`embedder`/`reranker`) + cache LRU bọc `threading.Lock` (FastAPI threadpool).
- **F4:** nhánh ứng viên (vector/hybrid/rerank) phân trang trong pool hữu hạn → `total`=`total_candidates`
  (kích thước pool), thêm `total_matched` (tổng khớp thực nhánh BM25 nền; `-1` nếu không áp dụng).
- **F5:** mỗi hit mang `score_type` (bm25/vsm/lm/cosine/rrf/cross-encoder) → biết thang điểm, tránh
  so trực tiếp phần đã rerank với phần đuôi.
- **F7:** pin `transformers==4.46.3` trong `requirements.txt` (G1 tái lập).
- **F8:** `/healthz` chỉ báo tiến trình sống; thêm `model_loaded` để biết model đã nạp chưa.

### Nợ còn lại cho Phase 3
- **qrels `eval/qrels/pool-to-label.tsv` (570 nhãn) là bản AI tự gán first-pass** → user cần soát lại; sửa cột `rel` rồi
  `python eval/pool_to_qrels.py` + eval-run là bảng tự cập nhật. Vài truy vấn gần như không có doc liên quan (q12=0, q04/q10/q11/q32 rất ít).
- **BM25 = VSM giống hệt mọi chỉ số** → dấu hiệu lạ từ Phase 1 (scripted tf-idf có thể chưa khác BM25 trên tập này) — nên soi riêng.
- **Hạ tầng Phase 2** (chạy LOCAL): thêm service `embedding` trong compose; key `EMBED_*`/`RERANK_MODEL`/`EMBED_FAKE`/`EMBED_URL` ở `deploy/.env.example`.

## PHASE 3 — Chất lượng & tín hiệu (đang làm; code + unit test trên cloud, verify local)

### S3.1 PageRank + anchor text — CODE XONG (chờ verify local)
- **Đồ thị liên kết:** crawler nay lưu CẠNH trang→trang + anchor vào bảng SQLite `links`
  (`src_url,dst_url,anchor`, UNIQUE, bỏ self-loop). `vn.hust.ir.store.Db`: `insertLink/allLinks/countLinks`.
- **Thuật toán:** `vn.hust.ir.linkgraph.PageRank` (power iteration, damping 0.85, xử lý dangling,
  hội tụ theo L1 < tol) + `LinkGraph` (intern URL, khử self-loop/cạnh trùng, gom anchor theo dst). Thuần, unit test đầy đủ.
- **Lệnh:** `pagerank [osUrl]` → đọc `links`+`documents` từ SQLite → tính PageRank → **cập nhật từng phần**
  (`OpenSearchClient.bulkUpdate`, action `update`+`doc`) field `pagerank` + `anchor_text`/`anchor_text_seg`
  vào cả 3 index. Env: `PAGERANK_DAMPING`, `PAGERANK_INDICES`, `PAGERANK_BATCH`.
- **Trộn ranking:** `SearchEngine.withPageRank` bọc truy vấn từ khóa bằng `function_score`
  (`field_value_factor` trên `pagerank`, `boost_mode=sum`, `modifier=ln1p`). Trọng số qua env
  **`PAGERANK_WEIGHT`** (mặc định **0 = TẮT** → không đổi hành vi). Anchor text thành field tìm kiếm
  `anchor_text_seg^1.5` trong `multi_match`.
- **Mapping:** thêm `anchor_text`, `anchor_text_seg` vào 3 mapping (pagerank đã khai báo từ Phase 0).

#### Verify LOCAL S3.1 (thứ tự quan trọng)
1. **Re-crawl** để sinh đồ thị: `java -jar ... crawl` (corpus hiện tại migrate từ SQLite cũ **CHƯA có
   bảng `links`** → PageRank sẽ đều nhau tới khi crawl lại). Kiểm `countLinks() > 0`.
2. `migrate` → `create-ranker-indices.sh` (tạo đủ 3 index) → `pagerank` (ghi pagerank+anchor vào cả 3).
3. Bật trộn: `PAGERANK_WEIGHT=<w>` khi `serve-api`/`eval-run`. **Tinh chỉnh w bằng eval** (PageRank ~1/N
   rất nhỏ nên cần w lớn hoặc đổi modifier). PASS: bật/tắt thấy thứ hạng đổi; nDCG không giảm.

### S3.2 Near-duplicate (MinHash/LSH) — CODE XONG (chờ verify local)
- **Thuật toán thuần** `vn.hust.ir.dedup`: `Shingling` (w-shingle k token), `MinHasher` (chữ ký
  MinHash, hash cơ sở FNV-1a, ước lượng Jaccard), `NearDuplicateDetector` (LSH banding → ứng viên →
  xác nhận Jaccard ≥ ngưỡng → union-find nhóm; canonical = url nhỏ nhất). Unit test đầy đủ.
- **Lệnh** `dedupe [osUrl]`: đọc corpus → gom nhóm → ghi `dup_group` (= url canonical) cho các bản
  TRÙNG vào 3 index (bulkUpdate). Env: `DEDUP_K`, `DEDUP_NUM_HASHES`, `DEDUP_BANDS`, `DEDUP_THRESHOLD`.
- **Migrate** nay ghi `dup_group = url` cho MỌI doc (mỗi doc tự nhóm) → field luôn tồn tại.
- **Gộp kết quả:** `SearchEngine` thêm `collapse` theo `dup_group` (mỗi nhóm 1 kết quả, doc điểm cao
  nhất), bật bằng env **`DEDUP_COLLAPSE=1`** (mặc định TẮT để an toàn index cũ chưa có field).
- **Mapping:** thêm `dup_group` (keyword) vào 3 index.

#### Verify LOCAL S3.2
1. `migrate` (đã ghi dup_group=url) → `create-ranker-indices.sh`.
2. `dedupe` → ghi canonical cho bản trùng. (Muốn test: chèn 2 trang gần trùng rồi chạy lại.)
3. `DEDUP_COLLAPSE=1` khi `serve-api` → kiểm mỗi nhóm trùng chỉ còn 1 kết quả; tỉ lệ trùng top-20 giảm.

### S3.3 Query expansion (synonym + Rocchio) — CODE XONG (chờ verify local)
- **Đồng nghĩa:** `SynonymDictionary` nạp `resources/vi-synonyms.txt` (nhóm cụm tương đương, gồm viết
  tắt đh/sv/cntt...). `SearchEngine.synonymExpansion` khớp truy vấn thô → thêm cụm còn lại (đã tách từ)
  vào nhánh `should` (boost 0.5) của `bool{must:gốc, should:mở rộng}`. Chỉ áp cho truy vấn KHÔNG cấu trúc.
- **Rocchio PRF:** `Rocchio.selectTerms` rút top token nổi bật từ `content` của top-`PRF_DOCS` kết quả
  lượt mồi (loại token truy vấn + stopword + token quá ngắn), thêm `PRF_TERMS` token vào `should`.
  `SearchEngine.expansionTerms` chạy lượt mồi khi bật PRF.
- **Bật/đo:** env `QUERY_EXPAND_SYN=1` (+`QUERY_EXPAND_SYN_MAX`), `QUERY_EXPAND_PRF=1`
  (+`PRF_DOCS`,`PRF_TERMS`) ở `serve-api`/`eval-run`. **Mặc định TẮT** (không đổi hành vi Phase 1/2).
- **Verify LOCAL:** chạy `eval-run` 2 lần (tắt vs bật) so Recall@10; PASS: recall tăng, precision không
  giảm nhiều. Chỉnh từ điển `vi-synonyms.txt` cho hợp truy vấn thực tế.

### S3.4 Phân loại + facet — CODE XONG (chờ verify local)
- **Naïve Bayes** đa thức (`vn.hust.ir.classify.NaiveBayes`, Laplace) + `ClassifierMetrics` (P/R/F1,
  macro-F1, accuracy). `DocumentClassifier` nạp `resources/category-train.tsv` (tập seed 5 lớp:
  tuyển sinh / đào tạo / thông báo / tin tức / nghiên cứu), tách từ (G3) rồi train.
- **Lệnh** `classify [osUrl]`: in báo cáo **leave-one-out P/R/F1** trên tập train, rồi gán `category`
  cho corpus và ghi vào 3 index (bulkUpdate). Migrate chưa ghi category → chạy `classify` sau migrate.
- **Facet + lọc:** `SearchEngine` (nhánh từ khóa) thêm aggregation `terms` cho category/doc_type/subdomain
  → `SearchResponse.facets`; tham số lọc `?category=&doc_type=&subdomain=` bọc `bool filter`. UI hiển thị
  facet bên trái, click để lọc/bỏ lọc, kèm tag danh mục trên mỗi kết quả.
- **Mapping:** thêm `category` (keyword) vào 3 index.

#### Verify LOCAL S3.4
1. `migrate` → `create-ranker-indices.sh` → `classify` (xem báo cáo P/R/F1; PASS mong đợi ≥ ~0.8 nếu
   bổ sung mẫu train từ corpus thật — tập seed hiện nhỏ).
2. `serve-api` → UI hiện facet Danh mục; click lọc đúng; kiểm phân bố hợp lý.
3. Tăng chất lượng: thêm dòng vào `resources/category-train.tsv` từ tài liệu thật rồi chạy lại `classify`.

---
**Trạng thái Phase 3 (cloud):** S3.1–S3.4 CODE XONG, `mvn test` xanh (102 test). Mọi tính năng
mặc định TẮT (PAGERANK_WEIGHT=0, DEDUP_COLLAPSE off, QUERY_EXPAND_* off) nên hành vi Phase 1/2
không đổi tới khi bật. Cần phiên LOCAL: re-crawl (sinh `links`) → migrate → create-ranker-indices →
`pagerank` + `dedupe` + `classify` → bật từng cờ, đo eval, tinh chỉnh → merge `feature/phase3` → main.

## PHASE 4 — Crawler quy mô + Monitoring + Load test + Báo cáo (code + unit test trên cloud)

### S4.1 Crawler Mercator đa luồng — CODE XONG (chờ verify local)
- **Frontier 2 lớp** `vn.hust.ir.crawler.Frontier`: khử trùng URL + hàng đợi FIFO/host + min-heap host
  theo `nextAllowedTime`. **Lịch sự (G4):** ≤ 1 request đồng thời/host; giãn cách giữa 2 request bắt
  đầu tới cùng host **≥ delay**. Thread-safe (wait/notify). Unit test đầy đủ.
- **`PageFetcher`** (interface) tách I/O: `JsoupFetcher` (HTML tĩnh); điểm mở rộng cho fetcher
  Selenium/headless (trang JS) — chưa hiện thực trên cloud (không test được). Nhờ tách I/O, crawler
  **unit-test được không cần mạng** (fetcher giả).
- **`MercatorCrawler`**: N worker song song, robots qua `Predicate<String>` (mặc định `RobotsCache`),
  lưu `documents`/`files` + CẠNH `links` (PageRank), ghi SQLite đồng bộ. Lệnh `crawl-mt [maxPages]
  [maxDepth]` (env `CRAWL_THREADS` mặc định 4, `CRAWL_DELAY_MS` mặc định 1000).
- **Verify LOCAL:** `crawl-mt 1000 3` → kiểm `countLinks() > 0`, log giãn cách/host ≥ delay, không
  trang ngoài `*.hust.edu.vn`.

### S4.2 Monitoring (Prometheus + Grafana) — CODE XONG
- **`vn.hust.ir.metrics.Metrics`** (KHÔNG phụ thuộc lib ngoài): counter `bksearch_http_requests_total
  {path,status}`, histogram `bksearch_http_request_duration_seconds` (p50/p95/p99 bằng
  `histogram_quantile`), gauge in-flight + uptime; xuất đúng Prometheus text (v0.0.4). Unit test
  rendering + bucket tích luỹ.
- **Query Service**: `app.before/after` đo mọi request; endpoint **`GET /metrics`** (không tự đếm
  scrape). Test tích hợp `QueryServiceMetricsTest` (Javalin thật + OpenSearch giả).
- **Hạ tầng** `deploy/monitoring/`: `prometheus.yml` (scrape host:7070), Grafana provisioning
  (datasource + dashboard JSON "BKSearch — Query Service"). Compose profile `monitoring`
  (Prometheus :9090, Grafana :3000). Bật: `docker compose --profile monitoring up -d`.
- **Verify LOCAL:** bắn tải → Grafana hiển thị QPS & p95 realtime.

### S4.3 Load test (k6) — CODE XONG
- `deploy/loadtest/keyword.js` (BM25/VSM, ngưỡng **p95 < 200ms** @ 30–50 QPS) và `hybrid.js`
  (hybrid+rerank, **p95 < 800ms** @ 20 QPS). Ngưỡng G7 nhúng trong script (k6 thoát ≠ 0 nếu vượt).
  `deploy/loadtest/README.md` hướng dẫn chạy (+Docker k6).
- **Verify LOCAL:** chạy k6, ghi số p95 thực (máy/corpus/QPS) vào `docs/BAO_CAO.md` mục 7.

### S4.4 Báo cáo + demo — CODE XONG
- **`docs/BAO_CAO.md`**: kiến trúc, crawler, chỉ mục/ranker, thuật toán Phase 3, bảng eval + so sánh
  mô hình, monitoring, load test, demo, quyết định kỹ thuật, việc còn lại. Trung thực về phạm vi
  verify (unit test cloud vs eval/p95 local).
- **`deploy/demo-up.sh`**: build jar → apply-mapping + migrate → tạo 3 index → `pagerank`/`dedupe`/
  `classify`. `deploy/README.md` cập nhật cổng + mục monitoring/demo.

---
**Trạng thái Phase 4 (cloud):** S4.1–S4.4 CODE XONG, `mvn test` xanh (toàn bộ), `pytest` xanh.
Crawler đa luồng/politeness + Monitoring có unit test; eval/p95/demo cần phiên LOCAL (Docker +
OpenSearch + model + k6). Fetcher Selenium là điểm mở rộng (chưa hiện thực). Nhánh `feature/phase4`.
