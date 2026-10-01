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
 */
public class SearchResponse {
    public String query;
    public String ranker;
    public String segmented_query;
    public long total;
    public int page;
    public int page_size;
    public int total_pages;
    public long took_ms;
    public String suggestion;          // null nếu không có did-you-mean
    public List<SearchHit> results;

    public static int totalPages(long total, int pageSize) {
        if (pageSize <= 0) return 0;
        return (int) ((total + pageSize - 1) / pageSize);
    }
}
