package vn.hust.ir.crawler;

import java.util.List;

/**
 * Trừu tượng hoá bước TẢI + PHÂN TÍCH một trang (S4.1).
 *
 * <p>Tách I/O mạng khỏi logic frontier/đa luồng để:
 * <ul>
 *   <li>kiểm thử crawler bằng fetcher GIẢ (không cần mạng);</li>
 *   <li>cắm fetcher khác nhau: {@link JsoupFetcher} (HTML tĩnh) hoặc một
 *       fetcher chạy Selenium/headless-browser cho trang JS (điểm mở rộng).</li>
 * </ul>
 */
public interface PageFetcher {

    /**
     * Tải và phân tích {@code url}. Trả {@code null} nếu không lấy được
     * (lỗi mạng, không phải HTML, quá lớn...) — crawler sẽ bỏ qua.
     */
    FetchedPage fetch(String url) throws Exception;

    /** Kết quả phân tích một trang: tiêu đề, nội dung text, link ra + ngày đăng. */
    record FetchedPage(String title, String content, List<Link> links, String publishedAt) {}

    /** Một liên kết ra: URL tuyệt đối + anchor text. */
    record Link(String absUrl, String anchor) {}
}
