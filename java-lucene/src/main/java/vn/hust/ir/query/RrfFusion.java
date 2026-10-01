package vn.hust.ir.query;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion (RRF) — hợp nhất nhiều danh sách xếp hạng thành một (S2.4).
 *
 * <p>Công thức: với mỗi tài liệu {@code d}, điểm {@code RRF(d) = Σ 1 / (k + rank_i(d))}
 * trong đó {@code rank_i} là vị trí 1-based của {@code d} ở danh sách thứ {@code i}
 * (chỉ cộng nếu {@code d} xuất hiện trong danh sách đó).
 *
 * <p>Ưu điểm: KHÔNG cần chuẩn hóa thang điểm giữa BM25 và vector (chỉ dùng thứ hạng) →
 * bền vững hơn cộng điểm trực tiếp. Tham số {@code k} làm mượt ảnh hưởng của top đầu
 * (k lớn → các hạng gần nhau hơn); mặc định 60 theo bài báo gốc (Cormack et al. 2009).
 *
 * <p>Hàm thuần (không phụ thuộc OpenSearch) để kiểm thử bằng dữ liệu giả.
 */
public final class RrfFusion {

    /** k mặc định theo bài báo RRF gốc. */
    public static final int DEFAULT_K = 60;

    private RrfFusion() {}

    /**
     * Hợp nhất các danh sách xếp hạng (mỗi danh sách là thứ tự giảm dần độ liên quan).
     *
     * @param rankedLists danh sách các danh sách id (vd url) đã xếp hạng
     * @param k           tham số làm mượt RRF (&gt; 0)
     * @param <T>         kiểu định danh tài liệu
     * @return map id → điểm RRF, SẮP XẾP giảm dần theo điểm (insertion-ordered)
     */
    public static <T> LinkedHashMap<T, Double> fuse(List<List<T>> rankedLists, int k) {
        int kk = k > 0 ? k : DEFAULT_K;
        Map<T, Double> acc = new LinkedHashMap<>();
        if (rankedLists != null) {
            for (List<T> list : rankedLists) {
                if (list == null) continue;
                for (int rank = 0; rank < list.size(); rank++) {
                    T id = list.get(rank);
                    if (id == null) continue;
                    double contrib = 1.0 / (kk + (rank + 1)); // rank 1-based
                    acc.merge(id, contrib, Double::sum);
                }
            }
        }
        // Sắp xếp giảm dần theo điểm, ổn định (giữ thứ tự gặp đầu tiên khi bằng điểm).
        LinkedHashMap<T, Double> out = new LinkedHashMap<>();
        acc.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    /** Như {@link #fuse(List, int)} nhưng chỉ trả danh sách id đã hợp nhất (giảm dần). */
    public static <T> List<T> fuseToList(List<List<T>> rankedLists, int k) {
        return new java.util.ArrayList<>(fuse(rankedLists, k).keySet());
    }
}
