package vn.hust.ir.crawler;

/**
 * Nhà máy chọn {@link PageFetcher} theo biến môi trường (S4.1):
 *
 * <pre>
 *   CRAWL_FETCHER = jsoup   (mặc định) — chỉ HTML tĩnh (Jsoup), nhanh nhất
 *                 | selenium            — luôn render JS bằng headless Chrome
 *                 | auto                — hybrid: Jsoup trước, fallback Selenium khi trang mỏng
 * </pre>
 *
 * Các tham số phụ: {@code CRAWL_JS_MIN_CHARS} (ngưỡng "mỏng", mặc định 200),
 * {@code SELENIUM_TIMEOUT_MS} (mặc định 20000), {@code SELENIUM_SETTLE_MS} (chờ JS, mặc định 1200).
 *
 * <p>Người gọi nên {@code close()} fetcher trả về (đóng Chrome) nếu nó là {@link AutoCloseable}.
 */
public final class Fetchers {

    private Fetchers() {}

    public static PageFetcher fromEnv() {
        String mode = env("CRAWL_FETCHER", "jsoup").trim().toLowerCase();
        JsoupFetcher jsoup = new JsoupFetcher(MercatorCrawler.DEFAULT_USER_AGENT, 15000);
        return switch (mode) {
            case "selenium" -> newSelenium();
            case "auto", "hybrid" -> new HybridFetcher(jsoup, newSelenium(),
                    envInt("CRAWL_JS_MIN_CHARS", HybridFetcher.DEFAULT_MIN_CHARS));
            default -> jsoup;
        };
    }

    /** Tên fetcher đang chọn (để log). */
    public static String modeFromEnv() {
        String mode = env("CRAWL_FETCHER", "jsoup").trim().toLowerCase();
        return switch (mode) { case "selenium" -> "selenium"; case "auto", "hybrid" -> "auto"; default -> "jsoup"; };
    }

    private static SeleniumFetcher newSelenium() {
        return new SeleniumFetcher(envInt("SELENIUM_TIMEOUT_MS", 20000),
                envInt("SELENIUM_SETTLE_MS", 1200));
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return (v == null || v.isBlank()) ? def : v;
    }

    private static int envInt(String k, int def) {
        try { String v = System.getenv(k); return v == null ? def : Integer.parseInt(v.trim()); }
        catch (Exception e) { return def; }
    }
}
