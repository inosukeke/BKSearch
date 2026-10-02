package vn.hust.ir.migrate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kiểm thử OpenSearchClient bằng HTTP server GIẢ (không cần OpenSearch thật).
 * Trọng tâm: xác nhận client ép HTTP/1.1 (F6) — nhất quán với EmbeddingClient.
 */
class OpenSearchClientTest {

    private HttpServer server;

    @AfterEach
    void stop() { if (server != null) server.stop(0); }

    @Test
    void usesHttp11_noH2cUpgradeHeader() throws Exception {
        // HttpClient mặc định HTTP/2 → http:// cleartext sẽ thử nâng cấp h2c (Upgrade + HTTP2-Settings).
        // OpenSearchClient ép HTTP/1.1 (F6) → KHÔNG gửi các header này.
        final String[] upgrade = {null};
        final String[] h2settings = {null};
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/documents/_search", ex -> {
            upgrade[0] = ex.getRequestHeaders().getFirst("Upgrade");
            h2settings[0] = ex.getRequestHeaders().getFirst("HTTP2-Settings");
            byte[] b = "{\"hits\":{\"total\":{\"value\":0},\"hits\":[]}}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        server.start();

        OpenSearchClient c = new OpenSearchClient("http://127.0.0.1:" + server.getAddress().getPort());
        ObjectMapper m = new ObjectMapper();
        JsonNode res = c.search("documents", m.createObjectNode());
        assertEquals(0, res.path("hits").path("total").path("value").asLong(-1));
        assertNull(upgrade[0], "không được gửi Upgrade: h2c (đã ép HTTP/1.1)");
        assertNull(h2settings[0], "không được gửi HTTP2-Settings (đã ép HTTP/1.1)");
    }
}
