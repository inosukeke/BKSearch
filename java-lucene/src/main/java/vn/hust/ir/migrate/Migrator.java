package vn.hust.ir.migrate;

import com.fasterxml.jackson.databind.node.ObjectNode;
import vn.hust.ir.nlp.VietnameseAnalyzer;
import vn.hust.ir.store.Db;
import vn.hust.ir.store.Document;

import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Di trú corpus từ SQLite (bản cũ) sang OpenSearch + PostgreSQL (S0.5).
 *
 * <ul>
 *   <li>Idempotent: _id = SHA-256(url) nên chạy lại chỉ ghi đè, không nhân đôi.</li>
 *   <li>Bulk theo lô (mặc định 500).</li>
 *   <li>Tách từ title/content bằng {@link VietnameseAnalyzer} (nhất quán với query — G3).</li>
 *   <li>Ghi metadata Postgres là best-effort: lỗi kết nối chỉ cảnh báo, không chặn index.</li>
 * </ul>
 */
public class Migrator {

    private final String dbPath;
    private final String osUrl;
    private final String index;
    private final int batchSize;
    private final boolean writePg;

    private final VietnameseAnalyzer analyzer = VietnameseAnalyzer.get();

    public Migrator(String dbPath, String osUrl, String index, int batchSize, boolean writePg) {
        this.dbPath = dbPath;
        this.osUrl = osUrl;
        this.index = index;
        this.batchSize = Math.max(1, batchSize);
        this.writePg = writePg;
    }

    public void run() throws Exception {
        OpenSearchClient os = new OpenSearchClient(osUrl);
        if (!os.indexExists(index)) {
            throw new IllegalStateException("Index '" + index + "' chưa tồn tại. Hãy chạy "
                    + "deploy/opensearch/apply-mapping.sh trước.");
        }

        List<Document> docs;
        try (Db db = new Db(dbPath)) {
            docs = db.allDocuments();
        }
        // Hợp lệ = có url không rỗng.
        List<Document> valid = new ArrayList<>();
        for (Document d : docs) if (d.url != null && !d.url.isBlank()) valid.add(d);
        System.out.printf("Đọc SQLite: %d bài (hợp lệ %d).%n", docs.size(), valid.size());

        PostgresMetaStore pg = null;
        if (writePg) {
            try {
                pg = new PostgresMetaStore();
            } catch (Exception e) {
                System.err.println("[CẢNH BÁO] Không kết nối được PostgreSQL, bỏ qua ghi metadata: " + e.getMessage());
            }
        }

        int totalOk = 0, totalFailed = 0, pgOk = 0;
        List<OpenSearchClient.Item> batch = new ArrayList<>(batchSize);
        for (int i = 0; i < valid.size(); i++) {
            Document d = valid.get(i);
            Instant crawled = parseInstant(d.crawledAt);
            batch.add(new OpenSearchClient.Item(sha256(d.url), toSource(os, d, crawled)));

            if (pg != null) {
                try { pg.upsert(d, crawled); pgOk++; }
                catch (Exception e) { System.err.println("[PG] lỗi upsert " + d.url + ": " + e.getMessage()); }
            }

            if (batch.size() >= batchSize || i == valid.size() - 1) {
                OpenSearchClient.BulkResult r = os.bulk(index, batch);
                totalOk += r.ok();
                totalFailed += r.failed();
                System.out.printf("  bulk %d doc → ok=%d failed=%d%s%n",
                        batch.size(), r.ok(), r.failed(),
                        r.firstError() != null ? (" | lỗi đầu: " + r.firstError()) : "");
                batch.clear();
            }
        }

        os.refresh(index);
        long osCount = os.count(index);
        System.out.printf("%nXong di trú: index ok=%d failed=%d; OpenSearch count=%d%n",
                totalOk, totalFailed, osCount);
        if (pg != null) {
            System.out.printf("PostgreSQL documents count=%d (upsert ok=%d)%n", pg.count(), pgOk);
            pg.close();
        }
    }

    private ObjectNode toSource(OpenSearchClient os, Document d, Instant crawled) {
        ObjectNode s = os.mapper().createObjectNode();
        s.put("url", d.url);
        s.put("title", nz(d.title));
        s.put("content", nz(d.content));
        s.put("title_seg", analyzer.segment(nz(d.title)));
        s.put("content_seg", analyzer.segment(nz(d.content)));
        s.put("doc_type", d.docType != null ? d.docType : "html");
        if (d.subdomain != null) s.put("subdomain", d.subdomain);
        s.put("lang", "vi");
        if (d.contentHash != null) s.put("content_hash", d.contentHash);
        if (crawled != null) s.put("crawled_at", crawled.toEpochMilli());
        Instant pub = parseInstant(d.publishedAt);
        if (pub != null) s.put("published_at", pub.toEpochMilli());
        return s;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /** Parse linh hoạt: ISO instant (có nano + Z) hoặc yyyy-MM-dd; lỗi → null. */
    static Instant parseInstant(String v) {
        if (v == null || v.isBlank()) return null;
        try { return Instant.parse(v.trim()); } catch (Exception ignore) {}
        try { return LocalDate.parse(v.trim()).atStartOfDay().toInstant(ZoneOffset.UTC); } catch (Exception ignore) {}
        return null;
    }

    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
