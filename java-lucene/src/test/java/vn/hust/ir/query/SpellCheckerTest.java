package vn.hust.ir.query;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** S1.5: did-you-mean (Levenshtein + Jaccard k-gram), thuần Java. */
class SpellCheckerTest {

    private final SpellChecker sc = new SpellChecker(
            List.of("tuyển sinh", "đại học", "thạc sĩ", "học bổng", "đào tạo", "sinh viên"));

    @Test
    void levenshtein_classicCases() {
        assertEquals(0, SpellChecker.levenshtein("abc", "abc"));
        assertEquals(3, SpellChecker.levenshtein("kitten", "sitting"));
        assertEquals(1, SpellChecker.levenshtein("sih", "sinh"));
    }

    @Test
    void jaccardKgram_overlap() {
        assertEquals(1.0, SpellChecker.jaccardKgram("abc", "abc", 2), 1e-9);
        assertEquals(1.0 / 3, SpellChecker.jaccardKgram("abc", "abd", 2), 1e-9);
    }

    @Test
    void suggestToken_corrects_misspelling() {
        assertEquals(Optional.of("tuyển"), sc.suggestToken("tuyen"));
        assertEquals(Optional.of("sinh"), sc.suggestToken("sih"));
    }

    @Test
    void suggestToken_noSuggestion_whenCorrect() {
        assertTrue(sc.suggestToken("đại").isEmpty(), "từ đúng không gợi ý thừa");
        assertTrue(sc.suggestToken("học").isEmpty());
    }

    @Test
    void suggestQuery_fixesWholeQuery() {
        assertEquals(Optional.of("tuyển sinh"), sc.suggestQuery("tuyen sih"));
    }

    @Test
    void suggestQuery_empty_whenAllCorrect() {
        assertTrue(sc.suggestQuery("đại học").isEmpty());
    }

    @Test
    void suggestQuery_keepsBooleanOperators() {
        // toán tử giữ nguyên, chỉ sửa term sai
        assertEquals(Optional.of("tuyển AND sinh"), sc.suggestQuery("tuyen AND sih"));
    }
}
