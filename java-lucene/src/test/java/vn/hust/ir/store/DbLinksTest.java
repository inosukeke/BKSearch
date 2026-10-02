package vn.hust.ir.store;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** S3.1: bảng links trong Db (SQLite in-memory) — chèn cạnh + anchor, khử self-loop & trùng. */
class DbLinksTest {

    @Test
    void insertAndReadLinks() throws Exception {
        try (Db db = new Db(":memory:")) {
            assertTrue(db.insertLink("A", "B", "tới B"));
            assertTrue(db.insertLink("B", "C", "tới C"));
            assertEquals(2, db.countLinks());

            List<String[]> links = db.allLinks();
            assertEquals(2, links.size());
            assertTrue(links.stream().anyMatch(e -> e[0].equals("A") && e[1].equals("B") && e[2].equals("tới B")));
        }
    }

    @Test
    void selfLoopRejected() throws Exception {
        try (Db db = new Db(":memory:")) {
            assertFalse(db.insertLink("A", "A", "tự trỏ"));
            assertEquals(0, db.countLinks());
        }
    }

    @Test
    void duplicateSameAnchorIgnored_differentAnchorKept() throws Exception {
        try (Db db = new Db(":memory:")) {
            assertTrue(db.insertLink("A", "B", "x"));
            assertFalse(db.insertLink("A", "B", "x"), "trùng (src,dst,anchor) → bỏ qua");
            assertTrue(db.insertLink("A", "B", "y"), "khác anchol → cạnh mới");
            assertEquals(2, db.countLinks());
        }
    }

    @Test
    void blankUrlRejected() throws Exception {
        try (Db db = new Db(":memory:")) {
            assertFalse(db.insertLink("", "B", "a"));
            assertFalse(db.insertLink("A", "  ", "a"));
            assertFalse(db.insertLink(null, "B", "a"));
            assertEquals(0, db.countLinks());
        }
    }
}
