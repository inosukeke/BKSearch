package vn.hust.ir.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import vn.hust.ir.embed.EmbeddingClient;
import vn.hust.ir.migrate.OpenSearchClient;
import vn.hust.ir.nlp.VietnameseAnalyzer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lõi truy xuất: nối OpenSearch, chạy BM25/VSM/LM (S1.2, S1.3), xử lý truy vấn
 * phrase/Boolean (S1.4), did-you-mean (S1.5); và Phase 2: vector k-NN (S2.3),
 * hybrid RRF (S2.4), cross-encoder rerank (S2.5).
 *
 * <p>Tách từ truy vấn qua {@link VietnameseAnalyzer} giống lúc ingest (ràng buộc G3) — cho cả
 * nhánh từ khóa (trong {@link QueryParser}) lẫn văn bản đưa đi embed (vector/hybrid).
 */
public class SearchEngine {

    private final OpenSearchClient os;
    private final String baseIndex;
    private final QueryParser parser;
    private final SpellChecker spell;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Client embedding/rerank (null nếu chưa cấu hình → chỉ phục vụ ranker từ khóa). */
    private final EmbeddingClient embed;
    /** Tham số RRF k (S2.4). */
    private final int rrfK;
    /** Số ứng viên lấy từ mỗi nguồn trước khi hợp nhất/rerank (S2.3/S2.4). */
    private final int candidatePool;
    /** Chỉ rerank top-K ứng viên (S2.5, ràng buộc G7). */
    private final int rerankTopK;
    /** Trọng số trộn PageRank vào điểm từ khóa qua function_score (S3.1); 0 = TẮT. */
    private final double pagerankWeight;
    /** Gộp near-duplicate theo field dup_group (S3.2) — mỗi nhóm chỉ 1 kết quả; false = TẮT. */
    private final boolean dedupCollapse;

    public static final String EMBEDDING_FIELD = "embedding";

    /**
     * Mặc định số ứng viên được rerank (S2.5/F2). Hạ từ 50→30 vì đo p95 local trên CPU:
     * top_k=50 (cross-encoder không truncate) → p95 ~10s (VƯỢT ngưỡng 800ms);
     * top_k≤30 + RERANK_MAX_LENGTH=256 → p95 ~540ms (ĐẠT). Có thể chỉnh qua env RERANK_TOP_K.
     * LƯU Ý: p95 phụ thuộc phần cứng (CPU/GPU) — số trên đo trên CPU local.
     */
    public static final int DEFAULT_RERANK_TOP_K = 30;

    private static final List<String> SOURCE_FIELDS =
            List.of("url", "title", "content", "doc_type", "subdomain");

    public SearchEngine(String osUrl, String baseIndex, SpellChecker spell) {
        this(osUrl, baseIndex, spell, null, RrfFusion.DEFAULT_K, 100, DEFAULT_RERANK_TOP_K, 0.0);
    }

    /** Back-compat (không PageRank): trọng số = 0. */
    public SearchEngine(String osUrl, String baseIndex, SpellChecker spell,
                        EmbeddingClient embed, int rrfK, int candidatePool, int rerankTopK) {
        this(osUrl, baseIndex, spell, embed, rrfK, candidatePool, rerankTopK, 0.0);
    }

    public SearchEngine(String osUrl, String baseIndex, SpellChecker spell,
                        EmbeddingClient embed, int rrfK, int candidatePool, int rerankTopK,
                        double pagerankWeight) {
        this(osUrl, baseIndex, spell, embed, rrfK, candidatePool, rerankTopK, pagerankWeight, false);
    }

    public SearchEngine(String osUrl, String baseIndex, SpellChecker spell,
                        EmbeddingClient embed, int rrfK, int candidatePool, int rerankTopK,
                        double pagerankWeight, boolean dedupCollapse) {
        this.os = new OpenSearchClient(osUrl);
        this.baseIndex = baseIndex;
        this.parser = new QueryParser(VietnameseAnalyzer.get(), mapper);
        this.spell = spell;
        this.embed = embed;
        this.rrfK = rrfK > 0 ? rrfK : RrfFusion.DEFAULT_K;
        this.candidatePool = Math.max(10, candidatePool);
        this.rerankTopK = Math.max(1, rerankTopK);
        this.pagerankWeight = Math.max(0.0, pagerankWeight);
        this.dedupCollapse = dedupCollapse;
    }

    /** Dùng cho test: cho phép tiêm client/parser (không bắt buộc OpenSearch sống). */
    SearchEngine(OpenSearchClient os, String baseIndex, QueryParser parser, SpellChecker spell) {
        this(os, baseIndex, parser, spell, null);
    }

    /** Dùng cho test: tiêm thêm EmbeddingClient (vector/hybrid/rerank). */
    SearchEngine(OpenSearchClient os, String baseIndex, QueryParser parser, SpellChecker spell,
                 EmbeddingClient embed) {
        this(os, baseIndex, parser, spell, embed, 0.0);
    }

    /** Dùng cho test: tiêm EmbeddingClient + trọng số PageRank (S3.1). */
    SearchEngine(OpenSearchClient os, String baseIndex, QueryParser parser, SpellChecker spell,
                 EmbeddingClient embed, double pagerankWeight) {
        this(os, baseIndex, parser, spell, embed, pagerankWeight, false);
    }

    /** Dùng cho test: tiêm thêm cờ gộp near-duplicate (S3.2). */
    SearchEngine(OpenSearchClient os, String baseIndex, QueryParser parser, SpellChecker spell,
                 EmbeddingClient embed, double pagerankWeight, boolean dedupCollapse) {
        this.os = os;
        this.baseIndex = baseIndex;
        this.parser = parser;
        this.spell = spell;
        this.embed = embed;
        this.rrfK = RrfFusion.DEFAULT_K;
        this.candidatePool = 100;
        this.rerankTopK = DEFAULT_RERANK_TOP_K;
        this.pagerankWeight = Math.max(0.0, pagerankWeight);
        this.dedupCollapse = dedupCollapse;
    }

    /**
     * Dựng thân request OpenSearch cho nhánh TỪ KHÓA (query + highlight + phân trang).
     * Tách riêng để test mà không cần engine sống.
     */
    ObjectNode buildRequest(String rawQuery, int from, int size) {
        ObjectNode body = mapper.createObjectNode();
        body.put("from", Math.max(0, from));
        body.put("size", Math.max(0, size));
        body.set("query", withPageRank(parser.buildQuery(rawQuery)));
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
        // Gộp near-duplicate (S3.2): mỗi dup_group chỉ giữ 1 kết quả (doc điểm cao nhất).
        if (dedupCollapse) {
            body.putObject("collapse").put("field", "dup_group");
        }
        return body;
    }

    /**
     * Trộn PageRank vào điểm từ khóa (S3.1) bằng {@code function_score}:
     * <pre>điểm_cuối = điểm_text + ln1p(pagerankWeight * pagerank)</pre>
     * ({@code field_value_factor} + {@code boost_mode=sum}; {@code missing=0} cho doc chưa có
     * pagerank). Trọng số = 0 → trả nguyên truy vấn gốc (TẮT, không đổi hành vi Phase 1/2).
     * Trọng số cần tinh chỉnh bằng eval ở phiên local (PageRank ~1/N nên rất nhỏ).
     */
    ObjectNode withPageRank(ObjectNode baseQuery) {
        if (pagerankWeight <= 0.0) return baseQuery;
        ObjectNode wrap = mapper.createObjectNode();
        ObjectNode fs = wrap.putObject("function_score");
        fs.set("query", baseQuery);
        ObjectNode fvf = fs.putObject("field_value_factor");
        fvf.put("field", "pagerank");
        fvf.put("factor", pagerankWeight);
        fvf.put("modifier", "ln1p");
        fvf.put("missing", 0);
        fs.put("boost_mode", "sum");
        return wrap;
    }

    /**
     * Dựng thân request k-NN (S2.3): truy vấn {@code knn} trên field {@code embedding}.
     * Tách riêng để test việc dựng body mà không cần engine sống.
     */
    ObjectNode buildKnnRequest(float[] vector, int k) {
        ObjectNode body = mapper.createObjectNode();
        body.put("size", Math.max(1, k));
        ObjectNode query = body.putObject("query");
        ObjectNode knn = query.putObject("knn");
        ObjectNode field = knn.putObject(EMBEDDING_FIELD);
        ArrayNode vec = field.putArray("vector");
        for (float v : vector) vec.add(v);
        field.put("k", Math.max(1, k));
        ArrayNode src = body.putArray("_source");
        SOURCE_FIELDS.forEach(src::add);
        return body;
    }

    // ---- API tìm kiếm --------------------------------------------------------

    /** Tương thích Phase 1: không rerank. */
    public SearchResponse search(String rawQuery, int page, int pageSize, Ranker ranker) throws Exception {
        return search(rawQuery, page, pageSize, ranker, false);
    }

    /**
     * Tìm kiếm với phân trang 1-based, tùy chọn rerank (S2.5).
     *
     * <p>Nhánh TỪ KHÓA (bm25/vsm/lm) KHÔNG rerank → giữ nguyên đường đi hiệu quả Phase 1
     * (from/size ở OpenSearch). Các nhánh vector/hybrid, hoặc bất kỳ ranker nào BẬT rerank →
     * đi qua "pool ứng viên" rồi (rerank &) phân trang ở client.
     *
     * @throws QueryParseException nếu cú pháp sai (tầng REST → 400).
     * @throws EmbeddingClient.EmbeddingException nếu embedding/rerank service lỗi (REST → 502).
     */
    public SearchResponse search(String rawQuery, int page, int pageSize, Ranker ranker, boolean rerank)
            throws Exception {
        int p = Math.max(1, page);
        int size = Math.max(1, pageSize);

        if (!ranker.usesEmbedding() && !rerank) {
            return keywordSearch(rawQuery, p, size, ranker);
        }
        return candidateSearch(rawQuery, p, size, ranker, rerank);
    }

    /** Đường đi Phase 1: OpenSearch lo phân trang từ khóa. */
    private SearchResponse keywordSearch(String rawQuery, int p, int size, Ranker ranker) throws Exception {
        int from = (p - 1) * size;
        String index = ranker.indexName(baseIndex);
        ObjectNode body = buildRequest(rawQuery, from, size);

        long t0 = System.nanoTime();
        JsonNode res = os.search(index, body);
        long tookMs = (System.nanoTime() - t0) / 1_000_000;

        SearchResponse out = baseResponse(rawQuery, ranker, p, size, false);
        out.took_ms = tookMs;
        out.total = res.path("hits").path("total").path("value").asLong(0);
        out.total_candidates = out.total;   // nhánh từ khóa: phân trang đi hết corpus
        out.total_pages = SearchResponse.totalPages(out.total, size);
        out.results = mapHits(res.path("hits").path("hits"));
        label(out.results, ranker.param());  // F5: thang điểm nền (bm25/vsm/lm)
        out.suggestion = spell.suggestQuery(rawQuery).orElse(null);
        return out;
    }

    /**
     * Đường đi Phase 2: lấy pool ứng viên (vector / hybrid / từ khóa), tùy chọn rerank top-K,
     * rồi phân trang trên pool. {@code total} = số ứng viên pool (không phải tổng corpus).
     */
    private SearchResponse candidateSearch(String rawQuery, int p, int size, Ranker ranker, boolean rerank)
            throws Exception {
        if (rawQuery == null || rawQuery.isBlank()) {
            throw new QueryParseException("Truy vấn rỗng.");
        }
        long t0 = System.nanoTime();
        CandPool cp = switch (ranker) {
            case VECTOR -> vectorCandidates(rawQuery, candidatePool);
            case HYBRID -> hybridCandidates(rawQuery, candidatePool);
            default     -> keywordCandidates(rawQuery, ranker, candidatePool); // bm25/vsm/lm + rerank
        };
        List<Cand> pool = cp.cands;
        if (rerank) rerankInPlace(rawQuery, pool);
        long tookMs = (System.nanoTime() - t0) / 1_000_000;

        SearchResponse out = baseResponse(rawQuery, ranker, p, size, rerank);
        out.took_ms = tookMs;
        // F4: nhánh ứng viên chỉ phân trang trong pool hữu hạn → total = kích thước pool
        // (= total_candidates), KHÔNG phải tổng corpus. total_matched cho tổng khớp thực nếu biết.
        out.total = pool.size();
        out.total_candidates = pool.size();
        out.total_matched = cp.matchedTotal;
        out.total_pages = SearchResponse.totalPages(out.total, size);
        int from = (p - 1) * size;
        List<SearchHit> hits = new ArrayList<>();
        for (int i = from; i < Math.min(from + size, pool.size()); i++) hits.add(pool.get(i).hit);
        out.results = hits;
        out.suggestion = spell.suggestQuery(rawQuery).orElse(null);
        return out;
    }

    // ---- Lấy ứng viên --------------------------------------------------------

    private CandPool keywordCandidates(String rawQuery, Ranker ranker, int pool) throws Exception {
        ObjectNode body = buildRequest(rawQuery, 0, pool);
        JsonNode res = os.search(ranker.indexName(baseIndex), body);
        List<Cand> cands = mapCands(res.path("hits").path("hits"));
        label(hits(cands), ranker.param());   // F5: thang nền bm25/vsm/lm
        long matched = res.path("hits").path("total").path("value").asLong(cands.size());
        return new CandPool(cands, matched);
    }

    private CandPool vectorCandidates(String rawQuery, int pool) throws Exception {
        float[] vec = embedQuery(rawQuery);
        ObjectNode body = buildKnnRequest(vec, pool);
        JsonNode res = os.search(baseIndex, body);
        List<Cand> cands = mapCands(res.path("hits").path("hits"));
        label(hits(cands), "cosine");   // F5: điểm k-NN (cosine similarity)
        // k-NN không cho tổng khớp corpus có ý nghĩa → -1 (không áp dụng).
        return new CandPool(cands, -1);
    }

    /** Hybrid: hợp nhất danh sách BM25 + vector bằng RRF (S2.4). */
    private CandPool hybridCandidates(String rawQuery, int pool) throws Exception {
        // BM25 trên index gốc.
        JsonNode bmRes = os.search(baseIndex, buildRequest(rawQuery, 0, pool));
        List<Cand> bm = mapCands(bmRes.path("hits").path("hits"));
        long bmMatched = bmRes.path("hits").path("total").path("value").asLong(bm.size());
        // Vector trên index gốc.
        List<Cand> vec = vectorCandidates(rawQuery, pool).cands;

        // Gộp nguồn theo url để lấy lại _source.
        Map<String, Cand> byUrl = new LinkedHashMap<>();
        for (Cand c : bm) byUrl.putIfAbsent(c.url, c);
        for (Cand c : vec) byUrl.putIfAbsent(c.url, c);

        List<List<String>> rankedLists = List.of(urls(bm), urls(vec));
        LinkedHashMap<String, Double> fused = RrfFusion.fuse(rankedLists, rrfK);

        List<Cand> out = new ArrayList<>(fused.size());
        for (var e : fused.entrySet()) {
            Cand base = byUrl.get(e.getKey());
            if (base == null) continue;
            base.hit.score = e.getValue();   // điểm RRF
            base.hit.score_type = "rrf";     // F5: thang RRF, không so trực tiếp với bm25/cosine
            out.add(base);
        }
        // total_matched = tổng khớp thực của nhánh BM25 nền (ước lượng độ phủ corpus).
        return new CandPool(out, bmMatched);
    }

    private static List<String> urls(List<Cand> cs) {
        List<String> out = new ArrayList<>(cs.size());
        for (Cand c : cs) out.add(c.url);
        return out;
    }

    /** Embedding truy vấn: tách từ (G3) giống ingest rồi gọi service. */
    private float[] embedQuery(String rawQuery) {
        requireEmbed();
        String seg = VietnameseAnalyzer.get().segment(rawQuery);
        if (seg.isBlank()) seg = VietnameseAnalyzer.get().normalize(rawQuery);
        return embed.embedOne(seg);
    }

    private void requireEmbed() {
        if (embed == null) {
            throw new EmbeddingClient.EmbeddingException(
                    "Embedding service chưa cấu hình (đặt EMBED_URL / bật service) — không chạy được vector/hybrid/rerank.");
        }
    }

    // ---- Rerank (S2.5) -------------------------------------------------------

    /** Chấm lại top-K của pool bằng cross-encoder rồi SẮP XẾP LẠI phần đầu đó. */
    private void rerankInPlace(String rawQuery, List<Cand> pool) {
        requireEmbed();
        int n = Math.min(rerankTopK, pool.size());
        if (n <= 1) return;
        List<Cand> head = new ArrayList<>(pool.subList(0, n));
        List<String> docs = new ArrayList<>(n);
        for (Cand c : head) docs.add(c.rerankText);

        double[] scores = embed.rerank(rawQuery, docs);
        int[] order = Reranker.order(scores);
        for (int rankPos = 0; rankPos < n; rankPos++) {
            int origIdx = order[rankPos];
            Cand c = head.get(origIdx);
            c.hit.score = scores[origIdx];        // điểm cross-encoder
            c.hit.score_type = "cross-encoder";   // F5: phần đầu đã rerank mang thang cross-encoder
            pool.set(rankPos, c);
        }
        // Phần đuôi (ngoài top-K) giữ nguyên thang nền (rrf/bm25/cosine) — đã gắn score_type từ
        // bước lấy ứng viên; consumer dựa score_type để biết không so trực tiếp hai thang.
    }

    /** F5: gắn nhãn thang điểm cho một loạt hit (dùng chung cho nhánh từ khóa/vector). */
    private static void label(List<SearchHit> hits, String scoreType) {
        for (SearchHit h : hits) h.score_type = scoreType;
    }

    /** Trích danh sách SearchHit từ danh sách Cand (giữ tham chiếu để gắn nhãn tại chỗ). */
    private static List<SearchHit> hits(List<Cand> cands) {
        List<SearchHit> out = new ArrayList<>(cands.size());
        for (Cand c : cands) out.add(c.hit);
        return out;
    }

    // ---- Ánh xạ kết quả ------------------------------------------------------

    private SearchResponse baseResponse(String rawQuery, Ranker ranker, int p, int size, boolean rerank) {
        SearchResponse out = new SearchResponse();
        out.query = rawQuery;
        out.ranker = ranker.param() + (rerank ? "+rerank" : "");
        out.segmented_query = VietnameseAnalyzer.get().segment(rawQuery);
        out.page = p;
        out.page_size = size;
        return out;
    }

    private List<SearchHit> mapHits(JsonNode hits) {
        List<SearchHit> list = new ArrayList<>();
        for (Cand c : mapCands(hits)) list.add(c.hit);
        return list;
    }

    private List<Cand> mapCands(JsonNode hits) {
        List<Cand> list = new ArrayList<>();
        if (!hits.isArray()) return list;
        for (JsonNode h : hits) {
            JsonNode src = h.path("_source");
            SearchHit hit = new SearchHit();
            hit.url = src.path("url").asText("");
            hit.title = src.path("title").asText("");
            hit.doc_type = src.path("doc_type").asText("");
            hit.subdomain = src.path("subdomain").asText("");
            hit.score = h.path("_score").asDouble(0);
            String content = src.path("content").asText("");
            hit.snippet = extractSnippet(h, content);
            list.add(new Cand(hit.url, hit, rerankText(hit.title, content)));
        }
        return list;
    }

    /** Văn bản đưa cross-encoder: title + content THÔ (không tách từ), cắt ngắn để an toàn. */
    private static String rerankText(String title, String content) {
        String t = (title == null ? "" : title).trim();
        String c = (content == null ? "" : content).trim();
        String joined = t.isEmpty() ? c : (c.isEmpty() ? t : t + ". " + c);
        return joined.length() > 2000 ? joined.substring(0, 2000) : joined;
    }

    /** Ưu tiên fragment highlight (thay '_'→' '); nếu không có thì cắt đầu content. */
    private String extractSnippet(JsonNode hit, String content) {
        JsonNode hl = hit.path("highlight").path(QueryParser.CONTENT_FIELD);
        if (hl.isArray() && !hl.isEmpty()) {
            return deSegment(hl.get(0).asText(""));
        }
        if (content.length() > 180) return content.substring(0, 180) + "…";
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

    /** Pool ứng viên kèm tổng khớp thực (F4): matchedTotal = -1 khi không áp dụng (vd vector thuần). */
    private record CandPool(List<Cand> cands, long matchedTotal) {}

    /** Ứng viên nội bộ: giữ kèm văn bản rerank (thô) ngoài SearchHit trả cho client. */
    private static final class Cand {
        final String url;
        final SearchHit hit;
        final String rerankText;
        Cand(String url, SearchHit hit, String rerankText) {
            this.url = url; this.hit = hit; this.rerankText = rerankText;
        }
    }
}
