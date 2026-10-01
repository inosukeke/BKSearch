package vn.hust.ir.crawler;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import crawlercommons.robots.BaseRobotRules;
import crawlercommons.robots.SimpleRobotRules;
import crawlercommons.robots.SimpleRobotRulesParser;

/**
 * Đọc & lưu cache robots.txt theo từng host — bảo đảm crawl có đạo đức
 * (chỉ lấy những URL mà trang cho phép).
 */
public class RobotsCache {

    private static final String USER_AGENT = "hust-ir-coursework-crawler";
    private final Map<String, BaseRobotRules> cache = new HashMap<>();
    private final SimpleRobotRulesParser parser = new SimpleRobotRulesParser();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public boolean isAllowed(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getScheme() + "://" + uri.getHost();
            BaseRobotRules rules = cache.computeIfAbsent(host, this::fetchRules);
            return rules.isAllowed(url);
        } catch (Exception e) {
            return true; // lỗi phân tích URL -> cho qua
        }
    }

    private BaseRobotRules fetchRules(String host) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(host + "/robots.txt"))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", USER_AGENT)
                    .GET().build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 200) {
                return parser.parseContent(host + "/robots.txt", resp.body(),
                        "text/plain", USER_AGENT);
            }
        } catch (Exception ignored) {
        }
        // Không có robots.txt -> cho phép tất cả.
        return new SimpleRobotRules(SimpleRobotRules.RobotRulesMode.ALLOW_ALL);
    }
}
