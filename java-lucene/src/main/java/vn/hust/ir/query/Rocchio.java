package vn.hust.ir.query;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mở rộng truy vấn bằng pseudo-relevance feedback kiểu Rocchio (S3.3).
 *
 * <p>Giả định top-k kết quả đầu là "liên quan" (pseudo-relevant), rút các token nổi bật trong
 * chúng để bổ sung vào truy vấn (nhánh {@code should} — tăng recall). Bản rút gọn của Rocchio
 * chỉ dùng phản hồi DƯƠNG: điểm mỗi token = Σ tần suất trong các doc pseudo-relevant, loại
 * token đã có trong truy vấn, stopword, token quá ngắn; lấy top-N.
 */
public final class Rocchio {

    private Rocchio() {}

    /**
     * @param pseudoRelevantSegTexts văn bản ĐÃ tách từ (token cách nhau bởi khoảng trắng) của top-k
     * @param queryTerms             token truy vấn (đã tách từ) để loại khỏi mở rộng
     * @param stopwords              tập stopword (token đơn) để loại
     * @param topN                   số token mở rộng tối đa
     * @return danh sách token mở rộng, giảm dần theo điểm
     */
    public static List<String> selectTerms(List<String> pseudoRelevantSegTexts, Set<String> queryTerms,
                                           Set<String> stopwords, int topN) {
        if (pseudoRelevantSegTexts == null || pseudoRelevantSegTexts.isEmpty() || topN <= 0) {
            return List.of();
        }
        Map<String, Integer> freq = new HashMap<>();
        for (String text : pseudoRelevantSegTexts) {
            if (text == null || text.isBlank()) continue;
            for (String tok : text.toLowerCase().split("\\s+")) {
                if (tok.length() < 2) continue;                       // bỏ token 1 ký tự
                if (queryTerms != null && queryTerms.contains(tok)) continue;
                if (stopwords != null && stopwords.contains(stripUnderscore(tok))) continue;
                if (!isWordy(tok)) continue;                          // bỏ token toàn số/ký hiệu
                freq.merge(tok, 1, Integer::sum);
            }
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(freq.entrySet());
        entries.sort((a, b) -> {
            int c = Integer.compare(b.getValue(), a.getValue());
            return c != 0 ? c : a.getKey().compareTo(b.getKey());     // ổn định
        });
        List<String> out = new ArrayList<>(Math.min(topN, entries.size()));
        for (int i = 0; i < entries.size() && out.size() < topN; i++) out.add(entries.get(i).getKey());
        return out;
    }

    /** "đại_học" → "đại" để so với stopword đơn (token ghép ít khi là stopword). */
    private static String stripUnderscore(String tok) {
        int u = tok.indexOf('_');
        return u < 0 ? tok : tok.substring(0, u);
    }

    private static boolean isWordy(String tok) {
        for (int i = 0; i < tok.length(); i++) {
            char c = tok.charAt(i);
            if (Character.isLetter(c)) return true;
        }
        return false;
    }
}
