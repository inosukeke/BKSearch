"""Bi-encoder sinh embedding + bản GIẢ cho test.

Thiết kế: tách phần "nạp model nặng" (SentenceTransformer) khỏi phần HTTP để có thể test
schema/endpoint mà KHÔNG cần tải model (dùng FakeEmbedder deterministic).
"""
from __future__ import annotations

import hashlib
import math
import threading
from typing import List, Protocol


class Embedder(Protocol):
    name: str
    dims: int

    def encode(self, texts: List[str]) -> List[List[float]]:
        ...


def _l2_normalize(vec: List[float]) -> List[float]:
    norm = math.sqrt(sum(x * x for x in vec))
    if norm == 0.0:
        return vec
    return [x / norm for x in vec]


class FakeEmbedder:
    """Embedding giả, deterministic theo nội dung (hash) — KHÔNG cần model/mạng.

    Dùng cho CI/test và smoke-test khi không tải được model lớn. Vector được chuẩn hóa L2
    nên cosine nằm trong [-1,1]; cùng một text luôn cho cùng vector (ổn định để assert).
    """

    def __init__(self, dims: int = 768, normalize: bool = True):
        self.dims = dims
        self.name = f"fake-{dims}d"
        self._normalize = normalize

    def _vec(self, text: str) -> List[float]:
        # Sinh dims giá trị giả từ nhiều lần băm (ổn định giữa các tiến trình/Python version).
        out: List[float] = []
        counter = 0
        seed = (text or "").encode("utf-8")
        while len(out) < self.dims:
            h = hashlib.sha256(seed + counter.to_bytes(4, "big")).digest()
            for i in range(0, len(h), 4):
                if len(out) >= self.dims:
                    break
                n = int.from_bytes(h[i : i + 4], "big")
                out.append((n / 2**32) * 2.0 - 1.0)  # [-1, 1)
            counter += 1
        return _l2_normalize(out) if self._normalize else out

    def encode(self, texts: List[str]) -> List[List[float]]:
        return [self._vec(t) for t in texts]


class SentenceTransformerEmbedder:
    """Bi-encoder thật qua sentence-transformers (nạp LAZY lần gọi đầu).

    LƯU Ý (G3 / PhoBERT): model ``vietnamese-bi-encoder`` dựa trên PhoBERT nên input NÊN là
    văn bản ĐÃ TÁCH TỪ kiểu underscore (vd "đại_học"). Phía Java truyền ``*_seg`` hoặc đã
    segment trước khi gọi — service này embed nguyên văn bản nhận được.
    """

    def __init__(self, model_name: str, dims: int, batch: int, normalize: bool):
        self.model_name = model_name
        self.dims = dims
        self.name = model_name
        self._batch = batch
        self._normalize = normalize
        self._model = None  # lazy
        self._lock = threading.Lock()  # F3: chỉ một luồng nạp model (FastAPI threadpool)

    def _ensure(self):
        # Double-checked locking: tránh khóa trên đường nóng sau khi model đã nạp.
        if self._model is None:
            with self._lock:
                if self._model is None:
                    from sentence_transformers import SentenceTransformer  # import chậm → hoãn

                    model = SentenceTransformer(self.model_name, device="cpu")
                    real = model.get_sentence_embedding_dimension()
                    if real and real != self.dims:
                        # Cảnh báo lệch dims (sẽ gãy ingest vì mapping cố định 768).
                        raise RuntimeError(
                            f"Model '{self.model_name}' sinh vector {real} dims nhưng cấu hình/mapping "
                            f"là {self.dims}. Hãy sửa EMBED_DIMS hoặc mapping OpenSearch cho khớp."
                        )
                    self._model = model  # chỉ gán khi đã validate xong
        return self._model

    def encode(self, texts: List[str]) -> List[List[float]]:
        model = self._ensure()
        vecs = model.encode(
            texts,
            batch_size=self._batch,
            normalize_embeddings=self._normalize,
            convert_to_numpy=True,
            show_progress_bar=False,
        )
        return [v.tolist() for v in vecs]


def build_embedder(settings) -> Embedder:
    if settings.FAKE:
        return FakeEmbedder(dims=settings.EMBED_DIMS, normalize=settings.EMBED_NORMALIZE)
    return SentenceTransformerEmbedder(
        model_name=settings.EMBED_MODEL,
        dims=settings.EMBED_DIMS,
        batch=settings.EMBED_BATCH,
        normalize=settings.EMBED_NORMALIZE,
    )
