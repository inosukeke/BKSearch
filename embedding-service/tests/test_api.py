"""Test endpoint ở chế độ GIẢ (không cần model): schema, dims, batch, rerank ordering, cache."""
import math

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_healthz():
    r = client.get("/healthz")
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "ok"
    assert body["config"]["fake"] is True
    assert body["config"]["embed_dims"] == 768


def test_embed_shape_and_dims():
    r = client.post("/embed", json={"texts": ["đại_học bách_khoa", "tuyển_sinh thạc_sĩ"]})
    assert r.status_code == 200
    body = r.json()
    assert body["count"] == 2
    assert body["dims"] == 768
    assert len(body["vectors"]) == 2
    assert all(len(v) == 768 for v in body["vectors"])


def test_embed_deterministic_and_normalized():
    payload = {"texts": ["xin_chào thế_giới"]}
    v1 = client.post("/embed", json=payload).json()["vectors"][0]
    v2 = client.post("/embed", json=payload).json()["vectors"][0]
    assert v1 == v2  # deterministic
    norm = math.sqrt(sum(x * x for x in v1))
    assert abs(norm - 1.0) < 1e-6  # đã chuẩn hóa L2


def test_embed_empty():
    r = client.post("/embed", json={"texts": []})
    assert r.status_code == 200
    assert r.json()["count"] == 0


def test_rerank_orders_by_overlap():
    # Fake reranker chấm theo chồng lấp token → doc chứa query được xếp trên.
    r = client.post(
        "/rerank",
        json={
            "query": "học phí kỹ sư",
            "documents": [
                "thông báo nghỉ lễ quốc khánh",       # 0: không liên quan
                "học phí kỹ sư chương trình chuẩn",   # 1: liên quan
                "lịch thi cuối kỳ",                   # 2: không liên quan
            ],
        },
    )
    assert r.status_code == 200
    results = r.json()["results"]
    assert results[0]["index"] == 1  # doc liên quan nhất đứng đầu
    # điểm giảm dần
    scores = [x["score"] for x in results]
    assert scores == sorted(scores, reverse=True)


def test_rerank_top_k():
    r = client.post(
        "/rerank",
        json={"query": "a b", "documents": ["a b c", "a", "x", "b"], "top_k": 2},
    )
    assert r.status_code == 200
    assert len(r.json()["results"]) == 2
