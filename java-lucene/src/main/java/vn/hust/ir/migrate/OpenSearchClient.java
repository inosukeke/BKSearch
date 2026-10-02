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
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1) // F6: nhất quán với EmbeddingClient; tránh h2c upgrade thừa
                .connectTimeout(Duration.ofSeconds(10)).build();
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

    /**
     * Thực thi truy vấn tìm kiếm trên một index (POST {index}/_search).
     * Giữ phong cách tối giản: nhận body JSON đã dựng sẵn, trả nguyên cây JSON kết quả.
     *
     * @param index tên index (vd "documents", "documents_vsm")
     * @param body  thân truy vấn OpenSearch (query/from/size/highlight...)
     * @return cây JSON phản hồi (hits.total.value, hits.hits[], ...)
     * @throws RuntimeException nếu HTTP ≥ 300 (vd cú pháp DSL sai) để tầng trên ánh xạ lỗi.
     */
    public JsonNode search(String index, JsonNode body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/" + index + "/_search"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode root = mapper.readTree(res.body());
        if (res.statusCode() >= 300) {
            JsonNode err = root.path("error");
            String type = err.path("type").asText("");
            // root_cause[0].type chính xác hơn khi có nhiều tầng lỗi
            JsonNode rootCause = err.path("root_cause");
            if (rootCause.isArray() && !rootCause.isEmpty()) {
                type = rootCause.get(0).path("type").asText(type);
            }
            String reason = err.path("reason").asText(err.toString());
            throw new OpenSearchException(res.statusCode(), type, reason);
        }
        return root;
    }

    /** Ngoại lệ mang mã HTTP + loại lỗi OpenSearch để tầng REST phân biệt lỗi cú pháp với lỗi hệ thống. */
    public static class OpenSearchException extends RuntimeException {
        public final int status;
        public final String type;   // vd "parsing_exception", "index_not_found_exception"
        public OpenSearchException(int status, String type, String reason) {
            super("OpenSearch HTTP " + status + " [" + type + "]: " + reason);
            this.status = status;
            this.type = type == null ? "" : type;
        }

        /** Lỗi do CÚ PHÁP truy vấn (người dùng) → REST 400; còn lại là lỗi hệ thống. */
        public boolean isQuerySyntaxError() {
            return status == 400 && (
                    type.equals("parsing_exception")
                 || type.equals("query_shard_exception")
                 || type.equals("search_phase_execution_exception")
                 || type.equals("illegal_argument_exception")
                 || type.equals("x_content_parse_exception"));
        }
    }

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
        // Cả request _bulk có thể hỏng (HTTP 4xx/5xx, ndjson sai, mapping từ chối) → body có "error",
        // KHÔNG có "items". Phải ném lỗi thay vì báo "ok=0 failed=0" giả thành công.
        if (res.statusCode() >= 300) {
            throw new RuntimeException("Bulk HTTP " + res.statusCode() + ": " + truncate(res.body()));
        }
        JsonNode itemsNode = root.path("items");
        if (!itemsNode.isArray() || itemsNode.isEmpty()) {
            String err = root.path("error").toString();
            throw new RuntimeException("Bulk không trả 'items'" + (err.isBlank() || err.equals("null") ? "" : ": " + err));
        }
        int ok = 0, failed = 0;
        String firstError = null;
        for (JsonNode it : itemsNode) {
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

    /**
     * Cập nhật từng phần (partial update) một lô tài liệu theo _id: chỉ MERGE các field trong
     * {@code source} vào doc hiện có, KHÔNG ghi đè field khác (dùng cho S3.1: ghi {@code pagerank},
     * {@code anchor_text} mà không mất {@code content}/{@code embedding}...).
     * Tài liệu chưa tồn tại (_id lạ) → lô đó "failed" (không upsert).
     */
    public BulkResult bulkUpdate(String index, List<Item> items) throws Exception {
        StringBuilder nd = new StringBuilder();
        for (Item it : items) {
            ObjectNode action = mapper.createObjectNode();
            ObjectNode meta = action.putObject("update");
            meta.put("_index", index);
            meta.put("_id", it.id());
            ObjectNode docWrap = mapper.createObjectNode();
            docWrap.set("doc", it.source());
            nd.append(mapper.writeValueAsString(action)).append('\n');
            nd.append(mapper.writeValueAsString(docWrap)).append('\n');
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/_bulk"))
                .header("Content-Type", "application/x-ndjson")
                .POST(HttpRequest.BodyPublishers.ofString(nd.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode root = mapper.readTree(res.body());
        if (res.statusCode() >= 300) {
            throw new RuntimeException("Bulk update HTTP " + res.statusCode() + ": " + truncate(res.body()));
        }
        JsonNode itemsNode = root.path("items");
        if (!itemsNode.isArray() || itemsNode.isEmpty()) {
            String err = root.path("error").toString();
            throw new RuntimeException("Bulk update không trả 'items'" + (err.isBlank() || err.equals("null") ? "" : ": " + err));
        }
        int ok = 0, failed = 0;
        String firstError = null;
        for (JsonNode it : itemsNode) {
            JsonNode r = it.path("update");
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

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 500 ? s : s.substring(0, 500) + "...";
    }
}
