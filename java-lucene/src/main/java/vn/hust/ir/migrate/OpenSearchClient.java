package vn.hust.ir.migrate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Client OpenSearch tối giản dùng JDK HttpClient (không kéo thêm thư viện nặng).
 * Chỉ các thao tác cần cho di trú: _bulk, _count, _refresh, kiểm tra index tồn tại.
 */
public class OpenSearchClient {

    private final String base;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenSearchClient(String baseUrl) {
        this.base = baseUrl.replaceAll("/+$", "");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public boolean indexExists(String index) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/" + index))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
        return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
    }

    public void refresh(String index) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/" + index + "/_refresh"))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        http.send(req, HttpResponse.BodyHandlers.discarding());
    }

    public long count(String index) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/" + index + "/_count"))
                .GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return mapper.readTree(res.body()).path("count").asLong(-1);
    }

    public ObjectMapper mapper() { return mapper; }

    /** Kết quả một lô bulk. */
    public record BulkResult(int ok, int failed, String firstError) {}

    /**
     * Index một lô tài liệu theo _id ổn định (upsert — chạy lại không nhân đôi).
     * @param items danh sách cặp (id, source JSON)
     */
    public BulkResult bulk(String index, List<Item> items) throws Exception {
        StringBuilder nd = new StringBuilder();
        for (Item it : items) {
            ObjectNode action = mapper.createObjectNode();
            ObjectNode meta = action.putObject("index");
            meta.put("_index", index);
            meta.put("_id", it.id());
            nd.append(mapper.writeValueAsString(action)).append('\n');
            nd.append(mapper.writeValueAsString(it.source())).append('\n');
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/_bulk"))
                .header("Content-Type", "application/x-ndjson")
                .POST(HttpRequest.BodyPublishers.ofString(nd.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode root = mapper.readTree(res.body());
        int ok = 0, failed = 0;
        String firstError = null;
        for (JsonNode it : root.path("items")) {
            JsonNode r = it.path("index");
            int status = r.path("status").asInt();
            if (status >= 200 && status < 300) {
                ok++;
            } else {
                failed++;
                if (firstError == null) firstError = r.path("error").toString();
            }
        }
        return new BulkResult(ok, failed, firstError);
    }

    public record Item(String id, ObjectNode source) {}
}
