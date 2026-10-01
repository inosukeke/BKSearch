package vn.hust.ir.query;

import java.util.ArrayList;
import java.util.List;

/**
 * Tiện ích sắp xếp lại theo điểm rerank (S2.5) — hàm thuần để kiểm thử.
 *
 * <p>Cross-encoder trả điểm theo đúng thứ tự tài liệu đầu vào; lớp này chỉ lo phần
 * SẮP XẾP LẠI (tách khỏi I/O) để test được bằng dữ liệu giả.
 */
public final class Reranker {

    private Reranker() {}

    /**
     * Trả mảng chỉ số {@code [0..n)} sắp xếp theo điểm GIẢM DẦN, ỔN ĐỊNH khi bằng điểm
     * (giữ nguyên thứ tự gốc — thường là thứ hạng hybrid trước rerank).
     */
    public static int[] order(double[] scores) {
        int n = scores == null ? 0 : scores.length;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> {
            int c = Double.compare(scores[b], scores[a]);   // giảm dần
            return c != 0 ? c : Integer.compare(a, b);       // bằng điểm → giữ thứ tự gốc
        });
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = idx[i];
        return out;
    }

    /**
     * Sắp xếp lại {@code items} theo {@code scores} (giảm dần, ổn định).
     * @throws IllegalArgumentException nếu kích thước lệch nhau.
     */
    public static <T> List<T> reorder(List<T> items, double[] scores) {
        if (items.size() != (scores == null ? 0 : scores.length)) {
            throw new IllegalArgumentException("items và scores phải cùng kích thước: "
                    + items.size() + " vs " + (scores == null ? 0 : scores.length));
        }
        int[] order = order(scores);
        List<T> out = new ArrayList<>(items.size());
        for (int i : order) out.add(items.get(i));
        return out;
    }
}
