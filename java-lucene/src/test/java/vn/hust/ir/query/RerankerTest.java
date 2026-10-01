package vn.hust.ir.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** S2.5: kiểm thử sắp xếp lại theo điểm rerank (hàm thuần). */
class RerankerTest {

    @Test
    void order_sortsDescending() {
        int[] order = Reranker.order(new double[]{0.1, 0.9, 0.5});
        assertArrayEquals(new int[]{1, 2, 0}, order);
    }

    @Test
    void order_stableOnTies_keepsOriginalOrder() {
        int[] order = Reranker.order(new double[]{0.5, 0.5, 0.5});
        assertArrayEquals(new int[]{0, 1, 2}, order);
    }

    @Test
    void reorder_movesBestScoredToFront() {
        List<String> items = List.of("a", "b", "c");
        List<String> out = Reranker.reorder(items, new double[]{0.2, 0.8, 0.5});
        assertEquals(List.of("b", "c", "a"), out);
    }

    @Test
    void reorder_rejectsSizeMismatch() {
        assertThrows(IllegalArgumentException.class,
                () -> Reranker.reorder(List.of("a", "b"), new double[]{1.0}));
    }

    @Test
    void order_empty() {
        assertEquals(0, Reranker.order(new double[]{}).length);
        assertEquals(0, Reranker.order(null).length);
    }
}
