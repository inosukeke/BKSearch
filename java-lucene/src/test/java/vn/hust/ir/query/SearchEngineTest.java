package vn.hust.ir.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vn.hust.ir.migrate.OpenSearchClient;
import vn.hust.ir.nlp.VietnameseAnalyzer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S1.2: dựng request OpenSearch (query + highlight + phân trang) — không cần engine sống.
 */
class SearchEngineTest {

    private SearchEngine engine() {
        ObjectMapper m = new ObjectMapper();
        return new SearchEngine(
                new OpenSearchClient("http://localhost:9200"),
                "documents",
                new QueryParser(VietnameseAnalyzer.get(), m),
                new SpellChecker(List.of()));
    }

    @Test
    void buildRequest_hasPagingQueryAndHighlight() {
        JsonNode body = engine().buildRequest("tuyển sinh", 10, 10);
        assertEquals(10, body.path("from").asInt());
        assertEquals(10, body.path("size").asInt());
        assertTrue(body.path("query").path("bool").path("should").isArray(),
                "truy vấn đơn gộp nhánh tách-từ + bỏ-dấu (bool.should)");
        assertTrue(body.path("query").toString().contains("multi_match"));
        assertTrue(body.path("highlight").path("fields").has("content_seg"));
        assertTrue(body.path("highlight").path("fields").has("title_seg"));
        assertTrue(body.path("_source").toString().contains("url"));
    }

    @Test
    void buildRequest_structuredQuery_isBool() {
        JsonNode body = engine().buildRequest("\"tuyển sinh\" AND thạc", 0, 10);
        assertTrue(body.path("query").path("bool").has("must"));
    }
}
