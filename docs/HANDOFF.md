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

## PHASE 2 (semantic hybrid + rerank), S2.1→S2.6 — CODE XONG, chờ verify tích hợp local
- S2.1 Embedding Service (Python FastAPI, bi-encoder TV, dims **768** khớp mapping) trong `embedding-service/`.
- S2.2 sinh embedding khi ingest → ghi field `embedding` (k-NN). S2.3 vector search. S2.4 hybrid RRF. S2.5 cross-encoder rerank. S2.6 PASS tổng.
- Query Service (`vn.hust.ir.query`) đã cắm nhánh vector/hybrid/rerank. `mvn test` xanh (58 test).

### Chỉnh sau review (Phase 2)
- **F1/F6:** `EmbeddingClient` & `OpenSearchClient` ép **HTTP/1.1** (uvicorn/h11 không h2c upgrade;
  JDK HttpClient mặc định HTTP/2). Có test khẳng định không gửi header `Upgrade: h2c`.
- **F2 (p95 rerank):** hạ `RERANK_TOP_K` mặc định **50→30**; cross-encoder truncate ở
  `RERANK_MAX_LENGTH=256`. Đo local CPU: top_k=50 không truncate → p95 ~10.3s (VƯỢT 800ms);
  top_k=30 + max_length=256 → p95 ~540ms (ĐẠT). **p95 PHỤ THUỘC PHẦN CỨNG** (CPU/GPU) — số trên
  đo trên CPU local; phiên local phải **đo lại** trên môi trường mục tiêu.
- **F3:** nạp model lazy (`embedder`/`reranker`) + cache LRU bọc `threading.Lock` (FastAPI threadpool).
- **F4:** nhánh ứng viên (vector/hybrid/rerank) phân trang trong pool hữu hạn → `total`=`total_candidates`
  (kích thước pool), thêm `total_matched` (tổng khớp thực nhánh BM25 nền; `-1` nếu không áp dụng).
- **F5:** mỗi hit mang `score_type` (bm25/vsm/lm/cosine/rrf/cross-encoder) → biết thang điểm, tránh
  so trực tiếp phần đã rerank với phần đuôi.
- **F7:** pin `transformers==4.46.3` trong `requirements.txt` (G1 tái lập).
- **F8:** `/healthz` chỉ báo tiến trình sống; thêm `model_loaded` để biết model đã nạp chưa.

### Verify tích hợp + merge (phiên LOCAL làm tiếp)
1. `docker compose -p bksearch up -d` (kèm service `embedding`), `apply-mapping.sh`.
2. `migrate --embed` (model thật) → ghi vector; `create-ranker-indices.sh`.
3. `serve-api` với `EMBED_URL` → thử `ranker=vector|hybrid`, `rerank=true`.
4. **Đo lại p95** (kỳ vọng <800ms với top_k=30 + max_length=256 trên phần cứng mục tiêu).
5. Gán nhãn `eval/qrels/pool-to-label.tsv` (570 cặp) → `pool_to_qrels.py` → `qrels.txt`;
   `eval-run --embed` → bảng **BM25 vs vector vs hybrid vs hybrid+rerank** (PASS S2.6: hybrid+rerank
   thắng BM25 về nDCG@10/MAP).
6. Thêm key `EMBED_*` vào `deploy/.env.example` (thư mục này cloud bị chặn ghi).
7. Merge `feature/phase2` → `main`.
