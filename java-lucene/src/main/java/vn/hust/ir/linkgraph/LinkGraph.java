package vn.hust.ir.linkgraph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Đồ thị liên kết web `*.hust.edu.vn` (S3.1): mỗi node là một URL, mỗi cạnh có hướng
 * {@code src → dst} kèm <b>anchor text</b> của liên kết đó.
 *
 * <p>Thiết kế thuần (không phụ thuộc OpenSearch/DB) để {@link PageRank} và việc gom anchor
 * test được dễ dàng. URL được "intern" thành chỉ số nguyên liên tục (0..n-1) theo thứ tự
 * xuất hiện để PageRank chạy trên mảng.
 *
 * <ul>
 *   <li>Bỏ qua <b>self-loop</b> (trang tự trỏ chính nó không làm tăng uy tín).</li>
 *   <li>Cạnh trùng (src,dst) chỉ tính <b>một lần</b> cho PageRank, nhưng mọi anchor đều được
 *       gom cho dst (anchor text là tín hiệu riêng).</li>
 * </ul>
 */
public class LinkGraph {

    private final Map<String, Integer> index = new LinkedHashMap<>();
    private final List<String> urls = new ArrayList<>();
    /** out.get(i) = tập chỉ số đích (đã khử trùng) của node i. */
    private final List<java.util.LinkedHashSet<Integer>> out = new ArrayList<>();
    /** dst url → danh sách anchor text trỏ tới nó (giữ trùng để đếm tần suất). */
    private final Map<String, List<String>> inboundAnchors = new LinkedHashMap<>();

    /** Thêm một node (URL) nếu chưa có; trả chỉ số của nó. */
    public int addNode(String url) {
        return intern(url);
    }

    private int intern(String url) {
        Integer i = index.get(url);
        if (i != null) return i;
        int id = urls.size();
        index.put(url, id);
        urls.add(url);
        out.add(new java.util.LinkedHashSet<>());
        return id;
    }

    /**
     * Thêm cạnh {@code src → dst} với anchor (có thể rỗng). Tự intern cả hai đầu.
     * Self-loop bị bỏ qua. Anchor không rỗng được gom cho {@code dst}.
     */
    public void addEdge(String src, String dst, String anchor) {
        if (src == null || dst == null) return;
        if (src.equals(dst)) {               // self-loop → bỏ (vẫn đảm bảo node tồn tại)
            intern(src);
            return;
        }
        int s = intern(src);
        int d = intern(dst);
        out.get(s).add(d);
        if (anchor != null && !anchor.isBlank()) {
            inboundAnchors.computeIfAbsent(dst, k -> new ArrayList<>()).add(anchor.trim());
        }
    }

    /** Số node. */
    public int size() { return urls.size(); }

    /** URL của node theo chỉ số (0..n-1). */
    public String url(int i) { return urls.get(i); }

    /** Danh sách URL theo đúng thứ tự chỉ số. */
    public List<String> urls() { return List.copyOf(urls); }

    /** Chỉ số đích của node i (đã khử trùng). */
    public int[] outLinks(int i) {
        java.util.LinkedHashSet<Integer> set = out.get(i);
        int[] a = new int[set.size()];
        int k = 0;
        for (int t : set) a[k++] = t;
        return a;
    }

    /** Bậc ra (số liên kết ra khác nhau) của node i. */
    public int outDegree(int i) { return out.get(i).size(); }

    /** Danh sách anchor text trỏ tới {@code url} (có thể rỗng). */
    public List<String> anchorsFor(String url) {
        return inboundAnchors.getOrDefault(url, List.of());
    }

    /** Toàn bộ map anchor theo dst (bất biến nông). */
    public Map<String, List<String>> inboundAnchors() {
        return Map.copyOf(inboundAnchors);
    }
}
