# Embedding + rerank service (CPU). Torch CPU-only để nhẹ (G1: chạy local ≤16GB RAM).
FROM python:3.11-slim

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    HF_HOME=/models \
    SENTENCE_TRANSFORMERS_HOME=/models

WORKDIR /app

# Cài torch CPU trước (tránh kéo bản CUDA nặng), rồi phần còn lại.
COPY requirements.txt .
RUN pip install --upgrade pip \
 && pip install --index-url https://download.pytorch.org/whl/cpu torch==2.5.1 \
 && pip install -r requirements.txt

COPY app ./app

# Thư mục cache model (mount volume để không tải lại mỗi lần khởi động).
RUN mkdir -p /models

EXPOSE 8000

HEALTHCHECK --interval=20s --timeout=10s --start-period=30s --retries=5 \
  CMD python -c "import urllib.request,sys; urllib.request.urlopen('http://localhost:8000/healthz'); sys.exit(0)" || exit 1

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
