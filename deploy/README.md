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

| Dịch vụ | Cổng |
|---|---|
| OpenSearch | 9200 |
| Dashboards | 5601 |
| PostgreSQL | 5432 |
| Redis | 6379 |

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
