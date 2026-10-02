package vn.hust.ir.classify;

import org.junit.jupiter.api.Test;
import vn.hust.ir.nlp.VietnameseAnalyzer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** S3.4: Naïve Bayes + metrics + classifier (tập train bundled) + cross-validation. */
class ClassifyTest {

    @Test
    void naiveBayes_learnsSeparableClasses() {
        NaiveBayes nb = new NaiveBayes();
        nb.train(List.of(
                new NaiveBayes.Sample("mèo chó thú cưng", "động vật"),
                new NaiveBayes.Sample("chó sủa mèo kêu", "động vật"),
                new NaiveBayes.Sample("java python lập trình", "công nghệ"),
                new NaiveBayes.Sample("python máy tính phần mềm", "công nghệ")));
        assertEquals("động vật", nb.predict("con mèo"));
        assertEquals("công nghệ", nb.predict("ngôn ngữ python"));
    }

    @Test
    void naiveBayes_untrained_returnsNull() {
        assertNull(new NaiveBayes().predict("gì đó"));
    }

    @Test
    void metrics_computesPrecisionRecallF1() {
        var r = ClassifierMetrics.evaluate(
                List.of("a", "a", "b", "b"),
                List.of("a", "b", "b", "b"));
        assertEquals(0.75, r.accuracy(), 1e-9);              // 3/4 đúng
        assertEquals(4, r.n());
        var b = r.perClass().stream().filter(p -> p.label().equals("b")).findFirst().orElseThrow();
        assertEquals(2, b.tp());
        assertEquals(1, b.fp());                              // 1 'a' bị gán 'b'
        assertEquals(1.0, b.recall(), 1e-9);
    }

    @Test
    void documentClassifier_defaultTrainingClassifiesSeedCategories() {
        DocumentClassifier dc = new DocumentClassifier(VietnameseAnalyzer.get()).trainDefault();
        assertTrue(dc.isReady());
        assertTrue(dc.categories().contains("tuyển sinh"));
        String c = dc.classify("thông báo tuyển sinh đại học năm 2026 chỉ tiêu xét tuyển");
        assertEquals("tuyển sinh", c);
    }

    @Test
    void crossValidation_reasonableAccuracy() {
        var report = ClassifyRunner.crossValidate(VietnameseAnalyzer.get());
        // Tập seed phân biệt tốt → LOO accuracy nên khá cao (ngưỡng an toàn 0.6).
        assertTrue(report.accuracy() >= 0.6, "accuracy LOO = " + report.accuracy());
        assertTrue(report.n() > 0);
    }
}
