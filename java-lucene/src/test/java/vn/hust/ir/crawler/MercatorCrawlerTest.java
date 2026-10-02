package vn.hust.ir.crawler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.hust.ir.store.Db;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S4.1: crawler Mercator đa luồng với {@link PageFetcher} GIẢ (không mạng).
 * Kiểm: tôn trọng scope, lưu doc/cạnh, chặn maxPages, và LỊCH SỰ (giãn cách/host).
 */
class MercatorCrawlerTest {

    /** Fetcher giả: trả trang theo map + ghi lại thời điểm fetch từng host (kiểm politeness). */
    private static final class FakeFetcher implements PageFetcher {
        final Map<String, FetchedPage> pages;
        final Map<String, List<Long>> fetchTimes = new ConcurrentHashMap<>();
        FakeFetcher(Map<String, FetchedPage> pages) { this.pages = pages; }
        @Override public FetchedPage fetch(String url) {
            String host = java.net.URI.create(url).getHost();
            fetchTimes.computeIfAbsent(host, k -> java.util.Collections.synchronizedList(new ArrayList<>()))
                    .add(System.currentTimeMillis());
            return pages.get(url);
        }
    }

    private static PageFetcher.FetchedPage page(String... links) {
        List<PageFetcher.Link> ls = new ArrayList<>();
        for (String l : links) ls.add(new PageFetcher.Link(l, "anchor " + l));
        return new PageFetcher.FetchedPage("title", "noi dung trang " + ls.size(), ls, null);
    }

    @Test
    void crawlsInScope_savesDocsAndEdges_skipsOutOfScope(@TempDir Path dir) throws Exception {
        String base = "https://hust.edu.vn/";
        Map<String, PageFetcher.FetchedPage> site = Map.of(
                base, page(base + "a", base + "b", "https://google.com/out"),
                base + "a", page(base + "b"),
                base + "b", page(base + "a"));
        FakeFetcher fetcher = new FakeFetcher(site);

        try (Db db = new Db(dir.resolve("t.db").toString())) {
            MercatorCrawler c = new MercatorCrawler(db, fetcher, u -> true,
                    MercatorCrawler.DEFAULT_DOMAIN_SUFFIX, 0, 3);
            MercatorCrawler.Stats st = c.crawl(List.of(base), 100, 3);

            assertEquals(3, st.pages.get(), "chỉ 3 trang trong *.hust.edu.vn");
            assertEquals(3, db.countDocuments());
            assertFalse(fetcher.fetchTimes.containsKey("google.com"), "KHÔNG fetch ngoài scope");
            assertTrue(db.countLinks() > 0, "phải lưu cạnh đồ thị cho PageRank");
        }
    }

    @Test
    void respectsMaxPages(@TempDir Path dir) throws Exception {
        String base = "https://hust.edu.vn/";
        Map<String, PageFetcher.FetchedPage> site = new java.util.HashMap<>();
        site.put(base, page(base + "1", base + "2", base + "3", base + "4", base + "5"));
        for (int i = 1; i <= 5; i++) site.put(base + i, page());
        FakeFetcher fetcher = new FakeFetcher(site);

        try (Db db = new Db(dir.resolve("t.db").toString())) {
            MercatorCrawler c = new MercatorCrawler(db, fetcher, u -> true,
                    MercatorCrawler.DEFAULT_DOMAIN_SUFFIX, 0, 4);
            MercatorCrawler.Stats st = c.crawl(List.of(base), 3, 3);
            assertEquals(3, st.pages.get(), "dừng đúng maxPages=3");
            assertEquals(3, db.countDocuments());
        }
    }

    @Test
    void robotsDisallowed_pageSkipped(@TempDir Path dir) throws Exception {
        String base = "https://hust.edu.vn/";
        Map<String, PageFetcher.FetchedPage> site = Map.of(
                base, page(base + "secret", base + "ok"),
                base + "secret", page(),
                base + "ok", page());
        FakeFetcher fetcher = new FakeFetcher(site);

        try (Db db = new Db(dir.resolve("t.db").toString())) {
            MercatorCrawler c = new MercatorCrawler(db, fetcher,
                    u -> !u.endsWith("/secret"),   // robots cấm /secret
                    MercatorCrawler.DEFAULT_DOMAIN_SUFFIX, 0, 2);
            c.crawl(List.of(base), 100, 3);
            assertEquals(2, db.countDocuments(), "trang bị robots cấm không được lưu");
            assertFalse(fetcher.fetchTimes.getOrDefault("hust.edu.vn", List.of()).isEmpty());
        }
    }

    @Test
    void politeness_perHostGapAtLeastDelay(@TempDir Path dir) throws Exception {
        String base = "https://hust.edu.vn/";
        Map<String, PageFetcher.FetchedPage> site = new java.util.HashMap<>();
        site.put(base, page(base + "1", base + "2", base + "3"));
        for (int i = 1; i <= 3; i++) site.put(base + i, page());
        FakeFetcher fetcher = new FakeFetcher(site);

        long delay = 100;
        try (Db db = new Db(dir.resolve("t.db").toString())) {
            // 4 luồng NHƯNG cùng 1 host → vẫn phải tuần tự với giãn cách ≥ delay.
            MercatorCrawler c = new MercatorCrawler(db, fetcher, u -> true,
                    MercatorCrawler.DEFAULT_DOMAIN_SUFFIX, delay, 4);
            c.crawl(List.of(base), 100, 3);

            List<Long> times = new ArrayList<>(fetcher.fetchTimes.get("hust.edu.vn"));
            java.util.Collections.sort(times);
            assertEquals(4, times.size());
            for (int i = 1; i < times.size(); i++) {
                long gap = times.get(i) - times.get(i - 1);
                assertTrue(gap >= delay - 20,
                        "giãn cách request #" + i + " = " + gap + "ms phải ≥ " + delay);
            }
        }
    }
}
