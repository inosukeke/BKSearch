# eval — Bộ đánh giá chất lượng tìm kiếm (S1.7)

Đo **Precision@k, Recall@k, F1@k, MAP, MRR, nDCG@k** để so sánh 3 mô hình xếp hạng
**BM25 / VSM / LM** trên một test collection tự xây.

## Cấu trúc
- `queries/queries.tsv` — danh sách truy vấn, định dạng `<qid>\t<câu truy vấn>` (~35 truy vấn).
- `qrels/qrels.txt` — nhãn phù hợp theo **định dạng TREC**: `<qid> 0 <docid=url> <rel>`
  (`rel`: 0 không phù hợp, 1 phù hợp, 2 rất phù hợp — hỗ trợ nDCG có mức độ).
- `run-eval.sh` — script chạy đánh giá (gọi `java -jar ... eval-run`).
- `results/eval-report.txt` — bảng kết quả (sinh ra khi chạy).

## Cách chạy (LOCAL, cần OpenSearch + corpus)
```bash
# 1) Hạ tầng + index + dữ liệu
cd deploy && docker compose up -d && cd ..
bash deploy/opensearch/apply-mapping.sh
(cd java-lucene && mvn -q -DskipTests package && java -jar target/hust-search.jar migrate)

# 2) Dựng 2 index song song cho VSM/LM (để so cả 3 ranker)
bash deploy/opensearch/create-ranker-indices.sh

# 3) Chạy đánh giá (k=10)
bash eval/run-eval.sh http://localhost:9200 documents 10
```
Kết quả in ra bảng so sánh và ghi `eval/results/eval-report.txt`.

> Lưu ý: `eval-run` chạy BM25 trên `documents`, VSM trên `documents_vsm`, LM trên `documents_lm`
> (xem `vn.hust.ir.query.Ranker`). Nếu chưa tạo 2 index song song, chỉ dòng BM25 có số liệu,
> VSM/LM sẽ bị bỏ qua (cảnh báo) — vẫn không lỗi.

## Tạo qrels đầy đủ (pooling)
`qrels/qrels.txt` hiện là **mẫu** để minh họa định dạng + chạy thử. Qrels thật nên tạo bằng
**pooling**: với mỗi truy vấn, gộp top-k kết quả của cả 3 ranker, loại trùng, rồi gán nhãn
thủ công theo mức 0/1/2. `docid` dùng **URL** để khớp với kết quả trả về của engine.

## Phần đã kiểm thử tự động (không cần OpenSearch)
Các hàm độ đo (`vn.hust.ir.eval.Metrics`) được test bằng dữ liệu giả trong
`MetricsTest` (P@k, R@k, F1, MAP, MRR, nDCG) — chạy `mvn test`.
