package vn.hust.ir.search;

/** Một kết quả tìm kiếm trả về cho CLI/Web. */
public class SearchResult {
    public String url;
    public String title;
    public String snippet;
    public String docType;
    public String subdomain;
    public float score;

    public SearchResult(String url, String title, String snippet,
                        String docType, String subdomain, float score) {
        this.url = url;
        this.title = title;
        this.snippet = snippet;
        this.docType = docType;
        this.subdomain = subdomain;
        this.score = score;
    }
}
