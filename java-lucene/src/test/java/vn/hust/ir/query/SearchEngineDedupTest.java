package vn.hust.ir.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vn.hust.ir.migrate.OpenSearchClient;
import vn.hust.ir.nlp.VietnameseAnalyzer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** S3.2: collapse theo dup_group bật/tắt trong thân truy vấn. */
class SearchEngineDedupTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private SearchEngine engine(boolean collapse) {
        OpenSearchClient os = new OpenSearchClient("http://127.0.0.1:1");
        QueryParser parser = new QueryParser(VietnameseAnalyzer.get(), mapper);
        return new SearchEngine(os, "documents", parser, new SpellChecker(List.of()), null, 0.0, collapse);
    }

    @Test
    void collapseOff_noCollapseClause() {
        JsonNode body = engine(false).buildRequest("tuyển sinh", 0, 10);
        assertTrue(body.path("collapse").isMissingNode(), "tắt → không có collapse");
    }

    @Test
    void collapseOn_collapsesByDupGroup() {
        JsonNode body = engine(true).buildRequest("tuyển sinh", 0, 10);
        assertEquals("dup_group", body.path("collapse").path("field").asText());
    }
}
