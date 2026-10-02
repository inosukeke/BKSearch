package vn.hust.ir.classify;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phân lớp văn bản Naïve Bayes đa thức (multinomial) với làm trơn Laplace (S3.4).
 *
 * <p>Huấn luyện: đếm tần suất token theo lớp + tiên nghiệm lớp. Dự đoán: chọn lớp cực đại
 * {@code log P(c) + Σ tf(t)·log P(t|c)} (dùng log để tránh tràn số). Token là chuỗi đã tách
 * (space-separated); gọi bên ngoài quyết định tách từ (G3 — nên truyền văn bản đã segment).
 */
public final class NaiveBayes {

    private final List<String> classes = new ArrayList<>();
    private final Map<String, Double> logPrior = new LinkedHashMap<>();
    private final Map<String, Map<String, Integer>> tokenCount = new LinkedHashMap<>();
    private final Map<String, Long> classTokenTotal = new LinkedHashMap<>();
    private int vocabSize = 0;
    private boolean trained = false;

    /** Một mẫu huấn luyện: văn bản + nhãn lớp. */
    public record Sample(String text, String label) {}

    public List<String> classes() { return List.copyOf(classes); }
    public boolean isTrained() { return trained; }

    /** Huấn luyện từ danh sách mẫu. */
    public void train(List<Sample> samples) {
        classes.clear(); logPrior.clear(); tokenCount.clear(); classTokenTotal.clear();
        Map<String, Integer> docPerClass = new LinkedHashMap<>();
        java.util.Set<String> vocab = new java.util.HashSet<>();
        int totalDocs = 0;

        for (Sample s : samples) {
            if (s.label() == null || s.label().isBlank()) continue;
            String c = s.label().trim();
            docPerClass.merge(c, 1, Integer::sum);
            tokenCount.computeIfAbsent(c, k -> new HashMap<>());
            classTokenTotal.putIfAbsent(c, 0L);
            totalDocs++;
            for (String tok : tokenize(s.text())) {
                vocab.add(tok);
                tokenCount.get(c).merge(tok, 1, Integer::sum);
                classTokenTotal.merge(c, 1L, Long::sum);
            }
        }
        if (totalDocs == 0) return;
        vocabSize = vocab.size();
        for (var e : docPerClass.entrySet()) {
            classes.add(e.getKey());
            logPrior.put(e.getKey(), Math.log((double) e.getValue() / totalDocs));
        }
        trained = true;
    }

    /** Dự đoán lớp cho văn bản; chưa train hoặc văn bản rỗng → null. */
    public String predict(String text) {
        var scores = scores(text);
        String best = null; double bestVal = Double.NEGATIVE_INFINITY;
        for (var e : scores.entrySet()) {
            if (e.getValue() > bestVal) { bestVal = e.getValue(); best = e.getKey(); }
        }
        return best;
    }

    /** Điểm log-posterior mỗi lớp (để kiểm tra/ngưỡng tin cậy). */
    public Map<String, Double> scores(String text) {
        LinkedHashMap<String, Double> out = new LinkedHashMap<>();
        if (!trained) return out;
        List<String> toks = tokenize(text);
        for (String c : classes) {
            double logp = logPrior.get(c);
            Map<String, Integer> counts = tokenCount.get(c);
            double denom = classTokenTotal.get(c) + (double) vocabSize;   // Laplace
            for (String t : toks) {
                int tf = counts.getOrDefault(t, 0);
                logp += Math.log((tf + 1.0) / denom);
            }
            out.put(c, logp);
        }
        return out;
    }

    static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String t : text.toLowerCase().replaceAll("\\s+", " ").trim().split(" ")) {
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
