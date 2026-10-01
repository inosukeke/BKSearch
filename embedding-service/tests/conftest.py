"""Thiết lập chung cho test: bật chế độ GIẢ TRƯỚC khi import app (không tải model)."""
import os
import sys
from pathlib import Path

os.environ.setdefault("EMBED_FAKE", "1")

# Cho phép `import app...` khi chạy pytest từ thư mục embedding-service.
ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
