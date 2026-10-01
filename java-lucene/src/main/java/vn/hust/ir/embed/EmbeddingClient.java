package vn.hust.ir.embed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Client gọi Embedding Service (Python FastAPI) — phong cách tối giản JDK HttpClient + Jackson,
 * cùng kiểu với {@link vn.hust.ir.migrate.OpenSearchClient}.
 *
 * <ul>
 *   <li>{@code POST /embed}  — sinh vector cho danh sách văn bản (S2.1/S2.2/S2.3).</li>
 *   <li>{@code POST /rerank} — chấm lại cặp (query, doc) bằng cross-encoder (S2.5).</li>
 * </ul>
 *
 * <p>Mọi lỗi (service chết, HTTP ≥ 300, kết nối hỏng) → {@link EmbeddingException} để tầng REST
 * ánh xạ thành <b>502</b> (không 500) — phân tầng lỗi theo quy ước HANDOFF.
 *
 * <p><b>G3:</b> văn bản gửi /embed PHẢI đã tách từ underscore (model bi-encoder dựa trên PhoBERT);
 * văn bản gửi /rerank dùng bản thô (cross-encoder đa ngữ).
 */
public class EmbeddingClient {

    private final String base;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Duration requestTimeout;

    public EmbeddingClient(String baseUrl) {
        this(baseUrl, Duration.ofSeconds(60));
    }

    public EmbeddingClient(String baseUrl, Duration requestTimeout) {
        this.base = baseUrl.replaceAll("/+$", "");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.requestTimeout = requestTimeout;
    }

    public String baseUrl() { return base; }

    /**
     * Sinh embedding cho một lô văn bản. Trả ma trận [n][dims] theo đúng thứ tự đầu vào.
     * @throws EmbeddingException nếu service lỗi/không phản hồi.
     */
    public float[][] embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) return new float[0][];
        ObjectNode body = mapper.createObjectNode();
        ArrayNode arr = body.putArray("texts");
        texts.forEach(arr::add);

        JsonNode root = post("/embed", body);
        JsonNode vectors = root.path("vectors");
        if (!vectors.isArray()) {
            throw new EmbeddingException("Phản hồi /embed thiếu 'vectors': " + truncate(root.toString()));
        }
        float[][] out = new float[vectors.size()][];
        for (int i = 0; i < vectors.size(); i++) {
            JsonNode v = vectors.get(i);
            float[] vec = new float[v.size()];
            for (int j = 0; j < v.size(); j++) vec[j] = (float) v.get(j).asDouble();
            out[i] = vec;
        }
        return out;
    }

    /** Tiện ích: embedding một văn bản đơn. */
    public float[] embedOne(String text) {
        float[][] r = embed(List.of(text));
        if (r.length == 0) throw new EmbeddingException("Không nhận được vector cho truy vấn.");
        return r[0];
    }

    /**
     * Chấm lại (query, documents) bằng cross-encoder. Trả mảng điểm THEO ĐÚNG thứ tự
     * {@code documents} đầu vào (dù service trả về đã sắp xếp).
     * @throws EmbeddingException nếu service lỗi/không phản hồi.
     */
    public double[] rerank(String query, List<String> documents) {
        double[] scores = new double[documents == null ? 0 : documents.size()];
        if (documents == null || documents.isEmpty()) return scores;
        ObjectNode body = mapper.createObjectNode();
        body.put("query", query == null ? "" : query);
        ArrayNode arr = body.putArray("documents");
        documents.forEach(arr::add);

        JsonNode root = post("/rerank", body);
        JsonNode results = root.path("results");
        if (!results.isArray()) {
            throw new EmbeddingException("Phản hồi /rerank thiếu 'results': " + truncate(root.toString()));
        }
        for (JsonNode r : results) {
            int idx = r.path("index").asInt(-1);
            if (idx >= 0 && idx < scores.length) scores[idx] = r.path("score").asDouble(0);
        }
        return scores;
    }

    // ---- HTTP ----------------------------------------------------------------

    private JsonNode post(String path, JsonNode body) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() >= 300) {
                String detail = "";
                try { detail = mapper.readTree(res.body()).path("detail").asText(""); } catch (Exception ignore) {}
                throw new EmbeddingException("Embedding service HTTP " + res.statusCode()
                        + (detail.isBlank() ? (": " + truncate(res.body())) : ": " + detail));
            }
            return mapper.readTree(res.body());
        } catch (EmbeddingException e) {
            throw e;
        } catch (Exception e) {
            throw new EmbeddingException("Không gọi được Embedding service tại " + base + path + ": " + e.getMessage(), e);
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }

    /** Lỗi gọi Embedding service → tầng REST ánh xạ 502. */
    public static class EmbeddingException extends RuntimeException {
        public EmbeddingException(String msg) { super(msg); }
        public EmbeddingException(String msg, Throwable cause) { super(msg, cause); }
    }
}
