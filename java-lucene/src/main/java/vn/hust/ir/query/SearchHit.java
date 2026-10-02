package vn.hust.ir.query;

/** Một kết quả tìm kiếm trả về cho client (JSON). */
public class SearchHit {
    public String url;
    public String title;
    public String snippet;     // đã highlight (<em>...</em>) nếu có
    public double score;
    /**
     * Thang điểm của {@link #score} (F5): "bm25"/"vsm"/"lm" (điểm OpenSearch nhánh từ khóa),
     * "cosine" (k-NN vector), "rrf" (hợp nhất hybrid), hay "cross-encoder" (đã rerank).
     * Khi rerank bật, phần đầu mang "cross-encoder" còn phần đuôi giữ thang nền → KHÔNG so
     * sánh trực tiếp score giữa hai thang; dùng field này để biết nguồn điểm.
     */
    public String score_type;
    public String doc_type;
    public String subdomain;

    public SearchHit() {}

    public SearchHit(String url, String title, String snippet, double score,
                     String docType, String subdomain) {
        this.url = url;
        this.title = title;
        this.snippet = snippet;
        this.score = score;
        this.doc_type = docType;
        this.subdomain = subdomain;
    }
}
