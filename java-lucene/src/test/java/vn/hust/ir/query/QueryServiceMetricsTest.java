package vn.hust.ir.query;

import com.sun.net.httpserver.HttpServer;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S4.2: kiểm thử tích hợp — Query Service (Javalin) phục vụ {@code /metrics} định dạng
 * Prometheus và ĐẾM request thực (dùng OpenSearch GIẢ, không cần cluster thật).
 */
class QueryServiceMetricsTest {

    private HttpServer os;
    private Javalin app;

    @AfterEach
    void stop() {
        if (app != null) app.stop();
        if (os != null) os.stop(0);
    }

    private String get(int port, String path) throws Exception {
        HttpClient c = HttpClient.newHttpClient();
        HttpResponse<String> r = c.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return r.body();
    }

    @Test
    void metricsEndpoint_exposesPrometheus_andCountsRequests() throws Exception {
        // OpenSearch giả: mọi _search trả rỗng 200.
        os = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        os.createContext("/", ex -> {
            byte[] b = "{\"took\":1,\"hits\":{\"total\":{\"value\":0},\"hits\":[]}}"
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(b); }
        });
        os.start();
        String osUrl = "http://127.0.0.1:" + os.getAddress().getPort();

        QueryService svc = new QueryService(osUrl, "documents");
        app = svc.build().start(0);
        int port = app.port();

        // Bắn vài request để sinh metric.
        get(port, "/healthz");
        get(port, "/api/search?q=tuyen+sinh");
        get(port, "/api/search?q=hoc+phi");

        String metrics = get(port, "/metrics");
        assertTrue(metrics.contains("# TYPE bksearch_http_requests_total counter"), metrics);
        assertTrue(metrics.contains("bksearch_http_requests_total{path=\"/healthz\",status=\"200\"} 1"), metrics);
        assertTrue(metrics.contains("bksearch_http_requests_total{path=\"/api/search\",status=\"200\"} 2"), metrics);
        assertTrue(metrics.contains("bksearch_http_request_duration_seconds_count{path=\"/api/search\"} 2"), metrics);
        // /metrics KHÔNG tự đếm chính nó.
        assertFalse(metrics.contains("path=\"/metrics\""), "không đếm scrape /metrics");
    }
}
