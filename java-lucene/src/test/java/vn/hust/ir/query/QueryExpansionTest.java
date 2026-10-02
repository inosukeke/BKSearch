package vn.hust.ir.query;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** S3.3: từ điển đồng nghĩa + chọn token Rocchio. */
class QueryExpansionTest {

    // ---- SynonymDictionary ----
    @Test
    void synonym_expandsGroupMembers() {
        SynonymDictionary d = SynonymDictionary.getDefault();
        assertTrue(d.size() > 0, "nạp được từ điển");
        Set<String> e = d.expand("thông tin tuyển sinh", 4);
        assertTrue(e.contains("xét tuyển"), "tuyển sinh → xét tuyển");
        assertFalse(e.contains("tuyển sinh"), "không trả lại chính cụm đã có");
    }

    @Test
    void synonym_abbreviationExpands() {
        SynonymDictionary d = SynonymDictionary.getDefault();
        assertTrue(d.expand("điểm chuẩn cntt", 4).contains("công nghệ thông tin"));
    }

    @Test
    void synonym_noMatch_empty_andRespectsMax() {
        SynonymDictionary d = SynonymDictionary.getDefault();
        assertTrue(d.expand("lịch nghỉ tết", 4).isEmpty());
        assertTrue(d.expand("tuyển sinh đại học sinh viên", 1).size() <= 1, "tôn trọng maxTerms");
    }

    // ---- Rocchio ----
    @Test
    void rocchio_picksFrequentNonQueryNonStopTerms() {
        List<String> docs = List.of(
                "học_bổng sinh_viên xuất_sắc và học_bổng khuyến_khích",
                "học_bổng cho sinh_viên nghèo vượt_khó");
        Set<String> q = Set.of("học_bổng");               // loại khỏi mở rộng
        Set<String> stop = Set.of("và", "cho");
        List<String> terms = Rocchio.selectTerms(docs, q, stop, 3);
        assertFalse(terms.contains("học_bổng"), "token truy vấn bị loại");
        assertTrue(terms.contains("sinh_viên"), "token nổi bật được chọn");
        assertTrue(terms.size() <= 3);
    }

    @Test
    void rocchio_emptyInputs() {
        assertTrue(Rocchio.selectTerms(List.of(), Set.of(), Set.of(), 5).isEmpty());
        assertTrue(Rocchio.selectTerms(List.of("a b c"), Set.of(), Set.of(), 0).isEmpty());
    }
}
