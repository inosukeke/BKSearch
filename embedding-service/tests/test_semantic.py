"""PASS S2.1 (cần model thật) — câu gần nghĩa có cosine CAO hơn câu khác nghĩa.

Bỏ qua trên CI/cloud (không có model). Chạy ở LOCAL:
    cd embedding-service
    pip install -r requirements.txt
    EMBED_FAKE=0 pytest tests/test_semantic.py -m needs_model -v
"""
import math
import os

import pytest

pytestmark = pytest.mark.needs_model


def _cos(a, b):
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(x * x for x in b))
    return dot / (na * nb) if na and nb else 0.0


@pytest.mark.skipif(os.getenv("EMBED_FAKE", "0") in {"1", "true"},
                    reason="Chạy với model thật: đặt EMBED_FAKE=0")
def test_semantic_similarity_real_model():
    from app.config import Settings
    from app.embedder import build_embedder

    emb = build_embedder(Settings())
    # Văn bản đã tách từ underscore (khớp yêu cầu PhoBERT / field *_seg).
    a = "học_phí ngành kỹ_thuật"
    b = "chi_phí đào_tạo kỹ_sư"      # gần nghĩa với a
    c = "lịch thi_đấu bóng_đá"       # khác nghĩa
    va, vb, vc = emb.encode([a, b, c])
    sim_near = _cos(va, vb)
    sim_far = _cos(va, vc)
    assert sim_near > sim_far, f"cosine gần={sim_near:.3f} phải > xa={sim_far:.3f}"
