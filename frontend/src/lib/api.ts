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
  let data: unknown = null;
  try {
    data = await res.json();
  } catch {
    data = null;
  }
  if (!res.ok) {
    const err = (data as { error?: string } | null)?.error;
    // Lỗi THẬT từ Query Service (trả JSON {error}) → hiện trạng thái lỗi cho người dùng.
    if (typeof err === "string") throw new ApiError(err, res.status);
    // Không phải lỗi API (proxy báo backend chưa chạy, body không phải JSON) → fallback mock.
    return delay(mockSearch(q, page, size));
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
