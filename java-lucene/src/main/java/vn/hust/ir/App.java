package vn.hust.ir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import vn.hust.ir.crawler.HustCrawler;
import vn.hust.ir.index.LuceneIndexer;
import vn.hust.ir.schedule.PeriodicRunner;
import vn.hust.ir.search.LuceneSearcher;
import vn.hust.ir.search.SearchResult;
import vn.hust.ir.search.WebServer;
import vn.hust.ir.store.Db;

/**
 * CLI điều phối toàn hệ thống thu thập & tìm kiếm tài liệu HUST.
 *
 *   initdb                         tạo SQLite + bảng
 *   crawl  [maxPages] [maxDepth]   thu thập dữ liệu (Jsoup)
 *   index  [maxFiles]              đánh chỉ mục Lucene (+Tika cho tài liệu)
 *   search <từ khóa...>            tìm kiếm ở dòng lệnh
 *   serve  [port]                  mở web UI tìm kiếm
 *   schedule [phút] [maxPages]     chạy định kỳ (crawl + index)
 */
public class App {

    static final String DB_PATH = "data/hust.db";
    static final Path INDEX_DIR = Path.of("data", "index");

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { usage(); return; }
        Files.createDirectories(Path.of("data"));
        switch (args[0]) {
            case "initdb"   -> initDb();
            case "crawl"    -> crawl(args);
            case "index"    -> index(args);
            case "search"   -> search(args);
            case "serve"    -> serve(args);
            case "serve-api"-> serveApi(args);
            case "schedule" -> schedule(args);
            case "migrate"  -> migrate(args);
            case "pagerank" -> pagerank(args);
            case "dedupe"   -> dedupe(args);
            case "eval-run" -> evalRun(args);
            default         -> usage();
        }
    }

    private static void initDb() throws Exception {
        try (Db db = new Db(DB_PATH)) {
            System.out.println("Đã tạo/đọc SQLite tại " + DB_PATH);
            System.out.println("  documents = " + db.countDocuments());
            System.out.println("  files     = " + db.countFiles());
        }
    }

    private static void crawl(String[] a) throws Exception {
        int maxPages = arg(a, 1, 200);
        int maxDepth = arg(a, 2, 2);
        System.out.printf("Bắt đầu crawl (maxPages=%d, maxDepth=%d)...%n", maxPages, maxDepth);
        try (Db db = new Db(DB_PATH)) {
            new HustCrawler(db).crawl(List.of("https://hust.edu.vn/"), maxPages, maxDepth);
            System.out.println("Tổng trong DB: " + db.countDocuments()
                    + " bài, " + db.countFiles() + " URL tài liệu.");
        }
    }

    private static void index(String[] a) throws Exception {
        int maxFiles = arg(a, 1, 0);   // 0 = chỉ index HTML (nhanh); >0 = kèm Tika bóc file
        try (Db db = new Db(DB_PATH)) {
            new LuceneIndexer(db, INDEX_DIR).index(maxFiles);
        }
        System.out.println("Chỉ mục lưu tại " + INDEX_DIR);
    }

    private static void search(String[] a) throws Exception {
        if (a.length < 2) { System.out.println("Cú pháp: search <từ khóa>"); return; }
        String q = String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length));
        try (LuceneSearcher s = new LuceneSearcher(INDEX_DIR)) {
            List<SearchResult> rs = s.search(q, 10);
            System.out.printf("Tìm \"%s\" -> %d kết quả (chỉ mục %d tài liệu):%n%n",
                    q, rs.size(), s.numDocs());
            int i = 1;
            for (SearchResult r : rs) {
                System.out.printf("%d. [%.2f] %s%n   %s%n   %s%n%n",
                        i++, r.score, r.title, r.url, r.snippet);
            }
        }
    }

    private static void serve(String[] a) throws Exception {
        int port = arg(a, 1, 8080);
        new WebServer(INDEX_DIR).start(port);
        Thread.currentThread().join();
    }

    /** serve-api [port] [osUrl] [index] — Query Service Javalin (S1.1). */
    private static void serveApi(String[] a) {
        int port = arg(a, 1, 7070);
        String osUrl = (a.length > 2) ? a[2] : System.getenv().getOrDefault("OPENSEARCH_URL", "http://localhost:9200");
        String index = (a.length > 3) ? a[3] : "documents";
        new vn.hust.ir.query.QueryService(osUrl, index).start(port);
        try { Thread.currentThread().join(); } catch (InterruptedException ignored) {}
    }

    /**
     * eval-run [osUrl] [index] [k] [--embed] — bộ đánh giá ranker (S1.7 + S2.6).
     * Khi có {@code --embed} hoặc env {@code EMBED_URL} → thêm vector/hybrid/hybrid+rerank.
     */
    private static void evalRun(String[] a) throws Exception {
        String osUrl = (a.length > 1 && !a[1].startsWith("--")) ? a[1]
                : System.getenv().getOrDefault("OPENSEARCH_URL", "http://localhost:9200");
        String index = (a.length > 2 && !a[2].startsWith("--")) ? a[2] : "documents";
        int k = arg(a, 3, 10);
        boolean embed = false;
        for (String s : a) if (s.equals("--embed")) embed = true;
        String embedUrl = embed ? System.getenv().getOrDefault("EMBED_URL", "http://localhost:8000")
                                : System.getenv("EMBED_URL");  // env đặt sẵn cũng bật
        int rrfK = parseEnvInt("RRF_K", vn.hust.ir.query.RrfFusion.DEFAULT_K);
        int pool = parseEnvInt("CANDIDATE_POOL", 100);
        int rerankTopK = parseEnvInt("RERANK_TOP_K", vn.hust.ir.query.SearchEngine.DEFAULT_RERANK_TOP_K);
        double pagerankWeight = parseEnvDouble("PAGERANK_WEIGHT", 0.0);
        new vn.hust.ir.eval.EvalRunner(osUrl, index, k, embedUrl, rrfK, pool, rerankTopK, pagerankWeight).run(
                Path.of("..", "eval", "queries", "queries.tsv"),
                Path.of("..", "eval", "qrels", "qrels.txt"));
    }

    private static void schedule(String[] a) {
        int minutes = arg(a, 1, 60);
        int maxPages = arg(a, 2, 100);
        new PeriodicRunner(DB_PATH, INDEX_DIR, maxPages, 2).start(minutes);
    }

    /** migrate [osUrl] [batchSize] [--no-pg] [--embed] — di trú SQLite → OpenSearch (+Postgres, +vector). */
    private static void migrate(String[] a) throws Exception {
        String osUrl = (a.length > 1 && !a[1].startsWith("--")) ? a[1] : "http://localhost:9200";
        int batch = arg(a, 2, 500);
        boolean writePg = true;
        boolean embed = false;
        for (String s : a) {
            if (s.equals("--no-pg")) writePg = false;
            if (s.equals("--embed")) embed = true;
        }
        String embedUrl = System.getenv().getOrDefault("EMBED_URL", "http://localhost:8000");
        int embedBatch = parseEnvInt("EMBED_BATCH", 32);
        new vn.hust.ir.migrate.Migrator(DB_PATH, osUrl, "documents", batch, writePg,
                embed, embedUrl, embedBatch).run();
    }

    /**
     * S3.1: tính PageRank theo lô từ đồ thị liên kết trong SQLite rồi cập nhật field
     * {@code pagerank} + anchor text vào các index OpenSearch (BM25/VSM/LM).
     * Cú pháp: {@code pagerank [osUrl]}. Env: {@code PAGERANK_DAMPING}, {@code PAGERANK_INDICES}
     * (phẩy ngăn cách, mặc định "documents,documents_vsm,documents_lm").
     */
    private static void pagerank(String[] a) throws Exception {
        String osUrl = (a.length > 1 && !a[1].startsWith("--")) ? a[1]
                : System.getenv().getOrDefault("OPENSEARCH_URL", "http://localhost:9200");
        double damping = parseEnvDouble("PAGERANK_DAMPING", vn.hust.ir.linkgraph.PageRank.DEFAULT_DAMPING);
        int batch = parseEnvInt("PAGERANK_BATCH", 500);
        String idxEnv = System.getenv().getOrDefault("PAGERANK_INDICES",
                "documents,documents_vsm,documents_lm");
        List<String> indices = new java.util.ArrayList<>();
        for (String s : idxEnv.split(",")) if (!s.isBlank()) indices.add(s.trim());

        try (Db db = new Db(DB_PATH)) {
            System.out.printf("PageRank: %d tài liệu, %d cạnh liên kết (damping=%.2f)...%n",
                    db.countDocuments(), db.countLinks(), damping);
            var runner = new vn.hust.ir.linkgraph.PageRankRunner();
            var sum = runner.run(db, new vn.hust.ir.migrate.OpenSearchClient(osUrl),
                    vn.hust.ir.nlp.VietnameseAnalyzer.get(), indices, damping,
                    vn.hust.ir.linkgraph.PageRank.DEFAULT_MAX_ITER,
                    vn.hust.ir.linkgraph.PageRank.DEFAULT_TOL, batch);
            System.out.printf("PageRank xong: %d node, %d cạnh | hội tụ=%s sau %d vòng%n",
                    sum.nodes(), sum.edgesKept(), sum.converged(), sum.iterations());
            sum.perIndex().forEach((idx, r) ->
                    System.out.printf("  %-18s ok=%d failed=%d%s%n", idx, r.ok(), r.failed(),
                            r.firstError() != null ? " err=" + r.firstError() : ""));
        }
    }

    /**
     * S3.2: phát hiện near-duplicate (MinHash+LSH) từ corpus trong SQLite rồi ghi {@code dup_group}
     * (url canonical) cho các bản trùng vào các index. Cú pháp: {@code dedupe [osUrl]}.
     * Env: {@code DEDUP_K}, {@code DEDUP_NUM_HASHES}, {@code DEDUP_BANDS}, {@code DEDUP_THRESHOLD}.
     */
    private static void dedupe(String[] a) throws Exception {
        String osUrl = (a.length > 1 && !a[1].startsWith("--")) ? a[1]
                : System.getenv().getOrDefault("OPENSEARCH_URL", "http://localhost:9200");
        int k = parseEnvInt("DEDUP_K", vn.hust.ir.dedup.Shingling.DEFAULT_K);
        int numHashes = parseEnvInt("DEDUP_NUM_HASHES", vn.hust.ir.dedup.NearDuplicateDetector.DEFAULT_NUM_HASHES);
        int bands = parseEnvInt("DEDUP_BANDS", vn.hust.ir.dedup.NearDuplicateDetector.DEFAULT_BANDS);
        double threshold = parseEnvDouble("DEDUP_THRESHOLD", vn.hust.ir.dedup.NearDuplicateDetector.DEFAULT_THRESHOLD);
        int batch = parseEnvInt("DEDUP_BATCH", 500);
        String idxEnv = System.getenv().getOrDefault("PAGERANK_INDICES",
                "documents,documents_vsm,documents_lm");
        List<String> indices = new java.util.ArrayList<>();
        for (String s : idxEnv.split(",")) if (!s.isBlank()) indices.add(s.trim());

        try (Db db = new Db(DB_PATH)) {
            System.out.printf("Near-duplicate: %d tài liệu (k=%d, hashes=%d, bands=%d, ngưỡng=%.2f)...%n",
                    db.countDocuments(), k, numHashes, bands, threshold);
            var sum = new vn.hust.ir.dedup.NearDuplicateRunner().run(db,
                    new vn.hust.ir.migrate.OpenSearchClient(osUrl), indices, k, numHashes,
                    vn.hust.ir.dedup.NearDuplicateDetector.DEFAULT_SEED, bands, threshold, batch);
            System.out.printf("Dedupe xong: %d nhóm trùng, %d bản trùng / %d doc%n",
                    sum.clusters(), sum.duplicates(), sum.docs());
            sum.perIndex().forEach((idx, r) ->
                    System.out.printf("  %-18s ok=%d failed=%d%s%n", idx, r.ok(), r.failed(),
                            r.firstError() != null ? " err=" + r.firstError() : ""));
            System.out.println("→ Bật DEDUP_COLLAPSE=1 khi serve-api để gộp mỗi nhóm còn 1 kết quả.");
        }
    }

    private static double parseEnvDouble(String name, double def) {
        try { String v = System.getenv(name); return v == null ? def : Double.parseDouble(v.trim()); }
        catch (Exception e) { return def; }
    }

    private static int parseEnvInt(String name, int def) {
        try { String v = System.getenv(name); return v == null ? def : Integer.parseInt(v.trim()); }
        catch (Exception e) { return def; }
    }

    private static int arg(String[] a, int i, int def) {
        try { return i < a.length ? Integer.parseInt(a[i]) : def; }
        catch (NumberFormatException e) { return def; }
    }

    private static void usage() {
        System.out.println("""
            HUST Search (Java + Lucene + Tika)
            Cách dùng: java -jar hust-search.jar <lệnh>
              initdb                       tạo SQLite + bảng documents/files
              crawl  [maxPages] [maxDepth] thu thập dữ liệu (mặc định 200, 2)
              index  [maxFiles]            đánh chỉ mục Lucene (0=chỉ HTML)
              search <từ khóa...>          tìm kiếm ở dòng lệnh
              serve  [port]                mở web UI Lucene cũ (mặc định 8080)
              serve-api [port] [osUrl] [index]  Query Service Javalin (S1.1, mặc định 7070)
              schedule [phút] [maxPages]   chạy định kỳ (mặc định 60 phút)
              migrate [osUrl] [batch]      di trú SQLite → OpenSearch (+Postgres); --no-pg bỏ Postgres; --embed sinh vector (EMBED_URL)
              pagerank [osUrl]             tính PageRank + anchor text → cập nhật pagerank/anchor vào index (S3.1)
              dedupe [osUrl]               phát hiện near-duplicate (MinHash/LSH) → ghi dup_group (S3.2)
              eval-run [osUrl] [index] [k] chạy bộ đánh giá 3 ranker BM25/VSM/LM (S1.7)
            """);
    }
}
