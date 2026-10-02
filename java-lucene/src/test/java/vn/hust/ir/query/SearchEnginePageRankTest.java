package vn.hust.ir.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vn.hust.ir.migrate.OpenSearchClient;
import vn.hust.ir.nlp.VietnameseAnalyzer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** S3.1: function_score trộn PageRank bật/tắt theo trọng số + anchor_text_seg trong truy vấn. */
class SearchEnginePageRankTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private SearchEngine engine(double pagerankWeight) {
        OpenSearchClient os = new OpenSearchClient("http://127.0.0.1:1");
        QueryParser parser = new QueryParser(VietnameseAnalyzer.get(), mapper);
        return new SearchEngine(os, "documents", parser, new SpellChecker(List.of()), null, pagerankWeight);
    }

    @Test
    void weightZero_noFunctionScoreWrap() {
        JsonNode body = engine(0.0).buildRequest("tuyển sinh", 0, 10);
        assertFalse(body.path("query").has("function_score"), "trọng số 0 → KHÔNG bọc function_score");
    }

    @Test
    void weightPositive_wrapsWithFieldValueFactorOnPagerank() {
        JsonNode body = engine(2.0).buildRequest("tuyển sinh", 0, 10);
        JsonNode fs = body.path("query").path("function_score");
        assertFalse(fs.isMissingNode(), "trọng số > 0 → bọc function_score");
        assertEquals("pagerank", fs.path("field_value_factor").path("field").asText());
        assertEquals(2.0, fs.path("field_value_factor").path("factor").asDouble(), 1e-9);
        assertEquals("sum", fs.path("boost_mode").asText());
        // truy vấn gốc (bool.should: tách-từ + bỏ-dấu) vẫn nằm trong function_score.query
        assertTrue(fs.path("query").path("bool").path("should").get(0).has("multi_match"));
    }

    @Test
    void multiMatch_includesAnchorTextField() {
        JsonNode body = engine(0.0).buildRequest("học phí", 0, 10);
        // Nhánh tách-từ = should[0].multi_match (sau khi gộp thêm nhánh bỏ-dấu).
        String fields = body.path("query").path("bool").path("should").get(0)
                .path("multi_match").path("fields").toString();
        assertTrue(fields.contains("anchor_text_seg"), "anchor text (S3.1) phải là field tìm kiếm");
        assertTrue(fields.contains("title_seg^2"));
    }

    @Test
    void synonymExpansion_wrapsBoolMustShould() {
        // S3.3: bật đồng nghĩa → truy vấn "tuyển sinh" thêm nhánh should (xét tuyển...).
        OpenSearchClient os = new OpenSearchClient("http://127.0.0.1:1");
        QueryParser parser = new QueryParser(VietnameseAnalyzer.get(), mapper);
        ExpansionOptions exp = new ExpansionOptions(true, 4, false, 5, 8);
        SearchEngine eng = new SearchEngine(os, "documents", parser, new SpellChecker(List.of()),
                null, 0.0, false, exp);
        JsonNode body = eng.buildRequest("tuyển sinh", 0, 10);
        JsonNode bool = body.path("query").path("bool");
        assertFalse(bool.isMissingNode(), "có mở rộng → bọc bool");
        assertTrue(bool.has("must") && bool.has("should"), "must=gốc, should=mở rộng");
    }

    @Test
    void synonymExpansion_disabled_segOrFoldBool() {
        JsonNode body = engine(0.0).buildRequest("tuyển sinh", 0, 10);
        // Không mở rộng: truy vấn đơn = bool.should gồm 2 nhánh (tách-từ + bỏ-dấu), KHÔNG có must.
        JsonNode bool = body.path("query").path("bool");
        assertFalse(bool.isMissingNode());
        assertFalse(bool.has("must"), "tắt mở rộng → không có nhánh must");
        assertEquals(3, bool.path("should").size(), "gồm nhánh tách-từ + bỏ-dấu + boost cụm");
    }
}
