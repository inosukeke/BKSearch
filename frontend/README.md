# BKSearch UI

Giao diện tìm kiếm (SPA) cho Query Service — **Vite + React + TypeScript + Tailwind + shadcn/ui +
Framer Motion**. Phong cách tối giản (Google/Perplexity), hỗ trợ dark mode. Xem `DESIGN.md`.

## Chạy

```bash
cd frontend
npm install
npm run dev          # http://localhost:5173  (proxy /api → http://localhost:7070)
```

Backend: chạy Query Service trước — `cd ../java-lucene && java -jar target/hust-search.jar serve-api 7070`.
Nếu backend chưa chạy, UI **tự fallback sang dữ liệu mock** để demo vẫn mượt (hoặc đặt `VITE_USE_MOCK=1`).

## Build

```bash
npm run build        # dist/ (static) — có thể serve bằng bất kỳ web server nào
npm run preview
```

## Cấu hình (`.env`, xem `.env.example`)
- `VITE_API_BASE` — base API (để trống = dùng dev proxy).
- `VITE_API_TARGET` — đích proxy khi dev (mặc định `http://localhost:7070`).
- `VITE_USE_MOCK=1` — ép dùng mock data.

## Tính năng
- Ô search lớn (hero) + chip gợi ý; chọn ranker (BM25/VSM/LM/Vector/Hybrid) + rerank.
- Kết quả: tiêu đề, URL, snippet **highlight từ khóa**, badge score/score_type/category; meta số kết quả + thời gian.
- Facet lọc (danh mục/loại tài liệu/tên miền), did-you-mean, "Tải thêm".
- Trạng thái: **loading (skeleton)**, **empty**, **error (thử lại)**, normal.
- Animation nhẹ (fade-in stagger, focus ring, hover) — tôn trọng `prefers-reduced-motion`.
