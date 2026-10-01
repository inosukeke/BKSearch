#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Tạo file POOL để gán nhãn qrels thủ công (S1.7/S1.8).

Pooling: với mỗi truy vấn trong queries.tsv, gộp top-k kết quả của CẢ 3 ranker
(bm25/vsm/lm) qua Query Service, loại trùng theo URL → xuất bảng để người gán nhãn
điền cột `rel` (0=không phù hợp, 1=phù hợp, 2=rất phù hợp).

Yêu cầu: serve-api đang chạy (java -jar hust-search.jar serve-api 7070) + đã có
documents + documents_vsm + documents_lm.

Dùng:
  PYTHONUTF8=1 python eval/build_pool.py [API_URL] [K]
  (mặc định API_URL=http://localhost:7070, K=10)

Xuất: eval/qrels/pool-to-label.tsv  (cột: qid, rel, url, title — điền `rel` rồi
chạy eval/pool_to_qrels.py để sinh qrels.txt).
"""
import sys, os, json, urllib.parse, urllib.request

API = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:7070"
K   = int(sys.argv[2]) if len(sys.argv) > 2 else 10
HERE = os.path.dirname(os.path.abspath(__file__))
QUERIES = os.path.join(HERE, "queries", "queries.tsv")
OUT = os.path.join(HERE, "qrels", "pool-to-label.tsv")
RANKERS = ["bm25", "vsm", "lm"]


def search(query, ranker, k):
    params = urllib.parse.urlencode({"q": query, "ranker": ranker, "size": k})
    with urllib.request.urlopen(f"{API}/api/search?{params}", timeout=30) as r:
        return json.load(r).get("results", [])


def load_queries():
    out = []
    with open(QUERIES, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            qid, _, q = line.partition("\t")
            if qid and q:
                out.append((qid.strip(), q.strip()))
    return out


def main():
    queries = load_queries()
    rows = []
    for qid, q in queries:
        seen = {}
        for ranker in RANKERS:
            try:
                for hit in search(q, ranker, K):
                    url = hit.get("url")
                    if url and url not in seen:
                        seen[url] = hit.get("title", "")
            except Exception as e:
                print(f"[WARN] {qid}/{ranker}: {e}", file=sys.stderr)
        rows.append((qid, q, seen))
        print(f"{qid}: {len(seen)} ứng viên (pool từ {len(RANKERS)} ranker)")

    with open(OUT, "w", encoding="utf-8") as f:
        f.write("# POOL để gán nhãn qrels — ĐIỀN cột `rel`: 0=không phù hợp, 1=phù hợp, 2=rất phù hợp.\n")
        f.write("# Cột: qid <TAB> rel <TAB> url <TAB> title. Dòng '# >>> <qid>: <truy vấn>' chỉ để tham khảo.\n")
        f.write("# Gán xong chạy: PYTHONUTF8=1 python eval/pool_to_qrels.py  → sinh eval/qrels/qrels.txt\n")
        total = 0
        for qid, q, seen in rows:
            f.write(f"\n# >>> {qid}: {q}\n")
            for url, title in seen.items():
                f.write(f"{qid}\t\t{url}\t{title}\n")  # cột rel để trống
                total += 1
    print(f"\nĐã ghi {OUT} ({total} cặp (truy vấn, tài liệu) cần gán nhãn).")


if __name__ == "__main__":
    main()
