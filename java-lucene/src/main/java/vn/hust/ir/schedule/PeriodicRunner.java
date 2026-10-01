package vn.hust.ir.schedule;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import vn.hust.ir.crawler.HustCrawler;
import vn.hust.ir.index.LuceneIndexer;
import vn.hust.ir.store.Db;

/**
 * Chạy định kỳ: cứ mỗi `intervalMinutes` lại crawl lại + đánh chỉ mục lại
 * -> phát hiện bài mới / nội dung thay đổi (yêu cầu "rà soát & cập nhật định kỳ").
 *
 * Dùng ScheduledExecutorService của JDK cho đơn giản & ổn định. (Có thể thay
 * bằng Quartz nếu cần lịch dạng cron phức tạp.)
 */
public class PeriodicRunner {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String dbPath;
    private final Path indexDir;
    private final int maxPages;
    private final int maxDepth;

    public PeriodicRunner(String dbPath, Path indexDir, int maxPages, int maxDepth) {
        this.dbPath = dbPath;
        this.indexDir = indexDir;
        this.maxPages = maxPages;
        this.maxDepth = maxDepth;
    }

    public void start(long intervalMinutes) {
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
        System.out.println("[scheduler] Chạy lại mỗi " + intervalMinutes
                + " phút. Nhấn Ctrl+C để dừng.");
        exec.scheduleAtFixedRate(this::runOnce, 0, intervalMinutes, TimeUnit.MINUTES);
        try {
            Thread.currentThread().join(); // giữ tiến trình sống
        } catch (InterruptedException ignored) {
        }
    }

    private void runOnce() {
        String now = LocalDateTime.now().format(TS);
        System.out.println("\n[" + now + "] === Bắt đầu một lượt cập nhật ===");
        try (Db db = new Db(dbPath)) {
            new HustCrawler(db).crawl(List.of("https://hust.edu.vn/"), maxPages, maxDepth);
            new LuceneIndexer(db, indexDir).index(0); // index lại phần HTML
            System.out.println("[" + LocalDateTime.now().format(TS) + "] Xong lượt cập nhật.");
        } catch (Exception e) {
            System.err.println("[scheduler] Lỗi lượt chạy: " + e.getMessage());
        }
    }
}
