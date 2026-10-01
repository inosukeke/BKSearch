package vn.hust.ir.migrate;

import vn.hust.ir.store.Document;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * Ghi metadata tài liệu vào PostgreSQL khi di trú (S0.5). Upsert theo `url` (idempotent).
 * Tham số kết nối lấy từ biến môi trường, mặc định khớp deploy/docker-compose.
 */
public class PostgresMetaStore implements AutoCloseable {

    private final Connection conn;

    public PostgresMetaStore() throws Exception {
        // pgjdbc gửi tên timezone mặc định của JVM cho server; trên Windows tên này có thể là
        // "Asia/Saigon" — Postgres không nhận. Ép về UTC để kết nối ổn định (ta dùng epoch nên không lệch).
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        String url  = env("PG_URL",  "jdbc:postgresql://localhost:5432/bksearch");
        String user = env("PG_USER", "bksearch");
        String pass = env("PG_PASSWORD", "bksearch_dev");
        this.conn = DriverManager.getConnection(url, user, pass);
        this.conn.setAutoCommit(false); // commit theo lô để giảm round-trip (F7)
    }

    /** Commit các upsert đã gom từ lần commit trước. */
    public void commit() throws Exception { conn.commit(); }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return (v == null || v.isBlank()) ? def : v;
    }

    /** Upsert 1 document theo url (idempotent). Cần gọi {@link #commit()} để lưu. */
    public void upsert(Document d, Instant crawledAt) throws Exception {
        String sql = """
            INSERT INTO documents (url, title, subdomain, doc_type, lang, content_hash, crawled_at, indexed_at)
            VALUES (?, ?, ?, ?, 'vi', ?, ?, now())
            ON CONFLICT (url) DO UPDATE SET
                title        = EXCLUDED.title,
                subdomain    = EXCLUDED.subdomain,
                doc_type     = EXCLUDED.doc_type,
                content_hash = EXCLUDED.content_hash,
                crawled_at   = EXCLUDED.crawled_at,
                indexed_at   = now(),
                updated_at   = now()
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, d.url);
            ps.setString(2, d.title);
            ps.setString(3, d.subdomain);
            ps.setString(4, d.docType);
            ps.setString(5, d.contentHash);
            if (crawledAt != null) ps.setTimestamp(6, Timestamp.from(crawledAt));
            else ps.setNull(6, java.sql.Types.TIMESTAMP);
            ps.executeUpdate();
        }
    }

    public long count() throws Exception {
        try (var st = conn.createStatement(); var rs = st.executeQuery("SELECT COUNT(*) FROM documents")) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    @Override public void close() throws Exception { conn.close(); }
}
