package vn.hust.ir.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import vn.hust.ir.migrate.OpenSearchClient;
import vn.hust.ir.nlp.VietnameseAnalyzer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** S3.4: facet aggregation + bộ lọc category trong nhánh từ khóa. */
class SearchEngineFacetTest {

    private HttpServer os;
    private final ObjectMapper mapper = new ObjectMapper();
    private String captured;

    private SearchEngine startEngine(String responseJson) throws IOException {
        os = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        os.createContext("/documents/_search", ex -> {
            captured = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] b = responseJson.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(b); }
        });
        os.start();
        OpenSearchClient client = new OpenSearchClient("http://127.0.0.1:" + os.getAddress().getPort());
        QueryParser parser = new QueryParser(VietnameseAnalyzer.get(), mapper);
        return new SearchEngine(client, "documents", parser, new SpellChecker(List.of()));
    }

    @AfterEach
    void stop() { if (os != null) os.stop(0); }

    private static String responseWithFacets() {
        return "{\"hits\":{\"total\":{\"value\":1},\"hits\":[{\"_score\":1.0,\"_source\":"
                + "{\"url\":\"u\",\"title\":\"t\",\"content\":\"c\",\"category\":\"tuyển sinh\"}}]},"
                + "\"aggregations\":{\"category\":{\"buckets\":[{\"key\":\"tuyển sinh\",\"doc_count\":5},"
                + "{\"key\":\"đào tạo\",\"doc_count\":3}]},\"doc_type\":{\"buckets\":[]},"
                + "\"subdomain\":{\"buckets\":[]}}}";
    }

    @Test
    void requestHasFacetAggs_andResponseParsesFacets() throws Exception {
        SearchEngine eng = startEngine(responseWithFacets());
        SearchResponse res = eng.search("tuyển sinh", 1, 10, Ranker.BM25, false);
        assertTrue(captured.contains("\"aggs\""), "request phải có aggs facet");
        assertTrue(captured.contains("\"category\""));
        assertNotNull(res.facets);
        assertEquals(2, res.facets.get("category").size());
        assertEquals("tuyển sinh", res.facets.get("category").get(0).key);
        assertEquals(5, res.facets.get("category").get(0).count);
        assertEquals("tuyển sinh", res.results.get(0).category);
    }

    @Test
    void categoryFilter_addsBoolFilterTerm() throws Exception {
        SearchEngine eng = startEngine(responseWithFacets());
        eng.search("học", 1, 10, Ranker.BM25, false, Map.of("category", "tuyển sinh"));
        JsonNode body = mapper.readTree(captured);
        JsonNode filter = body.path("query").path("bool").path("filter");
        assertTrue(filter.isArray() && filter.size() == 1, "có 1 mệnh đề filter");
        assertEquals("tuyển sinh", filter.get(0).path("term").path("category").asText());
    }

    @Test
    void unknownFilterField_ignored() throws Exception {
        SearchEngine eng = startEngine(responseWithFacets());
        eng.search("học", 1, 10, Ranker.BM25, false, Map.of("evil", "x"));
        JsonNode body = mapper.readTree(captured);
        // field lạ → không tạo filter; query giữ nguyên (không bọc bool filter rỗng với term lạ).
        JsonNode filter = body.path("query").path("bool").path("filter");
        if (filter.isArray()) assertEquals(0, filter.size());
    }
}
