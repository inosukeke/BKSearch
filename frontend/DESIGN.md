# BKSearch UI — Design System

Phong cách: **tối giản kiểu Google / Perplexity** — nhiều khoảng trắng, tập trung vào ô search
và danh sách kết quả, hỗ trợ **dark mode**. Mọi style lấy từ token dưới đây (không hardcode màu).

## Màu (HSL, định nghĩa qua CSS variables trong index.css)
- **Primary (brand HUST):** `--primary: 354 70% 36%` (đỏ mận `#9b1b23`) · foreground trắng.
- **Neutral:** nền `--background`, chữ `--foreground`, viền `--border`, phụ `--muted-foreground`.
  - Light: background `0 0% 100%`, foreground `222 20% 14%`, muted-fg `220 9% 46%`, border `220 13% 91%`.
  - Dark: background `222 24% 8%`, foreground `210 20% 96%`, muted-fg `217 11% 65%`, border `217 19% 20%`.
- **Accent highlight (từ khóa):** `--highlight: 45 93% 55%` (vàng) nền cho `<em>`.
- Semantic: `--destructive` (đỏ lỗi), `--ring` = primary (focus ring).

## Typography
- Font: **Inter** (fallback system-ui). Scale: display 36/28px (hero), title 17px (kết quả),
  body 15px, meta/caption 13px. Dòng 1.55.

## Hình khối & nhịp
- **Radius:** `--radius: 0.75rem` (card/input), nút/badge bo theo `calc(radius - 2–4px)`.
- **Spacing scale (Tailwind):** 2/3/4/6/8/12/16 — gutter mobile 16px, max-width nội dung 720px (hero) / 760px (kết quả).
- Shadow nhẹ: card dùng `shadow-sm`, nổi lên `shadow-md` khi hover.

## Trang (SPA 1 trang, search + kết quả gộp)
1. **Hero search** (chưa truy vấn): logo + ô search lớn giữa màn hình, chip gợi ý query mẫu, nút Tìm.
2. **Kết quả:** header search thu gọn trên cùng; cột trái facet (category/doc_type/subdomain),
   cột chính: meta (số kết quả · thời gian · ranker) → did-you-mean → danh sách card
   (tiêu đề, URL, snippet highlight, badge score/score_type/category) → "Tải thêm".

## Trạng thái bắt buộc
- **Loading:** skeleton 5 card (shimmer). **Empty:** icon + gợi ý đổi từ khóa.
- **Error:** card đỏ nhạt + nút thử lại. **Normal:** kết quả fade-in stagger.

## Animation (Framer Motion, 150–250ms, tôn trọng `prefers-reduced-motion`)
- Kết quả: fade-in + slide-up **stagger** 40ms/mục. Focus ring ô search mượt. Hover card nâng nhẹ.
