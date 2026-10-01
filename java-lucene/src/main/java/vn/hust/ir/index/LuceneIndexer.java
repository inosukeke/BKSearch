package vn.hust.ir.index;

import java.nio.file.Path;
import java.util.List;

import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.FSDirectory;

import vn.hust.ir.extract.TikaExtractor;
import vn.hust.ir.nlp.VietnameseSegmenter;
import vn.hust.ir.store.Db;
import vn.hust.ir.store.Document;

/**
 * Đánh chỉ mục bằng Apache Lucene.
 *
 * - Văn bản tiếng Việt được TÁCH TỪ (VietnameseSegmenter) trước khi index, nên
 *   Lucene chỉ cần WhitespaceAnalyzer trên chuỗi token đã xử lý.
 * - Nội dung file (PDF/DOC) được Tika bóc tách rồi cũng đưa vào chỉ mục.
 * - updateDocument(url) bảo đảm không trùng khi chạy lại.
 */
public class LuceneIndexer {

    private final Db db;
    private final Path indexDir;
    private final VietnameseSegmenter seg = new VietnameseSegmenter();

    public LuceneIndexer(Db db, Path indexDir) {
        this.db = db;
        this.indexDir = indexDir;
    }

    /** Đánh chỉ mục toàn bộ bài viết + tối đa maxFiles tài liệu qua Tika. */
    public void index(int maxFiles) throws Exception {
        try (FSDirectory dir = FSDirectory.open(indexDir);
             IndexWriter writer = new IndexWriter(dir,
                     new IndexWriterConfig(new WhitespaceAnalyzer()))) {

            List<Document> docs = db.allDocuments();
            for (Document d : docs) {
                writer.updateDocument(new Term("url", d.url), toLuceneDoc(
                        d.url, d.title, d.content, d.docType, d.subdomain, d.publishedAt));
            }
            System.out.println("Đã index " + docs.size() + " bài viết (HTML).");

            if (maxFiles != 0) {
                TikaExtractor tika = new TikaExtractor();
                List<String[]> files = db.someFiles(maxFiles);
                int ok = 0;
                System.out.println("Đang bóc tách " + files.size() + " tài liệu bằng Tika...");
                for (String[] f : files) {
                    String text = tika.extractFromUrl(f[0]);   // f: url,type,page,subdomain
                    if (text.isBlank()) continue;
                    String title = f[0].substring(f[0].lastIndexOf('/') + 1);
                    writer.updateDocument(new Term("url", f[0]),
                            toLuceneDoc(f[0], title, text, f[1], f[3], null));
                    ok++;
                }
                System.out.println("Đã index " + ok + " tài liệu (Tika).");
            }
            writer.commit();
        }
    }

    private org.apache.lucene.document.Document toLuceneDoc(
            String url, String title, String content, String docType,
            String subdomain, String publishedAt) {
        org.apache.lucene.document.Document doc = new org.apache.lucene.document.Document();
        doc.add(new StringField("url", url, Field.Store.YES));
        // Bản gốc để hiển thị.
        doc.add(new StoredField("title_display", title == null ? "" : title));
        doc.add(new StoredField("content_display",
                content.length() > 1500 ? content.substring(0, 1500) : content));
        doc.add(new StringField("doc_type", docType == null ? "" : docType, Field.Store.YES));
        doc.add(new StringField("subdomain", subdomain == null ? "" : subdomain, Field.Store.YES));
        // Bản đã tách từ để tìm kiếm (title được đánh trọng số cao hơn khi truy vấn).
        doc.add(new TextField("title", seg.segment(title == null ? "" : title), Field.Store.NO));
        doc.add(new TextField("content", seg.segment(content), Field.Store.NO));
        return doc;
    }
}
