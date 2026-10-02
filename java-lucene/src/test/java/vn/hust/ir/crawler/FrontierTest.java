package vn.hust.ir.crawler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** S4.1: frontier Mercator — khử trùng, lịch sự theo host, kết thúc khi cạn. */
class FrontierTest {

    @Test
    void dedupes_and_countsSeen() {
        Frontier f = new Frontier(0);
        assertTrue(f.add("https://hust.edu.vn/a", 0));
        assertFalse(f.add("https://hust.edu.vn/a", 0), "URL trùng → không thêm lại");
        assertTrue(f.add("https://hust.edu.vn/b", 0));
        assertEquals(2, f.seenCount());
    }

    @Test
    void next_returnsNullWhenDrained() throws InterruptedException {
        Frontier f = new Frontier(0);
        f.add("https://hust.edu.vn/a", 0);
        Frontier.Claim c = f.next();
        assertNotNull(c);
        assertEquals("https://hust.edu.vn/a", c.url());
        f.done(c);
        assertNull(f.next(), "hết URL + không còn đang xử lý → null");
    }

    @Test
    void enforcesPerHostDelay_betweenConsecutiveClaims() throws InterruptedException {
        long delay = 120;
        Frontier f = new Frontier(delay);
        f.add("https://hust.edu.vn/1", 0);
        f.add("https://hust.edu.vn/2", 0);   // cùng host

        long t0 = System.currentTimeMillis();
        Frontier.Claim c1 = f.next();
        f.done(c1);
        Frontier.Claim c2 = f.next();        // phải chờ ≥ delay vì cùng host
        long gap = System.currentTimeMillis() - t0;
        f.done(c2);

        assertTrue(gap >= delay - 15,
                "giãn cách claim cùng host phải ≥ delay (đo " + gap + "ms, delay " + delay + ")");
    }

    @Test
    void differentHosts_noArtificialDelay() throws InterruptedException {
        long delay = 500;
        Frontier f = new Frontier(delay);
        f.add("https://a.hust.edu.vn/x", 0);
        f.add("https://b.hust.edu.vn/y", 0);   // host khác → lấy ngay

        long t0 = System.currentTimeMillis();
        Frontier.Claim c1 = f.next();
        Frontier.Claim c2 = f.next();          // KHÔNG phải chờ delay (host khác)
        long gap = System.currentTimeMillis() - t0;
        f.done(c1);
        f.done(c2);

        assertNotEquals(c1.url(), c2.url());
        assertTrue(gap < delay, "hai host khác nhau không bị trễ nhân tạo (đo " + gap + "ms)");
    }
}
