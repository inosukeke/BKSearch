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
    public static final double TITLE_BOOST = 2.0;

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
            return multiMatch(seg);
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

    /** multi_match best_fields trên title_seg^2 + content_seg (BM25 S1.2). */
    ObjectNode multiMatch(String segmented) {
        ObjectNode mm = mapper.createObjectNode();
        ObjectNode body = mm.putObject("multi_match");
        body.put("query", segmented);
        body.put("type", "best_fields");
        body.put("operator", "or");
        ArrayNode fields = body.putArray("fields");
        fields.add(TITLE_FIELD + "^" + (int) TITLE_BOOST);
        fields.add(CONTENT_FIELD);
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

        /** andExpr := unary ((AND | kề-nhau) unary)* */
        Node parseAnd() {
            List<Node> kids = new ArrayList<>();
            kids.add(parseUnary());
            while (true) {
                if (is(Type.AND)) { next(); kids.add(parseUnary()); }
                else if (startsAtom()) { kids.add(parseUnary()); } // AND ngầm
                else break;
            }
            return kids.size() == 1 ? kids.get(0) : new And(kids);
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
