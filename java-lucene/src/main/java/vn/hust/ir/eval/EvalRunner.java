package vn.hust.ir.eval;

import vn.hust.ir.query.Ranker;
import vn.hust.ir.query.SearchEngine;
import vn.hust.ir.query.SearchHit;
import vn.hust.ir.query.SearchResponse;
import vn.hust.ir.query.SpellChecker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bộ chạy đánh giá (S1.7): nạp queries + qrels, chạy 3 ranker qua OpenSearch,
 * tính P@k/Recall@k/F1@k/MAP/MRR/nDCG@k và in bảng so sánh.
 *
 * <p>qrels theo định dạng TREC: {@code <qid> 0 <docid=url> <rel>} (rel>0 là phù hợp).
 * queries theo TSV: {@code <qid>\t<câu truy vấn>}.
 *
 * <p>Cần OpenSearch sống + corpus đã index (chạy ở local). Trên môi trường không có engine,
 * phần tính độ đo được kiểm thử riêng qua {@link Metrics} bằng dữ liệu giả.
 */
public class EvalRunner {

    private final String osUrl;
    private final String baseIndex;
    private final int k;

    public EvalRunner(String osUrl, String baseIndex, int k) {
        this.osUrl = osUrl;
        this.baseIndex = baseIndex;
        this.k = Math.max(1, k);
    }

    public void run(Path queriesFile, Path qrelsFile) throws Exception {
        Map<String, String> queries = loadQueries(queriesFile);
        Map<String, Map<String, Integer>> qrels = loadQrels(qrelsFile);
        System.out.printf("Nạp %d truy vấn, %d truy vấn có qrels. k=%d%n",
                queries.size(), qrels.size(), k);

        SearchEngine engine = new SearchEngine(osUrl, baseIndex, new SpellChecker(List.of()));
        Ranker[] rankers = { Ranker.BM25, Ranker.VSM, Ranker.LM };

        Map<Ranker, Aggregate> table = new LinkedHashMap<>();
        for (Ranker r : rankers) {
            Aggregate agg = new Aggregate();
            int evaluated = 0;
            for (var e : queries.entrySet()) {
                Map<String, Integer> gains = qrels.get(e.getKey());
                if (gains == null || gains.isEmpty()) continue;
                Set<String> relevant = gains.entrySet().stream()
                        .filter(x -> x.getValue() > 0).map(Map.Entry::getKey)
                        .collect(java.util.stream.Collectors.toSet());
                if (relevant.isEmpty()) continue;

                List<String> ranked;
                try {
                    ranked = runQuery(engine, e.getValue(), r);
                } catch (Exception ex) {
                    System.err.println("[WARN] bỏ qua qid=" + e.getKey() + " ranker=" + r.param()
                            + ": " + ex.getMessage());
                    continue;
                }
                agg.pAtK   += Metrics.precisionAtK(ranked, relevant, k);
                agg.rAtK   += Metrics.recallAtK(ranked, relevant, k);
                agg.f1AtK  += Metrics.f1AtK(ranked, relevant, k);
                agg.map    += Metrics.averagePrecision(ranked, relevant);
                agg.mrr    += Metrics.reciprocalRank(ranked, relevant);
                agg.ndcg   += Metrics.ndcgAtK(ranked, gains, k);
                evaluated++;
            }
            agg.n = evaluated;
            table.put(r, agg);
        }

        String report = formatTable(table, k);
        System.out.println(report);
        writeReport(report);
    }

    private List<String> runQuery(SearchEngine engine, String text, Ranker r) throws Exception {
        SearchResponse res = engine.search(text, 1, k, r);
        List<String> urls = new ArrayList<>();
        for (SearchHit h : res.results) urls.add(h.url);
        return urls;
    }

    // ---- Nạp file ------------------------------------------------------------

    static Map<String, String> loadQueries(Path f) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] p = line.split("\t", 2);
            if (p.length == 2) out.put(p[0].trim(), p[1].trim());
        }
        return out;
    }

    static Map<String, Map<String, Integer>> loadQrels(Path f) throws IOException {
        Map<String, Map<String, Integer>> out = new LinkedHashMap<>();
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] p = line.split("\\s+");
            if (p.length < 4) continue;
            String qid = p[0], docid = p[2];
            int rel;
            try { rel = Integer.parseInt(p[3]); } catch (NumberFormatException e) { continue; }
            out.computeIfAbsent(qid, x -> new LinkedHashMap<>()).put(docid, rel);
        }
        return out;
    }

    // ---- Tổng hợp + in -------------------------------------------------------

    private static final class Aggregate {
        double pAtK, rAtK, f1AtK, map, mrr, ndcg;
        int n;
        double avg(double sum) { return n == 0 ? 0.0 : sum / n; }
    }

    private static String formatTable(Map<Ranker, Aggregate> table, int k) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== Bảng so sánh ranker (trung bình trên tập truy vấn) ===\n");
        sb.append(String.format("%-8s %8s %8s %8s %8s %8s %10s%n",
                "ranker", "P@" + k, "R@" + k, "F1@" + k, "MAP", "MRR", "nDCG@" + k));
        for (var e : table.entrySet()) {
            Aggregate a = e.getValue();
            sb.append(String.format("%-8s %8.4f %8.4f %8.4f %8.4f %8.4f %10.4f%n",
                    e.getKey().param(), a.avg(a.pAtK), a.avg(a.rAtK), a.avg(a.f1AtK),
                    a.avg(a.map), a.avg(a.mrr), a.avg(a.ndcg)));
        }
        sb.append("(n = số truy vấn đánh giá mỗi ranker: ");
        for (var e : table.entrySet()) sb.append(e.getKey().param()).append('=').append(e.getValue().n).append(' ');
        sb.append(")\n");
        return sb.toString();
    }

    private void writeReport(String report) {
        try {
            Path dir = Path.of("..", "eval", "results");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("eval-report.txt"), report, StandardCharsets.UTF_8);
            System.out.println("Đã ghi báo cáo: eval/results/eval-report.txt");
        } catch (Exception e) {
            System.err.println("[WARN] không ghi được báo cáo: " + e.getMessage());
        }
    }
}
