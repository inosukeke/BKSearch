package vn.hust.ir.nlp;

import java.util.ArrayList;
import java.util.List;

/**
 * Backend tách từ dùng <b>VnCoreNLP</b> (chất lượng cao hơn Maximum Matching).
 *
 * <p>VnCoreNLP không có trên Maven Central nên được nạp <i>qua reflection</i>: dự án vẫn
 * biên dịch & chạy khi chưa có thư viện. Để kích hoạt, đặt {@code VnCoreNLP-x.y.jar} và thư
 * mục {@code models/} vào classpath (xem {@code java-lucene/libs/README.md}) rồi chạy với
 * {@code -Dbksearch.segmenter=vncorenlp}.
 *
 * <p>Chuẩn hóa/stopwords tái dùng {@link VietnameseSegmenter} để GIỮ NHẤT QUÁN định dạng
 * token ('_' cho từ ghép, viết thường, bỏ stopword đơn âm tiết) — dù dùng backend nào.
 */
public class VnCoreNlpSegmenter implements Segmenter {

    private final VietnameseSegmenter base = new VietnameseSegmenter(); // normalize + stopwords
    private final Object pipeline;        // vn.pipeline.VnCoreNLP
    private final Class<?> annotationCls; // vn.pipeline.Annotation
    private final java.lang.reflect.Method annotateM;
    private final java.lang.reflect.Method getWordSegmentedTextM;
    private final java.lang.reflect.Constructor<?> annotationCtor;

    /**
     * @throws ReflectiveOperationException nếu không tìm thấy lớp/model VnCoreNLP
     *         (gọi nơi dùng nên bắt để fallback sang Maximum Matching).
     */
    public VnCoreNlpSegmenter() throws ReflectiveOperationException {
        Class<?> vnCls = Class.forName("vn.pipeline.VnCoreNLP");
        this.annotationCls = Class.forName("vn.pipeline.Annotation");
        // new VnCoreNLP(new String[]{"wseg"})
        this.pipeline = vnCls.getConstructor(String[].class)
                .newInstance((Object) new String[]{"wseg"});
        this.annotationCtor = annotationCls.getConstructor(String.class);
        this.annotateM = vnCls.getMethod("annotate", annotationCls);
        this.getWordSegmentedTextM = annotationCls.getMethod("getWordSegmentedText");
    }

    @Override
    public String normalize(String text) { return base.normalize(text); }

    @Override
    public String segment(String text) {
        if (text == null || text.isBlank()) return "";
        try {
            Object ann = annotationCtor.newInstance(text);
            annotateM.invoke(pipeline, ann);
            String wseg = (String) getWordSegmentedTextM.invoke(ann); // "Trường Đại_học Bách_khoa ..."
            if (wseg == null) return "";
            List<String> tokens = new ArrayList<>();
            for (String w : wseg.trim().split("\\s+")) {
                // chuẩn hóa từng từ (NFC + lowercase); giữ '_' của VnCoreNLP
                String t = base.normalize(w.replace('_', ' ')).replace(' ', '_');
                if (t.isEmpty()) continue;
                // bỏ dấu câu lọt ra thành token đơn
                t = t.replaceAll("[^\\p{L}\\p{Nd}_]", "");
                if (t.isEmpty()) continue;
                // bỏ stopword đơn âm tiết (nhất quán với MM)
                if (!t.contains("_") && base.isStopword(t)) continue;
                tokens.add(t);
            }
            return String.join(" ", tokens);
        } catch (Exception e) {
            throw new RuntimeException("VnCoreNLP segment lỗi: " + e.getMessage(), e);
        }
    }

    @Override
    public String name() { return "VnCoreNLP"; }
}
