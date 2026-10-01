package vn.hust.ir.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import vn.hust.ir.embed.EmbeddingClient;
import vn.hust.ir.migrate.OpenSearchClient;
import vn.hust.ir.nlp.VietnameseAnalyzer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S2.3/S2.4/S2.5: kiểm thử nhánh vector / hybrid / rerank của SearchEngine bằng HTTP server GIẢ
 * cho CẢ OpenSearch lẫn Embedding Service (không cần engine/model thật).
 *
 * <p>Mock {@code _search} phân biệt BM25 vs k-NN qua việc body có chứa "knn" hay không.
 */
class SearchEngineSemanticTest {

    private HttpServer os;
    private HttpServer emb;
    private final ObjectMapper mapper = new ObjectMapper();

    private static String hit(String url, double score) {
        return "{\"_score\":" + score + ",\"_source\":{\"url\":\"" + url + "\",\"title\":\"T" + url
                + "\",\"content\":\"nội dung " + url + "\",\"doc_type\":\"html\",\"subdomain\":\"s\"}}";
    }

    private static String hits(String... hitJson) {
        return "{\"hits\":{\"total\":{\"value\":" + hitJson.length + "},\"hits\":["
                + String.join(",", hitJson) + "]}}";
    }

    private void startOpenSearch() throws IOException {
        os = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        os.createContext("/documents/_search", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String resp = body.contains("\"knn\"")
                    ? hits(hit("B", 0.9), hit("C", 0.8))   // k-NN: B, C
                    : hits(hit("A", 5.0), hit("B", 4.0));   // BM25: A, B
            write(ex, 200, resp);
        });
        os.start();
    }

    private void startEmbed(String rerankBody) throws IOException {
        emb = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        emb.createContext("/embed", ex ->
                write(ex, 200, "{\"vectors\":[[0.1,0.2,0.3,0.4]],\"dims\":4,\"model\":\"fake\",\"count\":1}"));
        emb.createContext("/rerank", ex -> write(ex, 200, rerankBody));
        emb.start();
    }

    private static void write(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }

    @AfterEach
    void stop() {
        if (os != null) os.stop(0);
        if (emb != null) emb.stop(0);
    }

    private SearchEngine engine() {
        OpenSearchClient client = new OpenSearchClient("http://127.0.0.1:" + os.getAddress().getPort());
        EmbeddingClient ec = new EmbeddingClient("http://127.0.0.1:" + emb.getAddress().getPort());
        QueryParser parser = new QueryParser(VietnameseAnalyzer.get(), mapper);
        return new SearchEngine(client, "documents", parser, new SpellChecker(List.of()), ec);
    }

    @Test
    void vectorSearch_returnsKnnHitsInOrder() throws Exception {
        startOpenSearch();
        startEmbed("{\"results\":[]}");
        SearchResponse res = engine().search("học phí", 1, 10, Ranker.VECTOR, false);
        assertEquals("vector", res.ranker);
        assertEquals(2, res.results.size());
        assertEquals("B", res.results.get(0).url);
        assertEquals("C", res.results.get(1).url);
    }

    @Test
    void hybridSearch_rrfRanksDocInBothSourcesFirst() throws Exception {
        startOpenSearch();      // BM25: A,B | kNN: B,C → B xuất hiện ở cả hai
        startEmbed("{\"results\":[]}");
        SearchResponse res = engine().search("học phí", 1, 10, Ranker.HYBRID, false);
        assertEquals("hybrid", res.ranker);
        assertEquals(3, res.results.size(), "union A,B,C");
        assertEquals("B", res.results.get(0).url, "B ở cả hai nguồn → RRF xếp #1");
    }

    @Test
    void rerank_reordersByCrossEncoderScore() throws Exception {
        startOpenSearch();
        // kNN trả [B,C]; rerank cho C (index 1) điểm cao hơn B (index 0) → đảo thành C,B.
        startEmbed("{\"results\":[{\"index\":0,\"score\":0.1},{\"index\":1,\"score\":0.9}]}");
        SearchResponse res = engine().search("học phí", 1, 10, Ranker.VECTOR, true);
        assertEquals("vector+rerank", res.ranker);
        assertEquals("C", res.results.get(0).url, "doc điểm rerank cao hơn lên đầu");
        assertEquals("B", res.results.get(1).url);
    }

    @Test
    void buildKnnRequest_hasKnnClauseAndSource() {
        OpenSearchClient client = new OpenSearchClient("http://127.0.0.1:1");
        SearchEngine e = new SearchEngine(client, "documents",
                new QueryParser(VietnameseAnalyzer.get(), mapper), new SpellChecker(List.of()),
                new EmbeddingClient("http://127.0.0.1:1"));
        JsonNode body = e.buildKnnRequest(new float[]{0.1f, 0.2f}, 50);
        assertEquals(50, body.path("size").asInt());
        assertTrue(body.path("query").path("knn").has("embedding"));
        assertEquals(50, body.path("query").path("knn").path("embedding").path("k").asInt());
        assertTrue(body.path("_source").toString().contains("url"));
    }
}
