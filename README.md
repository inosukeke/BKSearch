# BKSearch

Hệ thống thu thập và tìm kiếm bài viết, tài liệu từ **hust.edu.vn** và các miền con
(`*.hust.edu.vn`). Thu thập cả trang HTML lẫn tài liệu (PDF/DOC/XLS...), lập chỉ mục toàn
văn và cho phép tìm kiếm tiếng Việt với xếp hạng theo độ liên quan.

## Tính năng

- 🕷️ **Thu thập** trang HTML và link tài liệu từ `*.hust.edu.vn`, tuân thủ `robots.txt`.
- 📄 **Bóc tách văn bản** từ PDF/DOC/DOCX/XLS bằng Apache Tika để tìm được nội dung bên trong tài liệu.
- 🇻🇳 **Xử lý tiếng Việt**: chuẩn hóa Unicode và tách từ (Maximum Matching) trước khi lập chỉ mục.
- 🔎 **Tìm kiếm toàn văn** bằng Apache Lucene, xếp hạng **BM25**, có highlight và phân trang.
- 🔁 **Phát hiện nội dung mới** (so sánh hash SHA-256) và **cập nhật định kỳ**.
- 🌐 **Giao diện web** tìm kiếm đơn giản (nền sáng).

## Công nghệ

Java 21 · Apache Lucene · Apache Tika · Jsoup · SQLite · Maven

## Bắt đầu nhanh

Yêu cầu: **JDK 21**, **Maven 3.9+**.

```bash
cd java-lucene
mvn package                                     # đóng gói -> target/hust-search.jar

java -jar target/hust-search.jar crawl 500 3    # thu thập (số trang, độ sâu)
java -jar target/hust-search.jar index 40       # lập chỉ mục + bóc 40 tài liệu qua Tika
java -jar target/hust-search.jar serve 8080     # web UI: http://localhost:8080
```

Các lệnh khác: `initdb`, `search <từ khóa>`, `schedule <phút>`.

## Cấu trúc

```
BKSearch/
├── java-lucene/              # Mã nguồn chính (Java)
│   └── src/main/java/vn/hust/ir/
│       ├── crawler/          # thu thập (Jsoup) + robots.txt
│       ├── extract/          # bóc tách tài liệu (Tika)
│       ├── nlp/              # tách từ tiếng Việt
│       ├── index/            # lập chỉ mục Lucene
│       ├── search/           # tìm kiếm BM25 + web UI
│       ├── store/            # lưu trữ (SQLite)
│       └── schedule/         # cập nhật định kỳ
├── deploy/                   # Docker Compose: OpenSearch, Postgres, Redis (bản nâng cấp)
├── embedding-service/        # dịch vụ embedding tiếng Việt (bản nâng cấp)
├── eval/                     # bộ đánh giá chất lượng tìm kiếm
└── docs/                     # tài liệu thiết kế & lộ trình phát triển
```

## Giấy phép

Dự án học tập — môn Tìm kiếm thông tin, Đại học Bách khoa Hà Nội.
