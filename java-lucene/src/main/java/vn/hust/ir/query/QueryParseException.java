package vn.hust.ir.query;

/**
 * Lỗi cú pháp truy vấn do người dùng (S1.4). Tầng REST bắt ngoại lệ này và trả
 * HTTP 400 kèm thông báo thân thiện — KHÔNG để rơi xuống 500.
 */
public class QueryParseException extends RuntimeException {
    public QueryParseException(String message) {
        super(message);
    }
}
