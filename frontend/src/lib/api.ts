import type { Filters, Ranker, SearchResponse } from "@/types";
import { mockSearch, mockSuggest } from "./mock";

// Base API: mặc định "" → dùng Vite dev proxy (/api → :7070). Có thể đặt VITE_API_BASE.
const API_BASE = import.meta.env.VITE_API_BASE ?? "";
const USE_MOCK = import.meta.env.VITE_USE_MOCK === "1";

export interface SearchParams {
  q: string;
  page?: number;
  size?: number;
  ranker?: Ranker;
  rerank?: boolean;
  filters?: Filters;
}

export class ApiError extends Error {
  status: number;
  constructor(message: string, status: number) {
    super(message);
    this.status = status;
  }
}

export async function search(p: SearchParams): Promise<SearchResponse> {
  const { q, page = 1, size = 10, ranker = "bm25", rerank = false, filters = {} } = p;
  if (USE_MOCK) return delay(mockSearch(q, page, size));

  const qs = new URLSearchParams({
    q,
    page: String(page),
    size: String(size),
    ranker,
    rerank: rerank ? "1" : "0",
  });
  for (const [k, v] of Object.entries(filters)) if (v) qs.set(k, v);

  let res: Response;
  try {
    res = await fetch(`${API_BASE}/api/search?${qs.toString()}`);
  } catch {
    // Backend không chạy → rơi về mock để demo vẫn mượt.
    return delay(mockSearch(q, page, size));
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new ApiError((data as { error?: string }).error || `Lỗi máy chủ (${res.status})`, res.status);
  }
  return data as SearchResponse;
}

export async function suggest(q: string): Promise<string | null> {
  if (USE_MOCK) return mockSuggest(q);
  try {
    const res = await fetch(`${API_BASE}/api/suggest?q=${encodeURIComponent(q)}`);
    if (!res.ok) return null;
    const data = (await res.json()) as { suggestion?: string | null };
    return data.suggestion ?? null;
  } catch {
    return mockSuggest(q);
  }
}

function delay<T>(v: T, ms = 450): Promise<T> {
  return new Promise((r) => setTimeout(() => r(v), ms));
}
