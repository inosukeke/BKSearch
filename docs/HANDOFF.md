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

## Tiếp theo: PHASE 2 (semantic hybrid + rerank), S2.1→S2.6
- S2.1 Embedding Service (Python FastAPI, bi-encoder TV, dims **768** khớp mapping) trong `embedding-service/`.
- S2.2 sinh embedding khi ingest → ghi field `embedding` (k-NN). S2.3 vector search. S2.4 hybrid RRF. S2.5 cross-encoder rerank. S2.6 PASS tổng.
- Query Service đã có sẵn (`vn.hust.ir.query`) để cắm thêm nhánh vector/hybrid/rerank.
