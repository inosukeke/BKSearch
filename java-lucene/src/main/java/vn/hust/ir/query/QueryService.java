package vn.hust.ir.query;

import io.javalin.Javalin;
import io.javalin.http.Context;
import vn.hust.ir.migrate.OpenSearchClient;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Query Service (Javalin) — hợp đồng REST của Phase 1 (S1.1).
 *
 * <pre>
 *   GET /healthz                               → {"status":"ok"}
 *   GET /api/search?q=&amp;page=&amp;ranker=   → SearchResponse (JSON)
 *   GET /api/suggest?q=                        → {"query":..., "suggestion":...}
 *   GET /                                      → Web UI (S1.6)
 * </pre>
 *
 * Cú pháp truy vấn sai → 400 (không 500). OpenSearch chết → 502.
 */
public class QueryService {

    public static final int DEFAULT_PAGE_SIZE = 10;

    private final String osUrl;
    private final String baseIndex;
    private final SearchEngine engine;

    public QueryService(String osUrl, String baseIndex) {
        this.osUrl = osUrl;
        this.baseIndex = baseIndex;
        this.engine = new SearchEngine(osUrl, baseIndex, new SpellChecker(loadDefaultVocab()));
    }

    public Javalin build() {
        Javalin app = Javalin.create(cfg -> cfg.showJavalinBanner = false);
        app.get("/healthz", this::healthz);
        app.get("/api/search", this::handleSearch);
        app.get("/api/suggest", this::handleSuggest);
        app.get("/", ctx -> ctx.html(UI_PAGE));
        return app;
    }

    public void start(int port) {
        build().start(port);
        System.out.println("Query Service (Javalin) chạy tại http://localhost:" + port
                + "  | OpenSearch=" + osUrl + " index=" + baseIndex);
    }

    // ---- Handlers ------------------------------------------------------------

    private void healthz(Context ctx) {
        ctx.json(Map.of("status", "ok", "service", "bksearch-query", "index", baseIndex));
    }

    private void handleSearch(Context ctx) {
        String q = ctx.queryParam("q");
        int page = parseInt(ctx.queryParam("page"), 1);
        int size = parseInt(ctx.queryParam("size"), DEFAULT_PAGE_SIZE);

        final Ranker ranker;
        try {
            ranker = Ranker.fromParam(ctx.queryParam("ranker"));
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(error(e.getMessage()));
            return;
        }

        if (q == null || q.isBlank()) {
            ctx.json(emptyResponse(q, ranker));
            return;
        }

        try {
            SearchResponse res = engine.search(q, page, size, ranker);
            ctx.json(res);
        } catch (QueryParseException e) {
            ctx.status(400).json(error("Cú pháp truy vấn sai: " + e.getMessage()));
        } catch (OpenSearchClient.OpenSearchException e) {
            // Lỗi DSL do truy vấn (4xx) → 400; lỗi khác của engine → 502.
            if (e.status >= 400 && e.status < 500) ctx.status(400).json(error(e.getMessage()));
            else ctx.status(502).json(error("OpenSearch lỗi: " + e.getMessage()));
        } catch (Exception e) {
            ctx.status(502).json(error("Không gọi được OpenSearch: " + e.getMessage()));
        }
    }

    private void handleSuggest(Context ctx) {
        String q = ctx.queryParam("q");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", q == null ? "" : q);
        Optional<String> s = (q == null || q.isBlank()) ? Optional.empty() : engine.suggest(q);
        out.put("suggestion", s.orElse(null));
        ctx.json(out);
    }

    // ---- Helpers -------------------------------------------------------------

    private SearchResponse emptyResponse(String q, Ranker ranker) {
        SearchResponse r = new SearchResponse();
        r.query = q == null ? "" : q;
        r.ranker = ranker.param();
        r.segmented_query = "";
        r.total = 0;
        r.page = 1;
        r.page_size = DEFAULT_PAGE_SIZE;
        r.total_pages = 0;
        r.took_ms = 0;
        r.suggestion = null;
        r.results = new ArrayList<>();
        return r;
    }

    private Map<String, Object> error(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        return m;
    }

    private static int parseInt(String s, int def) {
        try { return s == null ? def : Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    /** Nạp từ vựng did-you-mean từ từ điển tách từ (/vi-words.txt). */
    static List<String> loadDefaultVocab() {
        List<String> out = new ArrayList<>();
        try (InputStream in = QueryService.class.getResourceAsStream("/vi-words.txt")) {
            if (in == null) return out;
            BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) out.add(line);
            }
        } catch (Exception e) {
            System.err.println("[QueryService] không nạp được từ vựng suggest: " + e.getMessage());
        }
        return out;
    }

    // ---- Web UI (S1.1 bản cơ sở — sẽ nâng cấp ở S1.6) ----
    static final String UI_PAGE = """
        <!doctype html><html lang="vi"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>BKSearch — Tìm kiếm tài liệu HUST</title>
        <style>
          :root{--bg:#f7f8fa;--card:#fff;--line:#e3e6ea;--brand:#9b1b23;--muted:#6b7280;--text:#1f2328}
          *{box-sizing:border-box}
          body{margin:0;background:var(--bg);color:var(--text);
               font:15px/1.55 system-ui,Segoe UI,Roboto,Arial,sans-serif}
          header{background:var(--brand);color:#fff;padding:16px 20px}
          header h1{margin:0;font-size:19px;font-weight:600}
          .wrap{max-width:820px;margin:0 auto;padding:22px 16px}
          form{display:flex;gap:8px;margin-bottom:16px}
          input[type=text]{flex:1;padding:11px 13px;border:1px solid var(--line);
               border-radius:8px;font-size:15px;background:var(--card)}
          button{padding:11px 18px;border:0;border-radius:8px;background:var(--brand);color:#fff;font-size:15px;cursor:pointer}
          .meta{color:var(--muted);font-size:13px;margin-bottom:10px}
          .item{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:14px 16px;margin-bottom:12px}
          .item a.t{color:#0b57d0;text-decoration:none;font-size:16px;font-weight:600}
          .url{color:#137333;font-size:12.5px;margin:3px 0;word-break:break-all}
          .snip{color:#3c4043;font-size:14px}
          .empty{color:var(--muted);text-align:center;padding:30px}
        </style></head><body>
        <header><h1>🔎 BKSearch — Tìm kiếm tài liệu HUST</h1></header>
        <div class="wrap">
          <form id="f"><input type="text" id="q" placeholder="Nhập từ khóa..." autofocus>
          <button type="submit">Tìm</button></form>
          <div class="meta" id="meta"></div>
          <div id="results"></div>
        </div>
        <script>
        const f=document.getElementById('f'),q=document.getElementById('q'),
              meta=document.getElementById('meta'),box=document.getElementById('results');
        f.addEventListener('submit',async e=>{
          e.preventDefault();const t=q.value.trim();if(!t)return;
          meta.textContent='Đang tìm...';box.innerHTML='';
          const d=await (await fetch('/api/search?q='+encodeURIComponent(t))).json();
          if(d.error){meta.textContent='Lỗi: '+d.error;return;}
          meta.textContent='Tìm thấy '+d.total+' kết quả — '+d.took_ms+' ms';
          if(!d.results.length){box.innerHTML='<div class="empty">Không có kết quả.</div>';return;}
          box.innerHTML=d.results.map(x=>`<div class="item">
            <a class="t" href="${x.url}" target="_blank" rel="noopener">${esc(x.title)||'(không tiêu đề)'}</a>
            <div class="url">${esc(x.url)}</div><div class="snip">${x.snippet||''}</div></div>`).join('');
        });
        function esc(s){return (s||'').replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));}
        </script></body></html>
        """;
}
