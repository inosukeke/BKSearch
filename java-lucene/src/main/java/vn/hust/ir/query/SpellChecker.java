package vn.hust.ir.query;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * "Did you mean?" / sửa lỗi chính tả (S1.5) — thuần Java, không cần OpenSearch.
 *
 * <p>Thuật toán: với mỗi âm tiết sai (không có trong từ vựng), tìm ứng viên trong từ vựng
 * thỏa <b>khoảng cách Levenshtein ≤ ngưỡng</b> VÀ <b>độ tương đồng Jaccard trên k-gram ≥ ngưỡng</b>;
 * chọn ứng viên tốt nhất (Levenshtein nhỏ nhất, hoà thì Jaccard lớn nhất). Chỉ trả gợi ý khi
 * có ít nhất một âm tiết được sửa (G: "không gợi ý linh tinh").
 */
public class SpellChecker {

    private static final int KGRAM = 2;
    private static final double MIN_JACCARD = 0.25;

    private final Set<String> vocab;         // âm tiết đúng (đã chuẩn hóa)

    public SpellChecker(Collection<String> rawVocab) {
        Set<String> v = new LinkedHashSet<>();
        for (String s : rawVocab) {
            if (s == null) continue;
            for (String syl : normalize(s).split("\\s+")) {
                if (!syl.isBlank()) v.add(syl);
            }
        }
        this.vocab = v;
    }

    public int vocabSize() { return vocab.size(); }

    /** Chuẩn hóa độc lập với NLP (NFC + lowercase + gộp space) để class thuần, dễ test. */
    static String normalize(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFC).toLowerCase().replaceAll("\\s+", " ").trim();
    }

    /** Ngưỡng edit distance theo độ dài: từ ngắn khắt khe hơn. */
    private static int maxEdit(String token) {
        int len = token.length();
        if (len <= 2) return 0;
        if (len <= 4) return 1;
        return 2;
    }

    /**
     * Gợi ý cho một âm tiết; rỗng nếu đã đúng hoặc không tìm được ứng viên đủ tốt.
     */
    public Optional<String> suggestToken(String rawToken) {
        String token = normalize(rawToken);
        if (token.isEmpty() || token.length() < 2) return Optional.empty();
        if (vocab.contains(token)) return Optional.empty();          // đã đúng → không gợi ý
        int maxEdit = maxEdit(token);
        if (maxEdit == 0) return Optional.empty();

        String best = null;
        int bestDist = Integer.MAX_VALUE;
        double bestJac = -1;
        for (String cand : vocab) {
            if (Math.abs(cand.length() - token.length()) > maxEdit) continue; // cắt tỉa nhanh
            int d = levenshtein(token, cand);
            if (d > maxEdit) continue;
            double jac = jaccardKgram(token, cand, KGRAM);
            if (jac < MIN_JACCARD) continue;
            if (d < bestDist || (d == bestDist && jac > bestJac)) {
                best = cand; bestDist = d; bestJac = jac;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Gợi ý cho cả truy vấn. Trả truy vấn đã sửa (nếu có ≥1 âm tiết đổi), ngược lại rỗng.
     * Bỏ qua token là toán tử Boolean / dấu nháy để không phá cú pháp.
     */
    public Optional<String> suggestQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) return Optional.empty();
        // Tách theo khoảng trắng của chuỗi gốc để GIỮ nguyên hoa/thường của toán tử.
        String[] tokens = rawQuery.trim().split("\\s+");
        List<String> out = new ArrayList<>(tokens.length);
        boolean changed = false;
        for (String t : tokens) {
            String up = t.toUpperCase();
            if (up.equals("AND") || up.equals("OR") || up.equals("NOT") || t.startsWith("\"")) {
                out.add(t);
                continue;
            }
            Optional<String> s = suggestToken(t);
            if (s.isPresent() && !s.get().equals(normalize(t))) { out.add(s.get()); changed = true; }
            else out.add(normalize(t));
        }
        return changed ? Optional.of(String.join(" ", out)) : Optional.empty();
    }

    // ---- Thuật toán thuần (test riêng) ---------------------------------------

    /** Khoảng cách chỉnh sửa Levenshtein (chèn/xóa/thay) — quy hoạch động O(m*n). */
    public static int levenshtein(String a, String b) {
        if (a == null) a = ""; if (b == null) b = "";
        int m = a.length(), n = b.length();
        if (m == 0) return n;
        if (n == 0) return m;
        int[] prev = new int[n + 1];
        int[] cur = new int[n + 1];
        for (int j = 0; j <= n; j++) prev[j] = j;
        for (int i = 1; i <= m; i++) {
            cur[0] = i;
            for (int j = 1; j <= n; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return prev[n];
    }

    /** Độ tương đồng Jaccard trên tập k-gram (ký tự). 1.0 nếu cả hai rỗng. */
    public static double jaccardKgram(String a, String b, int k) {
        Set<String> ga = kgrams(normalize(a), k);
        Set<String> gb = kgrams(normalize(b), k);
        if (ga.isEmpty() && gb.isEmpty()) return 1.0;
        if (ga.isEmpty() || gb.isEmpty()) return 0.0;
        Set<String> inter = new HashSet<>(ga);
        inter.retainAll(gb);
        int union = ga.size() + gb.size() - inter.size();
        return union == 0 ? 0.0 : (double) inter.size() / union;
    }

    private static Set<String> kgrams(String s, int k) {
        Set<String> out = new HashSet<>();
        if (s == null || s.isEmpty()) return out;
        if (s.length() < k) { out.add(s); return out; }
        for (int i = 0; i + k <= s.length(); i++) out.add(s.substring(i, i + k));
        return out;
    }
}
