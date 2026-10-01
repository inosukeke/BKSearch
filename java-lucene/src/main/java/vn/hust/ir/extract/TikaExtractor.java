package vn.hust.ir.extract;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.apache.tika.Tika;

/**
 * Bóc tách nội dung văn bản từ tài liệu có định dạng (PDF, DOC, DOCX, XLS...)
 * bằng Apache Tika — đáp ứng yêu cầu "bóc tách nội dung văn bản từ tài liệu".
 *
 * Tika tự nhận diện định dạng và trích ra text thuần để đưa vào chỉ mục.
 */
public class TikaExtractor {

    private final Tika tika = new Tika();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public TikaExtractor() {
        tika.setMaxStringLength(2_000_000); // cho phép tài liệu dài
    }

    /** Bóc text từ một InputStream (định dạng bất kỳ Tika hỗ trợ). */
    public String extract(InputStream in) throws Exception {
        return clean(tika.parseToString(in));
    }

    /** Tải tài liệu từ URL rồi bóc text. Trả về "" nếu lỗi. */
    public String extractFromUrl(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "hust-ir-coursework-crawler (muc dich hoc tap)")
                    .GET().build();
            HttpResponse<InputStream> resp =
                    http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) return "";
            try (InputStream in = resp.body()) {
                return extract(in);
            }
        } catch (Exception e) {
            System.err.println("  [Tika] Bỏ qua " + url + " (" + e.getMessage() + ")");
            return "";
        }
    }

    private String clean(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }
}
