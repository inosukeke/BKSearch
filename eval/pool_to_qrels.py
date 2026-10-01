#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Chuyển file pool ĐÃ GÁN NHÃN (eval/qrels/pool-to-label.tsv) → qrels TREC
(eval/qrels/qrels.txt): định dạng `<qid> 0 <url> <rel>`.

Chỉ lấy dòng có `rel` ∈ {0,1,2}. Dòng chưa điền rel bị bỏ qua (kèm đếm cảnh báo).

Dùng:  PYTHONUTF8=1 python eval/pool_to_qrels.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "qrels", "pool-to-label.tsv")
OUT = os.path.join(HERE, "qrels", "qrels.txt")


def main():
    if not os.path.exists(SRC):
        sys.exit(f"Không thấy {SRC}. Chạy build_pool.py trước.")
    out_lines, labeled, blank, bad = [], 0, 0, 0
    with open(SRC, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) < 3:
                continue
            qid, rel = parts[0].strip(), parts[1].strip()
            url = parts[2].strip()
            if rel == "":
                blank += 1
                continue
            if rel not in ("0", "1", "2"):
                print(f"[WARN] rel không hợp lệ '{rel}' ở {qid} {url}", file=sys.stderr)
                bad += 1
                continue
            out_lines.append(f"{qid} 0 {url} {rel}")
            labeled += 1

    with open(OUT, "w", encoding="utf-8") as f:
        f.write("# qrels TREC: <qid> 0 <docid=url> <rel>  (sinh từ pool_to_qrels.py)\n")
        f.write("\n".join(out_lines) + "\n")
    print(f"Đã ghi {OUT}: {labeled} nhãn ({blank} dòng chưa gán bị bỏ qua, {bad} rel sai).")


if __name__ == "__main__":
    main()
