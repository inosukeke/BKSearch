package vn.hust.ir.linkgraph;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** S3.1: dựng đồ thị từ tập tài liệu + cạnh, và gom anchor text. */
class PageRankRunnerTest {

    @Test
    void buildGraph_keepsOnlyEdgesBetweenKnownDocs_andAllDocsAsNodes() {
        List<String> docs = List.of("A", "B", "C");          // D không phải tài liệu
        List<String[]> links = List.of(
                new String[]{"A", "B", "tới B"},
                new String[]{"A", "D", "ra ngoài"},          // D lạ → bỏ cạnh
                new String[]{"B", "C", "tới C"});
        LinkGraph g = PageRankRunner.buildGraph(docs, links);
        assertEquals(3, g.size(), "chỉ A,B,C là node (D bị loại)");
        assertTrue(g.urls().containsAll(docs));
        assertFalse(g.urls().contains("D"));
        // A chỉ còn 1 cạnh hợp lệ (A→B); A→D bị loại.
        int ia = g.urls().indexOf("A");
        assertEquals(1, g.outDegree(ia));
    }

    @Test
    void isolatedDocHasNodeButNoEdges() {
        List<String> docs = List.of("A", "B", "X");          // X không có liên kết nào
        List<String[]> links = List.<String[]>of(new String[]{"A", "B", null});
        LinkGraph g = PageRankRunner.buildGraph(docs, links);
        assertEquals(3, g.size());
        int ix = g.urls().indexOf("X");
        assertEquals(0, g.outDegree(ix));
        // PageRank vẫn tính được và tổng = 1.
        double sum = PageRank.compute(g).scores().values().stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(1.0, sum, 1e-6);
    }

    @Test
    void anchorText_aggregatesInboundAnchors() {
        List<String> docs = List.of("A", "B", "C");
        List<String[]> links = List.of(
                new String[]{"A", "C", "tuyển sinh 2026"},
                new String[]{"B", "C", "học bổng"});
        LinkGraph g = PageRankRunner.buildGraph(docs, links);
        String anchor = PageRankRunner.anchorText(g, "C");
        assertTrue(anchor.contains("tuyển sinh 2026"));
        assertTrue(anchor.contains("học bổng"));
        assertEquals("", PageRankRunner.anchorText(g, "A"), "A không có inlink → anchor rỗng");
    }

    @Test
    void anchorText_cappedAtMaxChars() {
        List<String> docs = List.of("A", "B");
        StringBuilder big = new StringBuilder();
        while (big.length() < PageRankRunner.ANCHOR_MAX_CHARS + 500) big.append("x ");
        List<String[]> links = List.<String[]>of(new String[]{"A", "B", big.toString()});
        LinkGraph g = PageRankRunner.buildGraph(docs, links);
        assertTrue(PageRankRunner.anchorText(g, "B").length() <= PageRankRunner.ANCHOR_MAX_CHARS);
    }
}
