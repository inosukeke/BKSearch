# API — Query Service (Javalin) · Phase 1

Hợp đồng REST của dịch vụ truy vấn BKSearch (S1.1). Phiên bản: **v1**.

Chạy:
```bash
cd java-lucene && mvn -q -DskipTests package
java -jar target/hust-search.jar serve-api 7070 http://localhost:9200 documents
# mặc định: port=7070, OPENSEARCH_URL hoặc http://localhost:9200, index=documents
```

Mọi phản hồi là `application/json; charset=utf-8`. Tiếng Việt trả UTF-8.

---

## GET /healthz
Kiểm tra sống.
```json
200 OK
{ "status": "ok", "service": "bksearch-query", "index": "documents" }
```

## GET /api/search
Tìm kiếm có phân trang + chọn mô hình xếp hạng + highlight.

**Tham số**
| tên | mặc định | mô tả |
|-----|----------|------|
| `q` | — | chuỗi truy vấn (hỗ trợ `"cụm"`, `"cụm"~N`, `AND`/`OR`/`NOT`, ngoặc) |
| `page` | 1 | trang (1-based) |
| `size` | 10 | số kết quả/trang |
| `ranker` | `bm25` | `bm25` \| `vsm` \| `lm` |

**Phản hồi 200**
```json
{
  "query": "tuyển sinh",
  "ranker": "bm25",
  "segmented_query": "tuyển_sinh",
  "total": 42,
  "page": 1,
  "page_size": 10,
  "total_pages": 5,
  "took_ms": 12,
  "suggestion": null,
  "results": [
    {
      "url": "https://hust.edu.vn/...",
      "title": "Thông báo tuyển sinh ...",
      "snippet": "... <em>tuyển sinh</em> đại học ...",
      "score": 12.34,
      "doc_type": "html",
      "subdomain": "hust.edu.vn"
    }
  ]
}
```
- `q` rỗng → trả khung rỗng (`total=0`, `results=[]`) với 200.
- `took_ms`: độ trễ đo phía service (phục vụ ngân sách G7).
- `suggestion`: gợi ý did-you-mean (hoặc `null`).

**Lỗi**
| mã | khi nào | thân |
|----|---------|------|
| 400 | `ranker` sai, hoặc cú pháp truy vấn sai (nháy/ngoặc/toán tử) | `{"error": "..."}` |
| 502 | OpenSearch không truy cập được | `{"error": "..."}` |

## GET /api/suggest
Did-you-mean (sửa lỗi chính tả) độc lập.

**Tham số:** `q` (chuỗi).
```json
200 OK
{ "query": "tuyen sih", "suggestion": "tuyển sinh" }
```
`suggestion` là `null` nếu truy vấn đã đúng / không đủ tự tin để gợi ý.

## GET /
Web UI (S1.6): ô tìm kiếm, chọn ranker, hiển thị did-you-mean, highlight, phân trang,
khu vực facet (placeholder).

---

## Ghi chú thiết kế
- **Tách từ nhất quán (G3):** truy vấn đi qua `VietnameseAnalyzer.get().segment(...)` giống lúc
  ingest; khớp trên field `title_seg^2` + `content_seg` (analyzer `vi_seg`).
- **Đa ranker (S1.3):** BM25 dùng index `documents`; VSM/LM dùng index song song
  `documents_vsm` (ClassicSimilarity) / `documents_lm` (LMDirichlet) — tạo bằng
  `deploy/opensearch/create-ranker-indices.sh` (dùng `_reindex`, không reindex lại từ nguồn).
