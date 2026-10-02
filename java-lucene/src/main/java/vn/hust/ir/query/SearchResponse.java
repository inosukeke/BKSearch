package vn.hust.ir.query;

import java.util.List;

/**
 * Hợp đồng JSON của {@code GET /api/search} (S1.1).
 * <pre>
 * {
 *   "query": "...", "ranker": "bm25", "segmented_query": "...",
 *   "total": 123, "page": 1, "page_size": 10, "total_pages": 13,
 *   "took_ms": 12, "suggestion": "tuyển sinh" | null,
 *   "results": [ { url, title, snippet, score, doc_type, subdomain }, ... ]
 * }
 * </pre>
 *
 * <p><b>Ngữ nghĩa {@code total} (F4):</b>
 * <ul>
 *   <li>Nhánh TỪ KHÓA thuần (bm25/vsm/lm, không rerank): {@code total} = tổng tài liệu khớp
 *       trong corpus (OpenSearch {@code hits.total.value}); phân trang đi hết corpus.</li>
 *   <li>Nhánh ỨNG VIÊN (vector/hybrid, hoặc có rerank): kết quả rút từ một <i>pool ứng viên</i>
 *       hữu hạn (≤ {@code CANDIDATE_POOL}). {@code total} = {@code total_candidates} = kích thước
 *       pool (giới hạn phân trang). {@code total_matched} cho biết tổng khớp thực của nhánh từ
 *       khóa nền (BM25) nếu biết, hoặc {@code -1} khi không áp dụng (vd vector k-NN thuần).</li>
 * </ul>
 */
public class SearchResponse {
    public String query;
    public String ranker;
    public String segmented_query;
    public long total;
    /** Kích thước pool ứng viên mà phân trang duyệt (bằng {@code total} ở nhánh ứng viên; = tổng corpus ở nhánh từ khóa). */
    public long total_candidates;
    /** Tổng tài liệu khớp thực của nhánh BM25 nền (chỉ nhánh ứng viên); {@code -1} nếu không áp dụng. */
    public long total_matched = -1;
    public int page;
    public int page_size;
    public int total_pages;
    public long took_ms;
    public String suggestion;          // null nếu không có did-you-mean
    public List<SearchHit> results;
    /** Facet (S3.4): field → danh sách (giá trị, số lượng). null nếu nhánh không hỗ trợ facet. */
    public java.util.Map<String, List<FacetBucket>> facets;
    /** Bộ lọc facet đang áp dụng (field → giá trị), để UI hiển thị/bỏ lọc. */
    public java.util.Map<String, String> applied_filters;

    /** Một mục facet: giá trị + số tài liệu. */
    public static class FacetBucket {
        public String key;
        public long count;
        public FacetBucket() {}
        public FacetBucket(String key, long count) { this.key = key; this.count = count; }
    }

    public static int totalPages(long total, int pageSize) {
        if (pageSize <= 0) return 0;
        return (int) ((total + pageSize - 1) / pageSize);
    }
}
