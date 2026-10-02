package vn.hust.ir.dedup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phát hiện near-duplicate bằng MinHash + LSH banding (S3.2).
 *
 * <p>Quy trình: shingle → chữ ký MinHash → chia chữ ký thành {@code bands} dải × {@code rows}
 * hàng; hai doc rơi cùng một "xô" ở bất kỳ dải nào là <b>ứng viên</b>; xác nhận lại bằng
 * Jaccard ước lượng ≥ {@code threshold} rồi hợp nhóm (union-find). Mỗi nhóm chọn một
 * <b>canonical</b> (id nhỏ nhất theo thứ tự tự nhiên) để giữ lại.
 *
 * <p>Độ phức tạp ~ O(n·bands) thay vì O(n²) nhờ chỉ so các ứng viên cùng xô.
 */
public final class NearDuplicateDetector {

    public static final int DEFAULT_NUM_HASHES = 128;
    public static final int DEFAULT_BANDS = 32;          // rows = 128/32 = 4
    public static final double DEFAULT_THRESHOLD = 0.8;
    public static final long DEFAULT_SEED = 42L;

    private NearDuplicateDetector() {}

    /** Một tài liệu đã có chữ ký. */
    public record Signed(String id, long[] signature) {}

    /**
     * Kết quả: {@code canonical} ánh xạ id → id đại diện của nhóm (id trùng chính nó nếu đứng một
     * mình); {@code clusters} là các nhóm có ≥ 2 thành viên.
     */
    public record Result(Map<String, String> canonical, List<List<String>> clusters) {
        /** Số tài liệu là bản trùng (không phải canonical). */
        public int duplicateCount() {
            int n = 0;
            for (var e : canonical.entrySet()) if (!e.getKey().equals(e.getValue())) n++;
            return n;
        }
    }

    /** Chạy đầy đủ từ văn bản: shingle + MinHash + LSH. */
    public static Result detect(Map<String, String> idToText, int k, int numHashes, long seed,
                                int bands, double threshold) {
        MinHasher hasher = new MinHasher(numHashes, seed);
        List<Signed> signed = new ArrayList<>(idToText.size());
        for (var e : idToText.entrySet()) {
            var sh = Shingling.shingles(e.getValue(), k);
            if (sh.isEmpty()) continue;   // doc rỗng → không gom nhóm
            signed.add(new Signed(e.getKey(), hasher.signature(sh)));
        }
        return cluster(signed, bands, threshold);
    }

    public static Result detect(Map<String, String> idToText) {
        return detect(idToText, Shingling.DEFAULT_K, DEFAULT_NUM_HASHES, DEFAULT_SEED,
                DEFAULT_BANDS, DEFAULT_THRESHOLD);
    }

    /**
     * Gom nhóm từ các chữ ký đã có. {@code bands} phải chia hết độ dài chữ ký (= rows).
     */
    public static Result cluster(List<Signed> docs, int bands, double threshold) {
        LinkedHashMap<String, String> canonical = new LinkedHashMap<>();
        for (Signed d : docs) canonical.put(d.id(), d.id());   // mặc định: tự đại diện
        if (docs.isEmpty()) return new Result(canonical, List.of());

        int n = docs.size();
        int sigLen = docs.get(0).signature().length;
        if (bands <= 0 || sigLen % bands != 0) {
            throw new IllegalArgumentException("bands phải chia hết độ dài chữ ký (" + sigLen + ")");
        }
        int rows = sigLen / bands;

        // union-find
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        // LSH: mỗi dải → map khóa-xô → danh sách chỉ số doc.
        for (int band = 0; band < bands; band++) {
            Map<String, List<Integer>> buckets = new LinkedHashMap<>();
            int from = band * rows;
            for (int i = 0; i < n; i++) {
                long[] sig = docs.get(i).signature();
                StringBuilder key = new StringBuilder();
                for (int r = 0; r < rows; r++) key.append(sig[from + r]).append(',');
                buckets.computeIfAbsent(key.toString(), kk -> new ArrayList<>()).add(i);
            }
            // Trong mỗi xô, xác nhận Jaccard rồi hợp nhóm.
            for (List<Integer> bucket : buckets.values()) {
                if (bucket.size() < 2) continue;
                int base = bucket.get(0);
                for (int t = 1; t < bucket.size(); t++) {
                    int j = bucket.get(t);
                    if (MinHasher.estimatedJaccard(docs.get(base).signature(),
                            docs.get(j).signature()) >= threshold) {
                        union(parent, base, j);
                    }
                }
            }
        }

        // Gom theo gốc union-find; canonical = id nhỏ nhất (theo thứ tự tự nhiên) trong nhóm.
        Map<Integer, List<String>> groups = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            groups.computeIfAbsent(find(parent, i), r -> new ArrayList<>()).add(docs.get(i).id());
        }
        List<List<String>> clusters = new ArrayList<>();
        for (List<String> members : groups.values()) {
            String canon = members.stream().min(String::compareTo).orElse(members.get(0));
            for (String id : members) canonical.put(id, canon);
            if (members.size() >= 2) clusters.add(members);
        }
        return new Result(canonical, clusters);
    }

    private static int find(int[] p, int x) {
        while (p[x] != x) { p[x] = p[p[x]]; x = p[x]; }
        return x;
    }

    private static void union(int[] p, int a, int b) {
        int ra = find(p, a), rb = find(p, b);
        if (ra != rb) p[Math.max(ra, rb)] = Math.min(ra, rb);
    }
}
