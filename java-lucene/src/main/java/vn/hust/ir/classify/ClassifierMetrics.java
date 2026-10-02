package vn.hust.ir.classify;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Độ đo phân lớp (S3.4): precision/recall/F1 theo lớp + macro-F1 + accuracy. */
public final class ClassifierMetrics {

    private ClassifierMetrics() {}

    public record PerClass(String label, int tp, int fp, int fn, double precision, double recall, double f1) {}
    public record Report(double accuracy, double macroF1, List<PerClass> perClass, int n) {
        public String pretty() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("n=%d  accuracy=%.3f  macroF1=%.3f%n", n, accuracy, macroF1));
            sb.append(String.format("  %-16s %6s %6s %6s%n", "lớp", "P", "R", "F1"));
            for (PerClass p : perClass) {
                sb.append(String.format("  %-16s %6.3f %6.3f %6.3f%n", p.label(), p.precision(), p.recall(), p.f1()));
            }
            return sb.toString();
        }
    }

    /** Tính báo cáo từ cặp (nhãn thật, nhãn dự đoán) cùng độ dài. */
    public static Report evaluate(List<String> actual, List<String> predicted) {
        if (actual.size() != predicted.size()) {
            throw new IllegalArgumentException("actual/predicted khác độ dài");
        }
        int n = actual.size();
        Map<String, int[]> stat = new LinkedHashMap<>();   // label → [tp, fp, fn]
        int correct = 0;
        for (int i = 0; i < n; i++) {
            String a = actual.get(i), p = predicted.get(i);
            stat.computeIfAbsent(a, k -> new int[3]);
            if (p != null) stat.computeIfAbsent(p, k -> new int[3]);
            if (a.equals(p)) { stat.get(a)[0]++; correct++; }
            else { if (p != null) stat.get(p)[1]++; stat.get(a)[2]++; }
        }
        java.util.List<PerClass> per = new java.util.ArrayList<>();
        double sumF1 = 0;
        for (var e : stat.entrySet()) {
            int tp = e.getValue()[0], fp = e.getValue()[1], fn = e.getValue()[2];
            double prec = tp + fp == 0 ? 0 : (double) tp / (tp + fp);
            double rec = tp + fn == 0 ? 0 : (double) tp / (tp + fn);
            double f1 = prec + rec == 0 ? 0 : 2 * prec * rec / (prec + rec);
            per.add(new PerClass(e.getKey(), tp, fp, fn, prec, rec, f1));
            sumF1 += f1;
        }
        double macroF1 = per.isEmpty() ? 0 : sumF1 / per.size();
        double acc = n == 0 ? 0 : (double) correct / n;
        return new Report(acc, macroF1, per, n);
    }
}
