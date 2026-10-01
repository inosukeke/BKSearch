package vn.hust.ir.query;

/**
 * Mô hình xếp hạng từ khóa (S1.3). Chọn qua tham số REST {@code ranker=bm25|vsm|lm}.
 *
 * <h2>Cách hiện thực đa similarity mà KHÔNG reindex lại từ nguồn</h2>
 * OpenSearch gán {@code similarity} ở cấp <b>field mapping</b> (cố định khi tạo index),
 * nên không thể đổi công thức chấm điểm ngay lúc truy vấn trên cùng một field. Giải pháp
 * chọn ở đây: tạo các <b>index song song nhỏ</b> dùng chung {@code _source} nhưng khác
 * similarity:
 * <ul>
 *   <li>{@code documents}      — BM25 (mặc định của OpenSearch).</li>
 *   <li>{@code documents_vsm}  — {@code ClassicSimilarity} (VSM tf-idf cosine).</li>
 *   <li>{@code documents_lm}   — {@code LMDirichlet} (Language Model).</li>
 * </ul>
 * Hai index sau được dựng bằng OpenSearch <b>{@code _reindex}</b> (copy phía server, không
 * cần tách từ lại ở client, không tải corpus về) — xem
 * {@code deploy/opensearch/create-ranker-indices.sh}. Đổi ranker chỉ là đổi index đích,
 * mọi logic dựng truy vấn/tách từ (G3) giữ nguyên.
 */
public enum Ranker {

    /** Okapi BM25 — mặc định. */
    BM25("bm25", "", "BM25", false),
    /** Vector Space Model (tf-idf cosine) — Lucene {@code ClassicSimilarity}. */
    VSM("vsm", "_vsm", "ClassicSimilarity(tf-idf)", false),
    /** Language Model với làm mượt Dirichlet — Lucene {@code LMDirichletSimilarity}. */
    LM("lm", "_lm", "LMDirichlet", false),
    /** Tìm theo vector ngữ nghĩa (k-NN HNSW) trên field {@code embedding} (S2.3). */
    VECTOR("vector", "", "kNN(cosine)", true),
    /** Hợp nhất BM25 + vector bằng RRF (S2.4). Dùng index gốc (có cả text & embedding). */
    HYBRID("hybrid", "", "Hybrid(BM25+vector, RRF)", true);

    private final String param;
    private final String indexSuffix;
    private final String similarity;
    private final boolean usesEmbedding;

    Ranker(String param, String indexSuffix, String similarity, boolean usesEmbedding) {
        this.param = param;
        this.indexSuffix = indexSuffix;
        this.similarity = similarity;
        this.usesEmbedding = usesEmbedding;
    }

    public String param()      { return param; }
    public String similarity() { return similarity; }

    /** Ranker cần gọi Embedding Service (VECTOR, HYBRID). */
    public boolean usesEmbedding() { return usesEmbedding; }

    /**
     * Ánh xạ tham số người dùng → Ranker. Rỗng/null → BM25 (mặc định).
     * @throws IllegalArgumentException nếu giá trị không hợp lệ (tầng REST → 400).
     */
    public static Ranker fromParam(String p) {
        if (p == null || p.isBlank()) return BM25;
        String v = p.trim().toLowerCase();
        for (Ranker r : values()) if (r.param.equals(v)) return r;
        throw new IllegalArgumentException("ranker không hợp lệ: '" + p
                + "' (hợp lệ: bm25 | vsm | lm | vector | hybrid)");
    }

    /**
     * Tên index tương ứng với ranker, dựa trên index cơ sở.
     * Ví dụ base="documents": BM25→"documents", VSM→"documents_vsm", LM→"documents_lm".
     */
    public String indexName(String baseIndex) {
        return baseIndex + indexSuffix;
    }
}
