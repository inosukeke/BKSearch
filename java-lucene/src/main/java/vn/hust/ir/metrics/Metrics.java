package vn.hust.ir.metrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Registry metric tối giản, KHÔNG phụ thuộc thư viện ngoài (S4.2) — xuất đúng định dạng
 * Prometheus text exposition (v0.0.4) để Prometheus scrape qua {@code GET /metrics}.
 *
 * <p>Thu thập cho Query Service:
 * <ul>
 *   <li>{@code bksearch_http_requests_total{path,status}} — đếm request (suy ra QPS).</li>
 *   <li>{@code bksearch_http_request_duration_seconds} — histogram độ trễ theo path
 *       (suy ra p50/p95/p99 bằng {@code histogram_quantile}).</li>
 *   <li>{@code bksearch_http_in_flight} — gauge số request đang xử lý.</li>
 *   <li>{@code bksearch_uptime_seconds} — thời gian sống tiến trình.</li>
 * </ul>
 *
 * <p>Thread-safe: counter/gauge dùng {@link LongAdder}; mỗi histogram theo path tự đồng bộ.
 */
public class Metrics {

    /** Mốc bucket (giây) — hợp cho dịch vụ tìm kiếm (vài ms → vài giây rerank). */
    static final double[] BUCKETS =
            {0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10};

    private static final class Hist {
        final long[] bucket = new long[BUCKETS.length];
        long count;
        double sum;
        synchronized void observe(double v) {
            count++;
            sum += v;
            for (int i = 0; i < BUCKETS.length; i++) if (v <= BUCKETS[i]) bucket[i]++;
        }
    }

    private final Map<String, LongAdder> requests = new ConcurrentHashMap<>();   // "path|status"
    private final Map<String, Hist> durations = new ConcurrentHashMap<>();       // "path"
    private final LongAdder inFlight = new LongAdder();
    private final long startMs = System.currentTimeMillis();

    public void incInFlight() { inFlight.increment(); }
    public void decInFlight() { inFlight.decrement(); }

    /** Ghi nhận một request đã hoàn tất: path + mã HTTP + thời lượng (giây). */
    public void record(String path, int status, double seconds) {
        requests.computeIfAbsent(path + "|" + status, k -> new LongAdder()).increment();
        durations.computeIfAbsent(path, k -> new Hist()).observe(seconds);
    }

    /** Kết xuất toàn bộ metric ở định dạng Prometheus text. */
    public String renderPrometheus() {
        StringBuilder b = new StringBuilder(1024);

        b.append("# HELP bksearch_http_requests_total Tổng số HTTP request theo path và mã trạng thái.\n");
        b.append("# TYPE bksearch_http_requests_total counter\n");
        for (Map.Entry<String, LongAdder> e : requests.entrySet()) {
            String[] ps = e.getKey().split("\\|", 2);
            b.append("bksearch_http_requests_total{path=\"").append(esc(ps[0]))
             .append("\",status=\"").append(ps.length > 1 ? ps[1] : "0").append("\"} ")
             .append(e.getValue().sum()).append('\n');
        }

        b.append("# HELP bksearch_http_request_duration_seconds Độ trễ xử lý request (histogram).\n");
        b.append("# TYPE bksearch_http_request_duration_seconds histogram\n");
        for (Map.Entry<String, Hist> e : durations.entrySet()) {
            String path = esc(e.getKey());
            Hist h = e.getValue();
            long count; double sum; long[] bk;
            synchronized (h) { count = h.count; sum = h.sum; bk = h.bucket.clone(); }
            for (int i = 0; i < BUCKETS.length; i++) {
                b.append("bksearch_http_request_duration_seconds_bucket{path=\"").append(path)
                 .append("\",le=\"").append(fmt(BUCKETS[i])).append("\"} ").append(bk[i]).append('\n');
            }
            b.append("bksearch_http_request_duration_seconds_bucket{path=\"").append(path)
             .append("\",le=\"+Inf\"} ").append(count).append('\n');
            b.append("bksearch_http_request_duration_seconds_sum{path=\"").append(path)
             .append("\"} ").append(fmt(sum)).append('\n');
            b.append("bksearch_http_request_duration_seconds_count{path=\"").append(path)
             .append("\"} ").append(count).append('\n');
        }

        b.append("# HELP bksearch_http_in_flight Số request đang được xử lý.\n");
        b.append("# TYPE bksearch_http_in_flight gauge\n");
        b.append("bksearch_http_in_flight ").append(inFlight.sum()).append('\n');

        b.append("# HELP bksearch_uptime_seconds Thời gian sống của tiến trình (giây).\n");
        b.append("# TYPE bksearch_uptime_seconds gauge\n");
        b.append("bksearch_uptime_seconds ")
         .append(fmt((System.currentTimeMillis() - startMs) / 1000.0)).append('\n');

        return b.toString();
    }

    private static String fmt(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) return Long.toString((long) v);
        return Double.toString(v);
    }

    /** Thoát ký tự đặc biệt trong label Prometheus (\\, ", newline). */
    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
