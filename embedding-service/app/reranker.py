"""Cross-encoder rerank + bản GIẢ + cache (S2.5).

Cross-encoder chấm điểm (query, document) theo cặp → chất lượng cao hơn bi-encoder nhưng
đắt → chỉ áp cho top-K (mặc định ≤30, xem RERANK_TOP_K phía Java). Có cache LRU theo
(query, document) để tránh chấm lại. Cross-encoder truncate input ở RERANK_MAX_LENGTH token
(mặc định 256) để p95 không bị tài liệu rất dài kéo vọt lên.

An toàn luồng (F3): FastAPI chạy handler sync trong threadpool → nhiều luồng có thể gọi cùng
lúc. Nạp model lazy và cache LRU đều được bọc threading.Lock.
"""
from __future__ import annotations

import threading
from collections import OrderedDict
from typing import List, Protocol, Tuple


class Reranker(Protocol):
    name: str

    def score(self, query: str, documents: List[str]) -> List[float]:
        ...


class _LruCache:
    """Cache LRU nhỏ, khóa theo (query, document)."""

    def __init__(self, capacity: int):
        self.capacity = max(0, capacity)
        self._d: "OrderedDict[Tuple[str, str], float]" = OrderedDict()
        self.hits = 0
        self.misses = 0
        self._lock = threading.Lock()  # F3: nhiều luồng threadpool có thể đụng cache

    def get(self, key):
        with self._lock:
            if key in self._d:
                self._d.move_to_end(key)
                self.hits += 1
                return self._d[key]
            self.misses += 1
            return None

    def put(self, key, value):
        if self.capacity == 0:
            return
        with self._lock:
            self._d[key] = value
            self._d.move_to_end(key)
            while len(self._d) > self.capacity:
                self._d.popitem(last=False)


class _CachingReranker:
    """Bọc một reranker lõi bằng cache (dùng chung cho fake & thật)."""

    def __init__(self, core, cache_capacity: int):
        self._core = core
        self.name = core.name
        self._cache = _LruCache(cache_capacity)

    def score(self, query: str, documents: List[str]) -> List[float]:
        results: List[float] = [None] * len(documents)  # type: ignore
        missing_idx: List[int] = []
        missing_docs: List[str] = []
        for i, doc in enumerate(documents):
            cached = self._cache.get((query, doc))
            if cached is None:
                missing_idx.append(i)
                missing_docs.append(doc)
            else:
                results[i] = cached
        if missing_docs:
            fresh = self._core.score(query, missing_docs)
            for idx, doc, sc in zip(missing_idx, missing_docs, fresh):
                results[idx] = sc
                self._cache.put((query, doc), sc)
        return results

    def cache_stats(self) -> dict:
        return {
            "size": len(self._cache._d),
            "capacity": self._cache.capacity,
            "hits": self._cache.hits,
            "misses": self._cache.misses,
        }


class FakeReranker:
    """Điểm rerank giả = độ chồng lấp token (Jaccard-ish) giữa query và doc.

    Deterministic, không cần model: đủ để test rằng doc liên quan hơn nhận điểm cao hơn.
    """

    name = "fake-overlap"

    def score(self, query: str, documents: List[str]) -> List[float]:
        q = set((query or "").lower().split())
        out = []
        for doc in documents:
            d = set((doc or "").lower().split())
            if not q or not d:
                out.append(0.0)
                continue
            inter = len(q & d)
            union = len(q | d)
            out.append(inter / union if union else 0.0)
        return out


class CrossEncoderReranker:
    """Cross-encoder thật qua sentence-transformers (nạp LAZY).

    Dùng văn bản THÔ (title + content), KHÔNG cần tách từ underscore — model đa ngữ xử lý
    chuỗi tự nhiên tốt hơn.
    """

    def __init__(self, model_name: str, batch: int, max_length: int = 256):
        self.model_name = model_name
        self.name = model_name
        self._batch = batch
        self._max_length = max_length
        self._model = None
        self._lock = threading.Lock()  # F3: chỉ một luồng nạp model

    def _ensure(self):
        # Double-checked locking: tránh khóa trên đường nóng sau khi model đã nạp.
        if self._model is None:
            with self._lock:
                if self._model is None:
                    from sentence_transformers import CrossEncoder

                    # max_length truncate cặp (query, doc) → chặn tài liệu dài kéo p95 (F2).
                    self._model = CrossEncoder(
                        self.model_name, device="cpu", max_length=self._max_length
                    )
        return self._model

    def score(self, query: str, documents: List[str]) -> List[float]:
        model = self._ensure()
        pairs = [[query, d] for d in documents]
        scores = model.predict(pairs, batch_size=self._batch, show_progress_bar=False)
        return [float(s) for s in scores]


def build_reranker(settings) -> _CachingReranker:
    core = FakeReranker() if settings.FAKE else CrossEncoderReranker(
        model_name=settings.RERANK_MODEL,
        batch=settings.RERANK_BATCH,
        max_length=settings.RERANK_MAX_LENGTH,
    )
    return _CachingReranker(core, settings.RERANK_CACHE_SIZE)
