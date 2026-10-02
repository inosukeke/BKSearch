"""BKSearch Embedding Service (FastAPI) — S2.1 (/embed) + S2.5 (/rerank).

Endpoints:
  GET  /healthz            → trạng thái + cấu hình (không tải model).
  POST /embed              → {"texts":[...]} → {"vectors":[[...768...]], "dims":768, ...}
  POST /rerank             → {"query":..., "documents":[...], "top_k":N} → điểm cross-encoder.

Model nạp LAZY (lần gọi đầu) để /healthz luôn nhẹ và container khởi động nhanh. Lỗi nạp/model
→ HTTP 503 (phía Java coi mọi lỗi service là 502 cho client).
"""
from __future__ import annotations

from typing import List, Optional

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from .config import settings
from .embedder import build_embedder
from .reranker import build_reranker

app = FastAPI(title="BKSearch Embedding Service", version="0.2.0")

# Xây một lần; model bên trong vẫn nạp lazy.
_embedder = build_embedder(settings)
_reranker = build_reranker(settings)


# ---- Hợp đồng JSON ----------------------------------------------------------

class EmbedRequest(BaseModel):
    texts: List[str] = Field(..., description="Danh sách văn bản (nên đã tách từ underscore).")


class EmbedResponse(BaseModel):
    vectors: List[List[float]]
    dims: int
    model: str
    count: int


class RerankRequest(BaseModel):
    query: str
    documents: List[str] = Field(..., description="Văn bản thô (title + content) của top-K.")
    top_k: Optional[int] = Field(None, description="Chỉ trả top_k kết quả điểm cao nhất.")


class RerankItem(BaseModel):
    index: int   # vị trí trong 'documents' đầu vào
    score: float


class RerankResponse(BaseModel):
    query: str
    model: str
    results: List[RerankItem]   # đã sắp xếp giảm dần theo score


# ---- Endpoints --------------------------------------------------------------

@app.get("/healthz")
def healthz():
    # F8: healthcheck CHỈ xác nhận tiến trình sống + đọc được cấu hình. Model nạp LAZY nên
    # "status: ok" KHÔNG đảm bảo model đã tải/encode được — lần /embed hay /rerank đầu tiên mới
    # kích hoạt tải (và có thể 503 nếu thiếu model/mạng/OOM). Dùng model_loaded để biết đã nạp chưa.
    return {
        "status": "ok",
        "service": "bksearch-embedding",
        "config": settings.summary(),
        "model_loaded": {
            "embedder": getattr(_embedder, "_model", None) is not None,
            "reranker": getattr(getattr(_reranker, "_core", None), "_model", None) is not None,
        },
    }


def _clip(text: str) -> str:
    if text is None:
        return ""
    if len(text) > settings.MAX_INPUT_CHARS:
        return text[: settings.MAX_INPUT_CHARS]
    return text


@app.post("/embed", response_model=EmbedResponse)
def embed(req: EmbedRequest):
    if not req.texts:
        return EmbedResponse(vectors=[], dims=_embedder.dims, model=_embedder.name, count=0)
    texts = [_clip(t) for t in req.texts]
    try:
        vectors = _embedder.encode(texts)
    except Exception as e:  # nạp model lỗi / OOM / ...
        raise HTTPException(status_code=503, detail=f"embed thất bại: {e}") from e
    return EmbedResponse(
        vectors=vectors, dims=_embedder.dims, model=_embedder.name, count=len(vectors)
    )


@app.post("/rerank", response_model=RerankResponse)
def rerank(req: RerankRequest):
    if not req.documents:
        return RerankResponse(query=req.query, model=_reranker.name, results=[])
    docs = [_clip(d) for d in req.documents]
    try:
        scores = _reranker.score(req.query, docs)
    except Exception as e:
        raise HTTPException(status_code=503, detail=f"rerank thất bại: {e}") from e
    ranked = sorted(
        (RerankItem(index=i, score=float(s)) for i, s in enumerate(scores)),
        key=lambda x: x.score,
        reverse=True,
    )
    if req.top_k is not None and req.top_k >= 0:
        ranked = ranked[: req.top_k]
    return RerankResponse(query=req.query, model=_reranker.name, results=ranked)
