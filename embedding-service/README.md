# embedding-service

Dịch vụ **embedding tiếng Việt** (bi-encoder) + **rerank** (cross-encoder) cho tìm kiếm ngữ
nghĩa — Python FastAPI (Phase 2, S2.1 + S2.5).

## Endpoints
| Method | Path | Mô tả |
|---|---|---|
| GET  | `/healthz` | Trạng thái + cấu hình (không tải model). |
| POST | `/embed`   | `{"texts":[...]}` → `{"vectors":[[...768...]], "dims":768, "model":..., "count":N}`. Hỗ trợ batch. |
| POST | `/rerank`  | `{"query":..., "documents":[...], "top_k":N?}` → `{"results":[{"index","score"}...]}` (giảm dần). |

## Model
- **Bi-encoder** (mặc định): `bkai-foundation-models/vietnamese-bi-encoder` — **768 dims**,
  KHỚP mapping OpenSearch (`documents.embedding` = `knn_vector` dim 768).
  > ⚠️ Model dựa trên **PhoBERT** → input NÊN là **văn bản đã tách từ underscore** (vd
  > `đại_học`), giống field `*_seg`. Phía Java gửi `title_seg`/`content_seg` (ingest) và tách
  > từ truy vấn trước khi gọi (query) → nhất quán G3 cho cả vector.
- **Cross-encoder** (mặc định): `cross-encoder/mmarco-mMiniLMv2-L12-H384-v1` (đa ngữ).
  Rerank dùng văn bản **thô** (title + content), KHÔNG cần tách từ.

Cấu hình qua env: `EMBED_MODEL`, `EMBED_DIMS`, `EMBED_BATCH`, `EMBED_NORMALIZE`,
`RERANK_MODEL`, `RERANK_BATCH`, `RERANK_CACHE_SIZE`, `EMBED_FAKE`, `MAX_INPUT_CHARS`.

## Chạy local
```bash
cd embedding-service
pip install -r requirements.txt
# Thật (tải model lần đầu, cần mạng):
uvicorn app.main:app --port 8000
# Giả (không tải model — smoke nhanh):
EMBED_FAKE=1 uvicorn app.main:app --port 8000
```
Hoặc qua Docker Compose (đã thêm service `embedding`):
```bash
cd deploy && docker compose -p bksearch up -d embedding
```

## Test
```bash
cd embedding-service
python -m pytest -m "not needs_model"     # CI/cloud — chế độ GIẢ, không cần model
EMBED_FAKE=0 pytest tests/test_semantic.py -m needs_model -v   # LOCAL — PASS S2.1 với model thật
EMBED_FAKE=0 python scripts/check_semantic.py                  # minh họa cosine gần>xa
```

## Chế độ GIẢ (`EMBED_FAKE=1`)
Trả vector deterministic (hash) + điểm rerank theo chồng lấp token. Dùng cho CI/cloud và
smoke-test khi không tải được model lớn. **Không** dùng cho đo chất lượng.
