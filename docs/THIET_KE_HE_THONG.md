# Thiết kế hệ thống tìm kiếm thông tin quy mô lớn (bản nâng cấp)

> Nâng cấp từ bản bài tập lớn (Java/Lucene embedded) thành một hệ thống IR đủ sâu để
> trình bày như đồ án/portfolio.
> **Định hướng đã chốt:** ưu tiên **đào sâu thuật toán**; serving bằng **OpenSearch**;
> **hybrid BM25 + vector + rerank**; chạy **local bằng Docker Compose**; hạ tầng tối giản
> (không Kafka).

---

## 1. Mục tiêu & phạm vi

### 1.1 Mục tiêu quy mô (lean, thiên về thuật toán)
| Chỉ số | Mục tiêu | Ghi chú |
|---|---|---|
| Số tài liệu | **100k – 200k** | crawl sâu `*.hust.edu.vn` + tài liệu (PDF/DOC qua Tika) |
| QPS | ~20–50 (1 node) | không chạy đua throughput; đủ để đo |
| Độ trễ p95 — tìm từ khóa | **< 200 ms** | BM25 trên OpenSearch |
| Độ trễ p95 — hybrid + rerank | **< 800 ms** | rerank top-50 bằng cross-encoder |
| Chất lượng | nDCG@10, MAP tăng rõ so với BM25 thuần | đo bằng bộ đánh giá tự xây |

### 1.2 Điểm khác biệt so với bản hiện tại
| Khía cạnh | Bản hiện tại | Bản nâng cấp |
|---|---|---|
| Serving | Lucene embedded, 1 tiến trình | **OpenSearch** (sharding, REST, k-NN sẵn) |
| Xếp hạng | BM25 | **BM25 + VSM + LM** (so sánh) + **hybrid vector** + **rerank** |
| Ngữ nghĩa | không | **embedding tiếng Việt** (bi-encoder) + **cross-encoder rerank** |
| Xử lý truy vấn | tách từ cơ bản | **sửa lỗi (did-you-mean)**, **đồng nghĩa**, phrase/proximity, intent |
| Tách từ TV | Maximum Matching | **VnCoreNLP** + analyzer + chuẩn hóa NFC/TCVN 6909 |
| Đánh giá | thủ công | **bộ đánh giá** P@k/nDCG/MAP/MRR + test collection + A/B |
| Tín hiệu | chỉ nội dung | thêm **PageRank**, anchor text, **near-duplicate** khử trùng |
| Vận hành | chạy tay | **Docker Compose** + **monitoring** (Dashboards/Grafana) |
| Lưu trữ | SQLite | **PostgreSQL** (metadata) + **Redis** (cache) + object store (file) |

> Vẫn **giữ module Lucene tự viết** (bản cũ) như một thành phần "from-scratch" để minh họa
> hiểu thuật toán và **so sánh** với OpenSearch trong báo cáo.

---

## 2. Kiến trúc tổng thể

```
                         ┌─────────────────────────────────────────────┐
                         │                 OFFLINE (bất đồng bộ)        │
   *.hust.edu.vn         │                                             │
        │                │  ┌──────────┐   job   ┌──────────────────┐  │
        └───────────────▶│  │ CRAWLER  │────────▶│  INGESTION/ETL   │  │
                         │  │ (Jsoup,  │  Redis   │  - Tika bóc text │  │
                         │  │ Mercator)│  Stream  │  - tách từ TV    │  │
                         │  └──────────┘          │  - near-dup(LSH) │  │
                         │        │ raw           │  - gọi EMBEDDING │  │
                         │        ▼               │  - PageRank      │  │
                         │  ┌──────────┐          └───────┬──────────┘  │
                         │  │ PostgreSQL│  metadata        │ bulk index  │
                         │  │ + object  │◀─────────────────┤             │
                         │  │   store   │                  ▼             │
                         │  └──────────┘          ┌──────────────────┐  │
                         │                        │   OpenSearch     │  │
                         │                        │  BM25 + k-NN(HNSW)│ │
                         │                        └──────────────────┘  │
                         └───────────────────────────────┬──────────────┘
                                                          │
   ┌──────────────────────────────────────────────────── │ ONLINE (đồng bộ) ──────┐
   │                                                      ▼                        │
   │  UI web ──▶  QUERY SERVICE  ──▶ xử lý truy vấn (spell/synonym/phrase)         │
   │  (facet,      (Javalin)         │                                             │
   │  did-you-mean,                  ├─▶ BM25 (OpenSearch) ─┐                       │
   │  chọn ranker)                   ├─▶ vector k-NN ───────┤─▶ hợp nhất (RRF)      │
   │       ▲                         │                      │        │             │
   │       │                         │   EMBEDDING SERVICE  │        ▼             │
   │       │                         └──▶(FastAPI, bi-enc)  │   RERANK (cross-enc) │
   │       │                                                │        │             │
   │       └────────────── kết quả xếp hạng ◀───────────────┴────────┘             │
   │                         ▲ Redis cache                                         │
   └──────────────────────── MONITORING: OpenSearch Dashboards + Prometheus/Grafana┘
```

**Luồng dữ liệu:**
- **Offline (bất đồng bộ):** Crawler đẩy URL/raw vào hàng đợi Redis Stream → Ingestion lấy ra,
  bóc text (Tika), tách từ, khử trùng (MinHash/LSH), sinh embedding (gọi Embedding Service),
  tính PageRank theo lô, rồi **bulk index** vào OpenSearch + lưu metadata PostgreSQL.
- **Online (đồng bộ):** UI → Query Service: xử lý truy vấn → chạy song song **BM25** và
  **vector k-NN** → **hợp nhất RRF** → **rerank** top-K bằng cross-encoder → trả kết quả;
  cache ở Redis.

---

## 3. Công nghệ từng tầng (so sánh ≥2 lựa chọn + trade-off)

### 3.1 Search engine (serving)
| Lựa chọn | Ưu | Nhược | Quyết định |
|---|---|---|---|
| **OpenSearch** | mã nguồn mở hoàn toàn (Apache 2.0), có **k-NN/HNSW** sẵn → hybrid trong 1 engine, Dashboards | tốn RAM (JVM) | ✅ **Chọn** |
| Elasticsearch | tính năng tương đương, tài liệu nhiều | license SSPL (không thuần OSS) | thay thế được |
| Apache Solr | trên Lucene, admin UI | k-NN/hybrid kém tiện, cộng đồng TV ít | không |

### 3.2 Vector store
| Lựa chọn | Ưu | Nhược | Quyết định |
|---|---|---|---|
| **OpenSearch k-NN (HNSW)** | **một engine** cho cả BM25 + vector → hybrid dễ | cấu hình HNSW | ✅ **Chọn** |
| FAISS | nhanh, chuẩn nghiên cứu | thêm 1 dịch vụ riêng, tự lo bền vững | phương án B |
| pgvector (Postgres) | gộp với metadata | chậm hơn ở quy mô lớn | phương án B |

### 3.3 Metadata store
| Lựa chọn | Ưu | Nhược | Quyết định |
|---|---|---|---|
| **PostgreSQL** | mạnh, có pgvector dự phòng, chuẩn portfolio | thêm 1 service | ✅ **Chọn** |
| SQLite (giữ như cũ) | gọn, 0 cấu hình | 1 tiến trình, kém đa truy cập | fallback nếu muốn siêu gọn |

### 3.4 Hàng đợi (crawler → ingestion)
| Lựa chọn | Ưu | Nhược | Quyết định |
|---|---|---|---|
| **Redis Streams** | nhẹ, kiêm luôn **cache**, đủ dùng | không mạnh như Kafka | ✅ **Chọn** (hợp "gọn nhẹ") |
| Kafka | chuẩn quy mô lớn, bền | nặng, thừa cho 1 kỳ | không |
| Không dùng (gọi trực tiếp) | đơn giản nhất | khó tách crawl/ingest, kém chịu tải | phương án tối giản |

### 3.5 Cache: **Redis** (cache truy vấn + kết quả + embedding) — thay cho in-memory để chia sẻ.

### 3.6 Embedding & rerank serving
| Lựa chọn | Ưu | Nhược | Quyết định |
|---|---|---|---|
| **Python FastAPI + sentence-transformers** | hệ sinh thái model **tiếng Việt** tốt nhất (bi-encoder + cross-encoder) | thêm service Python | ✅ **Chọn** |
| ONNX chạy trong Java | 1 ngôn ngữ | ít model TV sẵn, cấu hình khó | không |
| API cloud (OpenAI...) | không cần hạ tầng | tốn phí, phụ thuộc mạng | không (muốn local) |

Model gợi ý: bi-encoder `bkai-foundation-models/vietnamese-bi-encoder` (hoặc `keepitreal/vietnamese-sbert`); cross-encoder đa ngữ cho rerank.

### 3.7 Query service & UI
- **Query Service: Javalin** (Java, nhẹ) điều phối truy vấn — giữ cùng ngôn ngữ với lõi
  Lucene/crawler. (Spring Boot mạnh hơn nhưng nặng; chọn Javalin cho gọn.)
- **UI:** nâng cấp web hiện tại (thêm facet, did-you-mean, chọn mô hình xếp hạng, highlight).

### 3.8 Monitoring
- **OpenSearch Dashboards** (xem index, truy vấn) + **Prometheus + Grafana** (QPS, p95 latency).

---

## 4. Thuật toán (phần trọng tâm)

### 4.1 Chỉ mục ngược & tách từ tiếng Việt
- Inverted index do OpenSearch/Lucene lo; **custom analyzer tiếng Việt**: chuẩn hóa Unicode
  NFC (TCVN 6909), **tách từ bằng VnCoreNLP**, lọc stopwords, lowercasing.
- Nhất quán analyzer giữa index & query.

### 4.2 Xếp hạng từ khóa (so sánh mô hình)
- **BM25** (mặc định), **VSM/tf-idf cosine** (ClassicSimilarity), **Language Model**
  (Dirichlet/Jelinek-Mercer) — cho phép chuyển đổi và **đo chênh lệch** bằng bộ đánh giá.

### 4.3 Semantic & hybrid
- **Bi-encoder** sinh vector cho tài liệu + truy vấn → **k-NN (HNSW)** trong OpenSearch.
- **Hợp nhất Hybrid bằng Reciprocal Rank Fusion (RRF)**: kết hợp thứ hạng BM25 và vector
  (bền hơn cộng điểm trực tiếp vì không cần chuẩn hóa thang điểm).
- **Rerank**: lấy top-50 sau hợp nhất, chấm lại bằng **cross-encoder** → sắp xếp cuối.

### 4.4 Xử lý truy vấn
- **Sửa lỗi / "Did you mean?"**: Levenshtein/Damerau + Jaccard trên k-gram.
- **Mở rộng đồng nghĩa**: từ điển thủ công + đồng xuất hiện (co-occurrence) / pseudo-feedback
  (Rocchio).
- **Phrase/proximity** và **Boolean** (AND/OR/NOT).
- **Phân loại ý định** truy vấn (informational/navigational) — tùy chọn.

### 4.5 Tín hiệu bổ sung
- **PageRank** trên đồ thị `*.hust.edu.vn` + **anchor text** → trộn vào điểm xếp hạng (g(d)).
- **Near-duplicate**: Shingles + **MinHash + LSH** để khử trang trùng trước khi index.

### 4.6 Đánh giá
- Tự xây **test collection**: ~30–50 truy vấn + nhãn phù hợp (pooling top-k các mô hình).
- Độ đo: **Precision@k, Recall@k, F1, MAP, MRR, nDCG@k**; đường cong P/R.
- **A/B offline**: so BM25 vs hybrid vs hybrid+rerank; báo cáo bảng số + biểu đồ.

---

## 5. Lộ trình triển khai (phase, mốc, ưu tiên, công sức)

> Ước lượng theo **người-tuần (p-w)** cho nhóm 4 người.

### Phase 0 — Hạ tầng & di trú dữ liệu  ·  ưu tiên ⭐⭐⭐  ·  ~3 p-w
- Docker Compose: OpenSearch + Dashboards + PostgreSQL + Redis + (skeleton) Embedding Service.
- Viết **custom analyzer tiếng Việt** cho OpenSearch (VnCoreNLP).
- Di trú dữ liệu hiện có → OpenSearch (bulk).
- **Done:** tìm được corpus hiện tại qua OpenSearch BM25 với tách từ TV; `docker compose up` chạy toàn hệ.

### Phase 1 — Lõi truy xuất + Đánh giá  ·  ⭐⭐⭐  ·  ~5 p-w
- Query Service (Javalin) + REST; nâng cấp UI (chọn ranker, highlight, phân trang).
- 3 mô hình: BM25 / VSM / LM.
- Xử lý truy vấn cơ bản: phrase, Boolean, did-you-mean.
- **Bộ đánh giá** + test collection (P@k, MAP, nDCG).
- **Done:** bảng so sánh 3 mô hình trên test collection; UI dùng được.

### Phase 2 — Semantic hybrid + Rerank  ·  ⭐⭐⭐  ·  ~5 p-w
- Embedding Service (bi-encoder TV) + sinh vector khi ingest → k-NN index.
- Hybrid **RRF** + **cross-encoder rerank**.
- **Done:** hybrid+rerank **vượt BM25** về nDCG@10/MAP (có số đo); p95 < 800ms.

### Phase 3 — Chất lượng & tín hiệu  ·  ⭐⭐  ·  ~4 p-w
- **PageRank** + anchor text (trộn vào ranking); **near-duplicate** khử trùng.
- Mở rộng truy vấn (đồng nghĩa/Rocchio); **phân loại tài liệu** → **facet** lọc theo nhãn.
- **Done:** các tính năng chạy trên UI và **đo được** cải thiện/giảm trùng.

### Phase 4 — Quy mô crawler + Monitoring + Hoàn thiện  ·  ⭐⭐  ·  ~4 p-w
- Crawler **Mercator đa luồng** (frontier ưu tiên + politeness theo host); Selenium cho trang JS.
- Prometheus + Grafana (QPS, p95); đạt mốc độ trễ.
- **Báo cáo + demo + bảo vệ**.
- **Done:** đạt p95 mục tiêu, dashboard theo dõi, báo cáo đầy đủ.

**Tổng ~21 p-w** ≈ khả thi cho 4 người trong ~12–14 tuần (song song theo chia việc ở
`PHAT_TRIEN_DU_AN.md`).

---

## 6. Rủi ro & giảm thiểu

| Rủi ro | Ảnh hưởng | Giảm thiểu |
|---|---|---|
| OpenSearch + model ngốn RAM (máy SV yếu) | không chạy nổi local | giới hạn heap, chạy 1 node, corpus 100k; model embedding nhỏ; tắt service chưa dùng |
| VnCoreNLP tích hợp vào OpenSearch khó | chậm Phase 0 | bước đầu dùng analyzer tách từ offline (ingest-time), chỉ cần whitespace trong ES |
| Thiếu dữ liệu gán nhãn để đánh giá | khó đo chất lượng | pooling top-k + nhóm tự gán ~30–50 truy vấn; dùng cả tín hiệu click giả lập |
| Embedding tiếng Việt chất lượng chưa cao | hybrid không vượt BM25 | thử 2–3 model, chuẩn hóa văn bản tốt, luôn có rerank; coi BM25 là baseline an toàn |
| Rerank làm chậm p95 | vượt ngưỡng trễ | chỉ rerank top-50, cache, batch; đo và cắt giảm K |
| Phạm vi quá rộng cho 1 kỳ | không kịp | bám **ưu tiên ⭐**: Phase 0–2 là bắt buộc; Phase 3–4 là điểm cộng |
| 4 người đụng code | merge conflict | module hóa + git nhánh + review (xem `PHAT_TRIEN_DU_AN.md` mục E) |

### Tiêu chí "Done" tổng thể của dự án
- `docker compose up` dựng toàn hệ; crawl → ingest → search chạy liền mạch.
- Hybrid + rerank **đo được** cải thiện so BM25 (nDCG@10, MAP) trên test collection.
- Web UI: tìm từ khóa + semantic, facet, did-you-mean, chọn mô hình, highlight, phân trang.
- Dashboard monitoring + báo cáo + bản demo.

---

## 7. Việc cần bạn/nhóm quyết thêm (khi vào chi tiết)
- Model embedding tiếng Việt cụ thể (thử nghiệm ở Phase 2).
- Có làm facet phân loại (Ch 8) ngay Phase 3 hay để điểm cộng cuối.
- Mức dữ liệu thật sự crawl (100k hay 200k) tùy sức máy.
