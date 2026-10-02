package vn.hust.ir.dedup;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Sinh w-shingle (k token liên tiếp) từ văn bản (S3.2). Near-duplicate dựa trên tập shingle:
 * hai trang gần trùng chia sẻ phần lớn shingle.
 *
 * <p>Chuẩn hóa: lowercase, gộp khoảng trắng, tách theo khoảng trắng. Văn bản ngắn hơn k token
 * → trả về một shingle là toàn bộ chuỗi (để trang rất ngắn vẫn có đại diện).
 */
public final class Shingling {

    public static final int DEFAULT_K = 5;

    private Shingling() {}

    public static Set<String> shingles(String text, int k) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        String norm = text.toLowerCase().replaceAll("\\s+", " ").trim();
        if (norm.isEmpty()) return out;
        String[] toks = norm.split(" ");
        if (k <= 0) k = DEFAULT_K;
        if (toks.length < k) {
            out.add(String.join(" ", toks));
            return out;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + k <= toks.length; i++) {
            sb.setLength(0);
            for (int j = 0; j < k; j++) {
                if (j > 0) sb.append(' ');
                sb.append(toks[i + j]);
            }
            out.add(sb.toString());
        }
        return out;
    }

    public static Set<String> shingles(String text) {
        return shingles(text, DEFAULT_K);
    }
}
