package vn.hust.ir.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import vn.hust.ir.migrate.OpenSearchClient;
import vn.hust.ir.nlp.VietnameseAnalyzer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Lõi truy xuất: nối OpenSearch, chạy BM25/VSM/LM (S1.2, S1.3), xử lý truy vấn
 * phrase/Boolean (S1.4) và did-you-mean (S1.5).
 *
 * <p>Tách từ truy vấn qua {@link VietnameseAnalyzer} giống lúc ingest (ràng buộc G3)
 * — việc này nằm trong {@link QueryParser}.
 */
public class SearchEngine {

    private final OpenSearchClient os;
    private final String baseIndex;
    private final QueryParser parser;
    private final SpellChecker spell;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final List<String> SOURCE_FIELDS =
            List.of("url", "title", "content", "doc_type", "subdomain");

    public SearchEngine(String osUrl, String baseIndex, SpellChecker spell) {
        this.os = new OpenSearchClient(osUrl);
        this.baseIndex = baseIndex;
        this.parser = new QueryParser(VietnameseAnalyzer.get(), mapper);
        this.spell = spell;
    }

    /** Dùng cho test: cho phép tiêm client/parser (không bắt buộc OpenSearch sống). */
    SearchEngine(OpenSearchClient os, String baseIndex, QueryParser parser, SpellChecker spell) {
        this.os = os;
        this.baseIndex = baseIndex;
        this.parser = parser;
        this.spell = spell;
    }

    /**
     * Dựng thân request OpenSearch (query + highlight + phân trang). Tách riêng để test
     * mà không cần engine sống.
     */
    ObjectNode buildRequest(String rawQuery, int from, int size) {
        ObjectNode body = mapper.createObjectNode();
        body.put("from", Math.max(0, from));
        body.put("size", Math.max(0, size));
        body.set("query", parser.buildQuery(rawQuery));
        ArrayNode src = body.putArray("_source");
        SOURCE_FIELDS.forEach(src::add);
        // Highlight trên field đã tách từ; hiển thị sẽ thay '_' → ' '.
        ObjectNode hl = body.putObject("highlight");
        hl.put("fragment_size", 180);
        hl.put("number_of_fragments", 1);
        ArrayNode pre = hl.putArray("pre_tags"); pre.add("<em>");
        ArrayNode post = hl.putArray("post_tags"); post.add("</em>");
        ObjectNode fields = hl.putObject("fields");
        fields.putObject(QueryParser.CONTENT_FIELD);
        fields.putObject(QueryParser.TITLE_FIELD);
        return body;
    }

    /**
     * Tìm kiếm với phân trang 1-based.
     * @throws QueryParseException nếu cú pháp sai (tầng REST → 400).
     */
    public SearchResponse search(String rawQuery, int page, int pageSize, Ranker ranker) throws Exception {
        int p = Math.max(1, page);
        int size = Math.max(1, pageSize);
        int from = (p - 1) * size;
        String index = ranker.indexName(baseIndex);

        ObjectNode body = buildRequest(rawQuery, from, size);

        long t0 = System.nanoTime();
        JsonNode res = os.search(index, body);
        long tookMs = (System.nanoTime() - t0) / 1_000_000;

        SearchResponse out = new SearchResponse();
        out.query = rawQuery;
        out.ranker = ranker.param();
        out.segmented_query = VietnameseAnalyzer.get().segment(rawQuery);
        out.page = p;
        out.page_size = size;
        out.took_ms = tookMs;
        out.total = res.path("hits").path("total").path("value").asLong(0);
        out.total_pages = SearchResponse.totalPages(out.total, size);
        out.results = mapHits(res.path("hits").path("hits"));
        out.suggestion = spell.suggestQuery(rawQuery).orElse(null);
        return out;
    }

    private List<SearchHit> mapHits(JsonNode hits) {
        List<SearchHit> list = new ArrayList<>();
        if (!hits.isArray()) return list;
        for (JsonNode h : hits) {
            JsonNode src = h.path("_source");
            SearchHit hit = new SearchHit();
            hit.url = src.path("url").asText("");
            hit.title = src.path("title").asText("");
            hit.doc_type = src.path("doc_type").asText("");
            hit.subdomain = src.path("subdomain").asText("");
            hit.score = h.path("_score").asDouble(0);
            hit.snippet = extractSnippet(h, src);
            list.add(hit);
        }
        return list;
    }

    /** Ưu tiên fragment highlight (thay '_'→' '); nếu không có thì cắt đầu content. */
    private String extractSnippet(JsonNode hit, JsonNode src) {
        JsonNode hl = hit.path("highlight").path(QueryParser.CONTENT_FIELD);
        if (hl.isArray() && !hl.isEmpty()) {
            return deSegment(hl.get(0).asText(""));
        }
        String content = src.path("content").asText("");
        if (content.length() > 180) content = content.substring(0, 180) + "…";
        return content;
    }

    /** "đại_học" → "đại học" (giữ nguyên thẻ <em>). */
    private static String deSegment(String s) {
        return s == null ? "" : s.replace('_', ' ');
    }

    /** Chỉ gợi ý did-you-mean (S1.5). */
    public Optional<String> suggest(String rawQuery) {
        return spell.suggestQuery(rawQuery);
    }

    public String baseIndex() { return baseIndex; }
}
