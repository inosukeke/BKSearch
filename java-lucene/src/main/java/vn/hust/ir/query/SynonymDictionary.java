package vn.hust.ir.query;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Từ điển đồng nghĩa/viết tắt cho mở rộng truy vấn (S3.3).
 *
 * <p>Mỗi dòng tài nguyên là một <b>nhóm</b> cụm tương đương (phẩy ngăn cách). Khi truy vấn chứa
 * một cụm trong nhóm, {@link #expand} trả các cụm CÒN LẠI để thêm vào nhánh {@code should}
 * (tăng recall mà không bắt buộc). Khớp theo chuỗi-từ (bao bởi khoảng trắng) trên văn bản đã
 * chuẩn hóa lowercase.
 */
public final class SynonymDictionary {

    private final List<List<String>> groups;

    public SynonymDictionary(List<List<String>> groups) {
        this.groups = groups;
    }

    /** Nạp từ resource mặc định {@code /vi-synonyms.txt}. */
    public static SynonymDictionary getDefault() {
        return load("/vi-synonyms.txt");
    }

    public static SynonymDictionary load(String resource) {
        List<List<String>> groups = new ArrayList<>();
        try (InputStream in = SynonymDictionary.class.getResourceAsStream(resource)) {
            if (in == null) return new SynonymDictionary(groups);
            BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                List<String> grp = new ArrayList<>();
                for (String part : line.split(",")) {
                    String p = part.trim().toLowerCase();
                    if (!p.isEmpty()) grp.add(p);
                }
                if (grp.size() >= 2) groups.add(grp);
            }
        } catch (Exception e) {
            System.err.println("[SynonymDictionary] không nạp được " + resource + ": " + e.getMessage());
        }
        return new SynonymDictionary(groups);
    }

    public int size() { return groups.size(); }

    /**
     * Trả các cụm đồng nghĩa (RAW) cho một truy vấn thô, tối đa {@code maxTerms} cụm. Không gồm
     * các cụm đã xuất hiện trong truy vấn. Thứ tự ổn định theo thứ tự nhóm.
     */
    public Set<String> expand(String rawQuery, int maxTerms) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (rawQuery == null || rawQuery.isBlank() || maxTerms <= 0) return out;
        String q = " " + rawQuery.toLowerCase().replaceAll("\\s+", " ").trim() + " ";
        for (List<String> grp : groups) {
            boolean matched = false;
            for (String phrase : grp) {
                if (q.contains(" " + phrase + " ")) { matched = true; break; }
            }
            if (!matched) continue;
            for (String phrase : grp) {
                if (!q.contains(" " + phrase + " ")) {
                    out.add(phrase);
                    if (out.size() >= maxTerms) return out;
                }
            }
        }
        return out;
    }
}
