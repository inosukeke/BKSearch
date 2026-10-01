package vn.hust.ir.store;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;

/**
 * Lưu trữ metadata bằng SQLite (cùng schema với bản Python để dễ so sánh).
 * Hai bảng: documents (bài viết) và files (URL tài liệu PDF/DOC...).
 */
public class Db implements AutoCloseable {

    private final Connection conn;

    public Db(String dbPath) throws SQLException {
        this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        initSchema();
    }

    private void initSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS documents (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    url          TEXT UNIQUE,
                    title        TEXT,
                    content      TEXT,
                    content_hash TEXT,
                    doc_type     TEXT,
                    subdomain    TEXT,
                    published_at TEXT,
                    crawled_at   TEXT,
                    updated_at   TEXT,
                    status       TEXT
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS files (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    file_url      TEXT UNIQUE,
                    file_type     TEXT,
                    page_url      TEXT,
                    subdomain     TEXT,
                    discovered_at TEXT
                )""");
        }
    }

    /**
     * Chèn hoặc cập nhật một bài viết, trả về trạng thái: "new" | "updated" | "unchanged".
     * Đây là cơ chế phát hiện nội dung mới (so sánh content_hash).
     */
    public String upsertDocument(Document d) throws SQLException {
        String now = Instant.now().toString();
        String oldHash = null;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT content_hash FROM documents WHERE url = ?")) {
            ps.setString(1, d.url);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) oldHash = rs.getString(1);
            }
        }

        if (oldHash == null) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO documents (url,title,content,content_hash,doc_type,"
                    + "subdomain,published_at,crawled_at,updated_at,status) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, d.url);
                ps.setString(2, d.title);
                ps.setString(3, d.content);
                ps.setString(4, d.contentHash);
                ps.setString(5, d.docType);
                ps.setString(6, d.subdomain);
                ps.setString(7, d.publishedAt);
                ps.setString(8, d.crawledAt != null ? d.crawledAt : now);
                ps.setString(9, now);
                ps.setString(10, "new");
                ps.executeUpdate();
            }
            return "new";
        } else if (!oldHash.equals(d.contentHash)) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE documents SET title=?, content=?, content_hash=?, "
                    + "updated_at=?, status=? WHERE url=?")) {
                ps.setString(1, d.title);
                ps.setString(2, d.content);
                ps.setString(3, d.contentHash);
                ps.setString(4, now);
                ps.setString(5, "updated");
                ps.setString(6, d.url);
                ps.executeUpdate();
            }
            return "updated";
        } else {
            return "unchanged";
        }
    }

    /** Lưu URL một tài liệu (không tải file). Bỏ qua nếu URL đã có. */
    public boolean insertFile(String fileUrl, String fileType, String pageUrl, String subdomain)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO files (file_url,file_type,page_url,subdomain,discovered_at) "
                + "VALUES (?,?,?,?,?)")) {
            ps.setString(1, fileUrl);
            ps.setString(2, fileType);
            ps.setString(3, pageUrl);
            ps.setString(4, subdomain);
            ps.setString(5, Instant.now().toString());
            return ps.executeUpdate() > 0;
        }
    }

    /** Lấy toàn bộ bài viết để đánh chỉ mục. */
    public java.util.List<Document> allDocuments() throws SQLException {
        java.util.List<Document> out = new java.util.ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT url,title,content,content_hash,doc_type,subdomain,"
                 + "published_at,crawled_at FROM documents")) {
            while (rs.next()) {
                out.add(new Document(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getString(8)));
            }
        }
        return out;
    }

    /** Lấy tối đa `limit` URL tài liệu để Tika bóc tách (0 = không giới hạn). */
    public java.util.List<String[]> someFiles(int limit) throws SQLException {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        String sql = "SELECT file_url,file_type,page_url,subdomain FROM files"
                + (limit > 0 ? " LIMIT " + limit : "");
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(new String[]{rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getString(4)});
            }
        }
        return out;
    }

    public int countDocuments() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM documents")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public int countFiles() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM files")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }
}
