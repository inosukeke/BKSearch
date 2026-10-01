# HUST Search — bản Java (Lucene + Tika)

Bản viết lại toàn phần bằng Java. Kế hoạch chi tiết: [`KE_HOACH_JAVA.md`](KE_HOACH_JAVA.md).

## Yêu cầu
- JDK 21, Maven 3.9+

## Biên dịch
```bash
mvn compile              # biên dịch
mvn package              # đóng gói fat-jar -> target/hust-search.jar
```

## Chạy
```bash
# Qua Maven (dev)
mvn -q exec:java -Dexec.args="initdb"

# Hoặc qua jar đã đóng gói
java -jar target/hust-search.jar initdb
```

## Lệnh
| Lệnh | Chức năng | Trạng thái |
|---|---|---|
| `initdb` | Tạo SQLite `data/hust.db` + bảng documents/files | ✅ |
| `crawl [maxPages] [maxDepth]` | Thu thập dữ liệu (Jsoup + robots.txt) | ✅ |
| `index [maxFiles]` | Đánh chỉ mục Lucene (+ Tika bóc tài liệu) | ✅ |
| `search <từ khóa>` | Tìm kiếm BM25 ở dòng lệnh | ✅ |
| `serve [port]` | Web UI tìm kiếm (nền sáng), mặc định 8080 | ✅ |
| `schedule [phút] [maxPages]` | Chạy định kỳ (crawl + index) | ✅ |

## Web UI
```bash
java -jar target/hust-search.jar serve 8080
# mở http://localhost:8080 → gõ từ khóa tiếng Việt → kết quả xếp hạng BM25
```

## Cấu trúc
```
src/main/java/vn/hust/ir/
├── App.java            # CLI
└── store/
    ├── Db.java         # SQLite: upsert + phát hiện new/updated
    └── Document.java   # model
data/hust.db            # sinh ra sau khi initdb
```
