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
    void simpleQuery_gopNhanhTachTu_vaBoDau() {
        JsonNode q = p.buildQuery("tuyển sinh");
        JsonNode should = q.path("bool").path("should");
        assertTrue(should.isArray() && should.size() == 3, "gộp nhánh tách-từ + bỏ-dấu + boost cụm");
        // Nhánh 1: tách từ trên *_seg — G3: "tuyển sinh" → "tuyển_sinh"
        JsonNode seg = should.get(0).path("multi_match");
        assertEquals("tuyển_sinh", seg.path("query").asText());
        String segFields = seg.path("fields").toString();
        assertTrue(segFields.contains("title_seg^2"), "phải boost title_seg^2");
        assertTrue(segFields.contains("content_seg"), "phải tìm trên content_seg");
        // Nhánh 2: bỏ dấu dùng query THÔ trên title/content (analyzer vi_fold)
        JsonNode fold = should.get(1).path("multi_match");
        assertEquals("tuyển sinh", fold.path("query").asText(), "nhánh bỏ dấu dùng query thô");
        assertTrue(fold.path("fields").toString().contains("content"), "tìm trên content (đã bỏ dấu)");
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
        // phrase + chuỗi term "thạc sĩ" (gom 1 lần) = 2 mệnh đề must
        assertEquals(2, must.size(), "phrase + term-run = 2 mệnh đề must");
        assertTrue(must.get(0).path("bool").has("should"), "mệnh đề đầu là phrase");
    }

    @Test
    void booleanAnd_segmentsVietnameseCompounds_once_G3() {
        // Lỗi N2: trước sửa, mỗi term lẻ bị tách riêng → "thạc","sĩ" không ghép → total=0.
        JsonNode q = p.buildQuery("\"tuyển sinh\" AND thạc sĩ");
        JsonNode must = q.path("bool").path("must");
        // phrase phải chứa token ghép "tuyển_sinh"
        String phraseQ = must.get(0).path("bool").path("should").get(1)
                .path("match_phrase").path("content_seg").path("query").asText();
        assertEquals("tuyển_sinh", phraseQ);
        // term-run "thạc sĩ" phải tách MỘT LẦN → "thạc_sĩ" (operator and)
        JsonNode mm = must.get(1).path("multi_match");
        assertEquals("thạc_sĩ", mm.path("query").asText());
        assertEquals("and", mm.path("operator").asText());
    }

    @Test
    void booleanAnd_bareCompoundTerms_segmentGrouped_G3() {
        // "đại học AND học phí": mỗi vế là chuỗi 2 âm tiết của 1 từ ghép, phải ghép đúng.
        JsonNode q = p.buildQuery("đại học AND học phí");
        JsonNode must = q.path("bool").path("must");
        assertEquals(2, must.size());
        assertEquals("đại_học", must.get(0).path("multi_match").path("query").asText());
        assertEquals("học_phí", must.get(1).path("multi_match").path("query").asText());
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
