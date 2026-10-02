package vn.hust.ir.classify;

import com.fasterxml.jackson.databind.node.ObjectNode;
import vn.hust.ir.migrate.Migrator;
import vn.hust.ir.migrate.OpenSearchClient;
import vn.hust.ir.nlp.VietnameseAnalyzer;
import vn.hust.ir.store.Db;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chạy phân lớp theo lô (S3.4): huấn luyện {@link DocumentClassifier}, gán danh mục cho từng tài
 * liệu rồi ghi field {@code category} vào OpenSearch. Có đánh giá leave-one-out trên tập huấn luyện
 * để báo cáo P/R/F1 (PASS: macro-F1/accuracy hợp lý).
 */
public class ClassifyRunner {

    public record Summary(int docs, Map<String, Integer> distribution,
                          Map<String, OpenSearchClient.BulkResult> perIndex) {}

    static String docText(String title, String content) {
        String t = title == null ? "" : title;
        String c = content == null ? "" : content;
        return (t + " " + c).trim();
    }

    /** Đánh giá leave-one-out trên tập huấn luyện mặc định (trung thực cho tập nhỏ). */
    public static ClassifierMetrics.Report crossValidate(VietnameseAnalyzer analyzer) {
        List<NaiveBayes.Sample> raw = DocumentClassifier.loadTraining("/category-train.tsv");
        List<String> actual = new ArrayList<>(), predicted = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            List<NaiveBayes.Sample> train = new ArrayList<>(raw);
            NaiveBayes.Sample test = train.remove(i);
            DocumentClassifier dc = new DocumentClassifier(analyzer).train(train);
            actual.add(test.label());
            predicted.add(dc.classify(test.text()));
        }
        return ClassifierMetrics.evaluate(actual, predicted);
    }

    public Summary run(Db db, OpenSearchClient os, VietnameseAnalyzer analyzer,
                       List<String> indices, int batch) throws Exception {
        DocumentClassifier clf = new DocumentClassifier(analyzer).trainDefault();
        if (!clf.isReady()) throw new IllegalStateException("Không huấn luyện được classifier (thiếu tập train).");

        Map<String, Integer> dist = new LinkedHashMap<>();
        List<OpenSearchClient.Item> items = new ArrayList<>();
        for (var d : db.allDocuments()) {
            String cat = clf.classify(docText(d.title, d.content));
            if (cat == null) continue;
            dist.merge(cat, 1, Integer::sum);
            ObjectNode doc = os.mapper().createObjectNode();
            doc.put("category", cat);
            items.add(new OpenSearchClient.Item(Migrator.sha256(d.url), doc));
        }

        LinkedHashMap<String, OpenSearchClient.BulkResult> perIndex = new LinkedHashMap<>();
        for (String index : indices) {
            if (items.isEmpty()) { perIndex.put(index, new OpenSearchClient.BulkResult(0, 0, null)); continue; }
            OpenSearchClient.BulkResult agg = new OpenSearchClient.BulkResult(0, 0, null);
            for (int start = 0; start < items.size(); start += batch) {
                int end = Math.min(start + batch, items.size());
                OpenSearchClient.BulkResult r = os.bulkUpdate(index, items.subList(start, end));
                agg = new OpenSearchClient.BulkResult(agg.ok() + r.ok(), agg.failed() + r.failed(),
                        agg.firstError() != null ? agg.firstError() : r.firstError());
            }
            os.refresh(index);
            perIndex.put(index, agg);
        }
        return new Summary(items.size(), dist, perIndex);
    }
}
