package vn.hust.ir.embed;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S2.1/S2.5: kiểm thử EmbeddingClient bằng HTTP server GIẢ (không cần model/service thật).
 * Mock trả vector cố định + điểm rerank đã-sắp-xếp để kiểm việc ánh xạ lại theo index.
 */
class EmbeddingClientTest {

    private HttpServer server;

    private EmbeddingClient start(String path, int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, ex -> {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        server.start();
        return new EmbeddingClient("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() { if (server != null) server.stop(0); }

    @Test
    void embed_parsesVectorsInOrder() throws Exception {
        String body = "{\"vectors\":[[0.1,0.2,0.3],[0.4,0.5,0.6]],\"dims\":3,\"model\":\"fake\",\"count\":2}";
        EmbeddingClient c = start("/embed", 200, body);
        float[][] v = c.embed(List.of("a", "b"));
        assertEquals(2, v.length);
        assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f}, v[0], 1e-6f);
        assertArrayEquals(new float[]{0.4f, 0.5f, 0.6f}, v[1], 1e-6f);
    }

    @Test
    void embed_emptyInput_noCall() {
        // Không cần server: list rỗng trả ngay.
        EmbeddingClient c = new EmbeddingClient("http://127.0.0.1:1");
        assertEquals(0, c.embed(List.of()).length);
    }

    @Test
    void rerank_alignsScoresToInputIndex() throws Exception {
        // Service trả đã sắp xếp (index 2 cao nhất); client phải trả theo thứ tự ĐẦU VÀO.
        String body = "{\"query\":\"q\",\"model\":\"fake\",\"results\":["
                + "{\"index\":2,\"score\":0.9},{\"index\":0,\"score\":0.5},{\"index\":1,\"score\":0.1}]}";
        EmbeddingClient c = start("/rerank", 200, body);
        double[] scores = c.rerank("q", List.of("d0", "d1", "d2"));
        assertEquals(0.5, scores[0], 1e-9);
        assertEquals(0.1, scores[1], 1e-9);
        assertEquals(0.9, scores[2], 1e-9);
    }

    @Test
    void serviceError_throwsEmbeddingException() throws Exception {
        EmbeddingClient c = start("/embed", 503, "{\"detail\":\"model chưa nạp\"}");
        EmbeddingClient.EmbeddingException ex = assertThrows(
                EmbeddingClient.EmbeddingException.class, () -> c.embed(List.of("a")));
        assertTrue(ex.getMessage().contains("503"));
    }

    @Test
    void connectionRefused_throwsEmbeddingException() {
        EmbeddingClient c = new EmbeddingClient("http://127.0.0.1:1");  // không có server
        assertThrows(EmbeddingClient.EmbeddingException.class, () -> c.embed(List.of("a")));
    }

    @Test
    void usesHttp11_noH2cUpgradeHeader() throws Exception {
        // JDK HttpClient mặc định HTTP/2 → với http:// cleartext sẽ thử nâng cấp h2c bằng các header
        // "Upgrade: h2c" + "HTTP2-Settings". EmbeddingClient ép HTTP/1.1 (F1) → KHÔNG có header này.
        final String[] upgrade = {null};
        final String[] h2settings = {null};
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embed", ex -> {
            upgrade[0] = ex.getRequestHeaders().getFirst("Upgrade");
            h2settings[0] = ex.getRequestHeaders().getFirst("HTTP2-Settings");
            byte[] b = "{\"vectors\":[[0.1]],\"dims\":1,\"model\":\"fake\",\"count\":1}"
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        server.start();
        EmbeddingClient c = new EmbeddingClient("http://127.0.0.1:" + server.getAddress().getPort());
        c.embed(List.of("a"));
        assertNull(upgrade[0], "không được gửi Upgrade: h2c (đã ép HTTP/1.1)");
        assertNull(h2settings[0], "không được gửi HTTP2-Settings (đã ép HTTP/1.1)");
    }
}
