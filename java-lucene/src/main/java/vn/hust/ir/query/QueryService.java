package vn.hust.ir.query;

import io.javalin.Javalin;
import io.javalin.http.Context;
import vn.hust.ir.embed.EmbeddingClient;
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
        // Embedding Service: cấu hình qua env (bật vector/hybrid/rerank khi có). null nếu không đặt.
        String embedUrl = System.getenv().getOrDefault("EMBED_URL", "http://localhost:8000");
        EmbeddingClient embed = (embedUrl == null || embedUrl.isBlank()) ? null : new EmbeddingClient(embedUrl);
        int rrfK = envInt("RRF_K", RrfFusion.DEFAULT_K);
        int pool = envInt("CANDIDATE_POOL", 100);
        int rerankTopK = envInt("RERANK_TOP_K", SearchEngine.DEFAULT_RERANK_TOP_K);
        this.engine = new SearchEngine(osUrl, baseIndex, new SpellChecker(loadDefaultVocab()),
                embed, rrfK, pool, rerankTopK);
    }

    private static int envInt(String name, int def) {
        try { String v = System.getenv(name); return v == null ? def : Integer.parseInt(v.trim()); }
        catch (Exception e) { return def; }
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

        boolean rerank = parseBool(ctx.queryParam("rerank"));

        if (q == null || q.isBlank()) {
            ctx.json(emptyResponse(q, ranker));
            return;
        }

        try {
            SearchResponse res = engine.search(q, page, size, ranker, rerank);
            ctx.json(res);
        } catch (QueryParseException e) {
            ctx.status(400).json(error("Cú pháp truy vấn sai: " + e.getMessage()));
        } catch (EmbeddingClient.EmbeddingException e) {
            // Embedding/rerank service chết → 502 (không 500), theo phân tầng lỗi HANDOFF.
            ctx.status(502).json(error("Embedding service không phục vụ được: " + e.getMessage()));
        } catch (OpenSearchClient.OpenSearchException e) {
            // CHỈ lỗi cú pháp DSL → 400. Index thiếu (404)/5xx/… là lỗi hệ thống → 502.
            if (e.isQuerySyntaxError()) {
                ctx.status(400).json(error("Cú pháp truy vấn sai: " + e.getMessage()));
            } else {
                ctx.status(502).json(error("OpenSearch không phục vụ được truy vấn: " + e.getMessage()));
            }
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

    private static boolean parseBool(String s) {
        if (s == null) return false;
        String v = s.trim().toLowerCase();
        return v.equals("1") || v.equals("true") || v.equals("yes") || v.equals("on");
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

    // ---- Web UI (S1.6) — nền sáng, chọn ranker, did-you-mean, highlight, phân trang, facet ----
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
          .wrap{max-width:900px;margin:0 auto;padding:20px 16px}
          form{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:12px}
          input[type=text]{flex:1;min-width:220px;padding:11px 13px;border:1px solid var(--line);
               border-radius:8px;font-size:15px;background:var(--card)}
          input[type=text]:focus{outline:2px solid var(--brand);border-color:var(--brand)}
          select{padding:11px 10px;border:1px solid var(--line);border-radius:8px;background:var(--card);font-size:14px}
          button{padding:11px 18px;border:0;border-radius:8px;background:var(--brand);
               color:#fff;font-size:15px;cursor:pointer}
          button:hover{opacity:.92}
          .layout{display:flex;gap:16px;align-items:flex-start}
          .facets{width:200px;flex:0 0 200px;background:var(--card);border:1px solid var(--line);
               border-radius:10px;padding:12px 14px}
          .facets h3{margin:2px 0 8px;font-size:13px;color:var(--muted);text-transform:uppercase;letter-spacing:.03em}
          .facets .ph{color:var(--muted);font-size:13px}
          .main{flex:1;min-width:0}
          .meta{color:var(--muted);font-size:13px;margin-bottom:10px}
          .dym{background:#fff8e1;border:1px solid #f0e0a0;border-radius:8px;padding:9px 12px;margin-bottom:12px;font-size:14px}
          .dym a{color:var(--brand);font-weight:600;cursor:pointer;text-decoration:none}
          .dym a:hover{text-decoration:underline}
          .item{background:var(--card);border:1px solid var(--line);border-radius:10px;
               padding:14px 16px;margin-bottom:12px}
          .item a.t{color:#0b57d0;text-decoration:none;font-size:16px;font-weight:600}
          .item a.t:hover{text-decoration:underline}
          .url{color:#137333;font-size:12.5px;margin:3px 0;word-break:break-all}
          .snip{color:#3c4043;font-size:14px}
          .snip em{background:#fff1a8;font-style:normal;padding:0 1px;border-radius:2px}
          .tags{margin-top:7px}
          .tag{display:inline-block;background:#eef1f5;color:#3c4043;border-radius:6px;
               padding:2px 8px;font-size:12px;margin-right:6px}
          .empty{color:var(--muted);text-align:center;padding:30px}
          .pager{display:flex;flex-wrap:wrap;gap:6px;justify-content:center;margin:18px 0}
          .pager button{background:var(--card);color:var(--text);border:1px solid var(--line);padding:7px 12px;font-size:14px}
          .pager button.cur{background:var(--brand);color:#fff;border-color:var(--brand)}
          .pager button:disabled{opacity:.45;cursor:default}
          @media(max-width:680px){.layout{flex-direction:column}.facets{width:100%;flex:none}}
        </style></head><body>
        <header><h1>🔎 BKSearch — Tìm kiếm tài liệu HUST</h1></header>
        <div class="wrap">
          <form id="f">
            <input type="text" id="q" placeholder='Nhập từ khóa... (hỗ trợ "cụm", AND/OR/NOT)' autofocus>
            <select id="ranker" title="Mô hình xếp hạng">
              <option value="bm25">BM25</option>
              <option value="vsm">VSM (tf-idf)</option>
              <option value="lm">LM (Dirichlet)</option>
              <option value="vector">Vector (k-NN)</option>
              <option value="hybrid">Hybrid (RRF)</option>
            </select>
            <label style="display:flex;align-items:center;gap:5px;font-size:14px;white-space:nowrap">
              <input type="checkbox" id="rerank"> rerank
            </label>
            <button type="submit">Tìm</button>
          </form>
          <div class="layout">
            <aside class="facets">
              <h3>Bộ lọc</h3>
              <div class="ph">Facet (loại tài liệu, subdomain) — sẽ bổ sung ở Phase 3.</div>
            </aside>
            <div class="main">
              <div class="dym" id="dym" style="display:none"></div>
              <div class="meta" id="meta"></div>
              <div id="results"></div>
              <div class="pager" id="pager"></div>
            </div>
          </div>
        </div>
        <script>
        const f=document.getElementById('f'),q=document.getElementById('q'),
              rk=document.getElementById('ranker'),rr=document.getElementById('rerank'),
              meta=document.getElementById('meta'),
              box=document.getElementById('results'),pager=document.getElementById('pager'),
              dym=document.getElementById('dym');
        let curTerm='';
        f.addEventListener('submit',e=>{e.preventDefault();const t=q.value.trim();if(t){curTerm=t;go(1);}});
        rk.addEventListener('change',()=>{if(curTerm)go(1);});
        rr.addEventListener('change',()=>{if(curTerm)go(1);});
        async function go(page){
          meta.textContent='Đang tìm...';box.innerHTML='';pager.innerHTML='';dym.style.display='none';
          let r;
          try{ r=await fetch('/api/search?q='+encodeURIComponent(curTerm)+'&page='+page
                 +'&ranker='+rk.value+'&rerank='+(rr.checked?'1':'0')); }
          catch(err){ meta.textContent='Lỗi mạng: '+err; return; }
          const d=await r.json();
          if(d.error){meta.textContent='Lỗi: '+d.error;return;}
          meta.textContent='Tìm thấy '+d.total+' kết quả cho "'+d.query+'" — ranker '+d.ranker.toUpperCase()+
             ' — '+d.took_ms+' ms — trang '+d.page+'/'+(d.total_pages||1);
          if(d.suggestion){
            dym.style.display='block';
            dym.innerHTML='Có phải bạn muốn tìm: <a id="dyml">'+esc(d.suggestion)+'</a>?';
            document.getElementById('dyml').onclick=()=>{q.value=d.suggestion;curTerm=d.suggestion;go(1);};
          }
          if(!d.results.length){box.innerHTML='<div class="empty">Không có kết quả phù hợp.</div>';return;}
          box.innerHTML=d.results.map(x=>`
            <div class="item">
              <a class="t" href="${x.url}" target="_blank" rel="noopener">${esc(x.title)||'(không tiêu đề)'}</a>
              <div class="url">${esc(x.url)}</div>
              <div class="snip">${hl(x.snippet)}</div>
              <div class="tags"><span class="tag">${esc(x.doc_type||'')}</span>
                <span class="tag">${esc(x.subdomain||'')}</span>
                <span class="tag">score ${(x.score||0).toFixed(3)}</span></div>
            </div>`).join('');
          renderPager(d.page,d.total_pages||1);
          window.scrollTo(0,0);
        }
        function renderPager(page,total){
          if(total<=1){pager.innerHTML='';return;}
          let h='<button '+(page<=1?'disabled':'')+' data-p="'+(page-1)+'">‹ Trước</button>';
          const from=Math.max(1,page-2),to=Math.min(total,page+2);
          if(from>1)h+='<button data-p="1">1</button>'+(from>2?'<span>…</span>':'');
          for(let i=from;i<=to;i++)h+='<button class="'+(i===page?'cur':'')+'" data-p="'+i+'">'+i+'</button>';
          if(to<total)h+=(to<total-1?'<span>…</span>':'')+'<button data-p="'+total+'">'+total+'</button>';
          h+='<button '+(page>=total?'disabled':'')+' data-p="'+(page+1)+'">Sau ›</button>';
          pager.innerHTML=h;
          pager.querySelectorAll('button[data-p]').forEach(b=>b.onclick=()=>go(parseInt(b.dataset.p)));
        }
        function esc(s){return (s||'').replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));}
        // Snippet: escape toàn bộ (chống XSS lưu trữ từ nội dung crawl) rồi KHÔI PHỤC thẻ <em> highlight.
        function hl(s){return esc(s||'').replace(/&lt;em&gt;/g,'<em>').replace(/&lt;\\/em&gt;/g,'</em>');}
        </script></body></html>
        """;
}
