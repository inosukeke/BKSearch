import { useCallback, useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import { Moon, Sun, Loader2, Lightbulb } from "lucide-react";
import { Button } from "@/components/ui/button";
import { SearchBar } from "@/components/SearchBar";
import { ResultCard } from "@/components/ResultCard";
import { Facets } from "@/components/Facets";
import { EmptyState, ErrorState, LoadingState } from "@/components/StateViews";
import { useTheme } from "@/hooks/useTheme";
import { search, ApiError } from "@/lib/api";
import type { Filters, Ranker, SearchHit, SearchResponse } from "@/types";

const PAGE_SIZE = 10;
const EXAMPLES = ["tuyển sinh", "học bổng", "lịch thi", "chương trình đào tạo", "nghiên cứu khoa học"];

type Status = "idle" | "loading" | "loadingmore" | "ready" | "error";

export default function App() {
  const { theme, toggle } = useTheme();
  const [input, setInput] = useState("");
  const [ranker, setRanker] = useState<Ranker>("bm25");
  const [rerank, setRerank] = useState(false);
  const [filters, setFilters] = useState<Filters>({});
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState<Status>("idle");
  const [resp, setResp] = useState<SearchResponse | null>(null);
  const [hits, setHits] = useState<SearchHit[]>([]);
  const [page, setPage] = useState(1);
  const [error, setError] = useState("");

  const run = useCallback(
    async (q: string, p: number, f: Filters, r: Ranker, rr: boolean, append: boolean) => {
      setStatus(append ? "loadingmore" : "loading");
      setError("");
      try {
        const res = await search({ q, page: p, size: PAGE_SIZE, ranker: r, rerank: rr, filters: f });
        setResp(res);
        setPage(res.page ?? p);
        setHits((prev) => (append ? [...prev, ...(res.results ?? [])] : res.results ?? []));
        setStatus("ready");
      } catch (e) {
        const msg =
          e instanceof ApiError
            ? e.status === 400
              ? "Cú pháp truy vấn không hợp lệ. Kiểm tra lại dấu ngoặc/AND/OR/NOT."
              : e.message
            : "Không kết nối được máy chủ tìm kiếm.";
        setError(msg);
        setStatus("error");
      }
    },
    []
  );

  const submit = useCallback(
    (text?: string) => {
      const q = (text ?? input).trim();
      if (!q) return;
      setInput(q);
      setQuery(q);
      setFilters({});
      run(q, 1, {}, ranker, rerank, false);
    },
    [input, ranker, rerank, run]
  );

  const onRanker = (r: Ranker) => {
    setRanker(r);
    if (query) run(query, 1, filters, r, rerank, false);
  };
  const onRerank = (b: boolean) => {
    setRerank(b);
    if (query) run(query, 1, filters, ranker, b, false);
  };
  const toggleFilter = (field: keyof Filters, value: string) => {
    const next: Filters = { ...filters };
    if (next[field] === value) delete next[field];
    else next[field] = value;
    setFilters(next);
    if (query) run(query, 1, next, ranker, rerank, false);
  };
  const loadMore = () => query && run(query, page + 1, filters, ranker, rerank, true);
  const retry = () => query && run(query, 1, filters, ranker, rerank, false);

  const hero = status === "idle";
  const canLoadMore = resp && resp.page < (resp.total_pages ?? 1);
  const hasFacets = Object.keys(resp?.facets ?? {}).some((f) => resp!.facets![f]?.length);
  const showFacets =
    (status === "ready" || status === "loadingmore") && hits.length > 0 && (hasFacets || Object.values(filters).some(Boolean));

  const themeBtn = (
    <Button variant="ghost" size="icon" aria-label="Đổi giao diện sáng/tối" onClick={toggle}>
      {theme === "dark" ? <Sun /> : <Moon />}
    </Button>
  );

  // ---- Hero (chưa tìm) --------------------------------------------------------
  if (hero) {
    return (
      <div className="min-h-dvh">
        <div className="absolute right-4 top-4">{themeBtn}</div>
        <main className="mx-auto flex min-h-dvh max-w-[720px] flex-col items-center justify-center px-4 pb-24">
          <motion.div
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.3 }}
            className="mb-7 text-center"
          >
            <div className="mb-3 inline-flex items-center gap-2 text-3xl font-bold tracking-tight sm:text-4xl">
              <span className="flex size-9 items-center justify-center rounded-lg bg-primary text-primary-foreground sm:size-10">
                🔎
              </span>
              BKSearch
            </div>
            <p className="text-muted-foreground">Tìm kiếm tài liệu HUST — demo Information Retrieval</p>
          </motion.div>

          <motion.div
            initial={{ opacity: 0, y: 10 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.3, delay: 0.05 }}
            className="w-full"
          >
            <SearchBar
              value={input}
              onChange={setInput}
              onSubmit={() => submit()}
              ranker={ranker}
              onRankerChange={setRanker}
              rerank={rerank}
              onRerankChange={setRerank}
              size="hero"
              autoFocus
            />
            <div className="mt-6 flex flex-wrap justify-center gap-2">
              {EXAMPLES.map((ex) => (
                <button
                  key={ex}
                  onClick={() => submit(ex)}
                  className="rounded-full border border-border bg-card px-3.5 py-1.5 text-sm text-foreground/80 transition-colors hover:border-primary/40 hover:text-primary"
                >
                  {ex}
                </button>
              ))}
            </div>
          </motion.div>
        </main>
      </div>
    );
  }

  // ---- Kết quả ----------------------------------------------------------------
  return (
    <div className="min-h-dvh">
      <header className="sticky top-0 z-10 border-b bg-background/85 backdrop-blur">
        <div className="mx-auto flex max-w-[960px] items-center gap-3 px-4 py-3">
          <button
            onClick={() => {
              setStatus("idle");
              setQuery("");
            }}
            className="flex shrink-0 items-center gap-1.5 text-lg font-bold tracking-tight"
            aria-label="Về trang chủ"
          >
            <span className="flex size-7 items-center justify-center rounded-md bg-primary text-sm text-primary-foreground">
              🔎
            </span>
            <span className="hidden sm:inline">BKSearch</span>
          </button>
          <div className="min-w-0 flex-1">
            <SearchBar
              value={input}
              onChange={setInput}
              onSubmit={() => submit()}
              ranker={ranker}
              onRankerChange={onRanker}
              rerank={rerank}
              onRerankChange={onRerank}
            />
          </div>
          {themeBtn}
        </div>
      </header>

      <main className="mx-auto max-w-[960px] px-4 py-5">
        <div className="flex flex-col gap-6 md:flex-row md:items-start">
          {/* Facet — mobile: disclosure gấp gọn; desktop: sidebar dính. Chỉ hiện khi có facet. */}
          {showFacets && (
            <>
              <details className="rounded-lg border bg-card px-4 py-3 md:hidden">
                <summary className="cursor-pointer select-none text-sm font-medium">Bộ lọc</summary>
                <div className="pt-3">
                  <Facets facets={resp?.facets} filters={filters} onToggle={toggleFilter} />
                </div>
              </details>
              <aside className="hidden md:sticky md:top-[132px] md:block md:w-56 md:shrink-0">
                <Facets facets={resp?.facets} filters={filters} onToggle={toggleFilter} />
              </aside>
            </>
          )}

          {/* Kết quả */}
          <div className="min-w-0 flex-1">
            {status === "loading" ? (
              <LoadingState />
            ) : status === "error" ? (
              <ErrorState message={error} onRetry={retry} />
            ) : hits.length === 0 ? (
              <EmptyState query={query} suggestion={resp?.suggestion} onPick={(s) => submit(s)} />
            ) : (
              <>
                <div className="mb-3 flex flex-wrap items-center gap-x-2 gap-y-1 text-sm text-muted-foreground">
                  <span>
                    <strong className="font-semibold text-foreground">{resp?.total?.toLocaleString("vi-VN")}</strong>{" "}
                    kết quả
                  </span>
                  <span aria-hidden>·</span>
                  <span>{resp?.took_ms} ms</span>
                  <span aria-hidden>·</span>
                  <span className="uppercase">{resp?.ranker}</span>
                </div>

                {resp?.suggestion && (
                  <div className="mb-4 flex items-center gap-2 rounded-lg border border-highlight/40 bg-highlight/10 px-3.5 py-2.5 text-sm">
                    <Lightbulb className="size-4 shrink-0 text-highlight-foreground" />
                    <span>
                      Có phải bạn muốn tìm{" "}
                      <button
                        onClick={() => submit(resp.suggestion!)}
                        className="font-semibold text-primary hover:underline"
                      >
                        {resp.suggestion}
                      </button>
                      ?
                    </span>
                  </div>
                )}

                <div className="space-y-3">
                  <AnimatePresence mode="popLayout">
                    {hits.map((hit, i) => (
                      <ResultCard key={`${hit.url}-${i}`} hit={hit} index={i} />
                    ))}
                  </AnimatePresence>
                </div>

                {canLoadMore && (
                  <div className="mt-6 flex justify-center">
                    <Button variant="outline" onClick={loadMore} disabled={status === "loadingmore"}>
                      {status === "loadingmore" && <Loader2 className="animate-spin" />}
                      Tải thêm kết quả
                    </Button>
                  </div>
                )}
              </>
            )}
          </div>
        </div>
      </main>
    </div>
  );
}
