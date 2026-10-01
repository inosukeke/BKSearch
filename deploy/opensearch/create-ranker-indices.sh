#!/usr/bin/env bash
# S1.3 — Dựng 2 index song song cho VSM và LM (similarity khác BM25), rồi _reindex
# dữ liệu từ `documents` sang (copy phía server, KHÔNG tách từ lại, KHÔNG tải corpus về).
#
# Chạy SAU khi đã apply-mapping + migrate `documents`:
#   bash deploy/opensearch/create-ranker-indices.sh [OS_URL]
#
# Idempotent: index đã có thì bỏ qua tạo; _reindex giữ nguyên _id nên chạy lại không nhân đôi.
# Chịu lỗi: một mapping/_reindex hỏng KHÔNG chặn index còn lại (không dùng `set -e`).
set -uo pipefail

OS_URL="${1:-http://localhost:9200}"
SRC="${2:-documents}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
rc=0

# Tạo index nếu chưa có. Trả 0 nếu index tồn tại (đã có hoặc vừa tạo), 1 nếu tạo lỗi.
create_index () {
  local index="$1" mapping="$2" code body
  code=$(curl -s -o /dev/null -w "%{http_code}" "$OS_URL/$index")
  if [ "$code" = "200" ]; then
    echo "Index '$index' đã tồn tại — bỏ qua tạo."
    return 0
  fi
  echo "Tạo index '$index'..."
  body=$(curl -s -w $'\n%{http_code}' -X PUT "$OS_URL/$index" \
    -H 'Content-Type: application/json' --data-binary @- < "$mapping")
  code=$(printf '%s' "$body" | tail -n1)
  if [ "$code" = "200" ] || [ "$code" = "201" ]; then
    echo "  OK ($code)"
    return 0
  fi
  echo "  LỖI tạo '$index' (HTTP $code): $(printf '%s' "$body" | sed '$d')" >&2
  return 1
}

# _reindex từ SRC sang dest (chỉ khi dest tồn tại).
reindex () {
  local dest="$1" code
  code=$(curl -s -o /dev/null -w "%{http_code}" "$OS_URL/$dest")
  if [ "$code" != "200" ]; then
    echo "Bỏ qua reindex '$dest' (index không tồn tại)." >&2
    return 1
  fi
  echo "Reindex $SRC -> $dest ..."
  if curl -sf -X POST "$OS_URL/_reindex?refresh=true" \
       -H 'Content-Type: application/json' \
       -d "{\"source\":{\"index\":\"$SRC\"},\"dest\":{\"index\":\"$dest\"}}" >/dev/null; then
    echo "  OK"
    return 0
  fi
  echo "  LỖI reindex '$dest'." >&2
  return 1
}

# Tạo CẢ HAI index trước (độc lập), rồi mới reindex — lỗi một cái không chặn cái kia.
create_index "documents_vsm" "$DIR/documents_vsm.mapping.json" || rc=1
create_index "documents_lm"  "$DIR/documents_lm.mapping.json"  || rc=1
reindex "documents_vsm" || rc=1
reindex "documents_lm"  || rc=1

echo "Số doc:"
for idx in "$SRC" documents_vsm documents_lm; do
  cnt=$(curl -s "$OS_URL/$idx/_count" | sed -n 's/.*"count":\([0-9]*\).*/\1/p')
  echo "  $idx = ${cnt:-(không có)}"
done

[ "$rc" = 0 ] && echo "Xong (đủ 2 index)." || echo "Xong NHƯNG có lỗi — xem log phía trên." >&2
exit $rc
