package vn.hust.ir.nlp;

/**
 * Điểm vào DUY NHẤT cho tách từ + chuẩn hóa tiếng Việt của hệ thống (ràng buộc G3).
 *
 * <p>Cả ingest (sinh {@code content_seg}/{@code title_seg}) và query (tách từ truy vấn)
 * đều gọi cùng một thể hiện ở đây → bảo đảm nhất quán tuyệt đối.
 *
 * <p>Chọn backend theo thuộc tính hệ thống {@code -Dbksearch.segmenter}:
 * <ul>
 *   <li>{@code mm} (mặc định): Maximum Matching — chạy offline, không phụ thuộc.</li>
 *   <li>{@code vncorenlp}: dùng VnCoreNLP (cần jar + model, xem libs/README).</li>
 *   <li>{@code auto}: thử VnCoreNLP, không được thì tự lùi về Maximum Matching.</li>
 * </ul>
 */
public final class VietnameseAnalyzer implements Segmenter {

    private static volatile VietnameseAnalyzer instance;

    private final Segmenter backend;

    private VietnameseAnalyzer() {
        this.backend = chooseBackend(System.getProperty("bksearch.segmenter", "mm"));
        System.err.println("[VietnameseAnalyzer] backend = " + backend.name());
    }

    /** Thể hiện dùng chung (singleton) cho toàn hệ thống. */
    public static VietnameseAnalyzer get() {
        if (instance == null) {
            synchronized (VietnameseAnalyzer.class) {
                if (instance == null) instance = new VietnameseAnalyzer();
            }
        }
        return instance;
    }

    private static Segmenter chooseBackend(String mode) {
        switch (mode == null ? "mm" : mode.toLowerCase()) {
            case "vncorenlp":
                try {
                    return new VnCoreNlpSegmenter();
                } catch (Throwable t) {
                    throw new IllegalStateException(
                        "Yêu cầu VnCoreNLP nhưng không nạp được (thiếu jar/model?): " + t.getMessage(), t);
                }
            case "auto":
                try {
                    return new VnCoreNlpSegmenter();
                } catch (Throwable t) {
                    System.err.println("[VietnameseAnalyzer] VnCoreNLP không sẵn sàng → dùng Maximum Matching. "
                        + t.getMessage());
                    return new VietnameseSegmenter();
                }
            case "mm":
            default:
                return new VietnameseSegmenter();
        }
    }

    @Override public String normalize(String text) { return backend.normalize(text); }
    @Override public String segment(String text)   { return backend.segment(text); }
    @Override public String name()                 { return backend.name(); }

    /** Backend đang dùng (phục vụ kiểm thử/giám sát). */
    public Segmenter backend() { return backend; }
}
