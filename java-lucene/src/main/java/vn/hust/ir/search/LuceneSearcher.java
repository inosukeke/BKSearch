package vn.hust.ir.search;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.FSDirectory;

import vn.hust.ir.nlp.VietnameseSegmenter;

/**
 * Tìm kiếm bằng Apache Lucene với xếp hạng BM25 và hỗ trợ phân trang.
 * Truy vấn được TÁCH TỪ giống lúc index (bảo đảm nhất quán), tìm trên cả
 * hai field title (trọng số 2.0) và content.
 */
public class LuceneSearcher implements AutoCloseable {

    private final DirectoryReader reader;
    private final IndexSearcher searcher;
    private final VietnameseSegmenter seg = new VietnameseSegmenter();
    private final MultiFieldQueryParser parser;

    public LuceneSearcher(Path indexDir) throws Exception {
        this.reader = DirectoryReader.open(FSDirectory.open(indexDir));
        this.searcher = new IndexSearcher(reader);
        this.searcher.setSimilarity(new BM25Similarity());
        var boosts = new java.util.HashMap<String, Float>();
        boosts.put("title", 2.0f);
        boosts.put("content", 1.0f);
        this.parser = new MultiFieldQueryParser(
                new String[]{"title", "content"}, new WhitespaceAnalyzer(), boosts);
    }

    /** Lấy topN kết quả đầu (dùng cho CLI). */
    public List<SearchResult> search(String queryText, int topN) throws Exception {
        return searchPage(queryText, 1, topN).results;
    }

    /** Tìm kiếm có phân trang: trả về trang `page` (bắt đầu từ 1), mỗi trang `pageSize` kết quả. */
    public SearchPage searchPage(String queryText, int page, int pageSize) throws Exception {
        if (page < 1) page = 1;
        List<SearchResult> results = new ArrayList<>();
        String segmented = seg.segment(queryText);
        if (segmented.isBlank()) return new SearchPage(results, 0, page, pageSize);

        Query query = parser.parse(MultiFieldQueryParser.escape(segmented));
        int need = page * pageSize;                   // lấy đủ tới cuối trang hiện tại
        TopDocs top = searcher.search(query, Math.max(need, 1));
        long total = top.totalHits.value;

        int from = (page - 1) * pageSize;
        int to = Math.min(top.scoreDocs.length, from + pageSize);
        StoredFields storedFields = searcher.storedFields();
        for (int i = from; i < to; i++) {
            ScoreDoc sd = top.scoreDocs[i];
            Document d = storedFields.document(sd.doc);
            results.add(new SearchResult(
                    d.get("url"),
                    d.get("title_display"),
                    snippet(d.get("content_display"), queryText),
                    d.get("doc_type"),
                    d.get("subdomain"),
                    sd.score));
        }
        return new SearchPage(results, total, page, pageSize);
    }

    public int numDocs() { return reader.numDocs(); }

    /** Trích một đoạn quanh từ khóa đầu tiên (highlight đơn giản). */
    private String snippet(String content, String query) {
        if (content == null || content.isEmpty()) return "";
        String lc = content.toLowerCase();
        String first = query.trim().toLowerCase().split("\\s+")[0];
        int idx = lc.indexOf(first);
        if (idx < 0) return content.length() > 200 ? content.substring(0, 200) + "..." : content;
        int start = Math.max(0, idx - 60);
        int end = Math.min(content.length(), idx + 140);
        return (start > 0 ? "..." : "") + content.substring(start, end)
                + (end < content.length() ? "..." : "");
    }

    @Override
    public void close() throws Exception {
        reader.close();
    }
}
