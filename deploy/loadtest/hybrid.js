// S4.3 — Load test k6: kịch bản HYBRID + RERANK (dense + cross-encoder).
// Mục tiêu G7: p95 < 800ms ở ~20–50 QPS (cần Embedding Service bật — EMBED_URL).
//
// Chạy:  k6 run -e BASE=http://localhost:7070 deploy/loadtest/hybrid.js
// Lưu ý: rerank trên CPU rất chậm; chỉnh RERANK_TOP_K/RERANK_MAX_LENGTH + RATE cho phù hợp.
import http from "k6/http";
import { check } from "k6";
import { Trend } from "k6/metrics";

const BASE = __ENV.BASE || "http://localhost:7070";
const RERANK = (__ENV.RERANK || "1") === "1";
const RATE = parseInt(__ENV.RATE || "20");   // QPS mục tiêu (thấp hơn keyword vì nặng)

const QUERIES = [
  "tuyển sinh đại học", "điều kiện học bổng", "quy chế đào tạo tín chỉ",
  "lịch thi cuối kỳ", "chương trình kỹ sư tài năng", "học phí hệ chính quy",
  "nghiên cứu trí tuệ nhân tạo", "thủ tục nhập học",
];

const latency = new Trend("hybrid_latency", true);

export const options = {
  scenarios: {
    hybrid: {
      executor: "constant-arrival-rate",
      rate: RATE,
      timeUnit: "1s",
      duration: __ENV.DURATION || "1m",
      preAllocatedVUs: Math.max(20, RATE * 2),
      maxVUs: Math.max(100, RATE * 6),
    },
  },
  thresholds: {
    // G7: p95 hybrid+rerank < 800ms.
    "http_req_duration{kind:hybrid}": ["p(95)<800"],
    "http_req_failed": ["rate<0.02"],
  },
};

export default function () {
  const q = QUERIES[Math.floor(Math.random() * QUERIES.length)];
  const url = `${BASE}/api/search?q=${encodeURIComponent(q)}&ranker=hybrid&rerank=${RERANK ? 1 : 0}`;
  const res = http.get(url, { tags: { kind: "hybrid" } });
  latency.add(res.timings.duration);
  check(res, {
    "status 200": (r) => r.status === 200,
    "có JSON kết quả": (r) => r.body && r.body.indexOf("results") !== -1,
  });
}
