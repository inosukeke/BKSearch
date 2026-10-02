package vn.hust.ir.linkgraph;

import com.fasterxml.jackson.databind.node.ObjectNode;
import vn.hust.ir.migrate.Migrator;
import vn.hust.ir.migrate.OpenSearchClient;
import vn.hust.ir.nlp.VietnameseAnalyzer;
import vn.hust.ir.store.Db;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chạy PageRank theo lô (S3.1): đọc đồ thị liên kết + tài liệu từ {@link Db}, tính
 * {@link PageRank}, rồi <b>cập nhật từng phần</b> field {@code pagerank} + anchor text
 * ({@code anchor_text}, {@code anchor_text_seg}) vào các index OpenSearch (BM25/VSM/LM).
 *
 * <p>Phần dựng đồ thị + gom anchor tách riêng (thuần, test được) khỏi phần ghi OpenSearch.
 * Đồ thị chỉ gồm các URL là <b>tài liệu đã index</b> (bỏ cạnh trỏ ra ngoài tập này) để PageRank
 * phản ánh uy tín trong corpus.
 */
public class PageRankRunner {

    /** Số ký tự anchor tối đa gom cho mỗi tài liệu (chống field phình to). */
    public static final int ANCHOR_MAX_CHARS = 2000;

    /**
     * Dựng đồ thị từ tập URL tài liệu + danh sách cạnh {@code [src,dst,anchor]}.
     * Thêm MỌI tài liệu làm node (để trang không liên kết vẫn có PageRank nền), và chỉ thêm cạnh
     * khi CẢ hai đầu đều là tài liệu đã biết.
     */
    public static LinkGraph buildGraph(List<String> docUrls, List<String[]> links) {
        Set<String> docs = new HashSet<>(docUrls);
        LinkGraph g = new LinkGraph();
        for (String u : docUrls) g.addNode(u);
        for (String[] e : links) {
            if (e == null || e.length < 2) continue;
            String src = e[0], dst = e[1];
            String anchor = e.length > 2 ? e[2] : null;
            if (docs.contains(src) && docs.contains(dst)) {
                g.addEdge(src, dst, anchor);
            }
        }
        return g;
    }

    /** Gom anchor text trỏ tới {@code url} thành một chuỗi (nối bằng khoảng trắng, cắt ngắn). */
    public static String anchorText(LinkGraph g, String url) {
        List<String> anchors = g.anchorsFor(url);
        if (anchors.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String a : anchors) {
            if (a == null || a.isBlank()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(a.trim());
            if (sb.length() >= ANCHOR_MAX_CHARS) break;
        }
        String s = sb.toString();
        return s.length() > ANCHOR_MAX_CHARS ? s.substring(0, ANCHOR_MAX_CHARS) : s;
    }

    /** Kết quả chạy: số node, số vòng, hội tụ chưa, số doc cập nhật mỗi index. */
    public record Summary(int nodes, int edgesKept, int iterations, boolean converged,
                          Map<String, OpenSearchClient.BulkResult> perIndex) {}

    /**
     * Thực thi đầy đủ: đọc Db → tính PageRank → bulkUpdate các index.
     * @param indices danh sách index cần ghi (vd documents, documents_vsm, documents_lm)
     * @param batch   kích thước lô bulk
     */
    public Summary run(Db db, OpenSearchClient os, VietnameseAnalyzer analyzer,
                       List<String> indices, double damping, int maxIter, double tol, int batch)
            throws Exception {
        List<String> docUrls = new ArrayList<>();
        for (var d : db.allDocuments()) docUrls.add(d.url);
        List<String[]> links = db.allLinks();

        LinkGraph g = buildGraph(docUrls, links);
        PageRank.Result pr = PageRank.compute(g, damping, maxIter, tol);

        // Dựng source cập nhật từng phần cho mỗi tài liệu.
        List<OpenSearchClient.Item> items = new ArrayList<>(docUrls.size());
        for (String url : docUrls) {
            double score = pr.scores().getOrDefault(url, 0.0);
            String anchor = anchorText(g, url);
            ObjectNode doc = os.mapper().createObjectNode();
            doc.put("pagerank", score);
            if (!anchor.isBlank()) {
                doc.put("anchor_text", anchor);
                doc.put("anchor_text_seg", analyzer.segment(anchor));
            }
            items.add(new OpenSearchClient.Item(Migrator.sha256(url), doc));
        }

        java.util.LinkedHashMap<String, OpenSearchClient.BulkResult> perIndex = new java.util.LinkedHashMap<>();
        for (String index : indices) {
            OpenSearchClient.BulkResult agg = new OpenSearchClient.BulkResult(0, 0, null);
            for (int start = 0; start < items.size(); start += batch) {
                int end = Math.min(start + batch, items.size());
                OpenSearchClient.BulkResult r = os.bulkUpdate(index, items.subList(start, end));
                agg = new OpenSearchClient.BulkResult(
                        agg.ok() + r.ok(), agg.failed() + r.failed(),
                        agg.firstError() != null ? agg.firstError() : r.firstError());
            }
            os.refresh(index);
            perIndex.put(index, agg);
        }

        int edgesKept = 0;
        for (int i = 0; i < g.size(); i++) edgesKept += g.outDegree(i);
        return new Summary(g.size(), edgesKept, pr.iterations(), pr.converged(), perIndex);
    }
}
