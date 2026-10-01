# eval

Bộ công cụ **đánh giá chất lượng tìm kiếm**: test collection (truy vấn + nhãn phù hợp) và
script tính **Precision@k, Recall@k, F1, MAP, MRR, nDCG@k** để so sánh các mô hình xếp hạng.

> ⏳ Chưa triển khai — thuộc **Phase 1** của lộ trình (`docs/KE_HOACH_TRIEN_KHAI.md`, step
> S1.7). Thư mục này giữ chỗ cho cấu trúc đa module.

Dự kiến:
- `queries/`  — danh sách truy vấn thử.
- `qrels/`    — nhãn phù hợp (định dạng kiểu TREC).
- script tính chỉ số + xuất bảng/biểu đồ.
