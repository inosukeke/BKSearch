package vn.hust.ir.query;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** S2.4: kiểm thử Reciprocal Rank Fusion bằng dữ liệu giả. */
class RrfFusionTest {

    @Test
    void docInBothLists_outranksDocInOneList() {
        // A đứng đầu cả hai → điểm cao nhất. B chỉ ở list1, C chỉ ở list2.
        List<List<String>> lists = List.of(
                List.of("A", "B", "C"),
                List.of("A", "C", "D"));
        LinkedHashMap<String, Double> fused = RrfFusion.fuse(lists, 60);
        List<String> order = List.copyOf(fused.keySet());
        assertEquals("A", order.get(0), "Tài liệu đứng đầu cả hai nguồn phải xếp #1");
        // Điểm A = 1/61 + 1/61; các doc khác chỉ có một đóng góp.
        assertTrue(fused.get("A") > fused.get("B"));
        assertTrue(fused.get("A") > fused.get("C"));
    }

    @Test
    void smallerK_givesMoreWeightToTopRanks() {
        List<List<String>> lists = List.of(List.of("X", "Y"));
        double kSmall = RrfFusion.fuse(lists, 1).get("X");   // 1/(1+1)=0.5
        double kBig = RrfFusion.fuse(lists, 100).get("X");   // 1/101≈0.0099
        assertTrue(kSmall > kBig);
        assertEquals(0.5, kSmall, 1e-9);
    }

    @Test
    void fuseToList_unionOfAllIds_sortedByScore() {
        List<List<String>> lists = List.of(
                List.of("a", "b"),
                List.of("c", "a"));
        List<String> order = RrfFusion.fuseToList(lists, 60);
        assertEquals(3, order.size());
        assertEquals("a", order.get(0));   // xuất hiện 2 lần → đầu bảng
    }

    @Test
    void handlesNullAndEmptyLists() {
        LinkedHashMap<String, Double> fused = RrfFusion.fuse(List.of(List.of(), List.of("a")), 60);
        assertEquals(1, fused.size());
        assertTrue(fused.containsKey("a"));
    }
}
