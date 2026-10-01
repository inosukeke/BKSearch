package vn.hust.ir.nlp;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Xử lý tiếng Việt cho tìm kiếm — đáp ứng yêu cầu "tách từ & chuẩn hóa tiếng Việt".
 *
 * Ba bước:
 *   1) Chuẩn hóa: Unicode NFC + viết thường + bỏ ký tự thừa.
 *   2) Tách từ bằng thuật toán Maximum Matching (khớp dài nhất) dựa trên từ điển
 *      từ ghép -> ghép "đại học" thành "đại_học".
 *   3) Bỏ stopwords tiếng Việt.
 *
 * Kết quả trả về là chuỗi token cách nhau bằng dấu cách, dùng chung cho cả lúc
 * đánh chỉ mục và lúc truy vấn (bảo đảm nhất quán — nguyên tắc cốt lõi của IR).
 */
public class VietnameseSegmenter {

    private final Set<String> dictionary = new HashSet<>();
    private final Set<String> stopwords = new HashSet<>();
    private int maxWordLen = 1;   // số âm tiết dài nhất trong từ điển

    public VietnameseSegmenter() {
        loadDictionary("/vi-words.txt");
        loadStopwords("/vi-stopwords.txt");
    }

    private void loadDictionary(String resource) {
        for (String line : readLines(resource)) {
            String w = normalize(line);
            if (w.isEmpty()) continue;
            dictionary.add(w);
            int len = w.split(" ").length;
            if (len > maxWordLen) maxWordLen = len;
        }
    }

    private void loadStopwords(String resource) {
        for (String line : readLines(resource)) {
            String w = normalize(line);
            if (!w.isEmpty()) stopwords.add(w);
        }
    }

    private List<String> readLines(String resource) {
        List<String> out = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            if (in == null) return out;
            BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                out.add(line);
            }
        } catch (Exception e) {
            System.err.println("Không đọc được " + resource + ": " + e.getMessage());
        }
        return out;
    }

    /** Chuẩn hóa: NFC + thường + gộp khoảng trắng. */
    public String normalize(String text) {
        if (text == null) return "";
        String s = Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase();
        return s.replaceAll("\\s+", " ").trim();
    }

    /**
     * Tách từ + bỏ stopwords, trả chuỗi token (âm tiết ghép nối bằng '_').
     * Ví dụ: "Trường Đại học Bách khoa" -> "trường đại_học bách_khoa".
     */
    public String segment(String text) {
        String norm = normalize(text);
        if (norm.isEmpty()) return "";
        // Chỉ giữ chữ/số + khoảng trắng (bỏ dấu câu) để tách âm tiết.
        norm = norm.replaceAll("[^\\p{L}\\p{Nd}\\s]", " ").replaceAll("\\s+", " ").trim();
        if (norm.isEmpty()) return "";

        String[] syl = norm.split(" ");
        List<String> tokens = new ArrayList<>();
        int i = 0;
        while (i < syl.length) {
            int matchLen = 1;
            // Thử khớp dài nhất (Maximum Matching).
            for (int len = Math.min(maxWordLen, syl.length - i); len >= 2; len--) {
                String cand = String.join(" ", java.util.Arrays.copyOfRange(syl, i, i + len));
                if (dictionary.contains(cand)) {
                    matchLen = len;
                    break;
                }
            }
            String token = String.join("_", java.util.Arrays.copyOfRange(syl, i, i + matchLen));
            String bare = token.replace("_", " ");
            // Bỏ stopword đơn âm tiết.
            if (!(matchLen == 1 && stopwords.contains(bare))) {
                tokens.add(token);
            }
            i += matchLen;
        }
        return String.join(" ", tokens);
    }

    public int dictionarySize() { return dictionary.size(); }
    public int stopwordSize()   { return stopwords.size(); }
}
