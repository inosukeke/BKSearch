#!/usr/bin/env bash
# Smoke test nền Phase 0 (S0.6). Kiểm chứng: hạ tầng + di trú + tìm kiếm tiếng Việt + idempotent.
# Dùng:  bash deploy/smoke-test.sh
# Thoát != 0 nếu bất kỳ kiểm tra nào hỏng.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OS="${OS_URL:-http://localhost:9200}"
DASH="${DASH_URL:-http://localhost:5601}"
INDEX="${INDEX:-documents}"
DB="$ROOT/java-lucene/data/hust.db"
JAR="$ROOT/java-lucene/target/hust-search.jar"
fail=0
pass() { echo "  [PASS] $1"; }
bad()  { echo "  [FAIL] $1"; fail=1; }

count_os() { curl -s "$OS/$INDEX/_count" | grep -o '"count":[0-9]*' | cut -d: -f2; }
hits_for() { curl -s "$OS/$INDEX/_search?q=content_seg:$1&size=0" | grep -o '"value":[0-9]*' | head -1 | cut -d: -f2; }

echo "== 0) Đảm bảo index tồn tại (tự áp mapping nếu thiếu) =="
if [ "$(curl -s -o /dev/null -w '%{http_code}' "$OS/$INDEX")" != "200" ]; then
  echo "  index thiếu → chạy apply-mapping.sh"
  bash "$ROOT/deploy/opensearch/apply-mapping.sh" "$OS" "$INDEX" || true
fi

echo "== 1) OpenSearch cluster health =="
status=$(curl -s "$OS/_cluster/health" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
if [ "$status" = "green" ] || [ "$status" = "yellow" ]; then pass "cluster status=$status"; else bad "cluster status=$status"; fi

echo "== 2) Index '$INDEX' tồn tại =="
code=$(curl -s -o /dev/null -w "%{http_code}" "$OS/$INDEX")
if [ "$code" = "200" ]; then pass "index có ($code)"; else bad "index thiếu ($code)"; fi

echo "== 3) Số doc OpenSearch = số doc hợp lệ trong SQLite =="
# Suy số doc kỳ vọng từ chính SQLite (không hardcode). Fallback EXPECT_COUNT nếu không đọc được.
EXPECT="${EXPECT_COUNT:-}"
if [ -z "$EXPECT" ] && command -v python >/dev/null 2>&1 && [ -f "$DB" ]; then
  # Python trên Windows cần path kiểu Windows (cygpath), và truyền qua env để tránh lỗi quoting/Unicode.
  DB_PY=$(cygpath -w "$DB" 2>/dev/null || echo "$DB")
  EXPECT=$(DBP="$DB_PY" PYTHONUTF8=1 python -c "import os,sqlite3;print(sqlite3.connect(os.environ['DBP']).execute(\"select count(*) from documents where url is not null and url<>''\").fetchone()[0])" 2>/dev/null)
fi
cnt=$(count_os)
if [ -n "$EXPECT" ]; then
  if [ "$cnt" = "$EXPECT" ]; then pass "count=$cnt = SQLite hợp lệ ($EXPECT)"; else bad "count=$cnt ≠ SQLite hợp lệ ($EXPECT)"; fi
else
  if [ "${cnt:-0}" -gt 0 ]; then pass "count=$cnt (>0; không đọc được SQLite để so khớp chính xác)"; else bad "count=0"; fi
fi

echo "== 4) BM25 tiếng Việt (content_seg:tuyển_sinh) trả kết quả =="
h=$(hits_for "tuy%E1%BB%83n_sinh"); if [ "${h:-0}" -gt 0 ]; then pass "hits=$h"; else bad "không có hit cho 'tuyển_sinh'"; fi

echo "== 5) Tách từ: 'đại_học' khớp tài liệu chứa 'đại học' =="
h=$(hits_for "%C4%91%E1%BA%A1i_h%E1%BB%8Dc"); if [ "${h:-0}" -gt 0 ]; then pass "hits=$h"; else bad "không có hit cho 'đại_học'"; fi

echo "== 6) Dashboards phục vụ (http 200) =="
dcode=$(curl -s -o /dev/null -w "%{http_code}" "$DASH/api/status")
if [ "$dcode" = "200" ]; then pass "dashboards $dcode"; else bad "dashboards $dcode"; fi

echo "== 7) _cat/indices thấy index =="
line=$(curl -s "$OS/_cat/indices/$INDEX?h=index,docs.count,health")
if echo "$line" | grep -q "$INDEX"; then pass "$line"; else bad "không thấy trong _cat/indices"; fi

echo "== 8) Idempotent: chạy migrate lần nữa → count không đổi =="
if [ -f "$JAR" ] && command -v java >/dev/null 2>&1; then
  before=$(count_os)
  ( cd "$ROOT/java-lucene" && java -Dfile.encoding=UTF-8 -jar "$JAR" migrate "$OS" 500 --no-pg >/dev/null 2>&1 )
  curl -s "$OS/$INDEX/_refresh" >/dev/null
  after=$(count_os)
  if [ "$before" = "$after" ]; then pass "count trước=$before, sau=$after (không nhân đôi)"; else bad "count đổi: trước=$before, sau=$after"; fi
else
  echo "  [SKIP] chưa có $JAR (chạy 'mvn -DskipTests package' để kiểm idempotent)"
fi

echo ""
if [ "$fail" = "0" ]; then echo "KẾT QUẢ: PASS ✅"; else echo "KẾT QUẢ: FAIL ❌"; exit 1; fi
