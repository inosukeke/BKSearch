#!/usr/bin/env bash
# S1.7 — Chạy bộ đánh giá 3 ranker (BM25/VSM/LM) trên test collection.
# Yêu cầu: OpenSearch sống + đã index `documents` (+ documents_vsm, documents_lm nếu so cả 3).
#
# Dùng:  bash eval/run-eval.sh [OS_URL] [INDEX] [k]
set -euo pipefail

OS_URL="${1:-http://localhost:9200}"
INDEX="${2:-documents}"
K="${3:-10}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

JAR="$ROOT/java-lucene/target/hust-search.jar"
if [ ! -f "$JAR" ]; then
  echo "Chưa có jar, build trước: (cd java-lucene && mvn -q -DskipTests package)"
  (cd "$ROOT/java-lucene" && mvn -q -DskipTests package)
fi

# eval-run đọc eval/queries/queries.tsv + eval/qrels/qrels.txt (đường dẫn tương đối từ java-lucene)
(cd "$ROOT/java-lucene" && java -jar target/hust-search.jar eval-run "$OS_URL" "$INDEX" "$K")
