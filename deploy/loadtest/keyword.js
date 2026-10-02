// S4.3 — Load test k6: kịch bản TỪ KHÓA (BM25/VSM).
// Mục tiêu G7: p95 < 200ms ở ~20–50 QPS.
//
// Chạy:  k6 run -e BASE=http://localhost:7070 deploy/loadtest/keyword.js
// Tải cao hơn:  k6 run -e BASE=... -e RATE=50 deploy/loadtest/keyword.js
import http from "k6/http";
import { check } from "k6";
import { Trend } from "k6/metrics";

const BASE = __ENV.BASE || "http://localhost:7070";
const RANKER = __ENV.RANKER || "bm25";
const RATE = parseInt(__ENV.RATE || "30");   // QPS mục tiêu

// Truy vấn điều hướng tiêu biểu trên corpus HUST.
const QUERIES = [
  "tuyển sinh", "học phí", "lịch thi", "học bổng", "đào tạo",
  "chương trình đào tạo", "thông báo", "nghiên cứu khoa học",
  "ký túc xá", "điểm chuẩn", "cao học", "tiến sĩ",
];

const latency = new Trend("search_latency", true);

export const options = {
  scenarios: {
    keyword: {
      executor: "constant-arrival-rate",
      rate: RATE,
      timeUnit: "1s",
      duration: __ENV.DURATION || "1m",
      preAllocatedVUs: Math.max(20, RATE),
      maxVUs: Math.max(100, RATE * 4),
    },
  },
  thresholds: {
    // G7: p95 truy vấn từ khóa < 200ms.
    "http_req_duration{kind:keyword}": ["p(95)<200"],
    "http_req_failed": ["rate<0.01"],
  },
};

export default function () {
  const q = QUERIES[Math.floor(Math.random() * QUERIES.length)];
  const url = `${BASE}/api/search?q=${encodeURIComponent(q)}&ranker=${RANKER}`;
  const res = http.get(url, { tags: { kind: "keyword" } });
  latency.add(res.timings.duration);
  check(res, {
    "status 200": (r) => r.status === 200,
    "có JSON kết quả": (r) => r.body && r.body.indexOf("results") !== -1,
  });
}
