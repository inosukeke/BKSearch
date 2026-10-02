package vn.hust.ir.crawler;

import java.net.URI;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import vn.hust.ir.store.Db;
import vn.hust.ir.store.Document;

/**
 * Crawler đa luồng kiểu Mercator (S4.1).
 *
 * <p>Dùng {@link Frontier} 2 lớp để điều phối lịch sự + {@link PageFetcher} cắm được
 * (Jsoup cho HTML tĩnh; Selenium/headless cho trang JS). N worker song song rút URL
 * đã tới hạn từ frontier, tải trang, lưu {@code documents}/{@code files} và CẠNH đồ thị
 * {@code links} (cho PageRank S3.1), rồi nạp link nội bộ mới vào frontier.
 *
 * <p>Ràng buộc G4: tuân thủ robots.txt + giãn cách ≥ {@code delayMs} giữa hai request
 * tới cùng host + ≤ 1 request đồng thời/host (do frontier bảo đảm).
 *
 * <p>Ghi SQLite được đồng bộ trên {@code db} (một kết nối) — mạng vẫn chạy song song.
 */
public class MercatorCrawler {

    public static final String DEFAULT_DOMAIN_SUFFIX = "hust.edu.vn";
    public static final String DEFAULT_USER_AGENT =
            "hust-ir-coursework-crawler (+sinh vien BK; muc dich hoc tap)";
    private static final String[] DOC_EXT =
            {".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx"};

    private final Db db;
    private final PageFetcher fetcher;
    private final Predicate<String> robots;
    private final String domainSuffix;
    private final long delayMs;
    private final int threads;

    /** Bộ đếm kết quả (thread-safe) để báo cáo + kiểm thử. */
    public static final class Stats {
        public final AtomicInteger pages = new AtomicInteger();
        public final AtomicInteger created = new AtomicInteger();
        public final AtomicInteger updated = new AtomicInteger();
        public final AtomicInteger unchanged = new AtomicInteger();
        public final AtomicInteger files = new AtomicInteger();
        public final AtomicInteger edges = new AtomicInteger();
    }

    public MercatorCrawler(Db db, PageFetcher fetcher, Predicate<String> robots,
                           String domainSuffix, long delayMs, int threads) {
        this.db = db;
        this.fetcher = fetcher;
        this.robots = robots;
        this.domainSuffix = domainSuffix;
        this.delayMs = delayMs;
        this.threads = Math.max(1, threads);
    }

    /** Tiện dụng: fetcher Jsoup + robots thật. */
    public static MercatorCrawler withDefaults(Db db, long delayMs, int threads) {
        RobotsCache rc = new RobotsCache();
        return new MercatorCrawler(db, new JsoupFetcher(DEFAULT_USER_AGENT, 15000),
                rc::isAllowed, DEFAULT_DOMAIN_SUFFIX, delayMs, threads);
    }

    /** Crawl từ seeds tới khi đạt {@code maxPages} hoặc frontier cạn. */
    public Stats crawl(List<String> seeds, int maxPages, int maxDepth) throws InterruptedException {
        Frontier frontier = new Frontier(delayMs);
        for (String s : seeds) if (inScope(s)) frontier.add(s, 0);

        Stats st = new Stats();
        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            workers[i] = new Thread(() -> worker(frontier, st, maxPages, maxDepth), "crawler-" + i);
            workers[i].start();
        }
        for (Thread w : workers) w.join();

        System.out.printf(
            "Crawl (Mercator, %d luồng) xong: %d trang | mới=%d cập_nhật=%d không_đổi=%d | URL tài liệu mới=%d | cạnh link mới=%d%n",
            threads, st.pages.get(), st.created.get(), st.updated.get(), st.unchanged.get(),
            st.files.get(), st.edges.get());
        return st;
    }

    private void worker(Frontier frontier, Stats st, int maxPages, int maxDepth) {
        try {
            while (true) {
                if (st.pages.get() >= maxPages) { frontier.close(); return; }
                Frontier.Claim claim = frontier.next();
                if (claim == null) return;                 // frontier cạn
                try {
                    process(frontier, st, claim, maxPages, maxDepth);
                } finally {
                    frontier.done(claim);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void process(Frontier frontier, Stats st, Frontier.Claim claim,
                         int maxPages, int maxDepth) {
        String url = claim.url();
        int depth = claim.depth();
        if (!robots.test(url)) return;

        PageFetcher.FetchedPage page;
        try {
            page = fetcher.fetch(url);
        } catch (Exception e) {
            return;
        }
        if (page == null) return;

        // Đạt hạn ngạch khi đang tải → vẫn còn chỗ? kiểm lại để không vượt maxPages.
        if (st.pages.incrementAndGet() > maxPages) { st.pages.decrementAndGet(); return; }

        String host = hostOf(url);
        Document d = new Document(url, page.title(),
                page.content(), sha256(page.content()),
                "html", host, page.publishedAt(), Instant.now().toString());
        try {
            synchronized (db) {
                switch (db.upsertDocument(d)) {
                    case "new" -> st.created.incrementAndGet();
                    case "updated" -> st.updated.incrementAndGet();
                    default -> st.unchanged.incrementAndGet();
                }
                // Link tài liệu (PDF/DOC/...) + CẠNH đồ thị trang→trang.
                for (PageFetcher.Link a : page.links()) {
                    String abs = a.absUrl();
                    if (abs.isEmpty()) continue;
                    String low = abs.toLowerCase().split("\\?")[0];
                    for (String ext : DOC_EXT) {
                        if (low.endsWith(ext)) {
                            if (db.insertFile(abs, ext.substring(1), url, hostOf(abs)))
                                st.files.incrementAndGet();
                            break;
                        }
                    }
                }
                for (PageFetcher.Link a : page.links()) {
                    String abs = a.absUrl().split("#")[0];
                    if (!inScope(abs)) continue;
                    try {
                        if (db.insertLink(url, abs, a.anchor())) st.edges.incrementAndGet();
                    } catch (Exception ignore) { /* một cạnh lỗi không dừng crawl */ }
                }
            }
        } catch (Exception e) {
            System.err.println("[crawler] lỗi ghi DB cho " + url + ": " + e.getMessage());
            return;
        }

        // Nạp link nội bộ mới vào frontier (ngoài khối đồng bộ db).
        if (depth < maxDepth) {
            for (PageFetcher.Link a : page.links()) {
                String abs = a.absUrl().split("#")[0];
                if (inScope(abs)) frontier.add(abs, depth + 1);
            }
        }
    }

    boolean inScope(String url) {
        try {
            if (url == null || !url.startsWith("http")) return false;
            String host = URI.create(url).getHost();
            return host != null &&
                    (host.equals(domainSuffix) || host.endsWith("." + domainSuffix));
        } catch (Exception e) {
            return false;
        }
    }

    private static String hostOf(String url) {
        try { return URI.create(url).getHost(); } catch (Exception e) { return ""; }
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(md.digest(s.getBytes("UTF-8")));
        } catch (Exception e) {
            return "";
        }
    }
}
