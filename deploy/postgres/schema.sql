-- Schema PostgreSQL cho BKSearch (S0.3). Idempotent (IF NOT EXISTS).
-- Áp thủ công:  docker exec -i bksearch-postgres psql -U bksearch -d bksearch < deploy/postgres/schema.sql
-- Hoặc tự chạy khi volume Postgres còn trống (mount vào /docker-entrypoint-initdb.d).

-- documents: metadata chính của mỗi trang; `url` là khóa định danh để upsert chống trùng.
CREATE TABLE IF NOT EXISTS documents (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  url           TEXT        NOT NULL UNIQUE,
  title         TEXT,
  subdomain     TEXT,
  doc_type      TEXT,                       -- html | pdf | doc | ...
  lang          TEXT        DEFAULT 'vi',
  content_hash  CHAR(64),                   -- SHA-256 để phát hiện new/updated
  pagerank      DOUBLE PRECISION,
  published_at  TIMESTAMPTZ,
  crawled_at    TIMESTAMPTZ DEFAULT now(),
  indexed_at    TIMESTAMPTZ,
  updated_at    TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_documents_subdomain    ON documents (subdomain);
CREATE INDEX IF NOT EXISTS idx_documents_doc_type     ON documents (doc_type);
CREATE INDEX IF NOT EXISTS idx_documents_content_hash ON documents (content_hash);

-- files: tài liệu đính kèm (PDF/DOC/XLS...) được bóc tách qua Tika.
CREATE TABLE IF NOT EXISTS files (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  document_id   BIGINT      REFERENCES documents (id) ON DELETE CASCADE,
  file_url      TEXT        NOT NULL UNIQUE,
  file_type     TEXT,                       -- mime/phần mở rộng
  byte_size     BIGINT,
  content_hash  CHAR(64),
  extracted     BOOLEAN     DEFAULT FALSE,  -- đã bóc text bằng Tika chưa
  created_at    TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_files_document_id ON files (document_id);

-- crawl_log: nhật ký thu thập (phục vụ chẩn đoán + politeness + freshness).
CREATE TABLE IF NOT EXISTS crawl_log (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  url           TEXT        NOT NULL,
  status        TEXT,                       -- fetched | skipped | error
  http_status   INT,
  depth         INT,
  note          TEXT,
  crawled_at    TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_crawl_log_url        ON crawl_log (url);
CREATE INDEX IF NOT EXISTS idx_crawl_log_crawled_at ON crawl_log (crawled_at);
