"""Script minh họa PASS S2.1 (chạy LOCAL với model thật).

    cd embedding-service && pip install -r requirements.txt
    EMBED_FAKE=0 python scripts/check_semantic.py

In cosine của cặp gần nghĩa vs khác nghĩa; kỳ vọng gần > xa.
"""
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.config import Settings  # noqa: E402
from app.embedder import build_embedder  # noqa: E402


def cos(a, b):
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(x * x for x in b))
    return dot / (na * nb) if na and nb else 0.0


def main():
    emb = build_embedder(Settings())
    print(f"Model: {emb.name} (dims={emb.dims})")
    pairs = [
        ("học_phí ngành kỹ_thuật", "chi_phí đào_tạo kỹ_sư", "lịch thi_đấu bóng_đá"),
        ("tuyển_sinh đại_học năm 2024", "xét_tuyển vào trường đại_học", "thực_đơn nhà ăn sinh_viên"),
    ]
    all_ok = True
    for a, b, c in pairs:
        va, vb, vc = emb.encode([a, b, c])
        near, far = cos(va, vb), cos(va, vc)
        ok = near > far
        all_ok &= ok
        print(f"[{'OK ' if ok else 'FAIL'}] gần={near:.3f}  xa={far:.3f}  | '{a}'")
    sys.exit(0 if all_ok else 1)


if __name__ == "__main__":
    main()
