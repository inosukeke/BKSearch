package vn.hust.ir.eval;

import vn.hust.ir.embed.EmbeddingClient;
import vn.hust.ir.query.Ranker;
import vn.hust.ir.query.RrfFusion;
import vn.hust.ir.query.SearchEngine;
import vn.hust.ir.query.SearchHit;
import vn.hust.ir.query.SearchResponse;
import vn.hust.ir.query.SpellChecker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bộ chạy đánh giá (S1.7 + S2.6): nạp queries + qrels, chạy nhiều cấu hình ranker qua
 * OpenSearch, tính P@k/Recall@k/F1@k/MAP/MRR/nDCG@k + đo <b>p95 độ trễ</b>, in bảng so sánh.
 *
 * <p>Phase 1: BM25 / VSM / LM. Phase 2 (khi có {@code EMBED_URL}): thêm <b>vector</b>,
 * <b>hybrid (RRF)</b>, <b>hybrid+rerank</b> → phục vụ PASS S2.6 (hybrid+rerank vượt BM25).
 *
 * <p>qrels định dạng TREC: {@code <qid> 0 <docid=url> <rel>}. queries TSV: {@code <qid>\t<câu>}.
 * Cần OpenSearch sống + corpus đã index (+ vector nếu đánh giá vector/hybrid) → chạy LOCAL.
 */
public class EvalRunner {

    private final String osUrl;
    private final String baseIndex;
    private final int k;
    private final String embedUrl;   // null/blank → chỉ chạy ranker từ khóa
    private final int rrfK;
    private final int pool;
    private final int rerankTopK;
    private final double pagerankWeight;

    public EvalRunner(String osUrl, String baseIndex, int k) {
        this(osUrl, baseIndex, k, null, RrfFusion.DEFAULT_K, 100, SearchEngine.DEFAULT_RERANK_TOP_K, 0.0);
    }

    public EvalRunner(String osUrl, String baseIndex, int k,
                      String embedUrl, int rrfK, int pool, int rerankTopK) {
        this(osUrl, baseIndex, k, embedUrl, rrfK, pool, rerankTopK, 0.0);
    }

    public EvalRunner(String osUrl, String baseIndex, int k,
                      String embedUrl, int rrfK, int pool, int rerankTopK, double pagerankWeight) {
        this.osUrl = osUrl;
        this.baseIndex = baseIndex;
        this.k = Math.max(1, k);
        this.embedUrl = embedUrl;
        this.rrfK = rrfK;
        this.pool = pool;
        this.rerankTopK = rerankTopK;
        this.pagerankWeight = Math.max(0.0, pagerankWeight);
    }

    /** Một cấu hình đánh giá: ranker + có rerank không + nhãn hiển thị. */
    private record Config(Ranker ranker, boolean rerank, String label) {}

    public void run(Path queriesFile, Path qrelsFile) throws Exception {
        Map<String, String> queries = loadQueries(queriesFile);
        Map<String, Map<String, Integer>> qrels = loadQrels(qrelsFile);
        System.out.printf("Nạp %d truy vấn, %d truy vấn có qrels. k=%d%n",
                queries.size(), qrels.size(), k);

        boolean hasEmbed = embedUrl != null && !embedUrl.isBlank();
        EmbeddingClient embed = hasEmbed ? new EmbeddingClient(embedUrl) : null;
        SearchEngine engine = new SearchEngine(osUrl, baseIndex, new SpellChecker(List.of()),
                embed, rrfK, pool, rerankTopK, pagerankWeight);

        List<Config> configs = new ArrayList<>();
        configs.add(new Config(Ranker.BM25, false, "bm25"));
        configs.add(new Config(Ranker.VSM, false, "vsm"));
        configs.add(new Config(Ranker.LM, false, "lm"));
        if (hasEmbed) {
            configs.add(new Config(Ranker.VECTOR, false, "vector"));
            configs.add(new Config(Ranker.HYBRID, false, "hybrid"));
            configs.add(new Config(Ranker.HYBRID, true, "hybrid+rr"));
        } else {
            System.out.println("[INFO] EMBED_URL chưa đặt → bỏ qua vector/hybrid/rerank.");
        }

        Map<String, Aggregate> table = new LinkedHashMap<>();
        for (Config cfg : configs) {
            Aggregate agg = new Aggregate();
            for (var e : queries.entrySet()) {
                Map<String, Integer> gains = qrels.get(e.getKey());
                if (gains == null || gains.isEmpty()) continue;
                Set<String> relevant = gains.entrySet().stream()
                        .filter(x -> x.getValue() > 0).map(Map.Entry::getKey)
                        .collect(java.util.stream.Collectors.toSet());
                if (relevant.isEmpty()) continue;

                List<String> ranked;
                try {
                    long t0 = System.nanoTime();
                    ranked = runQuery(engine, e.getValue(), cfg);
                    agg.latenciesMs.add((System.nanoTime() - t0) / 1_000_000.0);
                } catch (Exception ex) {
                    System.err.println("[WARN] bỏ qua qid=" + e.getKey() + " cfg=" + cfg.label()
                            + ": " + ex.getMessage());
                    continue;
                }
                agg.pAtK  += Metrics.precisionAtK(ranked, relevant, k);
                agg.rAtK  += Metrics.recallAtK(ranked, relevant, k);
                agg.f1AtK += Metrics.f1AtK(ranked, relevant, k);
                agg.map   += Metrics.averagePrecision(ranked, relevant);
                agg.mrr   += Metrics.reciprocalRank(ranked, relevant);
                agg.ndcg  += Metrics.ndcgAtK(ranked, gains, k);
                agg.n++;
            }
            table.put(cfg.label(), agg);
        }

        String report = formatTable(table, k);
        System.out.println(report);
        writeReport(report);
    }

    private List<String> runQuery(SearchEngine engine, String text, Config cfg) throws Exception {
        SearchResponse res = engine.search(text, 1, k, cfg.ranker(), cfg.rerank());
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
        final List<Double> latenciesMs = new ArrayList<>();
        double avg(double sum) { return n == 0 ? 0.0 : sum / n; }
        double p95() {
            if (latenciesMs.isEmpty()) return 0.0;
            List<Double> s = new ArrayList<>(latenciesMs);
            Collections.sort(s);
            int idx = (int) Math.ceil(0.95 * s.size()) - 1;
            return s.get(Math.max(0, Math.min(idx, s.size() - 1)));
        }
    }

    private static String formatTable(Map<String, Aggregate> table, int k) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== Bảng so sánh ranker (trung bình trên tập truy vấn) ===\n");
        sb.append(String.format("%-10s %8s %8s %8s %8s %8s %10s %9s%n",
                "config", "P@" + k, "R@" + k, "F1@" + k, "MAP", "MRR", "nDCG@" + k, "p95(ms)"));
        for (var e : table.entrySet()) {
            Aggregate a = e.getValue();
            sb.append(String.format("%-10s %8.4f %8.4f %8.4f %8.4f %8.4f %10.4f %9.1f%n",
                    e.getKey(), a.avg(a.pAtK), a.avg(a.rAtK), a.avg(a.f1AtK),
                    a.avg(a.map), a.avg(a.mrr), a.avg(a.ndcg), a.p95()));
        }
        sb.append("(n = số truy vấn đánh giá mỗi config: ");
        for (var e : table.entrySet()) sb.append(e.getKey()).append('=').append(e.getValue().n).append(' ');
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
