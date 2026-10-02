// Ánh xạ hợp đồng JSON của Query Service (SearchResponse.java / SearchHit.java).

export type Ranker = "bm25" | "vsm" | "lm" | "vector" | "hybrid";

export interface SearchHit {
  url: string;
  title: string;
  snippet: string; // đã có <em>...</em> highlight
  score: number;
  score_type?: string; // bm25/vsm/lm/cosine/rrf/cross-encoder
  doc_type?: string;
  subdomain?: string;
  category?: string;
}

export interface FacetBucket {
  key: string;
  count: number;
}

export interface SearchResponse {
  query: string;
  ranker: string;
  segmented_query?: string;
  total: number;
  total_candidates?: number;
  total_matched?: number;
  page: number;
  page_size: number;
  total_pages: number;
  took_ms: number;
  suggestion?: string | null;
  results: SearchHit[];
  facets?: Record<string, FacetBucket[]>;
  applied_filters?: Record<string, string>;
}

export type Filters = Partial<Record<"category" | "doc_type" | "subdomain", string>>;

export const FACET_LABELS: Record<string, string> = {
  category: "Danh mục",
  doc_type: "Loại tài liệu",
  subdomain: "Tên miền",
};

export const RANKERS: { value: Ranker; label: string }[] = [
  { value: "bm25", label: "BM25" },
  { value: "vsm", label: "VSM (tf-idf)" },
  { value: "lm", label: "LM (Dirichlet)" },
  { value: "vector", label: "Vector (k-NN)" },
  { value: "hybrid", label: "Hybrid (RRF)" },
];
