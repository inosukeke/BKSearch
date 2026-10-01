package vn.hust.ir.nlp;

/**
 * Giao diện tách từ + chuẩn hóa tiếng Việt dùng chung.
 *
 * <p>Ràng buộc G3 (nhất quán tách từ): văn bản lúc đánh chỉ mục (ingest) và truy vấn
 * (query) PHẢI đi qua cùng một hàm. Mọi backend (Maximum Matching, VnCoreNLP...) đều
 * hiện thực giao diện này để có thể hoán đổi mà vẫn giữ nhất quán.
 */
public interface Segmenter {

    /** Chuẩn hóa: Unicode NFC + viết thường + gộp khoảng trắng. */
    String normalize(String text);

    /**
     * Tách từ + bỏ stopwords, trả chuỗi token cách nhau bằng dấu cách;
     * âm tiết trong một từ ghép nối bằng '_'.
     * Ví dụ: "Trường Đại học Bách khoa" → "trường đại_học bách_khoa".
     */
    String segment(String text);

    /** Tên backend (để log/giám sát). */
    default String name() { return getClass().getSimpleName(); }
}
