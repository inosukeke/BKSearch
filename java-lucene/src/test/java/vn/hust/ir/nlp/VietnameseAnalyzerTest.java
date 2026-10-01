package vn.hust.ir.nlp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kiểm thử S0.4 — tách từ tiếng Việt.
 *
 * PASS (theo KE_HOACH_TRIEN_KHAI.md):
 *   - "Trường Đại học Bách khoa" → "trường đại_học bách_khoa"
 *   - cùng hàm gọi ở ingest & query cho ra chuỗi GIỐNG NHAU (ràng buộc G3).
 */
class VietnameseAnalyzerTest {

    private final Segmenter seg = new VietnameseSegmenter(); // backend mặc định (Maximum Matching)

    @Test
    void segmentExample_matchesSpec() {
        assertEquals("trường đại_học bách_khoa", seg.segment("Trường Đại học Bách khoa"));
    }

    @Test
    void ingestAndQuery_areConsistent() {
        // Ingest chuẩn hóa văn bản gốc; query chuẩn hóa chuỗi người dùng gõ (khác hoa/thường + dấu cách thừa).
        String atIngest = seg.segment("Trường Đại Học Bách Khoa");
        String atQuery  = seg.segment("  trường   đại học   bách khoa ");
        assertEquals(atIngest, atQuery, "Tách từ ở ingest và query phải nhất quán (G3)");
    }

    @Test
    void normalize_doesNfcAndLowercaseAndCollapsesSpaces() {
        assertEquals("đại học", seg.normalize("  Đại    Học  "));
    }

    @Test
    void segment_dropsSingleSyllableStopwords() {
        // "của", "và" là stopword đơn âm tiết → bị loại; từ ghép vẫn giữ.
        String out = seg.segment("thông tin của tuyển sinh và đào tạo");
        assertEquals("thông_tin tuyển_sinh đào_tạo", out);
    }

    @Test
    void segment_keepsUnknownSyllablesAsSingleTokens() {
        String out = seg.segment("Hà Nội xyz");
        assertEquals("hà_nội xyz", out);
    }

    @Test
    void analyzerFacade_defaultsToMaximumMatching() {
        // Không set -Dbksearch.segmenter → mặc định MM, và kết quả khớp spec.
        VietnameseAnalyzer a = VietnameseAnalyzer.get();
        assertEquals("trường đại_học bách_khoa", a.segment("Trường Đại học Bách khoa"));
    }
}
