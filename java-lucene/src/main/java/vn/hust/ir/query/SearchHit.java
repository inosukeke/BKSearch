package vn.hust.ir.query;

/** Một kết quả tìm kiếm trả về cho client (JSON). */
public class SearchHit {
    public String url;
    public String title;
    public String snippet;     // đã highlight (<em>...</em>) nếu có
    public double score;
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
