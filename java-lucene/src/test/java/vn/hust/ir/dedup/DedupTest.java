package vn.hust.ir.dedup;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** S3.2: MinHash + shingling + LSH gom near-duplicate. */
class DedupTest {

    // ---- MinHash ----
    @Test
    void identicalSets_jaccardNearOne() {
        MinHasher h = new MinHasher(128, 1L);
        Set<String> s = Shingling.shingles("đại học bách khoa hà nội tuyển sinh năm 2026", 3);
        assertEquals(1.0, MinHasher.estimatedJaccard(h.signature(s), h.signature(s)), 1e-9);
    }

    @Test
    void disjointSets_jaccardNearZero() {
        MinHasher h = new MinHasher(128, 1L);
        List<String> a = new ArrayList<>(), b = new ArrayList<>();
        for (int i = 0; i < 100; i++) { a.add("a" + i); b.add("b" + i); }
        double j = MinHasher.estimatedJaccard(h.signature(a), h.signature(b));
        assertTrue(j < 0.05, "ước lượng Jaccard phải ~0, được " + j);
    }

    @Test
    void overlappingSets_estimatesTrueJaccard() {
        MinHasher h = new MinHasher(256, 7L);
        List<String> a = new ArrayList<>(), b = new ArrayList<>();
        for (int i = 1; i <= 100; i++) a.add("t" + i);      // 1..100
        for (int i = 50; i <= 150; i++) b.add("t" + i);     // 50..150
        double trueJ = 51.0 / 150.0;                         // |∩|=51, |∪|=150
        double est = MinHasher.estimatedJaccard(h.signature(a), h.signature(b));
        assertEquals(trueJ, est, 0.1, "ước lượng gần Jaccard thật");
    }

    // ---- Shingling ----
    @Test
    void shingles_countAndShortText() {
        assertEquals(3, Shingling.shingles("a b c d e", 3).size());   // abc,bcd,cde
        assertEquals(1, Shingling.shingles("a b", 5).size());         // ngắn hơn k → 1 shingle
        assertTrue(Shingling.shingles("", 3).isEmpty());
    }

    // ---- Detector ----
    @Test
    void detectsNearDuplicates_keepsOneCanonical() {
        Map<String, String> docs = new LinkedHashMap<>();
        String base = "thông báo tuyển sinh đại học chính quy năm 2026 của trường đại học bách khoa hà nội";
        docs.put("doc_b", base);
        docs.put("doc_a", base + " xin trân trọng thông báo");   // gần trùng (thêm đuôi nhỏ)
        docs.put("doc_c", "lịch thi học kỳ một các môn đại cương dành cho sinh viên năm nhất");
        NearDuplicateDetector.Result r = NearDuplicateDetector.detect(
                docs, 3, 128, 42L, 32, 0.6);

        // doc_a & doc_b cùng nhóm, canonical = min id = "doc_a"; doc_c đứng riêng.
        assertEquals(r.canonical().get("doc_a"), r.canonical().get("doc_b"), "near-dup cùng nhóm");
        assertEquals("doc_a", r.canonical().get("doc_a"), "canonical = id nhỏ nhất");
        assertNotEquals(r.canonical().get("doc_c"), r.canonical().get("doc_a"), "doc khác → nhóm riêng");
        assertEquals(1, r.duplicateCount(), "đúng 1 bản trùng (doc_b)");
        assertEquals(1, r.clusters().size());
    }

    @Test
    void distinctDocs_noFalseClusters() {
        Map<String, String> docs = new LinkedHashMap<>();
        docs.put("x", "tuyển sinh sau đại học thạc sĩ tiến sĩ đợt một");
        docs.put("y", "kết quả nghiên cứu khoa học sinh viên cấp trường");
        docs.put("z", "lịch nghỉ tết nguyên đán toàn trường thông báo");
        NearDuplicateDetector.Result r = NearDuplicateDetector.detect(docs);
        assertEquals(0, r.duplicateCount());
        assertTrue(r.clusters().isEmpty());
    }

    @Test
    void cluster_rejectsBadBands() {
        var docs = List.of(new NearDuplicateDetector.Signed("a", new long[]{1, 2, 3, 4, 5}));
        assertThrows(IllegalArgumentException.class,
                () -> NearDuplicateDetector.cluster(docs, 2, 0.8));  // 5 % 2 != 0
    }
}
