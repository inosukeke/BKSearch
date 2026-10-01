#!/usr/bin/env bash
# Tạo index `documents` trên OpenSearch từ documents.mapping.json (idempotent).
# Dùng: bash deploy/opensearch/apply-mapping.sh [OS_URL] [INDEX]
set -euo pipefail

OS_URL="${1:-http://localhost:9200}"
INDEX="${2:-documents}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAPPING="$DIR/documents.mapping.json"

code=$(curl -s -o /dev/null -w "%{http_code}" "$OS_URL/$INDEX")
if [ "$code" = "200" ]; then
  echo "Index '$INDEX' đã tồn tại — bỏ qua (idempotent)."
  exit 0
fi

echo "Tạo index '$INDEX'..."
# Đưa file qua stdin để tránh lỗi curl đọc path Unicode/có dấu cách trên Windows.
curl -sf -X PUT "$OS_URL/$INDEX" \
  -H 'Content-Type: application/json' \
  --data-binary @- < "$MAPPING"
echo ""
echo "Xong."
