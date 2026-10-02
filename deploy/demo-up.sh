#!/usr/bin/env bash
# S4.4 — Orchestrate nạp dữ liệu + chỉ mục + tín hiệu Phase 3 cho bản DEMO.
#
# Tiền đề: hạ tầng đã chạy (cd deploy && docker compose up -d) và SQLite corpus đã có
# (java-lucene/data/hust.db — crawl hoặc crawl-mt trước đó).
#
# Dùng:
#   bash deploy/demo-up.sh                      # OpenSearch ở localhost:9200
#   OS_URL=http://localhost:9200 EMBED=1 bash deploy/demo-up.sh   # kèm vector (cần Embedding Service)
#
# Idempotent: chạy lại không nhân đôi (upsert theo url; _id = SHA-256(url)).
set -uo pipefail

OS_URL="${OS_URL:-http://localhost:9200}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$ROOT/java-lucene/target/hust-search.jar"
EMBED_FLAG=""
[ "${EMBED:-0}" = "1" ] && EMBED_FLAG="--embed"

step () { echo; echo "==== $* ===="; }

step "1/6 Build fat-jar"
( cd "$ROOT/java-lucene" && mvn -q -DskipTests package ) || { echo "Build lỗi" >&2; exit 1; }

step "2/6 Apply mapping + migrate SQLite → OpenSearch ${EMBED_FLAG}"
bash "$ROOT/deploy/opensearch/apply-mapping.sh" "$OS_URL"
java -jar "$JAR" migrate "$OS_URL" 500 --no-pg $EMBED_FLAG

step "3/6 Tạo 3 index ranker (BM25/VSM/LM)"
bash "$ROOT/deploy/opensearch/create-ranker-indices.sh" "$OS_URL"

step "4/6 PageRank + anchor text (S3.1)"
java -jar "$JAR" pagerank "$OS_URL" || echo "(pagerank bỏ qua — cần bảng links; re-crawl bằng crawl-mt)"

step "5/6 Near-duplicate dup_group (S3.2)"
java -jar "$JAR" dedupe "$OS_URL"

step "6/6 Phân loại category + báo cáo P/R/F1 (S3.4)"
java -jar "$JAR" classify "$OS_URL"

echo
echo "Xong. Chạy Query Service:"
echo "  cd java-lucene && java -jar target/hust-search.jar serve-api 7070"
echo "Bật tín hiệu Phase 3 (ví dụ):"
echo "  PAGERANK_WEIGHT=2 DEDUP_COLLAPSE=1 QUERY_EXPAND_SYN=1 java -jar target/hust-search.jar serve-api 7070"
echo "Monitoring: cd deploy && docker compose --profile monitoring up -d  (Grafana :3000, Prometheus :9090)"
