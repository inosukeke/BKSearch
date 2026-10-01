# embedding-service

Dịch vụ sinh **embedding tiếng Việt** (bi-encoder) + **rerank** (cross-encoder) cho tìm
kiếm ngữ nghĩa, dùng Python FastAPI.

> ⏳ Chưa triển khai — thuộc **Phase 2** của lộ trình (`docs/KE_HOACH_TRIEN_KHAI.md`, step
> S2.1/S2.5). Thư mục này giữ chỗ cho cấu trúc đa module.

Dự kiến:
- `POST /embed`  — nhận danh sách văn bản → trả vector (dùng model như `bkai-foundation-models/vietnamese-bi-encoder`).
- `POST /rerank` — chấm lại top-K bằng cross-encoder.
