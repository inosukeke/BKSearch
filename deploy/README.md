# deploy — hạ tầng local (Docker Compose)

Dựng nền cho BKSearch: **OpenSearch** (tìm kiếm + k-NN), **OpenSearch Dashboards**,
**PostgreSQL** (metadata), **Redis** (cache + hàng đợi).

## Chạy
```bash
cd deploy
cp .env.example .env          # lần đầu
docker compose up -d
docker compose ps             # kiểm tra tất cả healthy
```

## Kiểm tra nhanh
```bash
curl http://localhost:9200/_cluster/health   # OpenSearch
# Dashboards: mở http://localhost:5601
```

## Khởi tạo lưu trữ (S0.3)
```bash
# Index OpenSearch `documents` (BM25 + *_seg tách từ + embedding knn_vector 768, idempotent)
bash opensearch/apply-mapping.sh

# Schema PostgreSQL (documents/files/crawl_log). Tự chạy khi volume còn trống;
# volume đã có dữ liệu thì áp thủ công:
docker exec -i bksearch-postgres psql -U bksearch -d bksearch < postgres/schema.sql
```
> Dims vector = **768** (khớp model bi-encoder `vietnamese-bi-encoder` dự kiến ở S2.1).
> Field `embedding` khai báo trước; vector sẽ được ghi khi ingest ở Phase 2.

## Di trú dữ liệu cũ (S0.5)
Đưa corpus SQLite (`java-lucene/data/hust.db`) vào OpenSearch + PostgreSQL (idempotent,
upsert theo `url`, tách từ tiếng Việt khi ingest):
```bash
cd ../java-lucene
mvn -q -DskipTests package
java -jar target/hust-search.jar migrate            # +Postgres
java -jar target/hust-search.jar migrate --no-pg    # chỉ OpenSearch
```
> Chạy lại không nhân đôi (`_id = SHA-256(url)`). Kiểm chứng: `curl localhost:9200/documents/_count`
> cho số không đổi giữa các lần chạy.

## Monitoring (S4.2) — tuỳ chọn
```bash
docker compose --profile monitoring up -d   # thêm Prometheus + Grafana
```
- **Prometheus** `:9090` scrape `/metrics` của Query Service (chạy trên host `:7070`).
- **Grafana** `:3000` (admin/admin) — tự nạp dashboard *"BKSearch — Query Service"* (QPS, p95, in-flight).
- Đo tải: xem `deploy/loadtest/` (k6, mục tiêu G7 p95 < 200ms từ khóa / < 800ms hybrid+rerank).

## Demo nhanh (S4.4)
```bash
docker compose up -d                 # hạ tầng
bash demo-up.sh                      # build jar → migrate → 3 index → pagerank/dedupe/classify
cd ../java-lucene && java -jar target/hust-search.jar serve-api 7070
```

| Dịch vụ | Cổng |
|---|---|
| OpenSearch | 9200 |
| Dashboards | 5601 |
| PostgreSQL | 5432 |
| Redis | 6379 |
| Query Service | 7070 |
| Prometheus (profile monitoring) | 9090 |
| Grafana (profile monitoring) | 3000 |

## Dừng
```bash
docker compose down           # giữ dữ liệu (volume)
docker compose down -v        # xóa cả dữ liệu
```

## Ghi chú
- Đã **tắt security OpenSearch** cho môi trường DEV (http, không auth). KHÔNG dùng cấu hình này ra production.
- Máy yếu RAM: giảm `OPENSEARCH_JAVA_OPTS=-Xms512m -Xmx512m` trong `.env`.
- Nếu OpenSearch không khởi động (lỗi `max_map_count`), trên Linux chạy:
  `sudo sysctl -w vm.max_map_count=262144`. Docker Desktop (Windows/macOS) thường đã đủ.
