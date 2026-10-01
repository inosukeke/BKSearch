package vn.hust.ir.crawler;

import java.net.URI;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import vn.hust.ir.store.Db;
import vn.hust.ir.store.Document;

/**
 * Crawler thu thập bài viết + link tài liệu từ hust.edu.vn và các miền con.
 *
 * - Duyệt theo chiều rộng (BFS) trong phạm vi *.hust.edu.vn.
 * - Tuân thủ robots.txt (RobotsCache) + nghỉ giữa các request (lịch sự).
 * - Trích title/content, tính SHA-256, lưu SQLite (phát hiện new/updated).
 * - Bắt link PDF/DOC/DOCX/XLS... lưu vào bảng files.
 */
public class HustCrawler {

    private static final String DOMAIN_SUFFIX = "hust.edu.vn";
    private static final String USER_AGENT =
            "hust-ir-coursework-crawler (+sinh vien BK; muc dich hoc tap)";
    private static final long DELAY_MS = 1000;           // nghỉ 1s giữa các request
    private static final String[] DOC_EXT =
            {".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx"};

    private final Db db;
    private final RobotsCache robots = new RobotsCache();

    public HustCrawler(Db db) {
        this.db = db;
    }

    /** Crawl từ các seed cho tới khi đạt maxPages hoặc maxDepth. */
    public void crawl(List<String> seeds, int maxPages, int maxDepth) throws Exception {
        Queue<String[]> queue = new ArrayDeque<>();   // [url, depth]
        Set<String> seen = new HashSet<>();
        for (String s : seeds) {
            if (seen.add(s)) queue.add(new String[]{s, "0"});
        }

        int pages = 0, nw = 0, up = 0, un = 0, files = 0;
        while (!queue.isEmpty() && pages < maxPages) {
            String[] cur = queue.poll();
            String url = cur[0];
            int depth = Integer.parseInt(cur[1]);

            if (!inScope(url) || !robots.isAllowed(url)) continue;

            org.jsoup.nodes.Document doc;
            try {
                doc = Jsoup.connect(url)
                        .userAgent(USER_AGENT)
                        .timeout(15000)
                        .ignoreContentType(false)
                        .followRedirects(true)
                        .get();
            } catch (Exception e) {
                continue; // lỗi tải/không phải HTML -> bỏ qua
            }
            pages++;

            String title = doc.title().trim();
            String content = doc.body() != null
                    ? doc.body().text().replaceAll("\\s+", " ").trim() : "";
            String host = URI.create(url).getHost();

            Document d = new Document(url, title, content, sha256(content),
                    "html", host, guessDate(doc), Instant.now().toString());
            switch (db.upsertDocument(d)) {
                case "new" -> nw++;
                case "updated" -> up++;
                default -> un++;
            }

            // Bắt link tài liệu.
            for (Element a : doc.select("a[href]")) {
                String abs = a.absUrl("href");
                if (abs.isEmpty()) continue;
                String low = abs.toLowerCase().split("\\?")[0];
                for (String ext : DOC_EXT) {
                    if (low.endsWith(ext)) {
                        if (db.insertFile(abs, ext.substring(1),
                                url, URI.create(abs).getHost())) files++;
                        break;
                    }
                }
            }

            // Thêm link nội bộ vào hàng đợi.
            if (depth < maxDepth) {
                for (Element a : doc.select("a[href]")) {
                    String abs = a.absUrl("href").split("#")[0];
                    if (inScope(abs) && seen.add(abs)) {
                        queue.add(new String[]{abs, String.valueOf(depth + 1)});
                    }
                }
            }

            if (pages % 20 == 0) {
                System.out.printf("  ...đã crawl %d trang (queue=%d)%n", pages, queue.size());
            }
            Thread.sleep(DELAY_MS);
        }

        System.out.printf(
            "Crawl xong: %d trang | mới=%d cập_nhật=%d không_đổi=%d | URL tài liệu mới=%d%n",
            pages, nw, up, un, files);
    }

    private boolean inScope(String url) {
        try {
            if (!url.startsWith("http")) return false;
            String host = URI.create(url).getHost();
            return host != null &&
                    (host.equals(DOMAIN_SUFFIX) || host.endsWith("." + DOMAIN_SUFFIX));
        } catch (Exception e) {
            return false;
        }
    }

    private String guessDate(org.jsoup.nodes.Document doc) {
        Element m = doc.selectFirst("meta[property=article:published_time]");
        if (m != null) return m.attr("content");
        return null;
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes("UTF-8")));
        } catch (Exception e) {
            return "";
        }
    }
}
