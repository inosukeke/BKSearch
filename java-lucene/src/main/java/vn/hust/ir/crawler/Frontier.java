package vn.hust.ir.crawler;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;

/**
 * Frontier kiểu Mercator (S4.1) — hàng đợi URL 2 lớp, đảm bảo LỊCH SỰ (politeness).
 *
 * <ul>
 *   <li><b>Front</b>: tập URL đã thấy (khử trùng) + hàng đợi theo từng host (FIFO).</li>
 *   <li><b>Back</b>: min-heap các host theo {@code nextAllowedTime} — thời điểm sớm nhất
 *       được phép gửi request kế tiếp tới host đó.</li>
 * </ul>
 *
 * <p><b>Bảo đảm lịch sự:</b> mỗi host chỉ do MỘT worker giữ tại một thời điểm
 * (≤ 1 request đồng thời/host) và sau khi nhận URL của host tại thời điểm {@code t},
 * host bị khoá tới {@code t + delay} → khoảng cách giữa hai request bắt đầu tới cùng
 * host luôn ≥ {@code delay}.
 *
 * <p>Thread-safe: mọi thao tác đồng bộ trên {@code this}; worker chờ bằng
 * {@code wait/notify} khi chưa có host nào tới hạn.
 */
public class Frontier {

    /** Một host đang chờ tới lượt trong back-queue. */
    private static final class Host {
        final String key;
        final Queue<String[]> urls = new ArrayDeque<>();   // [url, depth]
        long nextAllowedTime;                              // mốc thời gian (ms) được phép lấy tiếp
        boolean claimed;                                   // đang có worker xử lý?
        Host(String key, long t) { this.key = key; this.nextAllowedTime = t; }
    }

    /** Thẻ giữ host để worker trả lại sau khi xử lý xong (ẩn chi tiết nội bộ). */
    public static final class Claim {
        private final Host host;
        final String url;
        final int depth;
        private Claim(Host host, String url, int depth) { this.host = host; this.url = url; this.depth = depth; }
        public String url() { return url; }
        public int depth() { return depth; }
    }

    private final long delayMs;
    private final Set<String> seen = new HashSet<>();
    private final Map<String, Host> hosts = new HashMap<>();
    // Heap host CHƯA bị claim và CÒN url, sắp theo nextAllowedTime tăng dần.
    private final PriorityQueue<Host> ready =
            new PriorityQueue<>((x, y) -> Long.compare(x.nextAllowedTime, y.nextAllowedTime));

    private int inFlight = 0;    // số URL đã claim mà chưa done (đang xử lý)
    private boolean closed = false;

    public Frontier(long delayMs) { this.delayMs = delayMs; }

    /** Thêm URL (kèm depth). Trả {@code true} nếu URL mới (chưa từng thấy). */
    public synchronized boolean add(String url, int depth) {
        if (closed || url == null || !seen.add(url)) return false;
        String key = hostOf(url);
        Host h = hosts.computeIfAbsent(key, k -> new Host(k, now()));
        h.urls.add(new String[]{url, String.valueOf(depth)});
        if (!h.claimed && h.urls.size() == 1) ready.add(h);   // host vừa có việc trở lại
        notifyAll();
        return true;
    }

    /**
     * Lấy URL kế tiếp đã tới hạn lịch sự, đồng thời CLAIM host của nó.
     * Chặn (block) tới khi có việc; trả {@code null} khi frontier đã cạn
     * (không còn URL chờ và không còn URL đang xử lý) hoặc đã đóng.
     */
    public synchronized Claim next() throws InterruptedException {
        while (true) {
            if (closed) return null;
            Host h = ready.peek();
            if (h == null) {
                if (inFlight == 0) return null;   // hết sạch việc → kết thúc
                wait();                            // chờ host khác được trả lại / url mới
                continue;
            }
            long wait = h.nextAllowedTime - now();
            if (wait > 0) { wait(wait); continue; } // chưa tới hạn → ngủ đúng khoảng còn lại

            ready.poll();
            h.claimed = true;
            h.nextAllowedTime = now() + delayMs;   // khoá host tới mốc kế (đo từ lúc BẮT ĐẦU)
            String[] u = h.urls.poll();
            inFlight++;
            return new Claim(h, u[0], Integer.parseInt(u[1]));
        }
    }

    /** Worker gọi sau khi xử lý xong một claim — trả host về heap (nếu còn URL). */
    public synchronized void done(Claim c) {
        Host h = c.host;
        h.claimed = false;
        inFlight--;
        if (!h.urls.isEmpty()) ready.add(h);       // còn URL → chờ tới nextAllowedTime
        notifyAll();
    }

    /** Đóng frontier (dừng mọi worker đang chờ). */
    public synchronized void close() { closed = true; notifyAll(); }

    public synchronized int seenCount() { return seen.size(); }
    public synchronized boolean hasSeen(String url) { return seen.contains(url); }

    long now() { return System.currentTimeMillis(); }   // override được trong test

    private static String hostOf(String url) {
        try {
            String h = URI.create(url).getHost();
            return h == null ? "" : h.toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }
}
