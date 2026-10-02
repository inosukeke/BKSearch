package vn.hust.ir.dedup;

import java.util.Collection;
import java.util.Random;

/**
 * Sinh chữ ký MinHash (S3.2) để ước lượng độ tương tự Jaccard giữa hai tập shingle.
 *
 * <p>Mỗi hàm băm thứ {@code i} ánh xạ {@code h_i(x) = (a_i*hash(x) + b_i) mod P}; chữ ký lấy
 * giá trị nhỏ nhất qua mọi shingle. Xác suất hai chữ ký trùng tại một vị trí = Jaccard của hai
 * tập ⇒ tỉ lệ vị trí trùng giữa hai chữ ký là ước lượng không chệch của Jaccard.
 *
 * <p>Tham số {@code seed} cố định để tái lập (G1); dùng FNV-1a 64-bit làm hash cơ sở ổn định
 * giữa các lần chạy/JVM (khác với {@code String.hashCode}, ở đây chủ động để chắc chắn).
 */
public final class MinHasher {

    /** Nguyên tố Mersenne 2^61-1: đủ lớn, số học long không tràn với hệ số < 2^31. */
    private static final long PRIME = 2305843009213693951L;

    private final int numHashes;
    private final long[] a;
    private final long[] b;

    public MinHasher(int numHashes, long seed) {
        if (numHashes <= 0) throw new IllegalArgumentException("numHashes > 0");
        this.numHashes = numHashes;
        this.a = new long[numHashes];
        this.b = new long[numHashes];
        Random rnd = new Random(seed);
        for (int i = 0; i < numHashes; i++) {
            a[i] = 1 + (rnd.nextLong() & 0x7fffffffL);   // 1..2^31-1 (khác 0)
            b[i] = rnd.nextLong() & 0x7fffffffL;
        }
    }

    public int numHashes() { return numHashes; }

    /** FNV-1a 64-bit ổn định cho một shingle. */
    static long fnv1a(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h & 0x7fffffffffffffffL;   // bỏ dấu để mod dương
    }

    /**
     * Chữ ký MinHash của một tập shingle. Tập rỗng → mọi vị trí = {@link Long#MAX_VALUE}
     * (đánh dấu "không có"), để hai doc rỗng không bị coi là trùng với doc thường.
     */
    public long[] signature(Collection<String> shingles) {
        long[] sig = new long[numHashes];
        java.util.Arrays.fill(sig, Long.MAX_VALUE);
        if (shingles == null || shingles.isEmpty()) return sig;
        for (String sh : shingles) {
            long x = fnv1a(sh) % PRIME;
            for (int i = 0; i < numHashes; i++) {
                long hv = (a[i] * x + b[i]) % PRIME;
                if (hv < sig[i]) sig[i] = hv;
            }
        }
        return sig;
    }

    /** Ước lượng Jaccard = tỉ lệ vị trí trùng giữa hai chữ ký cùng độ dài. */
    public static double estimatedJaccard(long[] s1, long[] s2) {
        if (s1.length != s2.length || s1.length == 0) return 0.0;
        int eq = 0;
        for (int i = 0; i < s1.length; i++) if (s1[i] == s2[i]) eq++;
        return (double) eq / s1.length;
    }
}
