package vn.hust.ir.linkgraph;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PageRank bằng power iteration (S3.1) trên {@link LinkGraph}.
 *
 * <p>Công thức mỗi vòng:
 * <pre>
 *   PR(i) = (1 - d) / N  +  d * ( Σ_{j→i} PR(j)/L(j)  +  dangling/N )
 * </pre>
 * với {@code d} = hệ số damping (0.85), {@code N} = số node, {@code L(j)} = bậc ra của j,
 * và {@code dangling} = tổng PR của các node không có liên kết ra (được phân bổ đều cho mọi
 * node để tổng PR luôn = 1 — nếu không rank sẽ "rò rỉ" ra khỏi hệ).
 *
 * <p>Hội tụ khi chuẩn L1 giữa hai vòng {@code < tol} (hoặc đạt {@code maxIter}). Trả về map
 * {@code url → PR} (tổng ≈ 1). Đồ thị rỗng → map rỗng.
 */
public final class PageRank {

    public static final double DEFAULT_DAMPING = 0.85;
    public static final int DEFAULT_MAX_ITER = 100;
    public static final double DEFAULT_TOL = 1e-8;

    private PageRank() {}

    /** Kết quả tính PageRank: điểm theo url + số vòng lặp + đã hội tụ chưa. */
    public record Result(Map<String, Double> scores, int iterations, boolean converged) {}

    public static Result compute(LinkGraph g) {
        return compute(g, DEFAULT_DAMPING, DEFAULT_MAX_ITER, DEFAULT_TOL);
    }

    public static Result compute(LinkGraph g, double damping, int maxIter, double tol) {
        int n = g.size();
        LinkedHashMap<String, Double> empty = new LinkedHashMap<>();
        if (n == 0) return new Result(empty, 0, true);

        // Tiền tính bậc ra + danh sách đích.
        int[][] out = new int[n][];
        int[] deg = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = g.outLinks(i);
            deg[i] = out[i].length;
        }

        double[] pr = new double[n];
        double init = 1.0 / n;
        for (int i = 0; i < n; i++) pr[i] = init;

        double base = (1.0 - damping) / n;
        double[] next = new double[n];
        int iter = 0;
        boolean converged = false;

        for (; iter < maxIter; iter++) {
            // Tổng rank của các node treo (không có liên kết ra) → phân bổ đều.
            double dangling = 0.0;
            for (int i = 0; i < n; i++) if (deg[i] == 0) dangling += pr[i];
            double danglingShare = damping * dangling / n;

            for (int i = 0; i < n; i++) next[i] = base + danglingShare;
            // Dồn rank theo cạnh j→i.
            for (int j = 0; j < n; j++) {
                if (deg[j] == 0) continue;
                double share = damping * pr[j] / deg[j];
                for (int t : out[j]) next[t] += share;
            }

            double diff = 0.0;
            for (int i = 0; i < n; i++) {
                diff += Math.abs(next[i] - pr[i]);
                pr[i] = next[i];
            }
            if (diff < tol) { converged = true; iter++; break; }
        }

        LinkedHashMap<String, Double> scores = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) scores.put(g.url(i), pr[i]);
        return new Result(scores, iter, converged);
    }
}
