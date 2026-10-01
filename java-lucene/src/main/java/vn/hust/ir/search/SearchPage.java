package vn.hust.ir.search;

import java.util.List;

/** Một trang kết quả tìm kiếm (phục vụ phân trang). */
public class SearchPage {
    public final List<SearchResult> results;
    public final long totalHits;   // tổng số kết quả khớp
    public final int page;         // trang hiện tại (bắt đầu từ 1)
    public final int pageSize;

    public SearchPage(List<SearchResult> results, long totalHits, int page, int pageSize) {
        this.results = results;
        this.totalHits = totalHits;
        this.page = page;
        this.pageSize = pageSize;
    }

    public int totalPages() {
        return (int) Math.max(1, Math.ceil((double) totalHits / pageSize));
    }
}
