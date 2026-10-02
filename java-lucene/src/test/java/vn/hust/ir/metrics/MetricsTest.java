package vn.hust.ir.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** S4.2: registry metric xuất đúng định dạng Prometheus (counter + histogram). */
class MetricsTest {

    @Test
    void countsRequestsByPathAndStatus() {
        Metrics m = new Metrics();
        m.record("/api/search", 200, 0.02);
        m.record("/api/search", 200, 0.03);
        m.record("/api/search", 400, 0.01);
        String out = m.renderPrometheus();
        assertTrue(out.contains("bksearch_http_requests_total{path=\"/api/search\",status=\"200\"} 2"));
        assertTrue(out.contains("bksearch_http_requests_total{path=\"/api/search\",status=\"400\"} 1"));
    }

    @Test
    void histogramBucketsAreCumulative_andHaveSumCount() {
        Metrics m = new Metrics();
        m.record("/api/search", 200, 0.25);   // nhị phân chính xác
        m.record("/api/search", 200, 0.5);    // nhị phân chính xác (tổng = 0.75)
        String out = m.renderPrometheus();

        // 0.25 và 0.5 đều ≤ 0.5 → bucket le=0.5 phải = 2 (tích luỹ).
        assertTrue(out.contains("le=\"0.5\"} 2"), out);
        // chỉ 0.25 ≤ 0.25 → bucket le=0.25 = 1.
        assertTrue(out.contains("le=\"0.25\"} 1"), out);
        // +Inf = tổng số quan sát.
        assertTrue(out.contains("le=\"+Inf\"} 2"));
        assertTrue(out.contains("bksearch_http_request_duration_seconds_count{path=\"/api/search\"} 2"));
        assertTrue(out.contains("bksearch_http_request_duration_seconds_sum{path=\"/api/search\"} 0.75"));
        assertTrue(out.contains("# TYPE bksearch_http_request_duration_seconds histogram"));
    }

    @Test
    void inFlightGaugeReflectsBalance() {
        Metrics m = new Metrics();
        m.incInFlight();
        m.incInFlight();
        m.decInFlight();
        assertTrue(m.renderPrometheus().contains("bksearch_http_in_flight 1"));
    }

    @Test
    void escapesLabelQuotes() {
        Metrics m = new Metrics();
        m.record("/a\"b", 200, 0.01);
        assertTrue(m.renderPrometheus().contains("path=\"/a\\\"b\""));
    }
}
