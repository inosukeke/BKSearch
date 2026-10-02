package vn.hust.ir.migrate;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import vn.hust.ir.embed.EmbeddingClient;
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

    // Phase 2 (S2.2): sinh embedding khi ingest.
    private final boolean embed;
    private final String embedUrl;
    private final int embedBatch;
    /** Cắt ngắn văn bản đưa đi embed (PhoBERT ~256 token; tránh payload quá lớn). */
    private static final int EMBED_MAX_CHARS = 2000;

    private final VietnameseAnalyzer analyzer = VietnameseAnalyzer.get();

    public Migrator(String dbPath, String osUrl, String index, int batchSize, boolean writePg) {
        this(dbPath, osUrl, index, batchSize, writePg, false, null, 32);
    }

    public Migrator(String dbPath, String osUrl, String index, int batchSize, boolean writePg,
                    boolean embed, String embedUrl, int embedBatch) {
        this.dbPath = dbPath;
        this.osUrl = osUrl;
        this.index = index;
        this.batchSize = Math.max(1, batchSize);
        this.writePg = writePg;
        this.embed = embed;
        this.embedUrl = embedUrl;
        this.embedBatch = Math.max(1, embedBatch);
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

        EmbeddingClient ec = null;
        if (embed) {
            ec = new EmbeddingClient(embedUrl);
            System.out.println("Bật sinh embedding khi ingest (EMBED_URL=" + embedUrl
                    + ", embedBatch=" + embedBatch + ").");
        }

        int totalOk = 0, totalFailed = 0, pgOk = 0, embeddedOk = 0;
        List<OpenSearchClient.Item> batch = new ArrayList<>(batchSize);
        List<String> embedInputs = new ArrayList<>(batchSize);
        for (int i = 0; i < valid.size(); i++) {
            Document d = valid.get(i);
            Instant crawled = parseInstant(d.crawledAt);
            ObjectNode source = toSource(os, d, crawled);
            batch.add(new OpenSearchClient.Item(sha256(d.url), source));
            if (ec != null) embedInputs.add(embedText(source));

            if (pg != null) {
                try { pg.upsert(d, crawled); pgOk++; }
                catch (Exception e) { System.err.println("[PG] lỗi upsert " + d.url + ": " + e.getMessage()); }
            }

            if (batch.size() >= batchSize || i == valid.size() - 1) {
                // Gắn embedding trước khi bulk; lỗi 1 lô con chỉ bỏ vector lô đó, KHÔNG chặn index.
                if (ec != null) embeddedOk += attachEmbeddings(ec, batch, embedInputs);
                OpenSearchClient.BulkResult r = os.bulk(index, batch);
                totalOk += r.ok();
                totalFailed += r.failed();
                System.out.printf("  bulk %d doc → ok=%d failed=%d%s%n",
                        batch.size(), r.ok(), r.failed(),
                        r.firstError() != null ? (" | lỗi đầu: " + r.firstError()) : "");
                batch.clear();
                embedInputs.clear();
                if (pg != null) pg.commit(); // commit theo lô (giảm round-trip, F7)
            }
        }

        os.refresh(index);
        long osCount = os.count(index);
        System.out.printf("%nXong di trú: index ok=%d failed=%d; OpenSearch count=%d%n",
                totalOk, totalFailed, osCount);
        if (ec != null) {
            System.out.printf("Embedding: %d/%d doc có vector (%.1f%%).%n",
                    embeddedOk, totalOk, totalOk == 0 ? 0.0 : 100.0 * embeddedOk / totalOk);
        }
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
        // Nhóm near-duplicate (S3.2): mặc định mỗi doc tự thành nhóm (= url); lệnh `dedupe` sẽ
        // ghi đè canonical cho các bản trùng. Luôn có field → collapse an toàn.
        s.put("dup_group", d.url);
        s.put("lang", "vi");
        if (d.contentHash != null) s.put("content_hash", d.contentHash);
        if (crawled != null) s.put("crawled_at", crawled.toEpochMilli());
        Instant pub = parseInstant(d.publishedAt);
        if (pub != null) s.put("published_at", pub.toEpochMilli());
        s.put("indexed_at", Instant.now().toEpochMilli());
        return s;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /**
     * Văn bản đưa đi embed: {@code title_seg + " " + content_seg} (ĐÃ tách từ underscore — G3,
     * khớp input PhoBERT của bi-encoder), cắt ngắn {@value #EMBED_MAX_CHARS} ký tự.
     */
    private static String embedText(ObjectNode source) {
        String t = source.path("title_seg").asText("");
        String c = source.path("content_seg").asText("");
        String joined = t.isEmpty() ? c : (c.isEmpty() ? t : t + " " + c);
        return joined.length() > EMBED_MAX_CHARS ? joined.substring(0, EMBED_MAX_CHARS) : joined;
    }

    /**
     * Gọi Embedding Service theo lô con ({@code embedBatch}) và gắn field {@code embedding}
     * vào từng source. Lỗi một lô con → cảnh báo + bỏ qua vector lô đó (KHÔNG chặn cả lô bulk).
     * @return số doc đã gắn được vector trong lô này.
     */
    private int attachEmbeddings(EmbeddingClient ec, List<OpenSearchClient.Item> batch, List<String> inputs) {
        int ok = 0;
        for (int start = 0; start < batch.size(); start += embedBatch) {
            int end = Math.min(start + embedBatch, batch.size());
            List<String> chunk = inputs.subList(start, end);
            try {
                float[][] vecs = ec.embed(chunk);
                for (int j = 0; j < vecs.length && (start + j) < end; j++) {
                    ObjectNode src = batch.get(start + j).source();
                    ArrayNode arr = src.putArray("embedding");
                    for (float v : vecs[j]) arr.add(v);
                    ok++;
                }
            } catch (EmbeddingClient.EmbeddingException e) {
                System.err.printf("[EMBED] lỗi lô con [%d..%d): %s — index tiếp KHÔNG vector cho lô này.%n",
                        start, end, e.getMessage());
            }
        }
        return ok;
    }

    /** Parse linh hoạt: ISO instant (có nano + Z) hoặc yyyy-MM-dd; lỗi → null. */
    static Instant parseInstant(String v) {
        if (v == null || v.isBlank()) return null;
        try { return Instant.parse(v.trim()); } catch (Exception ignore) {}
        try { return LocalDate.parse(v.trim()).atStartOfDay().toInstant(ZoneOffset.UTC); } catch (Exception ignore) {}
        return null;
    }

    /** _id ổn định của một tài liệu = SHA-256(url). Dùng chung cho migrate & cập nhật pagerank (S3.1). */
    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
