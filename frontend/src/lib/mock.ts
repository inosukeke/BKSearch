import type { SearchResponse, SearchHit } from "@/types";

// Dữ liệu mock khi backend chưa chạy (bật bằng VITE_USE_MOCK=1 hoặc tự fallback khi fetch lỗi).
const DOCS: SearchHit[] = [
  {
    url: "https://hust.edu.vn/vi/tuyen-sinh/dai-hoc/thong-bao-tuyen-sinh-2026.html",
    title: "Thông báo tuyển sinh đại học chính quy năm 2026",
    snippet:
      "Đại học Bách khoa Hà Nội công bố phương án <em>tuyển sinh</em> năm 2026 với 3 phương thức xét tuyển: xét tuyển tài năng, điểm thi đánh giá tư duy và điểm thi tốt nghiệp THPT.",
    score: 18.42,
    score_type: "bm25",
    doc_type: "html",
    subdomain: "hust.edu.vn",
    category: "tuyển sinh",
  },
  {
    url: "https://sdh.hust.edu.vn/thong-bao/hoc-bong-sau-dai-hoc.html",
    title: "Học bổng dành cho học viên cao học và nghiên cứu sinh",
    snippet:
      "Chương trình <em>học bổng</em> hỗ trợ học phí và sinh hoạt phí cho học viên <em>cao học</em>, nghiên cứu sinh có thành tích học tập và nghiên cứu xuất sắc.",
    score: 15.07,
    score_type: "bm25",
    doc_type: "html",
    subdomain: "sdh.hust.edu.vn",
    category: "học bổng",
  },
  {
    url: "https://hust.edu.vn/vi/dao-tao/chuong-trinh-dao-tao/ky-su-tai-nang.html",
    title: "Chương trình đào tạo Kỹ sư tài năng",
    snippet:
      "Chương trình <em>đào tạo</em> kỹ sư tài năng tuyển chọn sinh viên xuất sắc, chú trọng nghiên cứu khoa học và hội nhập quốc tế.",
    score: 13.88,
    score_type: "bm25",
    doc_type: "html",
    subdomain: "hust.edu.vn",
    category: "đào tạo",
  },
  {
    url: "https://ctt.hust.edu.vn/thong-bao/lich-thi-cuoi-ky-20252.html",
    title: "Lịch thi cuối kỳ học kỳ 2 năm học 2025–2026",
    snippet:
      "Phòng Đào tạo thông báo <em>lịch thi</em> cuối kỳ cho sinh viên các khóa. Sinh viên xem chi tiết phòng thi và ca thi trên cổng thông tin.",
    score: 12.34,
    score_type: "bm25",
    doc_type: "html",
    subdomain: "ctt.hust.edu.vn",
    category: "thông báo",
  },
  {
    url: "https://hust.edu.vn/vi/nghien-cuu/cong-bo-khoa-hoc-2025.html",
    title: "Công bố khoa học nổi bật năm 2025",
    snippet:
      "Các nhóm <em>nghiên cứu</em> của trường công bố nhiều bài báo trên tạp chí quốc tế uy tín trong lĩnh vực trí tuệ nhân tạo và vật liệu mới.",
    score: 10.91,
    score_type: "bm25",
    doc_type: "html",
    subdomain: "hust.edu.vn",
    category: "nghiên cứu",
  },
  {
    url: "https://hust.edu.vn/vi/sinh-vien/ky-tuc-xa/dang-ky-noi-o.html",
    title: "Hướng dẫn đăng ký nội trú ký túc xá",
    snippet:
      "Thông tin đăng ký <em>ký túc xá</em>, mức phí, thủ tục và thời hạn dành cho sinh viên năm thứ nhất và các khóa.",
    score: 9.75,
    score_type: "bm25",
    doc_type: "html",
    subdomain: "hust.edu.vn",
    category: "thông báo",
  },
];

export function mockSearch(q: string, page: number, size: number): SearchResponse {
  const results = DOCS.slice((page - 1) * size, page * size);
  return {
    query: q,
    ranker: "bm25",
    segmented_query: q,
    total: DOCS.length,
    page,
    page_size: size,
    total_pages: Math.ceil(DOCS.length / size),
    took_ms: 12 + Math.floor(Math.random() * 20),
    suggestion: q.length < 3 ? "tuyển sinh" : null,
    results,
    facets: {
      category: [
        { key: "tuyển sinh", count: 42 },
        { key: "đào tạo", count: 31 },
        { key: "thông báo", count: 27 },
        { key: "nghiên cứu", count: 18 },
        { key: "học bổng", count: 9 },
      ],
      doc_type: [
        { key: "html", count: 120 },
        { key: "pdf", count: 34 },
      ],
      subdomain: [
        { key: "hust.edu.vn", count: 98 },
        { key: "sdh.hust.edu.vn", count: 21 },
        { key: "ctt.hust.edu.vn", count: 15 },
      ],
    },
    applied_filters: {},
  };
}

export function mockSuggest(q: string): string | null {
  const map: Record<string, string> = {
    "tuyen sinh": "tuyển sinh",
    "hoc bong": "học bổng",
    "dao tao": "đào tạo",
  };
  return map[q.toLowerCase()] ?? null;
}
