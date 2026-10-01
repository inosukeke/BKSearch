package vn.hust.ir.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Độ đo đánh giá truy xuất (S1.7): Precision@k, Recall@k, F1@k, MAP (AP), MRR (RR),
 * nDCG@k. Toàn hàm thuần (không I/O) để kiểm thử bằng dữ liệu giả.
 *
 * <p>Quy ước: {@code ranked} là danh sách doc-id đã xếp hạng (vị trí 0 = hạng 1).
 * Nhị phân: {@code relevant} là tập doc-id phù hợp. Có mức độ: {@code gains} ánh xạ
 * doc-id → điểm phù hợp (>=0), thiếu = 0.
 */
public final class Metrics {

    private Metrics() {}

    /** Precision@k = (số doc phù hợp trong top-k) / k. */
    public static double precisionAtK(List<String> ranked, Set<String> relevant, int k) {
        if (k <= 0) return 0.0;
        int hit = 0, n = Math.min(k, ranked.size());
        for (int i = 0; i < n; i++) if (relevant.contains(ranked.get(i))) hit++;
        return (double) hit / k;
    }

    /** Recall@k = (số doc phù hợp trong top-k) / (tổng số doc phù hợp). */
    public static double recallAtK(List<String> ranked, Set<String> relevant, int k) {
        if (relevant.isEmpty()) return 0.0;
        int hit = 0, n = Math.min(k, ranked.size());
        for (int i = 0; i < n; i++) if (relevant.contains(ranked.get(i))) hit++;
        return (double) hit / relevant.size();
    }

    /** F1@k = trung bình điều hòa của Precision@k và Recall@k. */
    public static double f1AtK(List<String> ranked, Set<String> relevant, int k) {
        double p = precisionAtK(ranked, relevant, k);
        double r = recallAtK(ranked, relevant, k);
        return (p + r == 0) ? 0.0 : 2 * p * r / (p + r);
    }

    /** Average Precision (AP) cho một truy vấn — trung bình các Precision tại mỗi vị trí phù hợp. */
    public static double averagePrecision(List<String> ranked, Set<String> relevant) {
        if (relevant.isEmpty()) return 0.0;
        int hit = 0;
        double sum = 0.0;
        for (int i = 0; i < ranked.size(); i++) {
            if (relevant.contains(ranked.get(i))) {
                hit++;
                sum += (double) hit / (i + 1);
            }
        }
        return sum / relevant.size();
    }

    /** Reciprocal Rank — 1/hạng của kết quả phù hợp đầu tiên; 0 nếu không có. */
    public static double reciprocalRank(List<String> ranked, Set<String> relevant) {
        for (int i = 0; i < ranked.size(); i++) {
            if (relevant.contains(ranked.get(i))) return 1.0 / (i + 1);
        }
        return 0.0;
    }

    /** DCG@k với gain mũ: sum (2^rel - 1) / log2(pos + 1). */
    public static double dcgAtK(List<String> ranked, Map<String, Integer> gains, int k) {
        double dcg = 0.0;
        int n = Math.min(k, ranked.size());
        for (int i = 0; i < n; i++) {
            int rel = gains.getOrDefault(ranked.get(i), 0);
            if (rel != 0) dcg += (Math.pow(2, rel) - 1) / (Math.log(i + 2) / Math.log(2));
        }
        return dcg;
    }

    /** IDCG@k — DCG của thứ tự lý tưởng (gain giảm dần). */
    public static double idcgAtK(Map<String, Integer> gains, int k) {
        List<Integer> vals = new ArrayList<>(gains.values());
        vals.sort((a, b) -> Integer.compare(b, a));
        double idcg = 0.0;
        int n = Math.min(k, vals.size());
        for (int i = 0; i < n; i++) {
            int rel = vals.get(i);
            if (rel != 0) idcg += (Math.pow(2, rel) - 1) / (Math.log(i + 2) / Math.log(2));
        }
        return idcg;
    }

    /** nDCG@k = DCG@k / IDCG@k (0 nếu IDCG=0). */
    public static double ndcgAtK(List<String> ranked, Map<String, Integer> gains, int k) {
        double idcg = idcgAtK(gains, k);
        return idcg == 0 ? 0.0 : dcgAtK(ranked, gains, k) / idcg;
    }
}
