package vn.hust.ir.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** S1.7: kiểm thử các hàm độ đo bằng dữ liệu giả (không cần OpenSearch). */
class MetricsTest {

    private final List<String> ranked = List.of("d1", "d2", "d3", "d4", "d5");
    private final Set<String> relevant = Set.of("d1", "d3", "d5");
    private final Map<String, Integer> gains = Map.of("d1", 1, "d3", 1, "d5", 1);

    @Test
    void precisionAndRecallAtK() {
        assertEquals(2.0 / 3, Metrics.precisionAtK(ranked, relevant, 3), 1e-9);
        assertEquals(2.0 / 3, Metrics.recallAtK(ranked, relevant, 3), 1e-9);
        assertEquals(3.0 / 5, Metrics.precisionAtK(ranked, relevant, 5), 1e-9);
        assertEquals(1.0, Metrics.recallAtK(ranked, relevant, 5), 1e-9);
    }

    @Test
    void f1AtK() {
        double p = 2.0 / 3, r = 2.0 / 3;
        assertEquals(2 * p * r / (p + r), Metrics.f1AtK(ranked, relevant, 3), 1e-9);
    }

    @Test
    void averagePrecision() {
        // vị trí phù hợp: 1 (1/1), 3 (2/3), 5 (3/5) → (1 + 0.6667 + 0.6)/3
        double expected = (1.0 + 2.0 / 3 + 3.0 / 5) / 3;
        assertEquals(expected, Metrics.averagePrecision(ranked, relevant), 1e-9);
    }

    @Test
    void reciprocalRank() {
        assertEquals(1.0, Metrics.reciprocalRank(ranked, relevant), 1e-9);
        assertEquals(1.0 / 2, Metrics.reciprocalRank(ranked, Set.of("d2")), 1e-9);
        assertEquals(0.0, Metrics.reciprocalRank(ranked, Set.of("zzz")), 1e-9);
    }

    @Test
    void ndcgAtK() {
        // DCG = 1/log2(2) + 1/log2(4) + 1/log2(6); IDCG = 1/log2(2)+1/log2(3)+1/log2(4)
        double dcg = 1.0 + 1.0 / log2(4) + 1.0 / log2(6);
        double idcg = 1.0 + 1.0 / log2(3) + 1.0 / log2(4);
        assertEquals(dcg / idcg, Metrics.ndcgAtK(ranked, gains, 5), 1e-9);
    }

    @Test
    void ndcg_perfectRanking_isOne() {
        List<String> perfect = List.of("d1", "d3", "d5", "d2", "d4");
        assertEquals(1.0, Metrics.ndcgAtK(perfect, gains, 5), 1e-9);
    }

    private static double log2(double x) { return Math.log(x) / Math.log(2); }
}
