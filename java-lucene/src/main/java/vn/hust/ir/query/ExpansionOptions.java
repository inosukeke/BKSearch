package vn.hust.ir.query;

/**
 * Cấu hình mở rộng truy vấn (S3.3): đồng nghĩa + pseudo-relevance feedback (Rocchio).
 * Tất cả TẮT mặc định để không đổi hành vi Phase 1/2; bật/đo bằng eval.
 *
 * @param synonyms    bật mở rộng đồng nghĩa (thêm nhánh should)
 * @param maxSynonyms số cụm đồng nghĩa tối đa thêm vào
 * @param prf         bật pseudo-relevance feedback (Rocchio) — cần 1 lượt tìm mồi
 * @param prfDocs     số kết quả đầu coi là pseudo-relevant
 * @param prfTerms    số token Rocchio tối đa thêm vào
 */
public record ExpansionOptions(boolean synonyms, int maxSynonyms, boolean prf, int prfDocs, int prfTerms) {

    public static ExpansionOptions disabled() {
        return new ExpansionOptions(false, 4, false, 5, 8);
    }

    public boolean anyEnabled() { return synonyms || prf; }
}
