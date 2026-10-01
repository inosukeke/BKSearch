package vn.hust.ir.store;

/** Một tài liệu thu thập được (bài viết HTML). Ánh xạ tới bảng `documents`. */
public class Document {
    public String url;
    public String title;
    public String content;
    public String contentHash;   // SHA-256 để phát hiện thay đổi
    public String docType;       // 'html' | 'pdf' | 'docx'...
    public String subdomain;
    public String publishedAt;
    public String crawledAt;

    public Document() {}

    public Document(String url, String title, String content, String contentHash,
                    String docType, String subdomain, String publishedAt, String crawledAt) {
        this.url = url;
        this.title = title;
        this.content = content;
        this.contentHash = contentHash;
        this.docType = docType;
        this.subdomain = subdomain;
        this.publishedAt = publishedAt;
        this.crawledAt = crawledAt;
    }
}
