package vn.hust.ir.crawler;

/**
 * {@link PageFetcher} <b>hybrid (auto)</b> (S4.1): thử fetcher TĨNH ({@link JsoupFetcher}) trước —
 * nhanh và nhẹ — và CHỈ khi kết quả "mỏng" (ít nội dung, nghi là trang render bằng JavaScript)
 * mới fallback sang fetcher ĐỘNG ({@link SeleniumFetcher}, headless browser).
 *
 * <p>Giúp phủ được cả trang tĩnh lẫn trang JS mà không trả giá tốc độ cho mọi trang (đa số trang
 * HUST là HTML tĩnh). Ngưỡng "mỏng" tính theo số ký tự nội dung ({@code minChars}).
 *
 * <p>Thuần hợp phần nên <b>unit-test được không cần trình duyệt</b>: tiêm fetcher giả cho cả hai
 * nhánh.
 */
public class HybridFetcher implements PageFetcher, AutoCloseable {

    /** Số ký tự nội dung tối thiểu để coi trang tĩnh là "đủ" (dưới mức này → thử JS). */
    public static final int DEFAULT_MIN_CHARS = 200;

    private final PageFetcher primary;     // tĩnh (Jsoup)
    private final PageFetcher fallback;    // động (Selenium); null = tắt fallback
    private final int minChars;

    public HybridFetcher(PageFetcher primary, PageFetcher fallback, int minChars) {
        this.primary = primary;
        this.fallback = fallback;
        this.minChars = Math.max(0, minChars);
    }

    @Override
    public FetchedPage fetch(String url) throws Exception {
        FetchedPage p = primary.fetch(url);
        int len = contentLen(p);
        if (len >= minChars || fallback == null) return p;

        // Trang mỏng (có thể là JS) → thử trình duyệt động, giữ bản nhiều nội dung hơn.
        FetchedPage q = fallback.fetch(url);
        if (q == null) return p;
        if (p == null) return q;
        return contentLen(q) > len ? q : p;
    }

    private static int contentLen(FetchedPage p) {
        return (p == null || p.content() == null) ? 0 : p.content().length();
    }

    /** Đóng fetcher con nếu chúng cần giải phóng tài nguyên (vd Selenium đóng Chrome). */
    @Override
    public void close() throws Exception {
        if (primary instanceof AutoCloseable c) c.close();
        if (fallback instanceof AutoCloseable c) c.close();
    }
}
