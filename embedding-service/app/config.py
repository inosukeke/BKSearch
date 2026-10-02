"""Cấu hình dịch vụ qua biến môi trường (12-factor).

Mọi tham số (model, dims, batch, cache, chế độ giả) đều lấy từ env để chạy được cả trên
CPU local lẫn trong Docker mà không sửa code.
"""
from __future__ import annotations

import os


def _flag(name: str, default: bool = False) -> bool:
    v = os.getenv(name)
    if v is None:
        return default
    return v.strip().lower() in {"1", "true", "yes", "on"}


def _int(name: str, default: int) -> int:
    try:
        return int(os.getenv(name, str(default)))
    except (TypeError, ValueError):
        return default


class Settings:
    """Ảnh chụp cấu hình đọc một lần lúc khởi động."""

    # Model bi-encoder tiếng Việt (PhoBERT-based → input NÊN là văn bản đã tách từ underscore).
    EMBED_MODEL: str = os.getenv("EMBED_MODEL", "bkai-foundation-models/vietnamese-bi-encoder")
    # Dims phải KHỚP mapping OpenSearch (documents.embedding = knn_vector dim 768).
    EMBED_DIMS: int = _int("EMBED_DIMS", 768)
    EMBED_BATCH: int = _int("EMBED_BATCH", 32)
    # Chuẩn hóa L2 vector → cosine = dot product; khớp space_type=cosinesimil của HNSW.
    EMBED_NORMALIZE: bool = _flag("EMBED_NORMALIZE", True)

    # Cross-encoder rerank đa ngữ (chấm trên văn bản THÔ, không cần tách từ).
    RERANK_MODEL: str = os.getenv("RERANK_MODEL", "cross-encoder/mmarco-mMiniLMv2-L12-H384-v1")
    RERANK_BATCH: int = _int("RERANK_BATCH", 16)
    RERANK_CACHE_SIZE: int = _int("RERANK_CACHE_SIZE", 4096)
    # Số token tối đa khi cross-encoder encode cặp (query, doc). Tài liệu rất dài không được
    # cắt → p95 tăng vọt (đo local: top_k=50 không truncate → p95 ~10s). 256 giữ p95 trong ngưỡng.
    RERANK_MAX_LENGTH: int = _int("RERANK_MAX_LENGTH", 256)

    # Chế độ GIẢ (deterministic, không tải model) — dùng cho CI/test & smoke không có GPU/mạng.
    FAKE: bool = _flag("EMBED_FAKE", False)

    # Số ký tự tối đa mỗi input trước khi cắt (bảo vệ bộ nhớ / token limit).
    MAX_INPUT_CHARS: int = _int("MAX_INPUT_CHARS", 4000)

    def summary(self) -> dict:
        return {
            "embed_model": self.EMBED_MODEL,
            "embed_dims": self.EMBED_DIMS,
            "embed_batch": self.EMBED_BATCH,
            "embed_normalize": self.EMBED_NORMALIZE,
            "rerank_model": self.RERANK_MODEL,
            "rerank_cache_size": self.RERANK_CACHE_SIZE,
            "rerank_max_length": self.RERANK_MAX_LENGTH,
            "fake": self.FAKE,
        }


settings = Settings()
