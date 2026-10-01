package vn.hust.ir.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vn.hust.ir.nlp.VietnameseAnalyzer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S1.2 + S1.4: dựng truy vấn OpenSearch (BM25 simple, phrase, Boolean, proximity),
 * tách từ nhất quán (G3), và báo lỗi cú pháp (không 500).
 */
class QueryParserTest {

    private final ObjectMapper m = new ObjectMapper();
    private final QueryParser p = new QueryParser(VietnameseAnalyzer.get(), m);

    @Test
    void simpleQuery_usesMultiMatch_withSegmentedText() {
        JsonNode q = p.buildQuery("tuyển sinh");
        assertTrue(q.has("multi_match"), "truy vấn đơn giản phải là multi_match (BM25)");
        // G3: "tuyển sinh" tách thành "tuyển_sinh"
        assertEquals("tuyển_sinh", q.path("multi_match").path("query").asText());
        String fields = q.path("multi_match").path("fields").toString();
        assertTrue(fields.contains("title_seg^2"), "phải boost title_seg^2");
        assertTrue(fields.contains("content_seg"), "phải tìm trên content_seg");
    }

    @Test
    void phrase_producesMatchPhrase_onSegFields() {
        JsonNode q = p.buildQuery("\"tuyển sinh\"");
        JsonNode should = q.path("bool").path("should");
        assertTrue(should.isArray() && should.size() == 2);
        JsonNode mpContent = should.get(1).path("match_phrase").path("content_seg");
        assertEquals("tuyển_sinh", mpContent.path("query").asText());
    }

    @Test
    void proximity_setsSlop() {
        JsonNode q = p.buildQuery("\"đại học\"~2");
        JsonNode mp = q.path("bool").path("should").get(1).path("match_phrase").path("content_seg");
        assertEquals("đại_học", mp.path("query").asText());
        assertEquals(2, mp.path("slop").asInt());
    }

    @Test
    void booleanAnd_buildsMustClauses() {
        JsonNode q = p.buildQuery("\"tuyển sinh\" AND thạc sĩ");
        JsonNode must = q.path("bool").path("must");
        assertTrue(must.isArray());
        assertEquals(3, must.size(), "phrase + thạc + sĩ (AND ngầm) = 3 mệnh đề must");
        assertTrue(must.get(0).path("bool").has("should"), "mệnh đề đầu là phrase");
    }

    @Test
    void booleanOr_buildsShouldClauses() {
        JsonNode q = p.buildQuery("tuyển OR sinh");
        JsonNode bool = q.path("bool");
        assertEquals(2, bool.path("should").size());
        assertEquals(1, bool.path("minimum_should_match").asInt());
    }

    @Test
    void booleanNot_buildsMustNot() {
        JsonNode q = p.buildQuery("tuyển NOT sinh");
        JsonNode must = q.path("bool").path("must");
        assertEquals(2, must.size());
        assertTrue(must.get(1).path("bool").has("must_not"), "NOT → bool.must_not");
    }

    @Test
    void parentheses_group() {
        JsonNode q = p.buildQuery("(tuyển OR sinh) AND thạc");
        JsonNode must = q.path("bool").path("must");
        assertEquals(2, must.size());
        assertTrue(must.get(0).path("bool").has("should"), "nhóm ngoặc → OR bên trong must");
    }

    @Test
    void syntaxErrors_throwQueryParseException_not500() {
        assertThrows(QueryParseException.class, () -> p.buildQuery(""));
        assertThrows(QueryParseException.class, () -> p.buildQuery("\"chưa đóng nháy"));
        assertThrows(QueryParseException.class, () -> p.buildQuery("tuyển AND"));
        assertThrows(QueryParseException.class, () -> p.buildQuery("(tuyển OR sinh"));
        assertThrows(QueryParseException.class, () -> p.buildQuery("tuyển )"));
        assertThrows(QueryParseException.class, () -> p.buildQuery("\"cụm\"~abc"));
    }

    @Test
    void isStructured_detectsOperatorsAndQuotes() {
        assertFalse(QueryParser.isStructured("tuyển sinh"));
        assertTrue(QueryParser.isStructured("\"tuyển sinh\""));
        assertTrue(QueryParser.isStructured("a AND b"));
        assertTrue(QueryParser.isStructured("(a)"));
    }
}
