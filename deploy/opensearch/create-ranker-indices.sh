#!/usr/bin/env bash
# S1.3 — Dựng 2 index song song cho VSM và LM (similarity khác BM25), rồi _reindex
# dữ liệu từ `documents` sang (copy phía server, KHÔNG tách từ lại, KHÔNG tải corpus về).
#
# Chạy SAU khi đã apply-mapping + migrate `documents`:
#   bash deploy/opensearch/create-ranker-indices.sh [OS_URL]
#
# Idempotent: index đã có thì bỏ qua tạo; _reindex giữ nguyên _id nên chạy lại không nhân đôi.
set -euo pipefail

OS_URL="${1:-http://localhost:9200}"
SRC="${2:-documents}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

create_index () {
  local index="$1" mapping="$2"
  local code
  code=$(curl -s -o /dev/null -w "%{http_code}" "$OS_URL/$index")
  if [ "$code" = "200" ]; then
    echo "Index '$index' đã tồn tại — bỏ qua tạo."
  else
    echo "Tạo index '$index'..."
    curl -sf -X PUT "$OS_URL/$index" \
      -H 'Content-Type: application/json' \
      --data-binary @- < "$mapping"
    echo ""
  fi
}

reindex () {
  local dest="$1"
  echo "Reindex $SRC -> $dest ..."
  curl -sf -X POST "$OS_URL/_reindex?refresh=true" \
    -H 'Content-Type: application/json' \
    -d "{\"source\":{\"index\":\"$SRC\"},\"dest\":{\"index\":\"$dest\"}}"
  echo ""
}

create_index "documents_vsm" "$DIR/documents_vsm.mapping.json"
create_index "documents_lm"  "$DIR/documents_lm.mapping.json"
reindex "documents_vsm"
reindex "documents_lm"

echo "Xong. Số doc:"
for idx in "$SRC" documents_vsm documents_lm; do
  cnt=$(curl -s "$OS_URL/$idx/_count" | sed -n 's/.*"count":\([0-9]*\).*/\1/p')
  echo "  $idx = ${cnt:-?}"
done
