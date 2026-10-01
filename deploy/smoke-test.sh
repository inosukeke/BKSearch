#!/usr/bin/env bash
# Smoke test nền Phase 0 (S0.6). Kiểm chứng: hạ tầng + di trú + tìm kiếm tiếng Việt.
# Dùng:  bash deploy/smoke-test.sh
# Thoát != 0 nếu bất kỳ kiểm tra nào hỏng.
set -uo pipefail

OS="${OS_URL:-http://localhost:9200}"
DASH="${DASH_URL:-http://localhost:5601}"
INDEX="${INDEX:-documents}"
EXPECT="${EXPECT_COUNT:-500}"
fail=0
pass() { echo "  [PASS] $1"; }
bad()  { echo "  [FAIL] $1"; fail=1; }

echo "== 1) OpenSearch cluster health =="
status=$(curl -s "$OS/_cluster/health" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
[ "$status" = "green" ] || [ "$status" = "yellow" ] && pass "cluster status=$status" || bad "cluster status=$status"

echo "== 2) Index '$INDEX' tồn tại =="
code=$(curl -s -o /dev/null -w "%{http_code}" "$OS/$INDEX")
[ "$code" = "200" ] && pass "index có ($code)" || bad "index thiếu ($code)"

echo "== 3) Số doc = $EXPECT =="
cnt=$(curl -s "$OS/$INDEX/_count" | grep -o '"count":[0-9]*' | cut -d: -f2)
[ "$cnt" = "$EXPECT" ] && pass "count=$cnt" || bad "count=$cnt (mong đợi $EXPECT)"

echo "== 4) BM25 tiếng Việt (q=content_seg:tuyển_sinh) trả kết quả =="
hits=$(curl -s "$OS/$INDEX/_search?q=content_seg:tuy%E1%BB%83n_sinh&size=0" | grep -o '"value":[0-9]*' | head -1 | cut -d: -f2)
[ "${hits:-0}" -gt 0 ] && pass "hits=$hits" || bad "không có hit cho 'tuyển_sinh'"

echo "== 5) Tách từ: 'đại_học' khớp tài liệu chứa 'đại học' =="
hits2=$(curl -s "$OS/$INDEX/_search?q=content_seg:%C4%91%E1%BA%A1i_h%E1%BB%8Dc&size=0" | grep -o '"value":[0-9]*' | head -1 | cut -d: -f2)
[ "${hits2:-0}" -gt 0 ] && pass "hits=$hits2" || bad "không có hit cho 'đại_học'"

echo "== 6) Dashboards phục vụ (http 200) =="
dcode=$(curl -s -o /dev/null -w "%{http_code}" "$DASH/api/status")
[ "$dcode" = "200" ] && pass "dashboards $dcode" || bad "dashboards $dcode"

echo "== 7) _cat/indices thấy index (Dashboards sẽ thấy) =="
curl -s "$OS/_cat/indices/$INDEX?h=index,docs.count,health" | grep -q "$INDEX" \
  && pass "$(curl -s "$OS/_cat/indices/$INDEX?h=index,docs.count,health")" || bad "không thấy trong _cat/indices"

echo ""
[ "$fail" = "0" ] && echo "KẾT QUẢ: PASS ✅" || { echo "KẾT QUẢ: FAIL ❌"; exit 1; }
