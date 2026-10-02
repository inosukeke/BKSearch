package vn.hust.ir.crawler;

import java.util.ArrayList;
import java.util.List;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

/**
 * {@link PageFetcher} mặc định — tải HTML tĩnh bằng Jsoup (S4.1).
 *
 * <p>Giữ nguyên hành vi trích xuất của {@code HustCrawler}: lấy title/body text,
 * mọi {@code <a href>} tuyệt đối kèm anchor, và {@code article:published_time}.
 * Trang nặng JS (nội dung render phía client) nên dùng một fetcher Selenium/headless
 * cài qua cùng interface này.
 */
public class JsoupFetcher implements PageFetcher {

    private final String userAgent;
    private final int timeoutMs;

    public JsoupFetcher(String userAgent, int timeoutMs) {
        this.userAgent = userAgent;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public FetchedPage fetch(String url) throws Exception {
        org.jsoup.nodes.Document doc;
        try {
            doc = Jsoup.connect(url)
                    .userAgent(userAgent)
                    .timeout(timeoutMs)
                    .ignoreContentType(false)
                    .followRedirects(true)
                    .get();
        } catch (Exception e) {
            return null; // lỗi tải/không phải HTML -> bỏ qua
        }

        return parse(doc);
    }

    /**
     * Trích {@link FetchedPage} từ một DOM Jsoup — DÙNG CHUNG cho mọi fetcher (Jsoup tĩnh và
     * Selenium động) để nội dung/link/ngày đăng được rút giống hệt nhau. Fetcher Selenium
     * render JS xong gọi {@code JsoupFetcher.parse(Jsoup.parse(pageSource, url))}.
     */
    public static FetchedPage parse(org.jsoup.nodes.Document doc) {
        String title = doc.title().trim();
        String content = doc.body() != null
                ? doc.body().text().replaceAll("\\s+", " ").trim() : "";

        List<Link> links = new ArrayList<>();
        for (Element a : doc.select("a[href]")) {
            String abs = a.absUrl("href");
            if (!abs.isEmpty()) links.add(new Link(abs, a.text()));
        }

        String published = null;
        Element m = doc.selectFirst("meta[property=article:published_time]");
        if (m != null) published = m.attr("content");

        return new FetchedPage(title, content, links, published);
    }
}
