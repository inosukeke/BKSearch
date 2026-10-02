package vn.hust.ir.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import vn.hust.ir.nlp.Segmenter;

import java.util.ArrayList;
import java.util.List;

/**
 * Dựng truy vấn OpenSearch từ chuỗi người dùng (S1.2 + S1.4).
 *
 * <p><b>Hai chế độ:</b>
 * <ul>
 *   <li><b>Đơn giản</b> (không có {@code "}, ngoặc, hay toán tử): một {@code multi_match}
 *       best_fields trên {@code title_seg^2} + {@code content_seg} — đây là đường BM25 S1.2.</li>
 *   <li><b>Có cấu trúc</b>: hỗ trợ cụm {@code "..."}, proximity {@code "..."~N},
 *       Boolean {@code AND/OR/NOT}, ngoặc {@code ( )} — S1.4.</li>
 * </ul>
 *
 * <p><b>Ràng buộc G3:</b> mọi term/cụm đều được tách từ bằng {@link Segmenter} trước khi
 * đặt vào field {@code *_seg} (analyzer vi_seg = whitespace+lowercase) → nhất quán với ingest.
 *
 * <p>Cú pháp sai (ngoặc/nháy không cân, toán tử thừa...) ném {@link QueryParseException}.
 */
public class QueryParser {

    /** Field + boost dùng cho khớp term (giữ nhất quán giữa simple & structured). */
    public static final String TITLE_FIELD = "title_seg";
    public static final String CONTENT_FIELD = "content_seg";
    /** Anchor text của liên kết đến (S3.1) — tín hiệu mô tả trang từ bên ngoài. Rỗng → vô hại. */
    public static final String ANCHOR_FIELD = "anchor_text_seg";
    public static final double TITLE_BOOST = 2.0;
    public static final double ANCHOR_BOOST = 1.5;

    private final Segmenter analyzer;
    private final ObjectMapper mapper;

    public QueryParser(Segmenter analyzer, ObjectMapper mapper) {
        this.analyzer = analyzer;
        this.mapper = mapper;
    }

    /** Có dùng cú pháp nâng cao (cụm/Boolean/ngoặc) không? */
    public static boolean isStructured(String raw) {
        if (raw == null) return false;
        if (raw.indexOf('"') >= 0 || raw.indexOf('(') >= 0 || raw.indexOf(')') >= 0) return true;
        for (String w : raw.trim().split("\\s+")) {
            String u = w.toUpperCase();
            if (u.equals("AND") || u.equals("OR") || u.equals("NOT")) return true;
        }
        return false;
    }

    /**
     * Dựng mệnh đề {@code query} của OpenSearch cho chuỗi truy vấn.
     * @throws QueryParseException nếu truy vấn rỗng hoặc sai cú pháp.
     */
    public ObjectNode buildQuery(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new QueryParseException("Truy vấn rỗng.");
        }
        if (!isStructured(raw)) {
            String seg = analyzer.segment(raw);
            if (seg.isBlank()) seg = analyzer.normalize(raw);
            return segOrFold(seg, raw, "or");
        }
        List<Tok> toks = tokenize(raw);
        if (toks.isEmpty()) throw new QueryParseException("Truy vấn rỗng sau khi phân tích.");
        Parser p = new Parser(toks);
        Node ast = p.parseOr();
        if (!p.atEnd()) {
            throw new QueryParseException("Thừa ký tự gần: '" + p.peek().text + "'.");
        }
        return ast.toQuery(this);
    }

    // ---- Bộ dựng mệnh đề cơ sở (dùng chung) ----------------------------------

    /**
     * Gộp đường TÁCH TỪ (có dấu, chính xác) + đường BỎ DẤU (không dấu, recall) cho truy vấn đơn.
     * <ul>
     *   <li>nhánh {@code _seg}: multi_match {@code segmented} trên {@code title_seg/content_seg} (token
     *       ghép, giữ dấu) — khớp mạnh khi gõ ĐÚNG dấu;</li>
     *   <li>nhánh BỎ DẤU: multi_match {@code raw} trên {@code title/content} (analyzer {@code vi_fold} có
     *       {@code asciifolding}) — cho gõ KHÔNG dấu vẫn khớp ("giang vien" → "giảng viên").</li>
     * </ul>
     * Gộp bằng {@code bool.should} (khớp 1 trong 2 là đủ); nhánh _seg boost cao hơn để ưu tiên gõ đúng dấu.
     */
    ObjectNode segOrFold(String segmented, String raw, String operator) {
        ObjectNode q = mapper.createObjectNode();
        ObjectNode bool = q.putObject("bool");
        ArrayNode should = bool.putArray("should");
        should.add(multiMatch(segmented, operator));     // có dấu (tách từ)
        should.add(foldMatch(raw, operator));            // không dấu (bỏ dấu, theo từng từ)
        should.add(foldPhrase(raw));                     // BOOST cụm liền nhau (bỏ dấu) → "giảng viên" lên top
        bool.put("minimum_should_match", 1);
        return q;
    }

    /**
     * Boost tài liệu có các từ truy vấn ĐỨNG GẦN NHAU trên field đã bỏ dấu ({@code title/content}),
     * giúp cụm như "giang vien" (→ "giảng viên") xếp trên trang chỉ tình cờ chứa 1 từ (vd tên "Giang").
     * Dùng {@code match_phrase} slop=1 (cho phép lệch 1 vị trí), boost cao.
     */
    ObjectNode foldPhrase(String raw) {
        ObjectNode q = mapper.createObjectNode();
        ObjectNode bool = q.putObject("bool");
        ArrayNode should = bool.putArray("should");
        should.add(matchPhraseBoost("title", raw, 1, 4.0));
        should.add(matchPhraseBoost("content", raw, 1, 2.0));
        bool.put("minimum_should_match", 1);
        return q;
    }

    private ObjectNode matchPhraseBoost(String field, String raw, int slop, double boost) {
        ObjectNode q = mapper.createObjectNode();
        ObjectNode mp = q.putObject("match_phrase");
        ObjectNode body = mp.putObject(field);
        body.put("query", raw);
        if (slop > 0) body.put("slop", slop);
        body.put("boost", boost);
        return q;
    }

    /** multi_match trên field RAW đã bỏ dấu ({@code title/content}, analyzer vi_fold), boost thấp hơn _seg. */
    ObjectNode foldMatch(String raw, String operator) {
        ObjectNode mm = mapper.createObjectNode();
        ObjectNode body = mm.putObject("multi_match");
        body.put("query", raw);
        body.put("type", "best_fields");
        body.put("operator", operator);
        ArrayNode fields = body.putArray("fields");
        fields.add("title^1.5");
        fields.add("content");
        body.put("boost", 0.8);   // nhường điểm cho nhánh tách từ (có dấu) khi cả hai cùng khớp
        return mm;
    }

    /** multi_match best_fields trên title_seg^2 + content_seg (BM25 S1.2), operator "or". */
    ObjectNode multiMatch(String segmented) {
        return multiMatch(segmented, "or");
    }

    /** Như trên nhưng chỉ định operator ("or" cho truy vấn đơn, "and" cho chuỗi term bắt buộc). */
    ObjectNode multiMatch(String segmented, String operator) {
        ObjectNode mm = mapper.createObjectNode();
        ObjectNode body = mm.putObject("multi_match");
        body.put("query", segmented);
        body.put("type", "best_fields");
        body.put("operator", operator);
        ArrayNode fields = body.putArray("fields");
        fields.add(TITLE_FIELD + "^" + (int) TITLE_BOOST);
        fields.add(CONTENT_FIELD);
        fields.add(ANCHOR_FIELD + "^" + ANCHOR_BOOST);   // S3.1 anchor text (rỗng → không ảnh hưởng)
        return mm;
    }

    /**
     * Mệnh đề mở rộng truy vấn (S3.3): multi_match "or" trên các token ĐÃ tách từ, boost thấp
     * ({@code boost}) để tăng recall qua nhánh {@code should} mà không lấn át điểm gốc.
     */
    ObjectNode expansionClause(String segmentedTerms, double boost) {
        ObjectNode mm = multiMatch(segmentedTerms, "or");
        ((ObjectNode) mm.get("multi_match")).put("boost", boost);
        return mm;
    }

    /** match_phrase (có slop) trên cả hai field, gộp bằng should. */
    ObjectNode phrase(String segmented, int slop) {
        ObjectNode q = mapper.createObjectNode();
        ObjectNode bool = q.putObject("bool");
        ArrayNode should = bool.putArray("should");
        should.add(matchPhrase(TITLE_FIELD, segmented, slop));
        should.add(matchPhrase(CONTENT_FIELD, segmented, slop));
        bool.put("minimum_should_match", 1);
        return q;
    }

    private ObjectNode matchPhrase(String field, String segmented, int slop) {
        ObjectNode q = mapper.createObjectNode();
        ObjectNode mp = q.putObject("match_phrase");
        ObjectNode body = mp.putObject(field);
        body.put("query", segmented);
        if (slop > 0) body.put("slop", slop);
        return q;
    }

    String segment(String text) {
        String seg = analyzer.segment(text);
        return seg.isBlank() ? analyzer.normalize(text) : seg;
    }

    ObjectMapper mapper() { return mapper; }

    // ---- AST -----------------------------------------------------------------

    interface Node { ObjectNode toQuery(QueryParser qp); }

    record Term(String text) implements Node {
        public ObjectNode toQuery(QueryParser qp) { return qp.multiMatch(qp.segment(text)); }
    }

    /**
     * Chuỗi các term kề nhau (không bị toán tử/ngoặc/nháy ngăn): nối text GỐC rồi tách từ
     * MỘT LẦN (G3 — để từ ghép tiếng Việt như "đại học"→"đại_học" khớp field *_seg), dựng
     * một multi_match operator "and" (mọi token bắt buộc xuất hiện).
     */
    record TermRun(String rawJoined) implements Node {
        public ObjectNode toQuery(QueryParser qp) { return qp.multiMatch(qp.segment(rawJoined), "and"); }
    }

    record Phrase(String text, int slop) implements Node {
        public ObjectNode toQuery(QueryParser qp) { return qp.phrase(qp.segment(text), slop); }
    }

    record Not(Node inner) implements Node {
        public ObjectNode toQuery(QueryParser qp) {
            ObjectNode q = qp.mapper().createObjectNode();
            q.putObject("bool").putArray("must_not").add(inner.toQuery(qp));
            return q;
        }
    }

    record And(List<Node> kids) implements Node {
        public ObjectNode toQuery(QueryParser qp) {
            ObjectNode q = qp.mapper().createObjectNode();
            ArrayNode must = q.putObject("bool").putArray("must");
            for (Node k : kids) must.add(k.toQuery(qp));
            return q;
        }
    }

    record Or(List<Node> kids) implements Node {
        public ObjectNode toQuery(QueryParser qp) {
            ObjectNode q = qp.mapper().createObjectNode();
            ObjectNode bool = q.putObject("bool");
            ArrayNode should = bool.putArray("should");
            for (Node k : kids) should.add(k.toQuery(qp));
            bool.put("minimum_should_match", 1);
            return q;
        }
    }

    // ---- Tokenizer -----------------------------------------------------------

    private enum Type { TERM, PHRASE, AND, OR, NOT, LPAREN, RPAREN }

    private static final class Tok {
        final Type type; final String text; final int slop;
        Tok(Type t, String s, int slop) { this.type = t; this.text = s; this.slop = slop; }
    }

    private static List<Tok> tokenize(String raw) {
        List<Tok> out = new ArrayList<>();
        int i = 0, n = raw.length();
        while (i < n) {
            char c = raw.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '(') { out.add(new Tok(Type.LPAREN, "(", 0)); i++; continue; }
            if (c == ')') { out.add(new Tok(Type.RPAREN, ")", 0)); i++; continue; }
            if (c == '"') {
                int close = raw.indexOf('"', i + 1);
                if (close < 0) throw new QueryParseException("Dấu nháy kép không cân (thiếu \").");
                String phrase = raw.substring(i + 1, close);
                i = close + 1;
                int slop = 0;
                if (i < n && raw.charAt(i) == '~') {
                    int j = i + 1, start = j;
                    while (j < n && Character.isDigit(raw.charAt(j))) j++;
                    if (j == start) throw new QueryParseException("Proximity '~' phải kèm số, ví dụ \"...\"~2.");
                    slop = Integer.parseInt(raw.substring(start, j));
                    i = j;
                }
                if (phrase.isBlank()) throw new QueryParseException("Cụm trong nháy kép rỗng.");
                out.add(new Tok(Type.PHRASE, phrase.trim(), slop));
                continue;
            }
            // từ thường: đọc tới khoảng trắng hoặc ngoặc/nháy
            int j = i;
            while (j < n && !Character.isWhitespace(raw.charAt(j))
                    && raw.charAt(j) != '(' && raw.charAt(j) != ')' && raw.charAt(j) != '"') j++;
            String w = raw.substring(i, j);
            i = j;
            switch (w.toUpperCase()) {
                case "AND" -> out.add(new Tok(Type.AND, w, 0));
                case "OR"  -> out.add(new Tok(Type.OR, w, 0));
                case "NOT" -> out.add(new Tok(Type.NOT, w, 0));
                default    -> out.add(new Tok(Type.TERM, w, 0));
            }
        }
        return out;
    }

    // ---- Recursive-descent parser -------------------------------------------

    private static final class Parser {
        private final List<Tok> toks;
        private int pos = 0;
        Parser(List<Tok> toks) { this.toks = toks; }

        boolean atEnd() { return pos >= toks.size(); }
        Tok peek() { return toks.get(pos); }
        private Tok next() { return toks.get(pos++); }
        private boolean is(Type t) { return !atEnd() && peek().type == t; }

        /** orExpr := andExpr (OR andExpr)* */
        Node parseOr() {
            List<Node> kids = new ArrayList<>();
            kids.add(parseAnd());
            while (is(Type.OR)) { next(); kids.add(parseAnd()); }
            return kids.size() == 1 ? kids.get(0) : new Or(kids);
        }

        /**
         * andExpr := (TERM+ | unary) ((AND | kề-nhau) (TERM+ | unary))*
         * Các TERM kề nhau được GOM thành một {@link TermRun} và tách từ một lần (G3).
         */
        Node parseAnd() {
            List<Node> kids = new ArrayList<>();
            List<String> run = new ArrayList<>();          // các term gốc liên tiếp
            boolean started = false;
            while (true) {
                if (is(Type.TERM)) {
                    run.add(peek().text); next(); started = true;
                } else if (is(Type.AND)) {
                    if (!started) throw new QueryParseException("Toán tử 'AND' đặt sai vị trí.");
                    flushRun(run, kids); next();
                    if (!startsAtom()) throw new QueryParseException("Toán tử 'AND' thiếu từ khóa theo sau.");
                } else if (is(Type.NOT) || is(Type.PHRASE) || is(Type.LPAREN)) {
                    flushRun(run, kids); kids.add(parseUnary()); started = true;   // AND ngầm
                } else {
                    break;  // OR, RPAREN, hết
                }
            }
            flushRun(run, kids);
            if (kids.isEmpty()) throw new QueryParseException("Thiếu từ khóa.");
            return kids.size() == 1 ? kids.get(0) : new And(kids);
        }

        /** Gom chuỗi term gốc thành 1 TermRun (tách từ một lần ở toQuery). */
        private void flushRun(List<String> run, List<Node> kids) {
            if (!run.isEmpty()) {
                kids.add(new TermRun(String.join(" ", run)));
                run.clear();
            }
        }

        private boolean startsAtom() {
            return is(Type.NOT) || is(Type.TERM) || is(Type.PHRASE) || is(Type.LPAREN);
        }

        /** unary := NOT unary | atom */
        Node parseUnary() {
            if (is(Type.NOT)) { next(); return new Not(parseUnary()); }
            return parseAtom();
        }

        /** atom := '(' orExpr ')' | PHRASE | TERM */
        Node parseAtom() {
            if (atEnd()) throw new QueryParseException("Thiếu từ khóa ở cuối truy vấn.");
            Tok t = peek();
            switch (t.type) {
                case LPAREN -> {
                    next();
                    Node inner = parseOr();
                    if (!is(Type.RPAREN)) throw new QueryParseException("Thiếu dấu ')' đóng ngoặc.");
                    next();
                    return inner;
                }
                case PHRASE -> { next(); return new Phrase(t.text, t.slop); }
                case TERM   -> { next(); return new Term(t.text); }
                case AND, OR -> throw new QueryParseException("Toán tử '" + t.text + "' đặt sai vị trí.");
                case RPAREN -> throw new QueryParseException("Thừa dấu ')'.");
                default -> throw new QueryParseException("Cú pháp không hợp lệ gần '" + t.text + "'.");
            }
        }
    }
}
