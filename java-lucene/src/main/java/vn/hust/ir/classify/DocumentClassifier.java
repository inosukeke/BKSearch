package vn.hust.ir.classify;

import vn.hust.ir.nlp.VietnameseAnalyzer;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Phân loại tài liệu HUST thành danh mục (S3.4): nạp tập huấn luyện, tách từ (G3) rồi huấn luyện
 * {@link NaiveBayes}. Dùng để gán field {@code category} khi index và lọc theo facet trên UI.
 */
public final class DocumentClassifier {

    private final NaiveBayes nb = new NaiveBayes();
    private final VietnameseAnalyzer analyzer;

    public DocumentClassifier(VietnameseAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    /** Nạp mẫu từ resource TSV {@code nhãn<TAB>văn bản} (tách từ sẵn khi train). */
    public static List<NaiveBayes.Sample> loadTraining(String resource) {
        List<NaiveBayes.Sample> out = new ArrayList<>();
        try (InputStream in = DocumentClassifier.class.getResourceAsStream(resource)) {
            if (in == null) return out;
            BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                String label = line.substring(0, tab).trim();
                String text = line.substring(tab + 1).trim();
                if (!label.isEmpty() && !text.isEmpty()) out.add(new NaiveBayes.Sample(text, label));
            }
        } catch (Exception e) {
            System.err.println("[DocumentClassifier] không nạp được " + resource + ": " + e.getMessage());
        }
        return out;
    }

    /** Huấn luyện từ resource mặc định {@code /category-train.tsv}. */
    public DocumentClassifier trainDefault() {
        return train(loadTraining("/category-train.tsv"));
    }

    public DocumentClassifier train(List<NaiveBayes.Sample> raw) {
        List<NaiveBayes.Sample> seg = new ArrayList<>(raw.size());
        for (NaiveBayes.Sample s : raw) seg.add(new NaiveBayes.Sample(analyzer.segment(s.text()), s.label()));
        nb.train(seg);
        return this;
    }

    public boolean isReady() { return nb.isTrained(); }
    public List<String> categories() { return nb.classes(); }

    /** Dự đoán danh mục cho văn bản thô (tự tách từ). */
    public String classify(String rawText) {
        if (!nb.isTrained() || rawText == null || rawText.isBlank()) return null;
        return nb.predict(analyzer.segment(rawText));
    }

    NaiveBayes model() { return nb; }
}
