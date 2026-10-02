package vn.hust.ir.crawler;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

/**
 * {@link PageFetcher} chạy trình duyệt headless bằng <b>Selenium</b> (S4.1) — tải trang,
 * CHỜ JavaScript render rồi mới lấy HTML. Dùng cho trang động (SPA, nội dung nạp bằng AJAX)
 * mà {@link JsoupFetcher} (HTML tĩnh) bỏ sót.
 *
 * <p><b>An toàn đa luồng:</b> {@link WebDriver}/ChromeDriver KHÔNG thread-safe, trong khi
 * {@link MercatorCrawler} gọi fetcher từ N worker song song. Vì vậy mỗi luồng giữ MỘT driver
 * riêng qua {@link ThreadLocal} (tạo lười). {@link #close()} đóng mọi driver đã tạo.
 *
 * <p>Selenium 4 tự phân giải ChromeDriver qua <i>Selenium Manager</i> — chỉ cần Chrome cài sẵn
 * trên máy; không cần tải ChromeDriver thủ công. Vì cần trình duyệt thật, lớp này KHÔNG unit-test
 * được trên môi trường CI không có Chrome (xác minh ở phiên local) — logic chọn/parse dùng chung
 * đã được test ở {@link HybridFetcher} và {@link JsoupFetcher#parse}.
 */
public class SeleniumFetcher implements PageFetcher, AutoCloseable {

    private final int pageLoadTimeoutMs;
    private final long jsSettleMs;

    /** Mỗi worker-thread một driver (ChromeDriver không thread-safe). */
    private final ThreadLocal<WebDriver> local = ThreadLocal.withInitial(this::newDriver);
    /** Giữ mọi driver đã tạo để đóng sạch khi {@link #close()}. */
    private final CopyOnWriteArrayList<WebDriver> all = new CopyOnWriteArrayList<>();

    public SeleniumFetcher(int pageLoadTimeoutMs, long jsSettleMs) {
        this.pageLoadTimeoutMs = pageLoadTimeoutMs;
        this.jsSettleMs = Math.max(0, jsSettleMs);
    }

    private WebDriver newDriver() {
        ChromeOptions opts = new ChromeOptions();
        opts.addArguments("--headless=new", "--disable-gpu", "--no-sandbox",
                "--disable-dev-shm-usage", "--window-size=1280,900",
                "--blink-settings=imagesEnabled=false");   // bỏ ảnh cho nhẹ/nhanh
        WebDriver d = new ChromeDriver(opts);
        d.manage().timeouts().pageLoadTimeout(Duration.ofMillis(pageLoadTimeoutMs));
        all.add(d);
        return d;
    }

    @Override
    public FetchedPage fetch(String url) {
        try {
            WebDriver d = local.get();
            d.get(url);
            if (jsSettleMs > 0) {
                try { Thread.sleep(jsSettleMs); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            String html = d.getPageSource();
            if (html == null || html.isBlank()) return null;
            // Parse bằng Jsoup (base uri = url) để rút title/content/link/ngày đăng GIỐNG HỆT
            // nhánh tĩnh — một nguồn chân lý cho việc trích xuất.
            return JsoupFetcher.parse(org.jsoup.Jsoup.parse(html, url));
        } catch (Exception e) {
            return null;   // lỗi tải/timeout/driver → bỏ qua (như JsoupFetcher)
        }
    }

    /** Đóng mọi driver đã tạo (mỗi lần tạo = một tiến trình Chrome). */
    @Override
    public void close() {
        for (WebDriver d : all) {
            try { d.quit(); } catch (Exception ignore) {}
        }
        all.clear();
    }
}
