package vn.hust.ir.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** S1.3: chọn ranker qua tham số + ánh xạ index song song. */
class RankerTest {

    @Test
    void fromParam_mapsValues() {
        assertEquals(Ranker.BM25, Ranker.fromParam(null));
        assertEquals(Ranker.BM25, Ranker.fromParam(""));
        assertEquals(Ranker.BM25, Ranker.fromParam("bm25"));
        assertEquals(Ranker.VSM, Ranker.fromParam("VSM"));
        assertEquals(Ranker.LM, Ranker.fromParam("lm"));
    }

    @Test
    void fromParam_invalid_throws() {
        assertThrows(IllegalArgumentException.class, () -> Ranker.fromParam("xyz"));
    }

    @Test
    void indexName_resolvesParallelIndices() {
        assertEquals("documents", Ranker.BM25.indexName("documents"));
        assertEquals("documents_vsm", Ranker.VSM.indexName("documents"));
        assertEquals("documents_lm", Ranker.LM.indexName("documents"));
    }
}
