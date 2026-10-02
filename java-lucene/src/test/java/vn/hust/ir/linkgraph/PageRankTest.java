package vn.hust.ir.linkgraph;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** S3.1: kiểm thử PageRank (power iteration) + LinkGraph trên đồ thị nhỏ đã biết kết quả. */
class PageRankTest {

    @Test
    void emptyGraph_returnsEmptyConverged() {
        PageRank.Result r = PageRank.compute(new LinkGraph());
        assertTrue(r.scores().isEmpty());
        assertTrue(r.converged());
    }

    @Test
    void scoresSumToOne() {
        LinkGraph g = new LinkGraph();
        g.addEdge("A", "B", null);
        g.addEdge("B", "C", null);
        g.addEdge("C", "A", null);
        PageRank.Result r = PageRank.compute(g);
        double sum = r.scores().values().stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(1.0, sum, 1e-6);
    }

    @Test
    void hubWithMoreInlinksRanksHighest() {
        // B, C, D đều trỏ về A; A trỏ B → A nhiều inlink nhất → PR cao nhất.
        LinkGraph g = new LinkGraph();
        g.addEdge("B", "A", null);
        g.addEdge("C", "A", null);
        g.addEdge("D", "A", null);
        g.addEdge("A", "B", null);
        Map<String, Double> s = PageRank.compute(g).scores();
        double a = s.get("A");
        assertTrue(a > s.get("B"), "A > B");
        assertTrue(a > s.get("C"), "A > C");
        assertTrue(a > s.get("D"), "A > D");
    }

    @Test
    void twoNodesMutualLink_equalRank() {
        LinkGraph g = new LinkGraph();
        g.addEdge("A", "B", null);
        g.addEdge("B", "A", null);
        Map<String, Double> s = PageRank.compute(g).scores();
        assertEquals(s.get("A"), s.get("B"), 1e-9);
        assertEquals(0.5, s.get("A"), 1e-6);
    }

    @Test
    void danglingNode_stillSumsToOne() {
        // C không có liên kết ra (dangling) → rank phải được phân bổ lại, tổng vẫn = 1.
        LinkGraph g = new LinkGraph();
        g.addEdge("A", "B", null);
        g.addEdge("A", "C", null);
        g.addEdge("B", "C", null);
        PageRank.Result r = PageRank.compute(g);
        double sum = r.scores().values().stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(1.0, sum, 1e-6);
        assertTrue(r.converged());
        // C nhận inlink từ cả A và B → cao nhất.
        assertTrue(r.scores().get("C") > r.scores().get("A"));
        assertTrue(r.scores().get("C") > r.scores().get("B"));
    }

    @Test
    void convergesBeforeMaxIter() {
        LinkGraph g = new LinkGraph();
        g.addEdge("A", "B", null);
        g.addEdge("B", "C", null);
        g.addEdge("C", "A", null);
        g.addEdge("A", "C", null);
        PageRank.Result r = PageRank.compute(g, 0.85, 100, 1e-8);
        assertTrue(r.converged());
        assertTrue(r.iterations() < 100);
    }

    @Test
    void selfLoopIgnored() {
        LinkGraph g = new LinkGraph();
        g.addEdge("A", "A", "tự trỏ");   // bỏ qua
        g.addEdge("A", "B", null);
        int ia = indexOf(g, "A");
        assertEquals(1, g.outDegree(ia), "self-loop không tính vào bậc ra");
    }

    @Test
    void duplicateEdgeCountedOnce_butAnchorsAllKept() {
        LinkGraph g = new LinkGraph();
        g.addEdge("A", "B", "tuyển sinh");
        g.addEdge("A", "B", "đại học");   // cạnh trùng
        int ia = indexOf(g, "A");
        assertEquals(1, g.outDegree(ia), "cạnh (A,B) chỉ tính một lần");
        List<String> anchors = g.anchorsFor("B");
        assertEquals(2, anchors.size(), "mọi anchor tới B đều được gom");
        assertTrue(anchors.contains("tuyển sinh") && anchors.contains("đại học"));
    }

    private static int indexOf(LinkGraph g, String url) {
        List<String> us = g.urls();
        return us.indexOf(url);
    }
}
