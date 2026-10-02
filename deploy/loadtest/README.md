# Load test (S4.3) — đo p95 theo mục tiêu G7

Hai kịch bản k6 bắn tải vào Query Service để kiểm chứng mục tiêu hiệu năng **G7**:

| Kịch bản | Script | Mục tiêu p95 | QPS mặc định |
|---|---|---|---|
| Từ khóa (BM25/VSM) | `keyword.js` | **< 200 ms** | 30 |
| Hybrid + rerank | `hybrid.js` | **< 800 ms** | 20 |

## Chuẩn bị

1. Cài [k6](https://k6.io/docs/get-started/installation/) (`brew install k6` / `apt install k6` / Docker).
2. Dựng stack + nạp dữ liệu (xem `deploy/README.md`): OpenSearch có index, Query Service chạy ở `:7070`.
   - Kịch bản hybrid cần Embedding Service (`EMBED_URL`) đang chạy.

## Chạy

```bash
# Từ khóa — 30 QPS trong 1 phút
k6 run -e BASE=http://localhost:7070 deploy/loadtest/keyword.js

# Đẩy lên 50 QPS, 2 phút
k6 run -e BASE=http://localhost:7070 -e RATE=50 -e DURATION=2m deploy/loadtest/keyword.js

# Hybrid + rerank — 20 QPS
k6 run -e BASE=http://localhost:7070 deploy/loadtest/hybrid.js

# Hybrid KHÔNG rerank (so sánh)
k6 run -e BASE=http://localhost:7070 -e RERANK=0 deploy/loadtest/hybrid.js
```

Dùng Docker thay vì cài k6:

```bash
docker run --rm -i --network host -v "$PWD/deploy/loadtest:/t" \
  grafana/k6 run -e BASE=http://localhost:7070 /t/keyword.js
```

## Đọc kết quả

- k6 in `http_req_duration` với `p(95)`; **ngưỡng pass/fail** đã nhúng trong mỗi script
  (k6 thoát mã ≠ 0 nếu p95 vượt ngưỡng G7).
- Bật `deploy --profile monitoring` để xem **Grafana** (QPS, p95 theo thời gian thực) khi bắn tải —
  dashboard "BKSearch — Query Service".

## Ghi chú trung thực

- p95 **phụ thuộc phần cứng**. Rerank cross-encoder trên CPU rất chậm (xem bảng eval Phase 2);
  nếu không có GPU, hạ `RERANK_TOP_K`/`RERANK_MAX_LENGTH` hoặc tắt rerank để đạt ngưỡng hybrid.
- Dữ liệu đo thực tế (số máy, corpus, QPS, p95 đạt được) ghi vào `docs/BAO_CAO.md` mục Load test
  sau khi chạy ở phiên LOCAL.
