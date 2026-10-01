package vn.hust.ir.search;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Giao diện web tìm kiếm đơn giản (nền sáng), dùng HTTP server có sẵn của JDK
 * (không thêm thư viện). Hỗ trợ phân trang (10 kết quả/trang).
 *   GET  /                      -> trang tìm kiếm
 *   GET  /api/search?q=&page=1  -> kết quả JSON (có phân trang)
 */
public class WebServer {

    private static final int PAGE_SIZE = 10;
    private final Path indexDir;

    public WebServer(Path indexDir) {
        this.indexDir = indexDir;
    }

    public void start(int port) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", this::handlePage);
        server.createContext("/api/search", this::handleSearch);
        server.setExecutor(null);
        server.start();
        System.out.println("Web UI đang chạy: http://localhost:" + port + "  (Ctrl+C để dừng)");
    }

    private void handlePage(HttpExchange ex) throws java.io.IOException {
        if (!ex.getRequestURI().getPath().equals("/")) { send(ex, 404, "text/plain", "Not found"); return; }
        send(ex, 200, "text/html; charset=utf-8", PAGE);
    }

    private void handleSearch(HttpExchange ex) throws java.io.IOException {
        String q = param(ex, "q");
        int page = parseInt(param(ex, "page"), 1);
        StringBuilder json = new StringBuilder();
        if (q == null || q.isBlank()) {
            json.append("{\"query\":\"\",\"total_hits\":0,\"page\":1,\"page_size\":")
                .append(PAGE_SIZE).append(",\"total_pages\":0,\"results\":[]}");
        } else {
            try (LuceneSearcher searcher = new LuceneSearcher(indexDir)) {
                SearchPage sp = searcher.searchPage(q, page, PAGE_SIZE);
                json.append("{\"query\":").append(jstr(q))
                    .append(",\"total_indexed\":").append(searcher.numDocs())
                    .append(",\"total_hits\":").append(sp.totalHits)
                    .append(",\"page\":").append(sp.page)
                    .append(",\"page_size\":").append(sp.pageSize)
                    .append(",\"total_pages\":").append(sp.totalPages())
                    .append(",\"results\":[");
                for (int i = 0; i < sp.results.size(); i++) {
                    SearchResult r = sp.results.get(i);
                    if (i > 0) json.append(',');
                    json.append("{\"url\":").append(jstr(r.url))
                        .append(",\"title\":").append(jstr(r.title))
                        .append(",\"snippet\":").append(jstr(r.snippet))
                        .append(",\"doc_type\":").append(jstr(r.docType))
                        .append(",\"subdomain\":").append(jstr(r.subdomain))
                        .append(",\"score\":").append(String.format("%.3f", r.score))
                        .append('}');
                }
                json.append("]}");
            } catch (Exception e) {
                json.setLength(0);
                json.append("{\"error\":").append(jstr(e.getMessage())).append("}");
            }
        }
        send(ex, 200, "application/json; charset=utf-8", json.toString());
    }

    private String param(HttpExchange ex, String key) {
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return null;
        for (String p : raw.split("&")) {
            String[] kv = p.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) {
                return java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private int parseInt(String s, int def) {
        try { return s == null ? def : Integer.parseInt(s); } catch (Exception e) { return def; }
    }

    private void send(HttpExchange ex, int code, String ctype, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", ctype);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private static String jstr(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    // Trang tìm kiếm: nền sáng, đơn giản, có phân trang.
    private static final String PAGE = """
        <!doctype html><html lang="vi"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Tìm kiếm tài liệu HUST</title>
        <style>
          :root{--bg:#f7f8fa;--card:#fff;--line:#e3e6ea;--brand:#9b1b23;--muted:#6b7280;--text:#1f2328}
          *{box-sizing:border-box}
          body{margin:0;background:var(--bg);color:var(--text);
               font:15px/1.55 system-ui,Segoe UI,Roboto,Arial,sans-serif}
          header{background:var(--brand);color:#fff;padding:18px 20px}
          header h1{margin:0;font-size:19px;font-weight:600}
          .wrap{max-width:820px;margin:0 auto;padding:22px 16px}
          form{display:flex;gap:8px;margin-bottom:18px}
          input[type=text]{flex:1;padding:11px 13px;border:1px solid var(--line);
               border-radius:8px;font-size:15px;background:var(--card)}
          input[type=text]:focus{outline:2px solid var(--brand);border-color:var(--brand)}
          button{padding:11px 18px;border:0;border-radius:8px;background:var(--brand);
               color:#fff;font-size:15px;cursor:pointer}
          button:hover{opacity:.92}
          .meta{color:var(--muted);font-size:13px;margin-bottom:12px}
          .item{background:var(--card);border:1px solid var(--line);border-radius:10px;
               padding:14px 16px;margin-bottom:12px}
          .item a{color:#0b57d0;text-decoration:none;font-size:16px;font-weight:600}
          .item a:hover{text-decoration:underline}
          .url{color:#137333;font-size:12.5px;margin:3px 0;word-break:break-all}
          .snip{color:#3c4043;font-size:14px}
          .tags{margin-top:7px}
          .tag{display:inline-block;background:#eef1f5;color:#3c4043;border-radius:6px;
               padding:2px 8px;font-size:12px;margin-right:6px}
          .empty{color:var(--muted);text-align:center;padding:30px}
          .pager{display:flex;flex-wrap:wrap;gap:6px;justify-content:center;margin:18px 0}
          .pager button{background:var(--card);color:var(--text);border:1px solid var(--line);
               padding:7px 12px;font-size:14px}
          .pager button.cur{background:var(--brand);color:#fff;border-color:var(--brand)}
          .pager button:disabled{opacity:.45;cursor:default}
        </style></head><body>
        <header><h1>🔎 Tìm kiếm tài liệu HUST</h1></header>
        <div class="wrap">
          <form id="f"><input type="text" id="q" placeholder="Nhập từ khóa, ví dụ: tuyển sinh, học bổng..." autofocus>
          <button type="submit">Tìm</button></form>
          <div class="meta" id="meta"></div>
          <div id="results"></div>
          <div class="pager" id="pager"></div>
        </div>
        <script>
        const f=document.getElementById('f'),q=document.getElementById('q'),
              meta=document.getElementById('meta'),box=document.getElementById('results'),
              pager=document.getElementById('pager');
        let curTerm='';
        f.addEventListener('submit',e=>{e.preventDefault();const t=q.value.trim();if(t){curTerm=t;go(1);}});
        async function go(page){
          meta.textContent='Đang tìm...'; box.innerHTML=''; pager.innerHTML='';
          const r=await fetch('/api/search?q='+encodeURIComponent(curTerm)+'&page='+page);
          const d=await r.json();
          if(d.error){meta.textContent='Lỗi: '+d.error;return;}
          meta.textContent='Tìm thấy '+d.total_hits+' kết quả (chỉ mục '+(d.total_indexed||0)+
             ' tài liệu) cho "'+d.query+'" — trang '+d.page+'/'+d.total_pages;
          if(!d.results.length){box.innerHTML='<div class="empty">Không có kết quả phù hợp.</div>';return;}
          box.innerHTML=d.results.map(x=>`
            <div class="item">
              <a href="${x.url}" target="_blank" rel="noopener">${esc(x.title)||'(không tiêu đề)'}</a>
              <div class="url">${esc(x.url)}</div>
              <div class="snip">${esc(x.snippet)}</div>
              <div class="tags"><span class="tag">${x.doc_type}</span>
                <span class="tag">${esc(x.subdomain)}</span>
                <span class="tag">score ${x.score}</span></div>
            </div>`).join('');
          renderPager(d.page,d.total_pages);
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
        </script></body></html>
        """;
}
