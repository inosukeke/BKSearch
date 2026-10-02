package vn.hust.ir.crawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Kiểm thử logic chọn nhánh của {@link HybridFetcher} bằng fetcher GIẢ — không cần mạng/trình duyệt.
 * (Bản thân {@link SeleniumFetcher} cần Chrome nên xác minh ở phiên local.)
 */
class HybridFetcherTest {

    /** Fetcher giả: trả nội dung cố định + đếm số lần được gọi. */
    private static final class FakeFetcher implements PageFetcher, AutoCloseable {
        final String content;
        final AtomicInteger calls = new AtomicInteger();
        boolean closed = false;
        FakeFetcher(String content) { this.content = content; }
        @Override public FetchedPage fetch(String url) {
            calls.incrementAndGet();
            if (content == null) return null;
            return new FetchedPage("t", content, List.of(), null);
        }
        @Override public void close() { closed = true; }
    }

    @Test
    void trangTinhDayDu_khongGoiSelenium() throws Exception {
        FakeFetcher jsoup = new FakeFetcher("x".repeat(500));   // > ngưỡng
        FakeFetcher sel = new FakeFetcher("y".repeat(900));
        HybridFetcher h = new HybridFetcher(jsoup, sel, 200);

        PageFetcher.FetchedPage p = h.fetch("http://a");
        assertEquals(500, p.content().length());
        assertEquals(1, jsoup.calls.get());
        assertEquals(0, sel.calls.get(), "trang tĩnh đủ dày → KHÔNG fallback Selenium");
    }

    @Test
    void trangMong_fallbackSelenium_giuBanNhieuNoiDungHon() throws Exception {
        FakeFetcher jsoup = new FakeFetcher("short");           // 5 ký tự < 200
        FakeFetcher sel = new FakeFetcher("z".repeat(800));     // render JS ra nhiều hơn
        HybridFetcher h = new HybridFetcher(jsoup, sel, 200);

        PageFetcher.FetchedPage p = h.fetch("http://a");
        assertEquals(800, p.content().length(), "lấy bản động vì nhiều nội dung hơn");
        assertEquals(1, sel.calls.get(), "trang mỏng → có gọi Selenium");
    }

    @Test
    void trangMong_nhungSeleniumKhongTotHon_giuBanTinh() throws Exception {
        FakeFetcher jsoup = new FakeFetcher("abc");             // mỏng nhưng vẫn có
        FakeFetcher sel = new FakeFetcher(null);                // Selenium cũng fail
        HybridFetcher h = new HybridFetcher(jsoup, sel, 200);

        PageFetcher.FetchedPage p = h.fetch("http://a");
        assertEquals("abc", p.content(), "Selenium null → giữ bản tĩnh");
    }

    @Test
    void khongCoFallback_traThangBanTinh() throws Exception {
        FakeFetcher jsoup = new FakeFetcher("short");
        HybridFetcher h = new HybridFetcher(jsoup, null, 200);
        PageFetcher.FetchedPage p = h.fetch("http://a");
        assertEquals("short", p.content());
    }

    @Test
    void close_dongCaHaiFetcherCon() throws Exception {
        FakeFetcher jsoup = new FakeFetcher("a");
        FakeFetcher sel = new FakeFetcher("b");
        HybridFetcher h = new HybridFetcher(jsoup, sel, 200);
        h.close();
        assertTrue(jsoup.closed);
        assertTrue(sel.closed);
    }

    @Test
    void caHaiNull_traNull() throws Exception {
        FakeFetcher jsoup = new FakeFetcher(null);
        FakeFetcher sel = new FakeFetcher(null);
        HybridFetcher h = new HybridFetcher(jsoup, sel, 200);
        assertNull(h.fetch("http://a"));
        assertFalse(jsoup.closed);
    }
}
