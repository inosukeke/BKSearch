package vn.hust.ir.dedup;

import com.fasterxml.jackson.databind.node.ObjectNode;
import vn.hust.ir.migrate.Migrator;
import vn.hust.ir.migrate.OpenSearchClient;
import vn.hust.ir.store.Db;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chạy phát hiện near-duplicate theo lô (S3.2): đọc tài liệu từ {@link Db}, gom nhóm bằng
 * {@link NearDuplicateDetector}, rồi ghi field {@code dup_group} (= url canonical của nhóm) cho
 * các bản TRÙNG vào OpenSearch (các doc đứng một mình giữ {@code dup_group = url} từ lúc migrate).
 *
 * <p>Tìm kiếm bật {@code collapse} theo {@code dup_group} → mỗi nhóm chỉ hiện 1 kết quả.
 */
public class NearDuplicateRunner {

    /** Văn bản dùng để so trùng: title + content (thô). */
    static String docText(String title, String content) {
        String t = title == null ? "" : title;
        String c = content == null ? "" : content;
        return (t + " " + c).trim();
    }

    public record Summary(int docs, int clusters, int duplicates,
                          Map<String, OpenSearchClient.BulkResult> perIndex) {}

    public Summary run(Db db, OpenSearchClient os, List<String> indices,
                       int k, int numHashes, long seed, int bands, double threshold, int batch)
            throws Exception {
        Map<String, String> idToText = new LinkedHashMap<>();
        for (var d : db.allDocuments()) idToText.put(d.url, docText(d.title, d.content));

        NearDuplicateDetector.Result r = NearDuplicateDetector.detect(
                idToText, k, numHashes, seed, bands, threshold);

        // Chỉ cập nhật các doc là bản trùng (canonical khác chính nó).
        List<OpenSearchClient.Item> items = new ArrayList<>();
        for (var e : r.canonical().entrySet()) {
            String url = e.getKey(), canon = e.getValue();
            if (canon.equals(url)) continue;   // canonical/đơn lẻ: giữ dup_group = url sẵn có
            ObjectNode doc = os.mapper().createObjectNode();
            doc.put("dup_group", canon);
            items.add(new OpenSearchClient.Item(Migrator.sha256(url), doc));
        }

        LinkedHashMap<String, OpenSearchClient.BulkResult> perIndex = new LinkedHashMap<>();
        for (String index : indices) {
            if (items.isEmpty()) { perIndex.put(index, new OpenSearchClient.BulkResult(0, 0, null)); continue; }
            OpenSearchClient.BulkResult agg = new OpenSearchClient.BulkResult(0, 0, null);
            for (int start = 0; start < items.size(); start += batch) {
                int end = Math.min(start + batch, items.size());
                OpenSearchClient.BulkResult res = os.bulkUpdate(index, items.subList(start, end));
                agg = new OpenSearchClient.BulkResult(agg.ok() + res.ok(), agg.failed() + res.failed(),
                        agg.firstError() != null ? agg.firstError() : res.firstError());
            }
            os.refresh(index);
            perIndex.put(index, agg);
        }
        return new Summary(idToText.size(), r.clusters().size(), r.duplicateCount(), perIndex);
    }
}
